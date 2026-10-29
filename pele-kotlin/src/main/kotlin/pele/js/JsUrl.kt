// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

import java.text.Normalizer

// `new URL(input).href` from the WHATWG URL Standard, for what util/url.ts needs: http and https
// URLs without a base. java.net.URI rejects inputs browsers accept, so this follows the spec.

private fun isC0OrSpace(c: Char) = c.code <= 0x20

private fun inC0(cp: Int) = cp < 0x20 || cp > 0x7E

private fun inFragment(cp: Int) = inC0(cp) || cp == 0x20 || cp == '"'.code || cp == '<'.code || cp == '>'.code || cp == '`'.code

private fun inQuery(cp: Int) = inC0(cp) || cp == 0x20 || cp == '"'.code || cp == '#'.code || cp == '<'.code || cp == '>'.code

private fun inSpecialQuery(cp: Int) = inQuery(cp) || cp == '\''.code

private fun inPath(cp: Int) = inQuery(cp) || cp == '?'.code || cp == '^'.code || cp == '`'.code || cp == '{'.code || cp == '}'.code

private fun inUserinfo(cp: Int) =
  inPath(cp) || cp == '/'.code || cp == ':'.code || cp == ';'.code || cp == '='.code || cp == '@'.code ||
    cp == '['.code || cp == '\\'.code || cp == ']'.code || cp == '|'.code

private fun percentEncode(s: String, set: (Int) -> Boolean): String {
  val sb = StringBuilder(s.length)
  var i = 0
  while (i < s.length) {
    var cp = s.codePointAt(i)
    val len = Character.charCount(cp)
    if (cp in 0xD800..0xDFFF) cp = 0xFFFD
    if (!set(cp)) {
      sb.appendCodePoint(cp)
    } else {
      for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
        sb.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[b.toInt() and 0xF])
      }
    }
    i += len
  }
  return sb.toString()
}

private fun percentDecodeBytes(s: String): ByteArray {
  val bytes = s.toByteArray(Charsets.UTF_8)
  val out = java.io.ByteArrayOutputStream(bytes.size)
  var i = 0
  while (i < bytes.size) {
    val b = bytes[i]
    if (b == '%'.code.toByte() && i + 2 < bytes.size) {
      val h = Character.digit(bytes[i + 1].toInt().toChar(), 16)
      val l = Character.digit(bytes[i + 2].toInt().toChar(), 16)
      if (h >= 0 && l >= 0 && bytes[i + 1] >= 0 && bytes[i + 2] >= 0) {
        out.write((h shl 4) or l)
        i += 3
        continue
      }
    }
    out.write(b.toInt())
    i++
  }
  return out.toByteArray()
}

private const val FORBIDDEN_HOST = "\u0000\t\n\r #/:<>?@[\\]^|"

private fun isForbiddenDomain(c: Char) = FORBIDDEN_HOST.indexOf(c) >= 0 || c.code <= 0x1F || c == '%' || c.code == 0x7F

private fun punycode(label: String): String {
  val input = label.codePoints().toArray()
  val out = StringBuilder()
  for (c in input) if (c < 0x80) out.append(c.toChar())
  val b = out.length
  var h = b
  if (b > 0) out.append('-')
  var n = 128
  var delta = 0L
  var bias = 72
  fun adapt(d0: Long, numPoints: Int, first: Boolean): Int {
    var d = if (first) d0 / 700 else d0 / 2
    d += d / numPoints
    var k = 0
    while (d > ((36 - 1) * 26) / 2) {
      d /= 35
      k += 36
    }
    return (k + (36 * d) / (d + 38)).toInt()
  }
  fun digit(d: Int): Char = if (d < 26) ('a' + d) else ('0' + (d - 26))
  while (h < input.size) {
    var m = Int.MAX_VALUE
    for (c in input) if (c >= n && c < m) m = c
    delta += (m - n).toLong() * (h + 1)
    n = m
    for (c in input) {
      if (c < n) delta++
      if (c == n) {
        var q = delta
        var k = 36
        while (true) {
          val t = if (k <= bias) 1 else if (k >= bias + 26) 26 else k - bias
          if (q < t) break
          out.append(digit((t + (q - t) % (36 - t)).toInt()))
          q = (q - t) / (36 - t)
          k += 36
        }
        out.append(digit(q.toInt()))
        bias = adapt(delta, h + 1, h == b)
        delta = 0
        h++
      }
    }
    delta++
    n++
  }
  return out.toString()
}

