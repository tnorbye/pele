// Ported from Pele src/diagrams/pie/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.pie

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val pie: Diagram<PieModel> = object : Diagram<PieModel> {
  override val type = "pie"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): PieModel =
    buildPie(parsePie(source), title)

  override fun render(model: PieModel, config: Config, options: RenderOptions): Rendered = renderPie(model, config, options)
}
