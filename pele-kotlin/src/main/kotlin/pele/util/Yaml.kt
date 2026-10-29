// Ported from Pele src/util/yaml.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.util

import pele.js.JsRegExp
import pele.js.fromCodePoint
import pele.js.jsNumber
import pele.js.jsOrdered
import pele.js.jsParseInt
import pele.js.jsReplace
import pele.js.jsSplit
import pele.js.jsStr
import pele.js.jsString
import pele.js.jsTrim
import pele.js.jsTrimStart

// A small YAML reader covering what Mermaid feeds to js-yaml with the JSON schema:
// block and flow collections, quoted and plain scalars, and block scalars.

// null | Boolean | Double | String | MutableList<YamlValue> | MutableMap<String, YamlValue>
typealias YamlValue = Any?

// PORT: the Error that upstream throws; preprocess() reads its message.
class YamlError(message: String) : RuntimeException(message)

// Thrown when a quoted scalar or flow collection runs past the text read so far.
// Not an Error, so that throwing it does not capture a stack trace.
private class Incomplete(override val message: String) :
  RuntimeException(message, null, false, false)

private const val MAX_DEPTH = 64

private fun assign(target: MutableMap<String, YamlValue>, key: String, value: YamlValue) {
  target[key] = value
}

private val RE_CR = JsRegExp("""\r\n?""", "g")

private class Reader(src: String) {
  val lines: MutableList<String> =
    (if (src.contains('\r')) src.jsReplace(RE_CR, "\n") else src).jsSplit("\n").toMutableList()
  var i = 0

  fun skipBlank() {
    while (i < lines.size) {
      val t = lines[i].jsTrim()
      if (t != "" && t[0] != '#' && t != "---") break
      i++
    }
  }

  fun indent(): Int {
    val line = lines[i]
    var k = 0
    while (k < line.length && line[k] == ' ') k++
    if (k < line.length && line[k] == '\t')
      throw YamlError("YAML: tabs are not allowed for indentation")
    return k
  }
}

private val RE_INT = JsRegExp("""^[-+]?(?:0|[1-9][0-9]*)$""")
private val RE_HEX = JsRegExp("""^[-+]?0x[0-9a-fA-F]+$""")
private val RE_OCT = JsRegExp("""^[-+]?0o[0-7]+$""")
private val RE_BIN = JsRegExp("""^[-+]?0b[01]+$""")
private val RE_FLOAT =
  JsRegExp("""^[-+]?(?:[0-9][0-9]*(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?$""")
private val RE_KEY_START = JsRegExp("""[\s#:,[\]{}&*!|>'"%@`]""")

private fun charAt(s: String, i: Int): Char? = if (i >= 0 && i < s.length) s[i] else null

private fun isBlank(ch: Char?): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'

private class SplitKey(val key: String, val length: Int)

// Splits `key: rest` at the start of a line. Returns the raw key and the offset where the value
// starts.
private fun splitKey(line: String): SplitKey? {
  val first = charAt(line, 0)
  val end: Int
  var colon: Int
  if (first == '"' || first == '\'') {
    var i = 1
    while (true) {
      if (i >= line.length) return null
      val c = line[i]
      if (first == '"' && c == '\\') {
        i += 2
      } else if (c == first) {
        if (first == '"' || charAt(line, i + 1) != '\'') break
        i += 2
      } else {
        i++
      }
    }
    end = i + 1
    colon = end
    while (isBlank(charAt(line, colon))) colon++
    if (charAt(line, colon) != ':') return null
  } else {
    if (first == null || RE_KEY_START.test(first.toString())) return null
    if (first == '-' && (line.length < 2 || isBlank(line[1]) || line[1] == ':' || line[1] == '#'))
      return null
    colon = 1
    while (colon < line.length && line[colon] != ':' && line[colon] != '#') colon++
    if (charAt(line, colon) != ':') return null
    var e = colon
    while (isBlank(charAt(line, e - 1))) e--
    end = e
  }
  var length = colon + 1
  if (length < line.length && !isBlank(line[length])) return null
  while (isBlank(charAt(line, length))) length++
  return SplitKey(line.substring(0, end), length)
}

