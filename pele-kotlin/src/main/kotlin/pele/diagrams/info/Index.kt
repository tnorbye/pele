// Ported from Pele src/diagrams/info/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.info

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val info: Diagram<InfoModel> =
  object : Diagram<InfoModel> {
    override val type = "info"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): InfoModel = buildInfo(parseInfo(source), title)

    override fun render(model: InfoModel, config: Config, options: RenderOptions): Rendered =
      renderInfo(model, config, options)
  }
