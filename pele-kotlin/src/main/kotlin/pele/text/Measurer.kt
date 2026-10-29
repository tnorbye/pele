// Ported from Pele src/text/measurer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.text

import java.util.concurrent.ConcurrentHashMap

object Style {
  const val Bold = 1
  const val Italic = 2
  const val Mono = 4
  const val Sub = 8
  const val Sup = 16
  const val Underline = 32
}

// Approximate divisor for CSS font-size="smaller".
private const val SMALLER = 1.2

fun interface TextMeasurer {
  fun width(text: String, size: Double, style: Int): Double
}

private fun unpack(packed: String): IntArray {
  val out = IntArray(95)
  for (i in 0 until 95) out[i] = packed.substring(i * 2, i * 2 + 2).toInt(36)
  return out
}

private val regular: IntArray by lazy { unpack(REGULAR) }
private val bold: IntArray by lazy { unpack(BOLD) }

private fun isWide(c: Int): Boolean =
  (c >= 0x1100 && c <= 0x115f) ||
    (c >= 0x2e80 && c <= 0xa4cf) ||
    (c >= 0xac00 && c <= 0xd7a3) ||
    (c >= 0xf900 && c <= 0xfaff) ||
    (c >= 0xfe30 && c <= 0xfe4f) ||
    (c >= 0xff00 && c <= 0xff60) ||
    (c >= 0xffe0 && c <= 0xffe6) ||
    (c >= 0xd800 && c <= 0xdbff)

private fun isZeroWidth(c: Int): Boolean =
  c == 0xad ||
    (c >= 0x300 && c <= 0x36f) ||
    (c >= 0x200b && c <= 0x200f) ||
    (c >= 0xdc00 && c <= 0xdfff) ||
    (c >= 0xfe00 && c <= 0xfe0f) ||
    c == 0xfeff

// Estimates widths from a table of Arial advances. Used where no canvas is available.
val metricsMeasurer: TextMeasurer = TextMeasurer { text, size, style ->
  val mono = (style and Style.Mono) != 0
  val eff = if ((style and (Style.Sub or Style.Sup)) != 0) size / SMALLER else size
  val table = if ((style and Style.Bold) != 0) bold else regular
  var units = 0.0
  for (i in text.indices) {
    val c = text[i].code
    if (c < 32) continue
    if (c < 127) {
      units += if (mono) 600 else table[c - 32]
    } else if (isZeroWidth(c)) {
      continue
    } else if (isWide(c)) {
      units += if (mono) 1200 else 1000
    } else {
      units += if (mono) 600 else 580
    }
  }
  (units * eff) / 1000
}

// PORT: the canvas measurer is browser-only and not ported; the JVM always measures with metrics.
private val shared = ConcurrentHashMap<String, TextMeasurer>()

fun defaultMeasurer(fontFamily: String? = null, monoFamily: String? = null): TextMeasurer {
  val key = (fontFamily ?: "sans-serif") + "\n" + (monoFamily ?: "monospace")
  return shared.getOrPut(key) { metricsMeasurer }
}

// Widths measured before a font finished loading are those of its fallback.
fun forgetTextWidths() {
  shared.clear()
}
