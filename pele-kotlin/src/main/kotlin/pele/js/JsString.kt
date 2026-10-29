// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

/**
 * The characters JS `\s` and `String.prototype.trim` treat as whitespace, as a regex class body.
 */
const val JS_WHITESPACE_CLASS_BODY =
  "\\t-\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"

/** JS WhiteSpace or LineTerminator, as `trim()` and `\s` see it. */
fun isJsWhitespace(c: Char): Boolean =
  when (c) {
    '\t',
    '\n',
    '\u000B',
    '\u000C',
    '\r',
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    ' ',
    '　',
    '﻿' -> true
    else -> c in ' '..' '
  }

fun String.jsTrim(): String {
  var start = 0
  var end = length
  while (start < end && isJsWhitespace(this[start])) start++
  while (end > start && isJsWhitespace(this[end - 1])) end--
  return if (start == 0 && end == length) this else substring(start, end)
}

fun String.jsTrimStart(): String {
  var start = 0
  while (start < length && isJsWhitespace(this[start])) start++
  return if (start == 0) this else substring(start)
}

fun String.jsTrimEnd(): String {
  var end = length
  while (end > 0 && isJsWhitespace(this[end - 1])) end--
  return if (end == length) this else substring(0, end)
}

/**
 * `s.charCodeAt(i)`. Out of range is NaN in JS; this returns -1, which compares the same with
 * `===`, `!==`, `>=` and `<=` against character codes but not with `<` or `>`: check those sites.
 */
fun String.charCodeAt(i: Int): Int = if (i >= 0 && i < length) this[i].code else -1

/** `s[i]` in JS: undefined out of range. */
fun String.at(i: Int): String? = if (i >= 0 && i < length) this[i].toString() else null

private fun relIndex(i: Int, len: Int): Int = if (i < 0) maxOf(len + i, 0) else minOf(i, len)

/** `String.prototype.slice`. */
fun String.jsSlice(start: Int, end: Int = length): String {
  val from = relIndex(start, length)
  val to = relIndex(end, length)
  return if (from >= to) "" else substring(from, to)
}

/** `String.prototype.substring`: clamps to [0, length] and swaps reversed arguments. */
fun String.jsSubstring(start: Int, end: Int = length): String {
  val a = start.coerceIn(0, length)
  val b = end.coerceIn(0, length)
  return if (a <= b) substring(a, b) else substring(b, a)
}

/** `String.prototype.substr`. */
fun String.jsSubstr(start: Int, len: Int = length): String {
  val from = relIndex(start, length)
  val to = minOf(from + maxOf(len, 0), length)
  return if (from >= to) "" else substring(from, to)
}

/** `Array.prototype.slice` on a list. */
fun <T> List<T>.jsSlice(start: Int, end: Int = size): List<T> {
  val from = relIndex(start, size)
  val to = relIndex(end, size)
  return if (from >= to) emptyList() else subList(from, to).toList()
}

/** `String.prototype.padStart`. */
fun String.jsPadStart(target: Int, pad: String = " "): String {
  if (target <= length || pad.isEmpty()) return this
  val fill = target - length
  val sb = StringBuilder()
  while (sb.length < fill) sb.append(pad)
  sb.setLength(fill)
  return sb.toString() + this
}

/** `String.prototype.padEnd`. */
fun String.jsPadEnd(target: Int, pad: String = " "): String {
  if (target <= length || pad.isEmpty()) return this
  val fill = target - length
  val sb = StringBuilder()
  while (sb.length < fill) sb.append(pad)
  sb.setLength(fill)
  return this + sb.toString()
}

/** `Array.from(s)` and `for (const ch of s)`: the string split into code points. */
fun String.toCodePointStrings(): List<String> {
  val out = ArrayList<String>(length)
  var i = 0
  while (i < length) {
    val c = this[i]
    if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
      out.add(substring(i, i + 2))
      i += 2
    } else {
      out.add(c.toString())
      i++
    }
  }
  return out
}

/** `String.prototype.codePointAt`. Returns -1 out of range (undefined in JS). */
fun String.jsCodePointAt(i: Int): Int = if (i >= 0 && i < length) codePointAt(i) else -1

/** `String.fromCodePoint`. */
fun fromCodePoint(vararg codes: Int): String {
  val sb = StringBuilder()
  for (c in codes) sb.appendCodePoint(c)
  return sb.toString()
}

/** `String.fromCharCode`. */
fun fromCharCode(vararg codes: Int): String {
  val sb = StringBuilder()
  for (c in codes) sb.append((c and 0xFFFF).toChar())
  return sb.toString()
}

/** `String.prototype.split(separator)` with a string separator. */
fun String.jsSplit(separator: String, limit: Int = Int.MAX_VALUE): List<String> {
  if (limit == 0) return emptyList()
  if (separator.isEmpty()) return map { it.toString() }.take(limit)
  val out = ArrayList<String>()
  var start = 0
  while (true) {
    val i = indexOf(separator, start)
    if (i == -1) break
    out.add(substring(start, i))
    if (out.size >= limit) return out
    start = i + separator.length
  }
  out.add(substring(start))
  return out
}