private val RE_INF = JsRegExp("""^[-+]?\.(?:inf|Inf|INF)$""")
private val RE_NAN = JsRegExp("""^\.(?:nan|NaN|NAN)$""")

private fun scalar(raw: String): YamlValue {
  val s = raw.jsTrim()
  if (s == "" || s == "~" || s == "null" || s == "Null" || s == "NULL") return null
  if (s == "true" || s == "True" || s == "TRUE") return true
  if (s == "false" || s == "False" || s == "FALSE") return false
  val c = s[0]
  if (c != '-' && c != '+' && c != '.' && (c < '0' || c > '9')) return s
  if (RE_INT.test(s) || RE_FLOAT.test(s)) return jsNumber(s)
  if (RE_HEX.test(s) || RE_OCT.test(s) || RE_BIN.test(s)) {
    val neg = s[0] == '-'
    val v = jsNumber(if (neg || s[0] == '+') s.substring(1) else s)
    return if (neg) -v else v
  }
  if (RE_INF.test(s)) return if (s[0] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
  if (RE_NAN.test(s)) return Double.NaN
  return s
}

private val ESCAPES: Map<Char, String> =
  mapOf(
    '0' to "\u0000",
    'a' to "\u0007",
    'b' to "\b",
    't' to "\t",
    'n' to "\n",
    'v' to "\u000B",
    'f' to "\u000C",
    'r' to "\r",
    'e' to "\u001b",
    ' ' to " ",
    '"' to "\"",
    '/' to "/",
    '\\' to "\\",
    'N' to "\u0085",
    '_' to " ",
    'L' to " ",
    'P' to " ",
  )

private val RE_NEWLINES = JsRegExp("""\n+""", "g")

// Folds the line breaks of a scalar: blanks around a break go, one break becomes a space, and
// each further break stays. The lines are trimmed by hand, because a pattern for blanks before
// a line break rescans a long run of blanks from every one of them.
private fun fold(text: String): String {
  if (!text.contains('\n')) return text
  val lines = text.jsSplit("\n").toMutableList()
  val last = lines.size - 1
  for (i in 0..last) {
    val line = lines[i]
    var from = 0
    var to = line.length
    if (i > 0) while (from < to && (line[from] == ' ' || line[from] == '\t')) from++
    if (i < last) while (to > from && (line[to - 1] == ' ' || line[to - 1] == '\t')) to--
    lines[i] = line.substring(from, to)
  }
  return lines.joinToString("\n").jsReplace(RE_NEWLINES) { m ->
    if (m.value.length == 1) " " else "\n".repeat(m.value.length - 1)
  }
}

private val RE_ESCAPED_BREAK = JsRegExp("""\\\n[ \t]*""", "g")

private fun unquoteDouble(body: String): String {
  if (!body.contains('\\')) return fold(body)
  val out = StringBuilder()
  val s = fold(body.jsReplace(RE_ESCAPED_BREAK, ""))
  var i = 0
  while (i < s.length) {
    val ch = s[i]
    if (ch != '\\') {
      out.append(ch)
      i++
      continue
    }
    i++
    val e = charAt(s, i)
    if (e == 'x' || e == 'u' || e == 'U') {
      val len = if (e == 'x') 2 else if (e == 'u') 4 else 8
      val code = jsParseInt(s.substring(minOf(i + 1, s.length), minOf(i + 1 + len, s.length)), 16)
      // PORT: String.fromCodePoint throws a RangeError for these.
      if (code.isNaN() || code < 0 || code > 0x10ffff)
        throw YamlError("Invalid code point ${jsStr(code)}")
      out.append(fromCodePoint(code.toInt()))
      i += len
    } else if (e != null && e in ESCAPES) {
      out.append(ESCAPES[e])
    } else {
      throw YamlError("YAML: unknown escape sequence")
    }
    i++
  }
  return out.toString()
}

private fun isJsSpace(c: Char): Boolean = isSpace(c.code)

private fun skip(s: String, start: Int, commas: Boolean): Int {
  var pos = start
  while (pos < s.length) {
    val c = s[pos]
    if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || (commas && c == ',')) {
      pos++
    } else if (c == '#' && (pos == 0 || isJsSpace(s[pos - 1]))) {
      val nl = s.indexOf('\n', pos)
      pos = if (nl == -1) s.length else nl
    } else {
      break
    }
  }
  return pos
}