// Code points UTS #46 maps to nothing.
private fun isIgnored(cp: Int): Boolean =
  cp == 0xAD || cp == 0x34F || cp == 0x200B || cp == 0x2060 || cp == 0xFEFF || cp in 0x180B..0x180F ||
    cp in 0xFE00..0xFE0F || cp in 0x1BCA0..0x1BCA3 || cp in 0xE0100..0xE01EF

private fun isDisallowed(cp: Int): Boolean =
  when (Character.getType(cp).toByte()) {
    Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.UNASSIGNED,
    Character.PRIVATE_USE, Character.SURROGATE, Character.FORMAT -> true
    else -> false
  }

private fun isMark(cp: Int): Boolean =
  when (Character.getType(cp).toByte()) {
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
    else -> false
  }

// An approximation of UTS #46 ToASCII with the options the URL Standard uses: map (NFKC, lower
// case, ignored code points dropped), reject disallowed code points and labels that start with a
// combining mark, and Punycode-encode labels that are not ASCII.
// RFC 3492 decoding, or null when the input is not valid Punycode.
private fun punycodeDecode(input: String): String? {
  val out = ArrayList<Int>()
  val b = input.lastIndexOf('-')
  if (b > 0) {
    for (k in 0 until b) {
      if (input[k].code >= 0x80) return null
      out.add(input[k].code)
    }
  }
  var n = 128
  var i = 0L
  var bias = 72
  var pos = if (b > 0) b + 1 else 0
  while (pos < input.length) {
    val oldi = i
    var w = 1L
    var k = 36
    while (true) {
      if (pos >= input.length) return null
      val c = input[pos++]
      val digit = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        in '0'..'9' -> c - '0' + 26
        else -> return null
      }
      i += digit * w
      if (i > Int.MAX_VALUE) return null
      val t = if (k <= bias) 1 else if (k >= bias + 26) 26 else k - bias
      if (digit < t) break
      w *= (36 - t)
      if (w > Int.MAX_VALUE) return null
      k += 36
    }
    val len = out.size + 1
    var delta = if (oldi == 0L) (i - oldi) / 700 else (i - oldi) / 2
    delta += delta / len
    var kk = 0
    while (delta > ((36 - 1) * 26) / 2) {
      delta /= 35
      kk += 36
    }
    bias = (kk + (36 * delta) / (delta + 38)).toInt()
    n += (i / len).toInt()
    if (n > 0x10FFFF) return null
    i %= len
    out.add(i.toInt(), n)
    i++
  }
  val sb = StringBuilder()
  for (cp in out) sb.appendCodePoint(cp)
  return sb.toString()
}

private fun domainToAscii(domain: String): String? {
  val asciiOnly = domain.all { it.code < 0x80 }
  val kept = StringBuilder()
  var i = 0
  while (i < domain.length) {
    val cp = domain.codePointAt(i)
    if (!isIgnored(cp)) kept.appendCodePoint(cp)
    i += Character.charCount(cp)
  }
  var d = Normalizer.normalize(kept, Normalizer.Form.NFKC)
  d = d.replace('\u3002', '.').replace('\uFF0E', '.').replace('\uFF61', '.')
  // Full lower case (İ becomes i and a combining dot), keeping ß, which UTS 46 doesn't map.
  d = d.jsSplit("\u00DF").joinToString("\u00DF") { it.lowercase() }
  d = Normalizer.normalize(d, Normalizer.Form.NFC)
  if (d.codePoints().anyMatch { it >= 0x80 && isDisallowed(it) }) return null
  val labels = d.split('.')
  val out = labels.map { label ->
    if (label.isNotEmpty() && isMark(label.codePointAt(0))) return null
    if (!asciiOnly && label.startsWith("xn--")) {
      // An encoded label must decode to a valid label that is not all ASCII. Like Node (ada), only
      // checked when the host is not ASCII to begin with.
      if (label.any { it.code >= 0x80 }) return null
      val decoded = punycodeDecode(label.substring(4)) ?: return null
      if (decoded.isEmpty() || decoded.all { it.code < 0x80 }) return null
      if (isMark(decoded.codePointAt(0)) || decoded.codePoints().anyMatch { isDisallowed(it) }) return null
      if (decoded != Normalizer.normalize(decoded, Normalizer.Form.NFC) || decoded != decoded.lowercase()) return null
    }
    if (label.all { it.code < 0x80 }) label else "xn--" + punycode(label)
  }
  val result = out.joinToString(".")
  if (result.isEmpty()) return null
  return result
}

