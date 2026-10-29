// Ported from Pele src/diagrams/treeview/boxDrawing.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treeview

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsParseInt
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsTrim
import pele.js.jsTrimEnd
import pele.locate

// Mermaid's box-drawing preprocessor: turns `├──`, `└──`, `│` (and the heavy variants) into the
// indentation the grammar reads.

private val ALL_BOX_CHARS = JsRegExp("""[─━│┃└┗├┣]""")
private val BRANCH_CHAR = JsRegExp("""[└┗├┣]""")
private val DASH_CHAR = JsRegExp("""[─━]""")
private val DECORATION_ONLY = JsRegExp("""^[\s│┃]+$""")
private val BOX_ONLY = JsRegExp("""^[\s─━│┃└┗├┣]+$""")
private val METADATA_LINE = JsRegExp("""^\s*(title[\t ]|accTitle[\t ]*:|accDescr[\t ]*[:{])""")
private val COMMENT_LINE = JsRegExp("""^\s*%%""")
private val LINE_WORD = JsRegExp("""\bline\s+(\d+)\b""", "gi")
private val TAB = JsRegExp("""\t""", "g")
private val LEADING_SPACE = JsRegExp("""^\s+""")
private const val INDENT_UNIT = "    "

class PreprocessResult(
  val text: String,
  // Output line number to original line number, both from 1. Empty when the text is unchanged.
  val lineMap: Map<Int, Int>,
  // Per output line, maps rewritten indices to original indices.
  val columns: Map<Int, (Int) -> Int>,
)

fun isBoxDrawingFormat(lines: List<String>): Boolean = lines.any { line ->
  ALL_BOX_CHARS.test(line)
}

private fun inferSegmentWidth(contentLines: List<String>): Int {
  for (line in contentLines) {
    val match = BRANCH_CHAR.exec(line)
    if (match != null && match.index > 0) return match.index
  }
  return 4
}

fun remapErrorLines(message: String, lineMap: Map<Int, Int>): String =
  message.jsReplace(LINE_WORD) { m ->
    val parsed = jsParseInt(m[1]!!, 10)
    val original = if (parsed <= Int.MAX_VALUE) lineMap[parsed.toInt()] else null
    if (original != null && original != 0) "line $original" else m.value
  }

fun lineStart(text: String, line: Int): Int {
  var at = 0
  for (n in 1 until line) at = text.indexOf('\n', at) + 1
  return at
}

private fun fail(input: String, index: Int, message: String): Nothing {
  val line = locate(input, lineStart(input, index + 1)).line
  throw PeleError(
    "Line $line: $message",
    PeleErrorCode.SYNTAX,
    PeleErrorInfo(type = "treeView", line = line),
  )
}

// Undo four-space tab expansion.
private fun beforeTabs(line: String, at: Int): Int {
  var i = 0
  var n = 0
  while (i < line.length && n < at) {
    n += if (line[i] == '\t') 4 else 1
    i++
  }
  return i
}

fun preprocessBoxDrawing(input: String): PreprocessResult {
  val lineMap = LinkedHashMap<Int, Int>()
  val columns = LinkedHashMap<Int, (Int) -> Int>()
  if (!ALL_BOX_CHARS.test(input)) return PreprocessResult(input, lineMap, columns)
  val lines = input.jsSplit("\n")

  var keywordIdx = -1
  for (i in lines.indices) {
    if (lines[i].jsTrim() == "treeView-beta") {
      keywordIdx = i
      break
    }
  }
  if (keywordIdx == -1) return PreprocessResult(input, lineMap, columns)

  val passes = { line: String ->
    line.jsTrim() == "" || COMMENT_LINE.test(line) || METADATA_LINE.test(line)
  }

  val contentLines = ArrayList<String>()
  for (i in keywordIdx + 1 until lines.size) {
    val line = lines[i]
    if (passes(line) || DECORATION_ONLY.test(line)) continue
    contentLines.add(line.jsReplace(TAB, "    "))
  }
  if (!isBoxDrawingFormat(contentLines)) return PreprocessResult(input, lineMap, columns)

  val segmentWidth = inferSegmentWidth(contentLines)
  val out = ArrayList<String>()
  val keep = { text: String, index: Int, column: ((Int) -> Int)? ->
    out.add(text)
    lineMap[out.size] = index + 1
    if (column != null) columns[out.size] = column
  }

  for (i in 0..keywordIdx) keep(lines[i], i, null)
  for (i in keywordIdx + 1 until lines.size) {
    val line = lines[i]
    if (passes(line)) {
      keep(line, i, null)
      continue
    }
    if (DECORATION_ONLY.test(line)) continue

    val normalized = line.jsReplace(TAB, "    ")
    val branch = BRANCH_CHAR.exec(normalized)
    if (branch != null) {
      val depth = jsRound(branch.index.toDouble() / segmentWidth).toInt() + 1
      var pos = branch.index + 1
      while (pos < normalized.length && DASH_CHAR.test(normalized[pos].toString())) pos++
      while (pos < normalized.length && normalized[pos] == ' ') pos++
      val content = normalized.jsSlice(pos).jsTrimEnd()
      if (content.isEmpty())
        fail(
          input,
          i,
          "Empty node — expected a filename or directory name after the box-drawing prefix",
        )
      val indent = INDENT_UNIT.repeat(depth)
      val endPos = pos
      keep(indent + content, i) { at -> beforeTabs(line, endPos + maxOf(at - indent.length, 0)) }
    } else if (BOX_ONLY.test(normalized)) {
      continue
    } else if (ALL_BOX_CHARS.test(normalized)) {
      // A name that happens to contain a box character, such as "Section ─ A.txt", is a root item.
      keep(line, i, null)
    } else if (LEADING_SPACE.test(normalized)) {
      fail(
        input,
        i,
        "Unexpected indentation without box-drawing characters. In box-drawing format, use ├── or └── prefixes for indented nodes.",
      )
    } else {
      keep(line, i, null)
    }
  }
  return PreprocessResult(out.joinToString("\n"), lineMap, columns)
}