// Entries of a flow collection are separated by exactly one comma, as js-yaml requires.
private fun entryStart(ch: Char, separated: Boolean) {
  if (!separated) throw YamlError("YAML: missed comma between flow collection entries")
  if (ch == ',') throw YamlError("YAML: expected the node content, but found ','")
}

private val RE_FLOW_STOP = JsRegExp("""[\s,[\]{}]""")
private val RE_QUOTES = JsRegExp("''", "g")

// Parses a flow value starting at s[pos]; returns the value and the index after it.
private fun flow(s: String, start: Int, stops: String, depth: Int = 0): Pair<YamlValue, Int> {
  if (depth > MAX_DEPTH) throw YamlError("YAML: nesting is too deep")
  var pos = skip(s, start, false)
  val ch = charAt(s, pos)
  if (ch == '{') {
    val out = LinkedHashMap<String, YamlValue>()
    pos++
    var separated = true
    while (true) {
      pos = skip(s, pos, false)
      if (pos >= s.length)
        throw Incomplete("YAML: unexpected end of the stream within a flow collection")
      if (s[pos] == '}') return jsOrdered(out) to pos + 1
      entryStart(s[pos], separated)
      val (key, afterKey) = flow(s, pos, ":,}", depth + 1)
      pos = skip(s, afterKey, false)
      var value: YamlValue = null
      if (charAt(s, pos) == ':') {
        val r = flow(s, pos + 1, ",}", depth + 1)
        value = r.first
        pos = r.second
      }
      assign(out, jsString(key), value)
      pos = skip(s, pos, false)
      separated = charAt(s, pos) == ','
      if (separated) pos++
    }
  }
  if (ch == '[') {
    val out = ArrayList<YamlValue>()
    pos++
    var separated = true
    while (true) {
      pos = skip(s, pos, false)
      if (pos >= s.length)
        throw Incomplete("YAML: unexpected end of the stream within a flow collection")
      if (s[pos] == ']') return out to pos + 1
      entryStart(s[pos], separated)
      val (value, after) = flow(s, pos, ",]", depth + 1)
      out.add(value)
      pos = skip(s, after, false)
      separated = charAt(s, pos) == ','
      if (separated) pos++
    }
  }
  if (ch == '"') {
    var end = pos + 1
    while (end < s.length && s[end] != '"') end += if (s[end] == '\\') 2 else 1
    if (end >= s.length)
      throw Incomplete("YAML: unexpected end of the stream within a double quoted scalar")
    return unquoteDouble(s.substring(pos + 1, end)) to end + 1
  }
  if (ch == '\'') {
    var end = pos + 1
    while (end < s.length) {
      if (s[end] == '\'') {
        if (charAt(s, end + 1) != '\'') break
        end++
      }
      end++
    }
    if (end >= s.length)
      throw Incomplete("YAML: unexpected end of the stream within a single quoted scalar")
    return fold(s.substring(pos + 1, end)).jsReplace(RE_QUOTES, "'") to end + 1
  }
  var end = pos
  while (end < s.length) {
    val c = s[end]
    if (
      stops.contains(c) &&
        (c != ':' || end + 1 >= s.length || RE_FLOW_STOP.test(s[end + 1].toString()))
    )
      break
    if (c == '#' && end > pos && isJsSpace(s[end - 1])) {
      val nl = s.indexOf('\n', end)
      return scalar(fold(s.substring(pos, end).jsTrim())) to (if (nl == -1) s.length else nl)
    }
    end++
  }
  return scalar(fold(s.substring(minOf(pos, end), end).jsTrim())) to end
}

