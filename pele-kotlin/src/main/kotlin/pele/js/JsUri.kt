// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

/** JS `URIError`. */
class JsUriError(message: String) : RuntimeException(message)

private fun hexValue(c: Char): Int = Character.digit(c, 16).let { if (c.code < 128) it else -1 }

/** ECMAScript `decodeURIComponent`. */
fun jsDecodeURIComponent(s: String): String {
  if (s.indexOf('%') == -1) return s
  val sb = StringBuilder(s.length)
  var k = 0
  val n = s.length
  while (k < n) {
    val c = s[k]
    if (c != '%') {
      sb.append(c)
      k++
      continue
    }
    if (k + 2 >= n) throw JsUriError("URI malformed")
    val h = hexValue(s[k + 1])
    val l = hexValue(s[k + 2])
    if (h < 0 || l < 0) throw JsUriError("URI malformed")
    val b = (h shl 4) or l
    k += 3
    if (b and 0x80 == 0) {
      sb.append(b.toChar())
      continue
    }
    val count = when {
      b and 0xE0 == 0xC0 -> 2
      b and 0xF0 == 0xE0 -> 3
      b and 0xF8 == 0xF0 -> 4
      else -> throw JsUriError("URI malformed")
    }
    var cp = b and (0xFF shr (count + 1))
    for (j in 1 until count) {
      if (k >= n || s[k] != '%' || k + 2 >= n) throw JsUriError("URI malformed")
      val hh = hexValue(s[k + 1])
      val ll = hexValue(s[k + 2])
      if (hh < 0 || ll < 0) throw JsUriError("URI malformed")
      val bb = (hh shl 4) or ll
      if (bb and 0xC0 != 0x80) throw JsUriError("URI malformed")
      cp = (cp shl 6) or (bb and 0x3F)
      k += 3
    }
    val min = when (count) {
      2 -> 0x80
      3 -> 0x800
      else -> 0x10000
    }
    if (cp < min || cp > 0x10FFFF || (cp in 0xD800..0xDFFF)) throw JsUriError("URI malformed")
    sb.appendCodePoint(cp)
  }
  return sb.toString()
}
