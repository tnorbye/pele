// Ported from Pele src/svg/title.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.layoutLabel
import pele.text.withoutMath
import kotlin.math.ceil

// Mermaid draws diagram titles as text, whatever they hold.
fun titleLabel(title: String?, measurer: TextMeasurer, size: Double, maxWidth: Double): Label =
  withoutMath { layoutLabel(title, false, measurer, size, maxWidth, Style.Bold) }

class Titled(val width: Double, val height: Double, val content: String)

// Puts a diagram title above content that was laid out without one.
fun withTitle(
  title: String?,
  measurer: TextMeasurer,
  size: Double,
  pad: Double,
  width: Double,
  height: Double,
  content: String,
): Titled {
  val label = titleLabel(title, measurer, size, 4000.0)
  if (label.height == 0.0) return Titled(width, height, content)
  val shift = label.height + 12
  val full = maxOf(width, ceil(label.width + 2 * pad))
  return Titled(
    width = full,
    height = ceil(height + shift),
    content = labelSvg(label, full / 2, pad + label.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g transform=\"translate(${num((full - width) / 2)},${num(shift)})\">$content</g>",
  )
}
