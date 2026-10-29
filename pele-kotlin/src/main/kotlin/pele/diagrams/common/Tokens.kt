// Ported from Pele src/diagrams/common/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.js.JsRegExp
import pele.js.WeakIdentityMap
import pele.js.escapeRegExp
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsTrim
import pele.syntaxError

// Tokenizer for the diagram types Mermaid defines with Langium grammars. Token types are tried
// in order and the first match wins, as in Chevrotain; the tables in each diagram's folder are
// checked against Mermaid's generated lexers by the tests.

// `previous` is the last visible token's type index, or -1 at the start.
typealias Scanner = (src: String, at: Int, previous: Int) -> Int

data class TokenType(
  val name: String,
  // A literal string, or a regular expression with the sticky flag.
  val pattern: Any,
  val hidden: Boolean = false,
  // Start of a construct that needs a closer. When this matches but the whole pattern does not,
  // no closer exists in the rest of the text, so the pattern is not tried again.
  val opener: JsRegExp? = null,
  // Every ASCII character a match of a regular expression can start with, when that is known.
  // The type is then tried only at those characters and at characters outside ASCII.
  val first: String? = null,
  // Chevrotain's LONGER_ALT. When this type matches, these are tried at the same place, and the
  // first one that matches more text becomes the token.
  val longer: List<TokenType>? = null,
  // Makes a matcher that answers as `pattern` does, for a pattern that could rescan the text.
  // It is called once per text, so the matcher may remember what it has scanned. The matcher
  // returns the end of the match, or -1.
  val scanner: (() -> Scanner)? = null,
  // Matches in place of the pattern, for a pattern that would rescan text from every position
  // or that depends on the token before it. Returns the end of the match, or -1.
  val match: Scanner? = null,
) {
  // PORT: identity, as upstream compares token types by reference (indexOf, WeakMap keys).
  override fun equals(other: Any?): Boolean = this === other

  override fun hashCode(): Int = System.identityHashCode(this)
}

class Tokens(
  val kinds: MutableList<Int>,
  val starts: MutableList<Int>,
  val ends: MutableList<Int>,
  val src: String,
)

private class Table(
  // For each ASCII character, the token types that can start with it, in matching order.
  val starting: Array<IntArray>,
  val every: IntArray,
  val longer: List<IntArray?>,
)

private val tables = WeakIdentityMap<List<TokenType>, Table>()

private fun prepare(types: List<TokenType>): Table {
  val every = IntArray(types.size) { it }
  val starting = Array(128) { c ->
    val ch = c.toChar()
    every.filter { k ->
      val type = types[k]
      val pattern = type.pattern
      if (pattern is String) pattern.isNotEmpty() && pattern[0] == ch else type.first == null || type.first.contains(ch)
    }.toIntArray()
  }
  val table = Table(starting, every, types.map { type -> type.longer?.map { alt -> types.indexOf(alt) }?.toIntArray() })
  tables[types] = table
  return table
}

fun tokenize(src: String, types: List<TokenType>, diagram: String): Tokens {
  val kinds = ArrayList<Int>()
  val starts = ArrayList<Int>()
  val ends = ArrayList<Int>()
  val dead = BooleanArray(types.size)
  val table = tables[types] ?: prepare(types)
  val starting = table.starting
  val every = table.every
  val longer = table.longer
  val scanners = arrayOfNulls<Scanner>(types.size)
  val n = src.length

  val match = fun(k: Int, p: Int): Int {
    if (dead[k]) return -1
    val type = types[k]
    val previous = if (kinds.isEmpty()) -1 else kinds[kinds.size - 1]
    if (type.match != null) return type.match.invoke(src, p, previous)
    if (type.scanner != null) {
      val s = scanners[k] ?: type.scanner.invoke().also { scanners[k] = it }
      return s(src, p, previous)
    }
    val pattern = type.pattern
    if (pattern is String) return if (src.startsWith(pattern, p)) p + pattern.length else -1
    pattern as JsRegExp
    pattern.lastIndex = p
    if (pattern.test(src)) return pattern.lastIndex
    if (type.opener != null) {
      type.opener.lastIndex = p
      if (type.opener.test(src)) dead[k] = true
    }
    return -1
  }

  var p = 0
  scan@ while (p < n) {
    val c = src[p].code
    for (k in if (c < 128) starting[c] else every) {
      var end = match(k, p)
      if (end <= p) continue
      var kind = k
      val alts = longer[k]
      if (alts != null) {
        for (alt in alts) {
          val altEnd = match(alt, p)
          if (altEnd > end) {
            kind = alt
            end = altEnd
            break
          }
        }
      }
      if (!types[kind].hidden) {
        kinds.add(kind)
        starts.add(p)
        ends.add(end)
      }
      p = end
      continue@scan
    }
    throw syntaxError(diagram, src, p, "", true)
  }
  kinds.add(-1)
  starts.add(n)
  ends.add(n)
  return Tokens(kinds, starts, ends, src)
}