private fun stripComment(s: String): String {
  var quote = '\u0000'
  var i = 0
  while (i < s.length) {
    val c = s[i]
    if (quote != '\u0000') {
      if (c == '\\' && quote == '"') {
        i++
      } else if (c == quote) {
        quote = '\u0000'
      }
    } else if (c == '"' || c == '\'') {
      quote = c
    } else if (c == '#' && (i == 0 || s[i - 1] == ' ' || s[i - 1] == '\t')) {
      return s.substring(0, i)
    }
    i++
  }
  return s
}

private val RE_EXPLICIT = JsRegExp("[1-9]")
private val RE_LEADING_BLANK = JsRegExp("^[ \\t]")

private fun blockScalar(r: Reader, header: String, parentIndent: Int): String {
  val folded = header[0] == '>'
  val chomp = if (header.contains('-')) "-" else if (header.contains('+')) "+" else ""
  val explicit = RE_EXPLICIT.exec(header)
  var indent = if (explicit != null) parentIndent + jsNumber(explicit.value).toInt() else -1
  val out = ArrayList<String>()
  while (r.i < r.lines.size) {
    val line = r.lines[r.i]
    if (line.jsTrim() == "") {
      out.add("")
      r.i++
      continue
    }
    val k = line.length - line.jsTrimStart().length
    if (indent == -1) {
      if (k <= parentIndent) break
      indent = k
    }
    if (k < indent) break
    out.add(line.substring(minOf(indent, line.length)))
    r.i++
  }
  var trailing = 0
  while (out.isNotEmpty() && out[out.size - 1] == "") {
    out.removeAt(out.size - 1)
    trailing++
  }
  val text: String
  if (folded) {
    val sb = StringBuilder()
    for (i in out.indices) {
      val cur = out[i]
      if (i > 0) {
        val prev = out[i - 1]
        if (cur == "") {
          sb.append('\n')
        } else if (prev != "") {
          sb.append(if (RE_LEADING_BLANK.test(prev) || RE_LEADING_BLANK.test(cur)) '\n' else ' ')
        }
      }
      sb.append(cur)
    }
    text = sb.toString()
  } else {
    text = out.joinToString("\n")
  }
  if (out.isEmpty()) return if (chomp == "+") "\n".repeat(trailing) else ""
  if (chomp == "-") return text
  if (chomp == "+") return text + "\n".repeat(trailing + 1)
  return text + "\n"
}

private val RE_BLOCK_HEADER = JsRegExp("""^[|>][-+0-9]*\s*(?:#.*)?$""")

private fun inlineValue(r: Reader, rest: String, indent: Int): YamlValue {
  val first = charAt(rest, 0)
  if (first == '|' || first == '>') {
    if (RE_BLOCK_HEADER.test(rest)) return blockScalar(r, rest, indent)
  }
  if (first == '{' || first == '[' || first == '"' || first == '\'') {
    // The value may continue on later lines. Read them in growing batches until it closes.
    val start = r.i
    var text = rest
    var taken = 0
    var batch = 1
    while (true) {
      try {
        val (value, end) = flow(text, 0, "")
        var used = 0
        var k = text.indexOf('\n')
        while (k != -1 && k < end) {
          used++
          k = text.indexOf('\n', k + 1)
        }
        val lineEnd = text.indexOf('\n', end)
        if (
          stripComment(
              text.substring(minOf(end, text.length), if (lineEnd == -1) text.length else lineEnd)
            )
            .jsTrim() != ""
        ) {
          throw YamlError("YAML: unexpected content after a value")
        }
        r.i = start + used
        return value
      } catch (err: Incomplete) {
        if (start + taken >= r.lines.size) throw YamlError(err.message)
        val more =
          r.lines.subList(start + taken, minOf(r.lines.size, start + taken + batch)).toList()
        text += "\n" + more.joinToString("\n")
        taken += more.size
        batch *= 2
      }
    }
  }
  var text = stripComment(rest).jsTrim()
  while (r.i < r.lines.size) {
    val line = r.lines[r.i]
    if (line.jsTrim() == "") {
      var j = r.i
      while (j < r.lines.size && r.lines[j].jsTrim() == "") j++
      if (j >= r.lines.size || r.lines[j].length - r.lines[j].jsTrimStart().length <= indent) break
      text += "\n".repeat(j - r.i)
      r.i = j
      continue
    }
    if (line.length - line.jsTrimStart().length <= indent) break
    val more = stripComment(line).jsTrim()
    text += (if (text.endsWith("\n")) "" else " ") + more
    r.i++
  }
  return scalar(text)
}

