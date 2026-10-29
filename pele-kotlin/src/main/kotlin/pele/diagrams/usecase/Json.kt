// Ported from Pele src/diagrams/usecase/json.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.JsonSyntaxError
import pele.js.charCodeAt
import pele.js.jsParseInt
import pele.js.jsReplaceAll
import pele.js.jsonParse
import pele.text.writtenCodes
import pele.util.isDigit
import pele.util.isHex

class OrderedJson(
  val value: Map<String, Any?>,
  val propertyOrder: Map<String, List<String>>,
)

// Thrown with the offset of the first character that is not valid JSON.
private class WalkError(val offset: Int) : RuntimeException("Invalid JSON token", null, false, false)

private class Frame(
  val `object`: Boolean,
  val pointer: String,
  val keys: MutableList<String>,
  // For each key read so far, the stretch of `created` its value added.
  val ranges: MutableMap<String, IntArray>,
  var key: String,
  var from: Int,
  var index: Int,
)

private object State {
  const val Value = 0
  const val Key = 1
  const val After = 2
}

// Walks JSON text that JSON.parse has accepted, to recover the order its keys were written in,
// which JSON.parse loses for integer-like keys. On text JSON.parse rejected it finds where.
// Nesting is kept on a stack of its own, so depth is limited by memory and not by the call stack.
private fun propertyOrder(text: String): Map<String, List<String>> {
  val order = LinkedHashMap<String, List<String>>()
  val created = ArrayList<String>()
  val stack = ArrayList<Frame>()
  val n = text.length
  var at = 0
  var pointer = ""
  var state = State.Value

  fun skip() {
    while (at < n) {
      val c = text.charCodeAt(at)
      if (c != 32 && c != 9 && c != 10 && c != 13) return
      at++
    }
  }

  fun readString(decode: Boolean): String {
    at++
    val value = StringBuilder()
    while (at < n) {
      val start = at
      val ch = text[at++]
      if (ch == '"') return value.toString()
      if (ch.code < 0x20) throw WalkError(start)
      if (ch != '\\') {
        if (decode) value.append(ch)
        continue
      }
      val escapeAt = at
      val escape = text.getOrNull(at++)
      val decoded: String
      if (escape == '"' || escape == '\\' || escape == '/') decoded = escape.toString()
      else if (escape == 'b') decoded = "\b"
      else if (escape == 'f') decoded = "\u000C"
      else if (escape == 'n') decoded = "\n"
      else if (escape == 'r') decoded = "\r"
      else if (escape == 't') decoded = "\t"
      else if (escape == 'u') {
        val unit = text.substring(minOf(at, n), minOf(at + 4, n))
        if (unit.length != 4 || !unit.all { isHex(it.code) }) throw WalkError(at)
        decoded = unit.toInt(16).toChar().toString()
        at += 4
      } else throw WalkError(escapeAt)
      if (decode) value.append(decoded)
    }
    throw WalkError(at)
  }

  fun literal(word: String) {
    for (k in word.indices) if (text.getOrNull(at + k) != word[k]) throw WalkError(at + k)
    at += word.length
  }

  fun number() {
    if (text.charCodeAt(at) == 45) at++
    if (text.charCodeAt(at) == 48) at++
    else if (isDigit(text.charCodeAt(at))) while (isDigit(text.charCodeAt(at))) at++
    else throw WalkError(at)
    if (text.charCodeAt(at) == 46) {
      at++
      if (!isDigit(text.charCodeAt(at))) throw WalkError(at)
      while (isDigit(text.charCodeAt(at))) at++
    }
    if ((text.charCodeAt(at) or 32) == 101) {
      at++
      if (text.charCodeAt(at) == 43 || text.charCodeAt(at) == 45) at++
      if (!isDigit(text.charCodeAt(at))) throw WalkError(at)
      while (isDigit(text.charCodeAt(at))) at++
    }
  }

  skip()
  while (true) {
    if (state == State.Value) {
      skip()
      val c = text.charCodeAt(at)
      state = State.After
      if (c == 123) {
        at++
        val keys = ArrayList<String>()
        order[pointer] = keys
        created.add(pointer)
        skip()
        if (text.charCodeAt(at) == 125) at++
        else {
          stack.add(Frame(true, pointer, keys, LinkedHashMap(), "", 0, 0))
          state = State.Key
        }
      } else if (c == 91) {
        at++
        skip()
        if (text.charCodeAt(at) == 93) at++
        else {
          stack.add(Frame(false, pointer, ArrayList(), LinkedHashMap(), "", 0, 0))
          pointer += "/0"
          state = State.Value
        }
      } else if (c == 34) readString(false)
      else if (c == 116) literal("true")
      else if (c == 102) literal("false")
      else if (c == 110) literal("null")
      else if (c == 45 || isDigit(c)) number()
      else throw WalkError(at)
    } else if (state == State.Key) {
      val frame = stack[stack.size - 1]
      if (text.charCodeAt(at) != 34) throw WalkError(at)
      val key = readString(true)
      val range = frame.ranges[key]
      // A repeated key keeps its first place in the order and takes the nested order of its last value.
      if (range == null) frame.keys.add(key)
      else for (k in range[0] until range[1]) order.remove(created[k])
      frame.key = key
      frame.from = created.size
      skip()
      if (text.charCodeAt(at) != 58) throw WalkError(at)
      at++
      pointer = "${frame.pointer}/${key.jsReplaceAll("~", "~0").jsReplaceAll("/", "~1")}"
      state = State.Value
    } else {
      val frame = stack.getOrNull(stack.size - 1) ?: break
      if (frame.`object`) frame.ranges[frame.key] = intArrayOf(frame.from, created.size)
      else frame.index++
      skip()
      val c = text.charCodeAt(at)
      if (c == (if (frame.`object`) 125 else 93)) {
        at++
        stack.removeAt(stack.size - 1)
      } else if (c != 44) throw WalkError(at)
      else {
        at++
        skip()
        pointer = "${frame.pointer}/${frame.index}"
        state = if (frame.`object`) State.Key else State.Value
      }
    }
  }
  skip()
  if (at != n) throw WalkError(at)
  return order
}

