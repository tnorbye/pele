// Ported from Pele src/preprocess.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele

import pele.js.JsMatch
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.isTruthy
import pele.js.jsKeys
import pele.js.jsReplace
import pele.js.jsSplit
import pele.js.jsString
import pele.js.jsTrim
import pele.js.jsTrimStart
import pele.js.jsonParse
import pele.util.YamlValue
import pele.util.isSpace as isWhitespace
import pele.util.parseYaml

typealias Config = MutableMap<String, YamlValue>

class Preprocessed(
  val text: String,
  val title: String?,
  val config: Config,
  // The text before its comment lines are removed, and the number of front matter lines before it.
  val withComments: String,
  val lineOffset: Int,
)

private val RE_COMMENT = JsRegExp("""^\s*%%(?!{)[^\n]+\n?""", "gm")
private val RE_DIRECTIVE_HEAD = JsRegExp("""%{2}{\s*(?:(\w+)\s*:|(\w+))\s*""", "gi")
private val RE_DIRECTIVE_WORD = JsRegExp("""\w+""", "y")
private val RE_HTML_TAG = JsRegExp("""<(\w+)([^>]*)>""", "g")
private val RE_ATTR = JsRegExp("""="([^"]*)"""", "g")
private val UNSAFE_KEYS = setOf("__proto__", "constructor", "prototype")

private fun removeAll(text: String, re: JsRegExp, steps: MutableList<List<Edit>>?): String {
  steps?.add(replacements(text, re, 0))
  return text.jsReplace(re, "")
}

fun cleanupComments(textIn: String, steps: MutableList<List<Edit>>? = null): String {
  var text = textIn
  if (text.contains("%%")) text = removeAll(text, RE_COMMENT, steps)
  val trimmed = text.jsTrimStart()
  steps?.add(listOf(intArrayOf(0, text.length - trimmed.length, 0)))
  return trimmed
}

private val RE_CRLF = JsRegExp("""\r\n?""", "g")

fun normalizeLineBreaks(text: String): String = if (text.contains('\r')) text.jsReplace(RE_CRLF, "\n") else text

private fun isLineBreak(ch: Char?): Boolean = ch == '\n' || ch == '\r'

private fun isSpace(ch: Char?): Boolean = ch != null && isWhitespace(ch.code)

private fun charAt(s: String, i: Int): Char? = if (i >= 0 && i < s.length) s[i] else null

private class FrontMatter(val indent: String, val body: String, val length: Int)

// Finds a `---` fenced YAML block at the start of the text the way Mermaid's front matter regex does.
private fun matchFrontMatter(text: String): FrontMatter? {
  var cursor = 0
  while (cursor < text.length && isSpace(text[cursor]) && !isLineBreak(text[cursor])) cursor++
  val indent = text.substring(0, cursor)
  if (!text.startsWith("---", cursor)) return null

  val openingEnds = ArrayList<Int>()
  var i0 = cursor + 3
  while (i0 < text.length && isSpace(text[i0])) {
    if (isLineBreak(text[i0])) openingEnds.add(i0)
    i0++
  }
  if (openingEnds.isEmpty()) return null

  val fence = "$indent---"
  val closingEnd = { i: Int ->
    if (!isLineBreak(charAt(text, i)) || !text.startsWith(fence, i + 1)) {
      -1
    } else {
      var last = -1
      var j = i + 1 + fence.length
      while (j < text.length && isSpace(text[j])) {
        if (isLineBreak(text[j])) last = j
        j++
      }
      last
    }
  }

  val needle = "\n" + fence
  var lastFence = -1
  var i = text.lastIndexOf(needle)
  while (i > openingEnds[0]) {
    if (closingEnd(i) != -1) {
      lastFence = i
      break
    }
    i = if (i - 1 < 0) -1 else text.lastIndexOf(needle, i - 1)
  }
  if (lastFence == -1) return null

  var openingEnd = openingEnds[0]
  for (candidate in openingEnds) if (candidate < lastFence) openingEnd = candidate

  val bodyStart = openingEnd + 1
  val firstAt = if (bodyStart > 0) bodyStart - 1 else 0
  i = text.indexOf(needle, firstAt)
  while (i != -1 && i <= lastFence) {
    if (i >= bodyStart) {
      val end = closingEnd(i)
      if (end != -1) return FrontMatter(indent, text.substring(bodyStart, i), end + 1)
    }
    i = text.indexOf(needle, i + 1)
  }
  return null
}

@Suppress("UNCHECKED_CAST")
fun isRecord(value: Any?): Boolean = value is MutableMap<*, *>

@Suppress("UNCHECKED_CAST")
private fun asConfig(value: Any?): Config = value as Config

private fun merge(target: Config, source: Config): Config {
  for (key in jsKeys(source)) {
    if (key in UNSAFE_KEYS) continue
    val value = source[key]
    val current = target[key]
    if (isRecord(value) && isRecord(current)) {
      merge(asConfig(current), asConfig(value))
    } else if (isRecord(value)) {
      target[key] = merge(LinkedHashMap(), asConfig(value))
    } else {
      target[key] = value
    }
  }
  return target
}

private val RE_QUOTE = JsRegExp("'", "g")

// PORT: scan the repeated body of Mermaid's directive regex without Java's recursive alternation.
internal fun directiveMatches(text: String): List<JsMatch> {
  val matches = ArrayList<JsMatch>()
  var at = 0
  while (at < text.length) {
    val head = RE_DIRECTIVE_HEAD.matchAt(text, at) ?: break
    val start = head.end
    val word = RE_DIRECTIVE_WORD.matchAt(text, start)
    var end = word?.end ?: start
    if (word == null) {
      while (end < text.length && !text.startsWith("}%%", end)) {
        val c = text[end]
        if (c == '\u2028' || c == '\u2029' || (c == '\r' && text.getOrNull(end + 1) != '\n')) break
        end++
      }
    }
    val body = if (word == null) text.substring(start, end) else null
    while (end < text.length && isSpace(text[end])) end++
    if (text.startsWith("}%%", end)) end += 3
    matches.add(JsMatch(head.index, text, listOf(text.substring(head.index, end), head[1], head[2], word?.value, body)))
    at = end
  }
  return matches
}

private fun directives(text: String, config: Config, steps: MutableList<List<Edit>>?): String {
  if (!text.contains("%%{")) return text
  val normalized = text.jsTrim().jsReplace(RE_QUOTE, "\"")
  for (match in directiveMatches(normalized)) {
    val type = match[1] ?: match[2]
    if (type == "wrap") {
      config["wrap"] = true
    } else if ((type == "init" || type == "initialize") && !match[4].isNullOrEmpty()) {
      try {
        val args: Any? = jsonParse(match[4]!!.jsTrim())
        if (isRecord(args)) merge(config, asConfig(args))
      } catch (_: Exception) {
        // Mermaid ignores an init directive whose body is not valid JSON.
      }
    }
  }
  val matches = directiveMatches(text)
  steps?.add(matches.map { intArrayOf(it.index, it.end, 0) })
  val out = StringBuilder()
  var from = 0
  for (match in matches) {
    out.append(text, from, match.index)
    from = match.end
  }
  return out.append(text, from, text.length).toString()
}

// `steps` maps normalized source to `withComments`.
fun preprocess(source: String, steps: MutableList<List<Edit>>? = null): Preprocessed {
  var text = normalizeLineBreaks(source)
  // A tag needs a closing bracket, so nothing after the last one can match.
  val lastClose = if (text.contains('<')) text.lastIndexOf('>') else -1
  if (lastClose > 0) {
    text = text.substring(0, lastClose + 1).jsReplace(RE_HTML_TAG) { m ->
      "<" + m[1] + m[2]!!.jsReplace(RE_ATTR, "='$1'") + ">"
    } + text.substring(lastClose + 1)
  }

  val config: Config = LinkedHashMap()
  var title: String? = null
  var lineOffset = 0

  val front = if (text.contains("---")) matchFrontMatter(text) else null
  if (front != null) {
    val body = if (front.indent.isNotEmpty()) {
      front.body
        .jsSplit("\n")
        .joinToString("\n") { line -> if (line.startsWith(front.indent)) line.substring(front.indent.length) else line }
    } else {
      front.body
    }
    val parsed: YamlValue
    try {
      parsed = parseYaml(body)
    } catch (error: Exception) {
      throw PeleError("Front matter is not valid YAML. ${error.message}", PeleErrorCode.SYNTAX)
    }
    if (isRecord(parsed)) {
      val p = asConfig(parsed)
      if (isTruthy(p["title"])) title = jsString(p["title"])
      if (isRecord(p["config"])) merge(config, asConfig(p["config"]))
      if (isTruthy(p["displayMode"])) {
        val gantt = if (isRecord(config["gantt"])) asConfig(config["gantt"]) else LinkedHashMap<String, YamlValue>().also { config["gantt"] = it }
        gantt["displayMode"] = jsString(p["displayMode"])
      }
    }
    var i = text.indexOf('\n')
    while (i != -1 && i < front.length) {
      lineOffset++
      i = text.indexOf('\n', i + 1)
    }
    text = text.substring(front.length)
    steps?.add(listOf(intArrayOf(0, front.length, 0)))
  }

  text = directives(text, config, steps)
  return Preprocessed(cleanupComments(text), title, config, text, lineOffset)
}

private val RE_ENTITY = JsRegExp("""#\w+;""", "g")
private val RE_INT = JsRegExp("""^\+?\d+$""")
private val RE_LINE_BREAK = JsRegExp("[\\n\\r  ]", "g")

private fun recording(steps: MutableList<List<Edit>>?): MutableList<Edit>? {
  if (steps == null) return null
  val edits = ArrayList<Edit>()
  steps.add(edits)
  return edits
}

// Does what Mermaid's `text.replace(/keyword.*:\S*#.*;/g, drop the last character)` does:
// on a line where the keyword is followed by a colon, a color-like `#`, and later a semicolon,
// the last semicolon goes. Mermaid's regex backtracks without bound; this is one pass.
private fun dropColorSemicolons(text: String, keyword: String, steps: MutableList<List<Edit>>?): String {
  val edits = recording(steps)
  var from = text.indexOf(keyword)
  if (from == -1) return text
  val out = StringBuilder()
  var last = 0
  while (from != -1) {
    RE_LINE_BREAK.lastIndex = from
    val lineEnd = if (RE_LINE_BREAK.test(text)) RE_LINE_BREAK.lastIndex - 1 else text.length
    var afterColon = false
    var hash = -1
    for (j in from + keyword.length until lineEnd) {
      val c = text.charCodeAt(j)
      if (c == 58) {
        afterColon = true
      } else if (c == 35) {
        if (afterColon) {
          hash = j
          break
        }
      } else if (isWhitespace(c)) {
        afterColon = false
      }
    }
    if (hash != -1) {
      val semi = if (lineEnd - 1 < 0) -1 else text.lastIndexOf(';', lineEnd - 1)
      if (semi > hash) {
        edits?.add(intArrayOf(semi, semi + 1, 0))
        out.append(text, last, semi)
        last = semi + 1
      }
    }
    from = if (lineEnd >= text.length) -1 else text.indexOf(keyword, lineEnd + 1)
  }
  return if (last == 0) text else out.toString() + text.substring(last)
}

// Mermaid hides `#name;` entity codes behind placeholder characters so `#` and `;` survive parsing.
fun encodeEntities(textIn: String, steps: MutableList<List<Edit>>? = null): String {
  if (!textIn.contains('#')) return textIn
  val text = dropColorSemicolons(dropColorSemicolons(textIn, "style", steps), "classDef", steps)
  val edits = recording(steps)
  return text.jsReplace(RE_ENTITY) { m ->
    val s = m.value
    val at = m.index
    val inner = s.substring(1, s.length - 1)
    val code = (if (RE_INT.test(inner)) "ﬂ°°" else "ﬂ°") + inner + "¶ß"
    edits?.add(intArrayOf(at, at + s.length, code.length))
    code
  }
}