private fun isSeqLine(line: String): Boolean = line == "-" || line.startsWith("- ")

private fun block(r: Reader, minIndent: Int, depth: Int = 0): YamlValue {
  if (depth > MAX_DEPTH) throw YamlError("YAML: nesting is too deep")
  r.skipBlank()
  if (r.i >= r.lines.size) return null
  val indent = r.indent()
  if (indent < minIndent) return null
  val line = r.lines[r.i].substring(indent)

  if (isSeqLine(line)) {
    val out = ArrayList<YamlValue>()
    while (r.i < r.lines.size) {
      r.skipBlank()
      if (r.i >= r.lines.size || r.indent() != indent) break
      val cur = r.lines[r.i].substring(indent)
      if (!isSeqLine(cur)) break
      val rest = cur.substring(1)
      val pad = rest.length - rest.jsTrimStart().length + 1
      val body = stripComment(rest).jsTrim()
      if (body == "") {
        r.i++
        out.add(block(r, indent + 1, depth + 1))
      } else if (splitKey(body) != null) {
        r.lines[r.i] = " ".repeat(indent + pad) + rest.jsTrimStart()
        out.add(block(r, indent + pad, depth + 1))
      } else {
        r.i++
        out.add(inlineValue(r, rest.jsTrim(), indent))
      }
    }
    return out
  }

  if (splitKey(line) == null) {
    r.i++
    return inlineValue(r, line.jsTrim(), indent - 1)
  }

  val out = LinkedHashMap<String, YamlValue>()
  while (r.i < r.lines.size) {
    r.skipBlank()
    if (r.i >= r.lines.size) break
    val k = r.indent()
    if (k < indent) break
    if (k > indent) throw YamlError("YAML: bad indentation of a mapping entry")
    val cur = r.lines[r.i].substring(indent)
    val km = splitKey(cur) ?: throw YamlError("YAML: can not read a block mapping entry")
    val key = jsString(flow(km.key, 0, "").first)
    val rest = cur.substring(km.length)
    r.i++
    if (stripComment(rest).jsTrim() == "") {
      r.skipBlank()
      if (r.i < r.lines.size) {
        val next = r.indent()
        val nextLine = r.lines[r.i].substring(next)
        val seq = isSeqLine(nextLine)
        assign(
          out,
          key,
          if (next > indent || (seq && next == indent))
            block(r, if (seq) indent else indent + 1, depth + 1)
          else null,
        )
      } else {
        assign(out, key, null)
      }
    } else {
      assign(out, key, inlineValue(r, rest.jsTrim(), indent))
    }
  }
  return jsOrdered(out)
}

fun parseYaml(src: String): YamlValue {
  val trimmed = src.jsTrim()
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      val (value, end) = flow(trimmed, 0, "")
      if (skip(trimmed, end, false) == trimmed.length) return value
    } catch (err: Incomplete) {
      throw YamlError(err.message)
    }
  }
  val r = Reader(src)
  val value = block(r, 0)
  r.skipBlank()
  if (r.i < r.lines.size && r.lines[r.i].jsTrim() != "...") {
    throw YamlError("YAML: end of the stream or a document separator is expected")
  }
  return value
}
