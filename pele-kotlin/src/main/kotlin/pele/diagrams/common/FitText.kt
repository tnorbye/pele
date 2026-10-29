// Ported from Pele src/diagrams/common/fit-text.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import kotlin.math.floor
import pele.js.jsTrimEnd
import pele.js.toCodePointStrings
import pele.text.TextMeasurer

class Fitted(val text: String, val size: Double, val width: Double)

// Fits one line of text into a width: first by shrinking the font down to `min`, then by cutting
// the text and adding an ellipsis. Returns undefined when not even one character fits.
fun fitText(
  text: String,
  max: Double,
  size: Double,
  min: Double,
  measurer: TextMeasurer,
  style: Int = 0,
): Fitted? {
  if (text == "" || max <= 0) return null
  var width = measurer.width(text, size, style)
  if (width <= max) return Fitted(text, size, width)

  // Width grows with font size, so one division lands on the right size or just above it.
  var fitted = maxOf(min, floor((size * max) / width))
  while (fitted >= min) {
    width = measurer.width(text, fitted, style)
    if (width <= max) return Fitted(text, fitted, width)
    fitted--
  }

  val chars = text.toCodePointStrings()
  var low = 0
  var high = chars.size - 1
  while (low < high) {
    val mid = (low + high + 1) shr 1
    if (measurer.width(chars.subList(0, mid).joinToString("").jsTrimEnd() + "…", min, style) <= max)
      low = mid
    else high = mid - 1
  }
  if (low == 0) return null
  val cut = chars.subList(0, low).joinToString("").jsTrimEnd() + "…"
  width = measurer.width(cut, min, style)
  return if (width <= max) Fitted(cut, min, width) else null
}