val ACC_DESCR = TokenType(
  name = "ACC_DESCR",
  pattern = JsRegExp("""[\t ]*accDescr(?:[\t ]*:([^\n\r]*?(?=%%)|[^\n\r]*)|\s*{([^}]*)})""", "y"),
  first = "\t a",
)
val ACC_TITLE = TokenType(
  name = "ACC_TITLE",
  pattern = JsRegExp("""[\t ]*accTitle[\t ]*:(?:[^\n\r]*?(?=%%)|[^\n\r]*)""", "y"),
  first = "\t a",
)
val TITLE = TokenType(
  name = "TITLE",
  pattern = JsRegExp("""[\t ]*title(?:[\t ][^\n\r]*?(?=%%)|[\t ][^\n\r]*|)""", "y"),
  first = "\t t",
)

// `rest` matches a name suffix so `title` does not split longer names.
fun titleBefore(rest: String): TokenType =
  TITLE.copy(pattern = JsRegExp("""[\t ]*title(?:[\t ][^\n\r]*?(?=%%)|[\t ][^\n\r]*|(?!$rest))""", "y"))

// PORT: Java's repeated alternation uses one stack frame per character. Scan flat strings.
internal fun quotedEnd(src: String, at: Int, previous: Int): Int {
  val quote = src.getOrNull(at)
  if (quote != '"' && quote != '\'') return -1
  var i = at + 1
  while (i < src.length) {
    val c = src[i++]
    if (c == quote) return i
    if (c == '\\') {
      val escaped = src.getOrNull(i) ?: return -1
      if (escaped == '\n' || escaped == '\r' || escaped == '\u2028' || escaped == '\u2029') return -1
      i++
    }
  }
  return -1
}

val STRING = TokenType(name = "STRING", pattern = JsRegExp("""\"([^"\\]|\\.)*"|'([^'\\]|\\.)*'""", "y"), first = "\"'", match = ::quotedEnd)
val NEWLINE = TokenType(name = "NEWLINE", pattern = JsRegExp("""\r?\n""", "y"), first = "\r\n")
val WHITESPACE = TokenType(name = "WHITESPACE", pattern = JsRegExp("""[\t ]+""", "y"), hidden = true, first = "\t ")
val YAML = TokenType(
  name = "YAML",
  pattern = JsRegExp("""---[\t ]*\r?\n(?:[\S\s]*?\r?\n)?---(?:\r?\n|(?!\S))""", "y"),
  hidden = true,
  opener = JsRegExp("""---[\t ]*\r?\n""", "y"),
  first = "-",
)
val DIRECTIVE = TokenType(
  name = "DIRECTIVE",
  pattern = JsRegExp("""[\t ]*%%{[\S\s]*?}%%(?:\r?\n|(?!\S))""", "y"),
  hidden = true,
  opener = JsRegExp("""[\t ]*%%{""", "y"),
  first = "\t %",
)
val SINGLE_LINE_COMMENT = TokenType(
  name = "SINGLE_LINE_COMMENT",
  pattern = JsRegExp("""[\t ]*%%[^\n\r]*""", "y"),
  first = "\t %",
  hidden = true,
)

