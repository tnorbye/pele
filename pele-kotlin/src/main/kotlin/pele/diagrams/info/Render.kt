// Ported from Pele src/diagrams/info/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.info

import pele.Config
import pele.PELE_VERSION
import pele.RenderOptions
import pele.Rendered
import pele.svg.labelSvg
import pele.svg.svgDocument
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.ceil

fun renderInfo(model: InfoModel, config: Config, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val title = layoutLabel(model.title, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 8 else 0.0
  val text = layoutLabel("Pele v$PELE_VERSION", false, measurer, size, 4000.0)

  val width = ceil(maxOf(title.width, text.width) + 2 * pad)
  val height = ceil(titleHeight + text.height + 2 * pad)
  val content =
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      labelSvg(text, width / 2, pad + titleHeight + text.height / 2, " class=\"pele-version\" fill=\"var(--_m)\"")

  return Rendered(svg = svgDocument("info", width, height, size, options, model, content), width = width, height = height, links = ArrayList())
}
