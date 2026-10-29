// Ported from Pele src/diagrams/cynefin/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.cynefin

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val cynefin: Diagram<CynefinModel> = object : Diagram<CynefinModel> {
  override val type = "cynefin"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): CynefinModel =
    buildCynefin(parseCynefin(source), title)

  override fun render(model: CynefinModel, config: Config, options: RenderOptions): Rendered = renderCynefin(model, config, options)
}
