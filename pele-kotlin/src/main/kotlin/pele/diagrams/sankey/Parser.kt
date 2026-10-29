// Ported from Pele src/diagrams/sankey/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sankey

import pele.Edit
import pele.inputOffset
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsParseFloat
import pele.js.jsReplace
import pele.js.jsReplaceAll
import pele.js.jsSlice
import pele.js.jsTrim
import pele.js.jsTrimStart
import pele.replacements
import pele.syntaxError
import pele.text.decodeEntities

private val RE_LINE_BREAKS = JsRegExp("""[\n\r]+""", "g")
private val RE_INVALID_XML = JsRegExp("""[\x00-\x08\x0b\x0c\x0e-\x1f\ud800-\udfff￾￿]""", "u")

private fun isBlank(c: Int): Boolean {
  if (c == 10 || c == 13) return false
  if (c < 128) return c == 32 || c == 9 || c == 11 || c == 12
  return c == 0xa0 ||
    c == 0x1680 ||
    (c >= 0x2000 && c <= 0x200a) ||
    c == 0x2028 ||
    c == 0x2029 ||
    c == 0x202f ||
    c == 0x205f ||
    c == 0x3000 ||
    c == 0xfeff
}

private fun trimRows(text: String, edits: MutableList<Edit>): String {
  var end = text.length
  while (end > 0 && isBlank(text.charCodeAt(end - 1))) end--
  var out = ""
  var kept = 0
  var quoted = false
  var i = 0
  while (i < end) {
    val c = text.charCodeAt(i)
    if (quoted) {
      if (c == 34) {
        if (text.charCodeAt(i + 1) == 34) i++
        else quoted = false
      }
      i++
    } else if (c == 34) {
      quoted = true
      i++
    } else if (isBlank(c)) {
      var j = i + 1
      while (isBlank(text.charCodeAt(j))) j++
      val p = text.charCodeAt(i - 1)
      val q = text.charCodeAt(j)
      // Preserve blank first fields, which Mermaid accepts as empty names.
      if (i == 0 || q == 10 || q == 13 || ((p == 10 || p == 13) && q != 44)) {
        out += text.jsSlice(kept, i)
        edits.add(intArrayOf(i, j, 0))
        kept = j
      }
      i = j
    } else {
      i++
    }
  }
  if (end < text.length) edits.add(intArrayOf(end, text.length, 0))
  return out + text.jsSlice(kept, end)
}

// Unlike Mermaid's sankeyUtils, also trim row edges outside quoted fields.
fun prepareTextForParsing(text: String, steps: MutableList<List<Edit>>? = null): String {
  val edits = ArrayList<Edit>()
  val rows = trimRows(text, edits)
  val collapsed = rows.jsReplace(RE_LINE_BREAKS, "\n")
  val trimmed = collapsed.jsTrim()
  if (steps != null) {
    val lead = collapsed.length - collapsed.jsTrimStart().length
    steps.add(edits)
    steps.add(replacements(rows, RE_LINE_BREAKS, 1))
    steps.add(listOf(intArrayOf(0, lead, 0), intArrayOf(lead + trimmed.length, collapsed.length, 0)))
  }
  return trimmed
}

// Report errors in `source`, the text before prepareTextForParsing.
fun parseSankey(src: String, db: SankeyDb, source: String = src) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  val error = { at: Int, detail: String ->
    val steps = ArrayList<List<Edit>>()
    if (source != src) prepareTextForParsing(source, steps)
    // Map EOF before the trimmed trailing blanks.
    val offset = if (at > 0 && at >= src.length) inputOffset(steps, src.length - 1) + 1 else inputOffset(steps, at)
    syntaxError("sankey", source, offset, detail)
  }

  val fail = { expected: String ->
    val t = types[i]
    val got = if (t == T.STUCK) TOKEN_NAMES[types[i - 1]] else TOKEN_NAMES[t]
    throw error(starts[i], "Expecting $expected, got '$got'")
  }

  val expect = { type: Int, also: String ->
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'$also")
    i++
  }

  val field = {
    if (types[i] == T.NON_ESCAPED_TEXT) {
      texts[i++]
    } else {
      if (types[i] != T.DQUOTE) fail("'DQUOTE', 'NON_ESCAPED_TEXT'")
      i++
      if (types[i] != T.ESCAPED_TEXT) fail("'ESCAPED_TEXT'")
      val text = texts[i++]
      expect(T.DQUOTE, "")
      text
    }
  }

  val name = {
    val start = starts[i]
    val text = field().jsTrim().jsReplaceAll("\"\"", "\"")
    // Entity codes can encode characters excluded by the lexer.
    if (RE_INVALID_XML.test(decodeEntities(text))) {
      throw error(start, "Invalid XML character in node name")
    }
    text
  }

  expect(T.SANKEY, "")
  expect(T.NEWLINE, "")
  while (true) {
    val from = name()
    expect(T.COMMA, ", 'NEWLINE', 'EOF'")
    val to = name()
    expect(T.COMMA, ", 'NEWLINE', 'EOF'")
    val start = starts[i]
    // Keep Mermaid's numeric prefixes, but reject non-finite values.
    val value = jsParseFloat(field())
    if (!value.isFinite()) throw error(start, "Expecting a number")
    val sourceNode = db.findOrCreateNode(from)
    val targetNode = db.findOrCreateNode(to)
    db.addLink(sourceNode, targetNode, value)
    if (types[i] != T.NEWLINE) break
    i++
  }
  if (types[i] == T.EOF) i++
  if (types[i] != T.END) fail("'NEWLINE', 'EOF'")
}
