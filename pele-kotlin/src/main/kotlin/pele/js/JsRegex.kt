// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

import java.util.regex.Matcher
import java.util.regex.Pattern

private const val JS_SPACE = "[$JS_WHITESPACE_CLASS_BODY]"
private const val JS_NOT_SPACE = "[^$JS_WHITESPACE_CLASS_BODY]"
private const val WORD = "[A-Za-z0-9_]"
private const val JS_WORD_BOUNDARY = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"
private const val JS_NOT_WORD_BOUNDARY = "(?:(?<=$WORD)(?=$WORD)|(?<!$WORD)(?!$WORD))"
private const val LINE_TERMINATORS = "\\n\\r\\u2028\\u2029"

/**
 * Translates an ECMAScript pattern into a java.util.regex pattern with the same meaning.
 * Handles where java.util.regex and ECMAScript differ: \s, \b, ., $, ^ with /m, [ and && in classes, lone braces.
 */
fun translateJsPattern(
  source: String,
  dotAll: Boolean = false,
  multiline: Boolean = false,
  fold: ((Char) -> Char)? = null,
): String {
  val out = StringBuilder(source.length + 16)
  var i = 0
  val n = source.length
  var inClass = false
  while (i < n) {
    val c = source[i]
    if (inClass) {
      when {
        c == ']' -> {
          inClass = false
          out.append(']')
          i++
        }
        c == '\\' && i + 1 < n -> {
          i = translateEscape(source, i, out, inClass = true)
        }
        c == '[' -> {
          out.append("\\[")
          i++
        }
        c == '&' -> {
          out.append("\\&")
          i++
        }
        else -> {
          // Range endpoints are left alone; no case-insensitive pattern in Pele has a non-ASCII range.
          val range = (i + 1 < n && source[i + 1] == '-') || (i > 0 && source[i - 1] == '-')
          out.append(if (fold != null && c.code >= 128 && !range) fold(c) else c)
          i++
        }
      }
      continue
    }
    when (c) {
      '\\' -> {
        if (i + 1 >= n) {
          out.append("\\\\")
          i++
        } else {
          i = translateEscape(source, i, out, inClass = false)
        }
      }
      '[' -> {
        // `[]` matches nothing and `[^]` matches anything in JS.
        if (source.startsWith("[]", i)) {
          out.append("(?!)")
          i += 2
        } else if (source.startsWith("[^]", i)) {
          out.append("(?s:.)")
          i += 3
        } else {
          inClass = true
          out.append('[')
          i++
          if (i < n && source[i] == '^') {
            out.append('^')
            i++
          }
        }
      }
      '.' -> {
        out.append(if (dotAll) "(?s:.)" else "[^$LINE_TERMINATORS]")
        i++
      }
      '^' -> {
        out.append(if (multiline) "(?:^|(?<=[$LINE_TERMINATORS]))" else "^")
        i++
      }
      '$' -> {
        out.append(if (multiline) "(?=[$LINE_TERMINATORS]|\\z)" else "\\z")
        i++
      }
      '{' -> {
        val q = quantifierEnd(source, i)
        if (q == -1) {
          out.append("\\{")
          i++
        } else {
          out.append(source, i, q)
          i = q
        }
      }
      '}' -> {
        out.append("\\}")
        i++
      }
      else -> {
        out.append(if (fold != null && c.code >= 128) fold(c) else c)
        i++
      }
    }
  }
  return out.toString()
}

/** The end of a `{n}`, `{n,}` or `{n,m}` quantifier starting at [i], or -1. */
private fun quantifierEnd(s: String, i: Int): Int {
  var j = i + 1
  val startDigits = j
  while (j < s.length && s[j].isAsciiDigit()) j++
  if (j == startDigits) return -1
  if (j < s.length && s[j] == '}') return j + 1
  if (j >= s.length || s[j] != ',') return -1
  j++
  while (j < s.length && s[j].isAsciiDigit()) j++
  return if (j < s.length && s[j] == '}') j + 1 else -1
}

private fun Char.isAsciiDigit() = this in '0'..'9'

