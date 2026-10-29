// Ported from Pele src/errors.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele

import pele.js.JsRegExp
import pele.js.jsMatchAll
import pele.js.jsSlice
import pele.js.jsTrimEnd

enum class PeleErrorCode(val wireName: String) {
  UNSUPPORTED_DIAGRAM("unsupported-diagram"),
  SYNTAX("syntax"),
  SEMANTIC("semantic"),
  LIMIT("limit");

  override fun toString(): String = wireName
}

class PeleErrorInfo(
  val type: String? = null,
  val mermaidType: String? = null,
  val line: Int? = null,
  val column: Int? = null,
  val snippet: String? = null,
)

class PeleError(
  message: String,
  val code: PeleErrorCode = PeleErrorCode.SYNTAX,
  info: PeleErrorInfo = PeleErrorInfo(),
) : RuntimeException(null, null, false, false) {
  // PORT: tagErrors() rewrites these after the error is thrown.
  override var message: String = message
  var type: String? = info.type
  var mermaidType: String? = info.mermaidType
  val line: Int = info.line ?: 0
  val column: Int = info.column ?: 0
  var snippet: String = info.snippet ?: ""

  constructor(
    message: String,
    code: PeleErrorCode,
    found: Detected,
  ) : this(message, code, PeleErrorInfo(type = found.type, mermaidType = found.mermaidType))
}

// Input range [from, to) replaced by `length` output characters.
typealias Edit = IntArray

fun replacements(text: String, re: JsRegExp, length: Int): MutableList<Edit> =
  text
    .jsMatchAll(re)
    .map { match -> intArrayOf(match.index, match.index + match.value.length, length) }
    .toMutableList()

private fun stepInput(edits: List<Edit>, at: Int): Int {
  var shift = 0
  for ((from, to, length) in edits) {
    val start = from - shift
    if (at < start) break
    if (at < start + length) return from + minOf(at - start, to - from - 1)
    shift += to - from - length
  }
  return at + shift
}

fun inputOffset(steps: List<List<Edit>>, at: Int): Int {
  var result = at
  for (s in steps.indices.reversed()) result = stepInput(steps[s], result)
  return result
}

class Origin(val parsed: String, val find: (Int) -> Pair<String, Int>)

// PORT: per-thread, as renders may run concurrently.
private val origin = ThreadLocal<Origin?>()

// Report positions in the original source while `run` parses.
fun <T> locatingIn(next: Origin, run: () -> T): T {
  val outer = origin.get()
  origin.set(next)
  try {
    return run()
  } finally {
    origin.set(outer)
  }
}

// Maps only when `src` is exactly the text being parsed.
fun written(src: String, offset: Int): Pair<String, Int> {
  val o = origin.get()
  return if (o != null && src == o.parsed) o.find(offset) else src to offset
}

data class Location(val line: Int, val column: Int, val snippet: String)

fun locate(srcIn: String, offsetIn: Int): Location {
  var src = srcIn
  var offset = offsetIn
  // Keep EOF errors on the last content line.
  val end = src.jsTrimEnd().length
  if (end > 0 && offset >= end) {
    val w = written(src, end - 1)
    src = w.first
    offset = w.second + 1
  } else {
    val w = written(src, offset)
    src = w.first
    offset = w.second
  }
  var line = 1
  var lineStart = 0
  var i = src.indexOf('\n')
  while (i != -1 && i < offset) {
    line++
    lineStart = i + 1
    i = src.indexOf('\n', i + 1)
  }
  var lineEnd = src.indexOf('\n', maxOf(offset, 0))
  if (lineEnd == -1) lineEnd = src.length
  val column = offset - lineStart + 1
  val text = src.jsSlice(lineStart, lineEnd)
  return Location(line, column, text + "\n" + "-".repeat(maxOf(column - 1, 0)) + "^")
}

fun syntaxError(
  type: String,
  src: String,
  offset: Int,
  detail: String,
  lexical: Boolean = false,
): PeleError {
  val loc = locate(src, offset)
  val head =
    if (lexical) "Lexical error on line ${loc.line}. Unrecognized text."
    else "Parse error on line ${loc.line}:"
  val message = if (lexical) "$head\n${loc.snippet}" else "$head\n${loc.snippet}\n$detail"
  return PeleError(
    message,
    PeleErrorCode.SYNTAX,
    PeleErrorInfo(type = type, line = loc.line, column = loc.column, snippet = loc.snippet),
  )
}
