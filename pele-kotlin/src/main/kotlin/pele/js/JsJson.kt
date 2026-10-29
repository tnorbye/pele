// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

/** A JSON.parse failure. The message follows V8's wording; matched against Node in JsCompatTest. */
class JsonSyntaxError(message: String) : RuntimeException(message)

/** Whether [key] is an array index, which JS objects list first, in ascending order. */
fun isArrayIndex(key: String): Boolean {
  if (key.isEmpty() || key.length > 10) return false
  if (key == "0") return true
  if (key[0] !in '1'..'9') return false
  for (c in key) if (c !in '0'..'9') return false
  return key.toLong() < 4294967295L
}

/**
 * The key order `Object.keys` reports: array indexes ascending, then the rest in insertion order.
 */
fun <V> jsKeys(map: Map<String, V>): List<String> {
  val keys = map.keys
  if (keys.none(::isArrayIndex)) return keys.toList()
  val indexes = keys.filter(::isArrayIndex).sortedBy { it.toLong() }
  return indexes + keys.filterNot(::isArrayIndex)
}

/** `Object.entries` order. */
fun <V> jsEntries(map: Map<String, V>): List<Pair<String, V>> =
  jsKeys(map).map { it to map.getValue(it) }

/** A new map with JS object key order, as a JS object literal or JSON.parse would have. */
fun <V> jsOrdered(map: Map<String, V>): LinkedHashMap<String, V> {
  val out = LinkedHashMap<String, V>(map.size * 2)
  for (k in jsKeys(map)) out[k] = map.getValue(k)
  return out
}

/**
 * `JSON.parse`. Objects become `LinkedHashMap<String, Any?>` in JS key order, arrays
 * `ArrayList<Any?>`, numbers `Double`.
 */
fun jsonParse(text: String): Any? = JsonParser(text).parseAll()

// Follows V8's JsonParser, so that messages and positions match; it keeps its nesting on a stack of
// its own, as V8 does.
private class JsonParser(val s: String) {
  val n = s.length
  var i = 0

  fun cur(): Int = if (i < n) s[i].code else -1

  // V8's CalculateFileLocation: \r\n counts as one line break.
  fun at(): String {
    var line = 1
    var lastBreak = 0
    var k = 0
    while (k < i) {
      if (s[k] == '\r' && k < i - 1 && s[k + 1] == '\n') k++
      if (s[k] == '\r' || s[k] == '\n') {
        line++
        lastBreak = k + 1
      }
      k++
    }
    return "at position $i (line $line column ${1 + (k - lastBreak)})"
  }

  fun failAt(prefix: String): Nothing = throw JsonSyntaxError("$prefix ${at()}")

  // V8's ReportUnexpectedCharacter: the message depends on the kind of character at the cursor.
  fun unexpected(): Nothing {
    val c = cur()
    if (c == -1) throw JsonSyntaxError("Unexpected end of JSON input")
    if (c == '-'.code || (c >= '0'.code && c <= '9'.code)) failAt("Unexpected number in JSON")
    if (c == '"'.code) failAt("Unexpected string in JSON")
    if (s == "NaN" || s == "Infinity" || s == "undefined" || s == "[object Object]")
      throw JsonSyntaxError("\"$s\" is not valid JSON")
    val token = s[i].toString()
    if (n < 21) throw JsonSyntaxError("Unexpected token '$token', \"$s\" is not valid JSON")
    if (i < 10)
      throw JsonSyntaxError(
        "Unexpected token '$token', \"${s.substring(0, i + 10)}\"... is not valid JSON"
      )
    if (i < n - 10)
      throw JsonSyntaxError(
        "Unexpected token '$token', ...\"${s.substring(i - 10, i + 10)}\"... is not valid JSON"
      )
    throw JsonSyntaxError(
      "Unexpected token '$token', ...\"${s.substring(i - 10)}\" is not valid JSON"
    )
  }