// Mermaid ends a keyword only at whitespace, a comment, or the end of input.
fun keyword(word: String): TokenType =
  TokenType(
    name = word,
    pattern = JsRegExp(escapeRegExp(word) + "(?:(?=%%)|(?!\\S))", "y"),
    first = word[0].toString(),
  )

private val RE_ACC_DESCR = JsRegExp("""accDescr(?:[\t ]*:([^\n\r]*)|\s*{([^}]*)})""")
private val RE_ACC_TITLE = JsRegExp("""accTitle[\t ]*:([^\n\r]*)""")
private val RE_TITLE = JsRegExp("""title([\t ][^\n\r]*|)""")
private val RE_BLANKS = JsRegExp("""[\t ]{2,}""", "gm")

private fun single(text: String): String = text.jsTrim().jsReplace(RE_BLANKS, " ")

fun titleValue(text: String): String = single(RE_TITLE.exec(text)!![1]!!)

fun accTitleValue(text: String): String = single(RE_ACC_TITLE.exec(text)!![1]!!)

private val RE_LEADING_SPACE = JsRegExp("""^\s*""", "gm")
private val RE_TRAILING_SPACE = JsRegExp("""\s+$""", "gm")
private val RE_BREAKS = JsRegExp("""[\n\r]{2,}""", "gm")

fun accDescrValue(text: String): String {
  val m = RE_ACC_DESCR.exec(text)!!
  if (m[1] != null) return single(m[1]!!)
  return m[2]!!
    .jsReplace(RE_LEADING_SPACE, "")
    .jsReplace(RE_TRAILING_SPACE, "")
    .jsReplace(RE_BLANKS, " ")
    .jsReplace(RE_BREAKS, "\n")
}

private val ESCAPES = mapOf(
  'b' to "\b",
  'f' to "\u000C",
  'n' to "\n",
  'r' to "\r",
  't' to "\t",
  'v' to "\u000B",
  '0' to "\u0000",
)

fun stringValue(text: String): String {
  if (!text.contains('\\')) return text.jsSlice(1, -1)
  val out = StringBuilder()
  var i = 1
  while (i < text.length - 1) {
    val c = text[i]
    if (c == '\\') {
      i++
      val next = text[i]
      out.append(ESCAPES[next] ?: next.toString())
    } else {
      out.append(c)
    }
    i++
  }
  return out.toString()
}

// Reads tokens for a hand-written parser, with Chevrotain-style messages on failure.
class Reader(
  val tokens: Tokens,
  val types: List<TokenType>,
  val diagram: String,
) {
  var i = 0
  val kinds: List<Int> = tokens.kinds

  val kind: Int get() = kinds.getOrElse(i) { UNDEFINED_KIND }

  fun text(index: Int = i): String = tokens.src.jsSlice(tokens.starts[index], tokens.ends[index])

  fun take(): String = text(i++)

  fun accept(kind: Int): Boolean {
    if (kinds.getOrElse(i) { UNDEFINED_KIND } != kind) return false
    i++
    return true
  }

  fun expect(kind: Int): String {
    if (kinds.getOrElse(i) { UNDEFINED_KIND } != kind) fail("token of type '${types[kind].name}'")
    return take()
  }

  fun fail(expected: String): Nothing {
    val found = if (kind == -1) "end of input" else "`${text()}`"
    val src = tokens.src
    val starts = tokens.starts
    val ends = tokens.ends
    var at = starts[i]
    if (kind == -1) {
      // Locate EOF errors after the last token, before trailing line breaks and hidden comments.
      var k = i
      while (k > 0 && src.jsSlice(starts[k - 1], ends[k - 1]).jsTrim() == "") k--
      if (k > 0) at = ends[k - 1]
    }
    throw syntaxError(diagram, src, at, "Expecting $expected but found $found.")
  }

  companion object {
    // PORT: `kinds[i]` past the end is undefined in JS, which equals no kind.
    const val UNDEFINED_KIND = Int.MIN_VALUE
  }
}