private fun translateEscape(s: String, i: Int, out: StringBuilder, inClass: Boolean): Int {
  val e = s[i + 1]
  when (e) {
    's' -> out.append(if (inClass) JS_WHITESPACE_CLASS_BODY else JS_SPACE)
    'S' -> out.append(JS_NOT_SPACE)
    'b' -> out.append(if (inClass) "\\x08" else JS_WORD_BOUNDARY)
    'B' -> out.append(if (inClass) "B" else JS_NOT_WORD_BOUNDARY)
    'd', 'D', 'w', 'W', 'f', 'n', 'r', 't' -> out.append('\\').append(e)
    'v' -> out.append("\\x0B")
    '0' -> {
      if (i + 2 < s.length && s[i + 2].isAsciiDigit()) {
        // Legacy octal; not used by Pele.
        out.append("\\0").append(s[i + 2])
        return i + 3
      }
      out.append("\\x00")
    }
    'x' -> {
      if (i + 3 < s.length && s[i + 2].isHex() && s[i + 3].isHex()) {
        out.append(s, i, i + 4)
        return i + 4
      }
      out.append('x')
    }
    'u' -> {
      if (i + 2 < s.length && s[i + 2] == '{') {
        val close = s.indexOf('}', i + 3)
        out.append("\\x{").append(s, i + 3, close).append('}')
        return close + 1
      }
      if (i + 5 < s.length && (2..5).all { s[i + it].isHex() }) {
        out.append(s, i, i + 6)
        return i + 6
      }
      out.append('u')
    }
    'c' -> {
      if (i + 2 < s.length && s[i + 2].isLetter()) {
        out.append(s, i, i + 3)
        return i + 3
      }
      out.append("\\\\c")
    }
    'p', 'P' -> {
      if (i + 2 < s.length && s[i + 2] == '{') {
        val close = s.indexOf('}', i + 3)
        out.append('\\').append(e).append(s, i + 2, close + 1)
        return close + 1
      }
      out.append(e)
    }
    in '1'..'9' -> {
      if (inClass) {
        out.append("\\x0").append(e)
      } else {
        out.append('\\').append(e)
      }
    }
    'k' -> out.append("\\k")
    else -> {
      if (e.isLetterOrDigit()) {
        // An identity escape: `\a` is `a` in JS.
        out.append(e)
      } else {
        out.append('\\').append(e)
      }
    }
  }
  return i + 2
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun flagsOf(flags: String): Int {
  var f = 0
  // PORT: Java compares only ASCII case-insensitively here. Other characters are matched by
  // folding both the input and the pattern's literals with JS's rules (see foldFor).
  if ('i' in flags) f = f or Pattern.CASE_INSENSITIVE
  return f
}

// JS Canonicalize without /u: the character's upper case, unless that is more than one character
// or turns a non-ASCII character into an ASCII one (so `ı` and `ſ` don't match `i` and `s`).
internal fun canonicalize(c: Char): Char {
  if (c.code < 128) return c
  val upper = c.toString().uppercase()
  if (upper.length != 1) return c
  val u = upper[0]
  return if (u.code < 128) c else u
}

// JS Canonicalize with /u: simple case folding. Lower case of upper case matches it, except for
// the Turkic dotted and dotless i, which fold only to themselves.
internal fun simpleFold(c: Char): Char {
  if (c.code < 128 || c == '\u0130' || c == '\u0131') return c
  val upper = c.toString().uppercase()
  val u = if (upper.length == 1) upper[0] else c
  val lower = u.toString().lowercase()
  return if (lower.length == 1) lower[0] else c
}

private fun foldFor(flags: String): ((Char) -> Char)? =
  if ('i' !in flags) null else if ('u' in flags) ::simpleFold else ::canonicalize

/** [input] with every character folded, or [input] itself when folding changes nothing. */
private fun foldInput(input: String, fold: (Char) -> Char): String {
  var i = 0
  while (i < input.length && (input[i].code < 128 || fold(input[i]) == input[i])) i++
  if (i == input.length) return input
  val chars = input.toCharArray()
  while (i < chars.size) {
    chars[i] = fold(chars[i])
    i++
  }
  return String(chars)
}

private val compiled = java.util.concurrent.ConcurrentHashMap<String, Pattern>()

private fun compile(source: String, flags: String): Pattern {
  val key = "$flags/$source"
  if (compiled.size > 4096) compiled.clear()
  return compiled.getOrPut(key) {
    Pattern.compile(translateJsPattern(source, dotAll = 's' in flags, multiline = 'm' in flags, fold = foldFor(flags)), flagsOf(flags))
  }
}

/** One match, as `RegExp.prototype.exec` returns it. */
class JsMatch(val index: Int, val input: String, val groups: List<String?>) {
  val value: String get() = groups[0]!!
  val end: Int get() = index + value.length
  operator fun get(i: Int): String? = groups.getOrNull(i)
  val size: Int get() = groups.size
}

/**
 * An ECMAScript RegExp. `lastIndex` is kept per thread so that module-level `g`/`y` constants
 * are safe to share.
 */
class JsRegExp(val source: String, val flags: String = "") {
  val global = 'g' in flags
  val sticky = 'y' in flags
  val ignoreCase = 'i' in flags
  val multiline = 'm' in flags
  val unicode = 'u' in flags
  val dotAll = 's' in flags
  val pattern: Pattern = compile(source, flags)

  // PORT: per-thread lastIndex so module-level g/y regex constants are safe across threads.
  private val lastIndexHolder = ThreadLocal.withInitial { IntArray(1) }
  var lastIndex: Int
    get() = lastIndexHolder.get()[0]
    set(v) {
      lastIndexHolder.get()[0] = v
    }

  private val fold = foldFor(flags)

  // The folded form of the last input, per thread: tokenizers match one input at many positions.
  private val folded = if (fold == null) null else ThreadLocal<Pair<String, String>?>()

  private fun subject(input: String): String {
    val fold = fold ?: return input
    val cache = folded!!
    val last = cache.get()
    if (last != null && last.first === input) return last.second
    val result = foldInput(input, fold)
    cache.set(input to result)
    return result
  }

  // Folding keeps every character in place, so matches on the folded input give the indices,
  // and the text, of the original.
  private fun matcher(input: String, start: Int): Matcher =
    pattern.matcher(subject(input)).apply {
      region(start, input.length)
      useTransparentBounds(true)
      useAnchoringBounds(false)
    }

  /** `RegExp.prototype.exec`. */
  fun exec(input: String): JsMatch? {
    val updates = global || sticky
    val start = if (updates) lastIndex else 0
    if (start < 0 || start > input.length) {
      if (updates) lastIndex = 0
      return null
    }
    val m = matcher(input, start)
    val found = if (sticky) m.lookingAt() else m.find()
    if (!found) {
      if (updates) lastIndex = 0
      return null
    }
    if (updates) lastIndex = m.end()
    return toMatch(m, input)
  }

  /** `RegExp.prototype.test`. */
  fun test(input: String): Boolean {
    if (global || sticky) return exec(input) != null
    val m = matcher(input, 0)
    return m.find()
  }

  /** Matches at [at] without touching lastIndex, like a sticky exec. */
  fun matchAt(input: String, at: Int): JsMatch? {
    if (at < 0 || at > input.length) return null
    val m = matcher(input, at)
    val found = if (sticky) m.lookingAt() else m.find()
    return if (found) toMatch(m, input) else null
  }

  /** Matches exactly at [at], as a sticky regex would, without touching lastIndex. */
  internal fun matchSticky(input: String, at: Int): JsMatch? {
    if (at < 0 || at > input.length) return null
    val m = matcher(input, at)
    return if (m.lookingAt()) toMatch(m, input) else null
  }

  /** Every match from [from], as `matchAll` or a global `replace` would see them. */
  internal fun allMatches(input: String, from: Int = 0): List<JsMatch> {
    val out = ArrayList<JsMatch>()
    var at = from
    while (at <= input.length) {
      val m = matcher(input, at)
      val found = if (sticky) m.lookingAt() else m.find()
      if (!found) break
      out.add(toMatch(m, input))
      at = if (m.end() == m.start()) advance(input, m.end()) else m.end()
    }
    return out
  }

  /** `replace` with a replacement that has no `$`: the matches are replaced without collecting them. */
  internal fun replaceLiteral(input: String, replacement: String): String {
    if (!global) {
      val start = if (sticky) lastIndex else 0
      if (start < 0 || start > input.length) {
        if (sticky) lastIndex = 0
        return input
      }
      val m = matcher(input, start)
      if (!(if (sticky) m.lookingAt() else m.find())) {
        if (sticky) lastIndex = 0
        return input
      }
      if (sticky) lastIndex = m.end()
      return StringBuilder(input.length - (m.end() - m.start()) + replacement.length)
        .append(input, 0, m.start())
        .append(replacement)
        .append(input, m.end(), input.length)
        .toString()
    }
    lastIndex = 0
    val m = matcher(input, 0)
    var at = 0
    var last = 0
    var sb: StringBuilder? = null
    while (at <= input.length) {
      m.region(at, input.length)
      if (!(if (sticky) m.lookingAt() else m.find())) break
      if (sb == null) sb = StringBuilder(input.length)
      sb.append(input, last, m.start()).append(replacement)
      last = m.end()
      at = if (m.end() == m.start()) advance(input, m.end()) else m.end()
    }
    if (sb == null) return input
    return sb.append(input, last, input.length).toString()
  }

  private fun advance(input: String, at: Int): Int {
    if (unicode && at + 1 < input.length && input[at].isHighSurrogate() && input[at + 1].isLowSurrogate()) return at + 2
    return at + 1
  }

  override fun toString(): String = "/$source/$flags"
}

private fun toMatch(m: Matcher, input: String): JsMatch {
  val groups = ArrayList<String?>(m.groupCount() + 1)
  for (g in 0..m.groupCount()) groups.add(if (m.start(g) < 0) null else input.substring(m.start(g), m.end(g)))
  return JsMatch(m.start(), input, groups)
}

/** A stateless JS regex: for `test`, non-global `exec`, `replace` and `split`. */
fun jsRegex(source: String, flags: String = ""): JsRegExp = JsRegExp(source, flags)

/** `String.prototype.match`. */
fun String.jsMatch(re: JsRegExp): JsMatch? {
  require(!re.global) { "Use jsMatchGlobal for /g" }
  return re.exec(this)
}

/** `String.prototype.match` with a global regex: the matched strings, or null. */
fun String.jsMatchGlobal(re: JsRegExp): List<String>? {
  re.lastIndex = 0
  val all = re.allMatches(this)
  return if (all.isEmpty()) null else all.map { it.value }
}

/** `String.prototype.matchAll`. */
fun String.jsMatchAll(re: JsRegExp): List<JsMatch> = re.allMatches(this, if (re.global) re.lastIndex else 0)

/** `String.prototype.search`. */
fun String.jsSearch(re: JsRegExp): Int = re.matchAt(this, 0)?.index ?: -1

private fun matchesForReplace(input: String, re: JsRegExp): List<JsMatch> {
  if (re.global) {
    re.lastIndex = 0
    return re.allMatches(input)
  }
  return listOfNotNull(re.exec(input))
}

/** `String.prototype.replace(regex, replacement)`, with `$` patterns expanded. */
fun String.jsReplace(re: JsRegExp, replacement: String): String {
  if ('$' !in replacement) return re.replaceLiteral(this, replacement)
  val matches = matchesForReplace(this, re)
  if (matches.isEmpty()) return this
  val sb = StringBuilder(length)
  var last = 0
  for (m in matches) {
    sb.append(this, last, m.index)
    sb.append(expandReplacement(replacement, this, m.index, m.groups))
    last = m.end
  }
  sb.append(this, last, length)
  return sb.toString()
}

/** `String.prototype.replace(regex, fn)`. The function gets the whole match. */
fun String.jsReplace(re: JsRegExp, replacer: (JsMatch) -> String): String {
  val matches = matchesForReplace(this, re)
  if (matches.isEmpty()) return this
  val sb = StringBuilder(length)
  var last = 0
  for (m in matches) {
    sb.append(this, last, m.index)
    sb.append(replacer(m))
    last = m.end
  }
  sb.append(this, last, length)
  return sb.toString()
}

/** `String.prototype.replaceAll(regex, …)`. The regex must be global, as in JS. */
fun String.jsReplaceAll(re: JsRegExp, replacement: String): String {
  require(re.global) { "replaceAll needs a global RegExp" }
  return jsReplace(re, replacement)
}

fun String.jsReplaceAll(re: JsRegExp, replacer: (JsMatch) -> String): String {
  require(re.global) { "replaceAll needs a global RegExp" }
  return jsReplace(re, replacer)
}

/** `String.prototype.split(regex)`, following the spec's SplitMatcher, captures included. */
fun String.jsSplit(re: JsRegExp, limit: Int = Int.MAX_VALUE): List<String?> {
  val out = ArrayList<String?>()
  if (limit == 0) return out
  val size = length
  if (size == 0) {
    return if (re.matchAtSticky(this, 0) != null) out else listOf(this)
  }
  var p = 0
  var q = 0
  while (q < size) {
    val m = re.matchAtSticky(this, q)
    if (m == null) {
      q++
      continue
    }
    val e = minOf(m.end, size)
    if (e == p) {
      q++
      continue
    }
    out.add(substring(p, q))
    if (out.size == limit) return out
    p = e
    for (g in 1 until m.size) {
      out.add(m[g])
      if (out.size == limit) return out
    }
    q = p
  }
  out.add(substring(p, size))
  return out
}

/** [jsSplit] for patterns without capture groups, where every piece is defined. */
fun String.jsSplitStrings(re: JsRegExp, limit: Int = Int.MAX_VALUE): List<String> = jsSplit(re, limit).map { it ?: "" }

private fun JsRegExp.matchAtSticky(input: String, at: Int): JsMatch? = matchSticky(input, at)

/** Escapes a string for use in a JS pattern, as `s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')` does. */
fun escapeRegExp(s: String): String {
  val sb = StringBuilder()
  for (c in s) {
    if (c in ".*+?^\${}()|[]\\") sb.append('\\')
    sb.append(c)
  }
  return sb.toString()
}