private fun locationAt(text: String, offset: Int, lineIn: Int, columnIn: Int): Pair<Int, Int> {
  var line = lineIn
  var column = columnIn
  val end = minOf(maxOf(offset, 0), text.length)
  for (i in 0 until end) {
    val c = text.charCodeAt(i)
    if (c == 13) {
      line++
      column = 1
    } else if (c == 10) {
      if (i == 0 || text.charCodeAt(i - 1) != 13) {
        line++
        column = 1
      }
    } else column++
  }
  return Pair(line, column)
}

private val RE_POSITION = JsRegExp("""\bposition (\d+)\b""")
private val RE_LOCAL = JsRegExp("""\bline (\d+) column (\d+)\b""")
private val RE_END = JsRegExp("""unexpected end|end of json input""", "i")

// Engines word JSON.parse errors differently: some give an offset, some a line and column.
private fun locationFromMessage(message: String, text: String, line: Int, column: Int): Pair<Int, Int>? {
  val position = RE_POSITION.exec(message)
  if (position != null) return locationAt(text, jsParseInt(position[1]!!, 10).toInt(), line, column)
  val local = RE_LOCAL.exec(message)
  if (local != null) {
    val localLine = jsParseInt(local[1]!!, 10).toInt()
    val localColumn = jsParseInt(local[2]!!, 10).toInt()
    return Pair(line + localLine - 1, if (localLine == 1) column + localColumn - 1 else localColumn)
  }
  if (RE_END.test(message)) return locationAt(text, text.length, line, column)
  return null
}

private fun jsonError(message: String, line: Int, column: Int): PeleError =
  PeleError("$message (line $line, column $column)", PeleErrorCode.SYNTAX, PeleErrorInfo(type = "usecase", line = line, column = column))

// Parses the body of a `json` node. `origin` gives the line and column the text starts at in the diagram.
fun parseOrderedJson(text: String, origin: () -> Pair<Int, Int>): OrderedJson {
  val parsed: Any?
  try {
    parsed = jsonParse(text)
  } catch (error: JsonSyntaxError) {
    var message = error.message ?: ""
    // Reparse restored entity codes so error messages and positions match the source.
    val shown = writtenCodes(text)
    if (shown != text) {
      try {
        jsonParse(shown)
      } catch (again: JsonSyntaxError) {
        message = again.message ?: ""
      }
    }
    val (line, column) = origin()
    var location = locationFromMessage(message, shown, line, column)
    if (location == null) {
      var offset = 0
      try {
        propertyOrder(shown)
      } catch (walk: WalkError) {
        offset = walk.offset
      }
      location = locationAt(shown, offset, line, column)
    }
    throw jsonError("Invalid JSON: $message", location.first, location.second)
  }
  if (parsed !is Map<*, *>) {
    val (line, column) = origin()
    throw jsonError("JSON value must have an object root", line, column)
  }
  @Suppress("UNCHECKED_CAST")
  return OrderedJson(parsed as Map<String, Any?>, propertyOrder(text))
}