  fun ws() {
    while (i < n && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
  }

  fun parseAll(): Any? {
    val v = value()
    ws()
    if (i < n) failAt("Unexpected non-whitespace character after JSON")
    return v
  }

  class Frame(val isObject: Boolean) {
    val map = LinkedHashMap<String, Any?>()
    val list = ArrayList<Any?>()
    var key = ""
  }

  // Reads `"key":` of an object property, the cursor being where the key should start.
  fun property(frame: Frame, missing: String) {
    ws()
    if (cur() != '"'.code) failAt(missing)
    frame.key = str()
    ws()
    if (cur() != ':'.code) failAt("Expected ':' after property name in JSON")
    i++
  }

  fun value(): Any? {
    val stack = ArrayList<Frame>()
    var value: Any?
    while (true) {
      // Produce a value; starting an object or array continues until its first member is complete.
      produce@ while (true) {
        ws()
        val c = cur()
        when {
          c == '"'.code -> value = str()
          c == '-'.code || (c >= '0'.code && c <= '9'.code) -> value = number()
          c == '{'.code -> {
            i++
            ws()
            if (cur() == '}'.code) {
              i++
              value = LinkedHashMap<String, Any?>()
            } else {
              val frame = Frame(true)
              stack.add(frame)
              property(frame, "Expected property name or '}' in JSON")
              continue@produce
            }
          }
          c == '['.code -> {
            i++
            ws()
            if (cur() == ']'.code) {
              i++
              value = ArrayList<Any?>()
            } else {
              stack.add(Frame(false))
              continue@produce
            }
          }
          c == 't'.code -> value = literal("true", true)
          c == 'f'.code -> value = literal("false", false)
          c == 'n'.code -> value = literal("null", null)
          else -> unexpected()
        }
        break
      }
      // Consume it into the object or array it belongs to.
      while (true) {
        val frame = stack.lastOrNull() ?: return value
        if (frame.isObject) {
          frame.map[frame.key] = value
          ws()
          if (cur() == ','.code) {
            i++
            property(frame, "Expected double-quoted property name in JSON")
            break
          }
          if (cur() != '}'.code) failAt("Expected ',' or '}' after property value in JSON")
          i++
          value = jsOrdered(frame.map)
        } else {
          frame.list.add(value)
          ws()
          if (cur() == ','.code) {
            i++
            break
          }
          if (cur() != ']'.code) failAt("Expected ',' or ']' after array element in JSON")
          i++
          value = frame.list
        }
        stack.removeAt(stack.size - 1)
      }
    }
  }

  fun literal(word: String, v: Any?): Any? {
    for (k in word.indices) {
      if (i + k >= n || s[i + k] != word[k]) {
        i += k
        unexpected()
      }
    }
    i += word.length
    return v
  }

  fun digit(): Boolean = i < n && s[i] in '0'..'9'

  fun number(): Double {
    val start = i
    if (s[i] == '-') {
      i++
      if (!digit()) failAt("No number after minus sign in JSON")
    }
    if (s[i] == '0') {
      i++
      if (digit()) failAt("Unexpected number in JSON")
    } else {
      while (digit()) i++
    }
    if (i < n && s[i] == '.') {
      i++
      if (!digit()) failAt("Unterminated fractional number in JSON")
      while (digit()) i++
    }
    if (i < n && (s[i] == 'e' || s[i] == 'E')) {
      i++
      if (i < n && (s[i] == '+' || s[i] == '-')) i++
      if (!digit()) failAt("Exponent part is missing a number in JSON")
      while (digit()) i++
    }
    return s.substring(start, i).toDouble()
  }

  fun str(): String {
    i++
    val sb = StringBuilder()
    while (true) {
      if (i >= n) failAt("Unterminated string in JSON")
      val c = s[i]
      when {
        c == '"' -> {
          i++
          return sb.toString()
        }
        c == '\\' -> {
          i++
          if (i >= n || s[i].code > 255) unexpected()
          when (s[i]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
              for (k in 1..4) {
                if (i + k >= n || Character.digit(s[i + k], 16) == -1 || s[i + k].code > 127) {
                  i += k
                  failAt("Bad Unicode escape in JSON")
                }
              }
              sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
              i += 4
            }
            else -> failAt("Bad escaped character in JSON")
          }
          i++
        }
        c.code < 0x20 -> failAt("Bad control character in string literal in JSON")
        else -> {
          sb.append(c)
          i++
        }
      }
    }
  }
}