private fun parseIpv4Number(s: String): Long? {
  if (s.isEmpty()) return null
  var input = s
  var radix = 10
  if (input.length >= 2 && (input.startsWith("0x") || input.startsWith("0X"))) {
    input = input.substring(2)
    radix = 16
  } else if (input.length >= 2 && input.startsWith("0")) {
    input = input.substring(1)
    radix = 8
  }
  if (input.isEmpty()) return 0
  for (c in input) if (Character.digit(c, radix) < 0 || c.code >= 128) return null
  return java.math.BigInteger(input, radix).min(java.math.BigInteger.valueOf(1L shl 40)).toLong()
}

private fun endsInNumber(host: String): Boolean {
  val parts = host.split('.').toMutableList()
  if (parts.last().isEmpty()) {
    if (parts.size == 1) return false
    parts.removeAt(parts.size - 1)
  }
  val last = parts.last()
  if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
  return parseIpv4Number(last) != null
}

private fun parseIpv4(host: String): String? {
  val parts = host.split('.').toMutableList()
  if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.size - 1)
  if (parts.size > 4) return null
  val numbers = ArrayList<Long>()
  for (p in parts) numbers.add(parseIpv4Number(p) ?: return null)
  for (i in 0 until numbers.size - 1) if (numbers[i] > 255) return null
  var limit = 1L
  repeat(5 - numbers.size) { limit *= 256 }
  if (numbers.last() >= limit) return null
  var ipv4 = numbers.last()
  for (i in 0 until numbers.size - 1) {
    var f = 1L
    repeat(3 - i) { f *= 256 }
    ipv4 += numbers[i] * f
  }
  return (0 until 4).reversed().joinToString(".") { ((ipv4 shr (8 * it)) and 0xFF).toString() }
}

private fun parseIpv6(input: String): String? {
  val address = IntArray(8)
  var pieceIndex = 0
  var compress = -1
  var p = 0
  val n = input.length
  fun c(i: Int): Char? = if (i < n) input[i] else null
  if (c(p) == ':') {
    if (c(p + 1) != ':') return null
    p += 2
    pieceIndex++
    compress = pieceIndex
  }
  while (c(p) != null) {
    if (pieceIndex == 8) return null
    if (c(p) == ':') {
      if (compress != -1) return null
      p++
      pieceIndex++
      compress = pieceIndex
      continue
    }
    var value = 0
    var length = 0
    while (length < 4 && c(p) != null && Character.digit(c(p)!!, 16) >= 0 && c(p)!!.code < 128) {
      value = value * 16 + Character.digit(c(p)!!, 16)
      p++
      length++
    }
    if (c(p) == '.') {
      if (length == 0) return null
      p -= length
      if (pieceIndex > 6) return null
      var numbersSeen = 0
      while (c(p) != null) {
        var ipv4Piece = -1
        if (numbersSeen > 0) {
          if (c(p) == '.' && numbersSeen < 4) p++ else return null
        }
        if (c(p) == null || c(p)!! !in '0'..'9') return null
        while (c(p) != null && c(p)!! in '0'..'9') {
          val number = c(p)!! - '0'
          if (ipv4Piece == -1) {
            ipv4Piece = number
          } else if (ipv4Piece == 0) {
            return null
          } else {
            ipv4Piece = ipv4Piece * 10 + number
          }
          if (ipv4Piece > 255) return null
          p++
        }
        address[pieceIndex] = address[pieceIndex] * 0x100 + ipv4Piece
        numbersSeen++
        if (numbersSeen == 2 || numbersSeen == 4) pieceIndex++
      }
      if (numbersSeen != 4) return null
      break
    } else if (c(p) == ':') {
      p++
      if (c(p) == null) return null
    } else if (c(p) != null) {
      return null
    }
    address[pieceIndex] = value
    pieceIndex++
  }
  if (compress != -1) {
    var swaps = pieceIndex - compress
    pieceIndex = 7
    while (pieceIndex != 0 && swaps > 0) {
      val t = address[pieceIndex]
      address[pieceIndex] = address[compress + swaps - 1]
      address[compress + swaps - 1] = t
      pieceIndex--
      swaps--
    }
  } else if (pieceIndex != 8) {
    return null
  }
  // Serialize, compressing the first longest run of two or more zero pieces.
  var bestStart = -1
  var bestLen = 1
  var i = 0
  while (i < 8) {
    if (address[i] == 0) {
      var j = i
      while (j < 8 && address[j] == 0) j++
      if (j - i > bestLen) {
        bestStart = i
        bestLen = j - i
      }
      i = j
    } else {
      i++
    }
  }
  val sb = StringBuilder("[")
  var ignore0 = false
  for (k in 0 until 8) {
    if (ignore0 && address[k] == 0) continue
    ignore0 = false
    if (bestStart == k) {
      sb.append(if (k == 0) "::" else ":")
      ignore0 = true
      continue
    }
    sb.append(Integer.toHexString(address[k]))
    if (k != 7) sb.append(':')
  }
  return sb.append(']').toString()
}

