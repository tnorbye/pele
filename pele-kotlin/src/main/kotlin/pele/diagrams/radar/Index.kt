// Ported from Pele src/diagrams/radar/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.radar

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val radar: Diagram<RadarModel> =
  object : Diagram<RadarModel> {
    override val type = "radar"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): RadarModel = buildRadar(parseRadar(source), title)

    override fun render(model: RadarModel, config: Config, options: RenderOptions): Rendered =
      renderRadar(model, config, options)
  }