/**
 * `String.prototype.replace(string, replacement)`: the first occurrence, with `$` patterns
 * expanded.
 */
fun String.jsReplace(search: String, replacement: String): String {
  val i = indexOf(search)
  if (i == -1) return this
  val expanded = expandReplacement(replacement, this, i, listOf(search))
  return substring(0, i) + expanded + substring(i + search.length)
}

/** `String.prototype.replace(string, fn)`. */
fun String.jsReplace(search: String, replacer: (String) -> String): String {
  val i = indexOf(search)
  if (i == -1) return this
  return substring(0, i) + replacer(search) + substring(i + search.length)
}

/** `String.prototype.replaceAll(string, replacement)`. */
fun String.jsReplaceAll(search: String, replacement: String): String {
  if (search.isEmpty()) {
    val sb = StringBuilder()
    for (i in 0..length) {
      sb.append(expandReplacement(replacement, this, i, listOf("")))
      if (i < length) sb.append(this[i])
    }
    return sb.toString()
  }
  val sb = StringBuilder()
  var start = 0
  while (true) {
    val i = indexOf(search, start)
    if (i == -1) break
    sb.append(this, start, i)
    sb.append(expandReplacement(replacement, this, i, listOf(search)))
    start = i + search.length
  }
  sb.append(this, start, length)
  return sb.toString()
}

/** GetSubstitution: expands `$$`, `$&`, `` $` ``, `$'`, `$n` and `$nn` like JS. */
internal fun expandReplacement(
  replacement: String,
  input: String,
  position: Int,
  groups: List<String?>,
): String {
  if (replacement.indexOf('$') == -1) return replacement
  val matched = groups[0] ?: ""
  val m = groups.size - 1
  val sb = StringBuilder()
  var i = 0
  while (i < replacement.length) {
    val c = replacement[i]
    if (c != '$' || i + 1 >= replacement.length) {
      sb.append(c)
      i++
      continue
    }
    val n = replacement[i + 1]
    when {
      n == '$' -> {
        sb.append('$')
        i += 2
      }
      n == '&' -> {
        sb.append(matched)
        i += 2
      }
      n == '`' -> {
        sb.append(input, 0, position)
        i += 2
      }
      n == '\'' -> {
        val end = minOf(position + matched.length, input.length)
        sb.append(input, end, input.length)
        i += 2
      }
      n in '0'..'9' -> {
        val d1 = n - '0'
        val n2 = if (i + 2 < replacement.length) replacement[i + 2] else ' '
        if (n2 in '0'..'9' && d1 * 10 + (n2 - '0') in 1..m) {
          sb.append(groups[d1 * 10 + (n2 - '0')] ?: "")
          i += 3
        } else if (d1 in 1..m) {
          sb.append(groups[d1] ?: "")
          i += 2
        } else {
          sb.append('$')
          i++
        }
      }
      else -> {
        sb.append('$')
        i++
      }
    }
  }
  return sb.toString()
}

/** `JSON.stringify` for strings. */
fun jsonQuote(s: String): String {
  val sb = StringBuilder(s.length + 2)
  sb.append('"')
  for (i in s.indices) {
    val c = s[i]
    when {
      c == '"' -> sb.append("\\\"")
      c == '\\' -> sb.append("\\\\")
      c == '\b' -> sb.append("\\b")
      c == '\u000C' -> sb.append("\\f")
      c == '\n' -> sb.append("\\n")
      c == '\r' -> sb.append("\\r")
      c == '\t' -> sb.append("\\t")
      c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
      c.isHighSurrogate() && !(i + 1 < s.length && s[i + 1].isLowSurrogate()) ->
        sb.append("\\u").append(Integer.toHexString(c.code))
      c.isLowSurrogate() && !(i > 0 && s[i - 1].isHighSurrogate()) ->
        sb.append("\\u").append(Integer.toHexString(c.code))
      else -> sb.append(c)
    }
  }
  sb.append('"')
  return sb.toString()
}

/** `JSON.stringify` for the plain values a port passes it (strings, numbers, lists, maps). */
fun jsonStringify(value: Any?): String =
  when (value) {
    null -> "null"
    is String -> jsonQuote(value)
    is Boolean -> value.toString()
    is Double -> if (value.isNaN() || value.isInfinite()) "null" else jsStr(value)
    is Number -> jsonStringify(value.toDouble())
    is List<*> -> value.joinToString(",", "[", "]") { jsonStringify(it) }
    is Map<*, *> ->
      value.entries.joinToString(",", "{", "}") { (k, v) ->
        jsonQuote(k.toString()) + ":" + jsonStringify(v)
      }
    else -> jsonQuote(value.toString())
  }