private fun parseHost(input: String): String? {
  if (input.startsWith("[")) {
    if (!input.endsWith("]")) return null
    return parseIpv6(input.substring(1, input.length - 1))
  }
  val decoded = String(percentDecodeBytes(input), Charsets.UTF_8)
  val ascii = domainToAscii(decoded) ?: return null
  if (ascii.any(::isForbiddenDomain)) return null
  if (endsInNumber(ascii)) return parseIpv4(ascii)
  return ascii
}

private fun isSingleDot(s: String) = s == "." || s.equals("%2e", ignoreCase = true)

private fun isDoubleDot(s: String) =
  s == ".." || s.equals(".%2e", ignoreCase = true) || s.equals("%2e.", ignoreCase = true) || s.equals("%2e%2e", ignoreCase = true)

/** `new URL(input).href`, or null where the constructor throws. Handles http and https only. */
fun whatwgHref(raw: String): String? {
  var start = 0
  var end = raw.length
  while (start < end && isC0OrSpace(raw[start])) start++
  while (end > start && isC0OrSpace(raw[end - 1])) end--
  val input = raw.substring(start, end).filter { it != '\t' && it != '\n' && it != '\r' }
  val colon = input.indexOf(':')
  if (colon <= 0) return null
  val scheme = input.substring(0, colon)
  if (!scheme[0].isLetter() || scheme[0].code >= 128) return null
  for (ch in scheme) {
    if (!(ch.code < 128 && (ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '.'))) return null
  }
  val lower = scheme.lowercase()
  if (lower != "http" && lower != "https") return null
  var p = colon + 1
  while (p < input.length && (input[p] == '/' || input[p] == '\\')) p++
  var authEnd = p
  while (authEnd < input.length && input[authEnd] !in "/\\?#") authEnd++
  var authority = input.substring(p, authEnd)
  var userinfo = ""
  val at = authority.lastIndexOf('@')
  if (at != -1) {
    val credentials = authority.substring(0, at)
    authority = authority.substring(at + 1)
    val sep = credentials.indexOf(':')
    val user = percentEncode(if (sep == -1) credentials else credentials.substring(0, sep), ::inUserinfo)
    val pass = if (sep == -1) "" else percentEncode(credentials.substring(sep + 1), ::inUserinfo)
    if (user.isNotEmpty() || pass.isNotEmpty()) userinfo = user + (if (pass.isNotEmpty()) ":$pass" else "") + "@"
    if (authority.isEmpty()) return null
  }
  var hostPart = authority
  var port = ""
  var bracket = false
  var portAt = -1
  for (i in authority.indices) {
    val ch = authority[i]
    if (ch == '[') bracket = true
    if (ch == ']') bracket = false
    if (ch == ':' && !bracket) {
      portAt = i
      break
    }
  }
  if (portAt != -1) {
    hostPart = authority.substring(0, portAt)
    port = authority.substring(portAt + 1)
    if (port.any { it !in '0'..'9' }) return null
  }
  if (hostPart.isEmpty()) return null
  val host = parseHost(hostPart) ?: return null
  var portOut = ""
  if (port.isNotEmpty()) {
    val value = port.trimStart('0').ifEmpty { "0" }
    if (value.length > 5 || value.toInt() > 65535) return null
    val default = if (lower == "http") 80 else 443
    if (value.toInt() != default) portOut = ":$value"
  }
  var rest = input.substring(authEnd)
  var fragment: String? = null
  val hash = rest.indexOf('#')
  if (hash != -1) {
    fragment = rest.substring(hash + 1)
    rest = rest.substring(0, hash)
  }
  var query: String? = null
  val q = rest.indexOf('?')
  if (q != -1) {
    query = rest.substring(q + 1)
    rest = rest.substring(0, q)
  }
  val segments = ArrayList<String>()
  if (rest.isNotEmpty()) {
    val parts = rest.substring(1).split('/', '\\')
    for ((i, part) in parts.withIndex()) {
      val last = i == parts.size - 1
      if (isDoubleDot(part)) {
        if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
        if (last) segments.add("")
      } else if (isSingleDot(part)) {
        if (last) segments.add("")
      } else {
        segments.add(percentEncode(part, ::inPath))
      }
    }
  }
  val path = "/" + segments.joinToString("/")
  return lower + "://" + userinfo + host + portOut + path +
    (if (query != null) "?" + percentEncode(query, ::inSpecialQuery) else "") +
    (if (fragment != null) "#" + percentEncode(fragment, ::inFragment) else "")
}
