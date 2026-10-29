// Ported from Pele src/diagrams/railroad/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val railroad: Diagram<RailroadDb> =
  object : Diagram<RailroadDb> {
    override val type = "railroad"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): RailroadDb = parseRailroad(source, title)

    override fun render(model: RailroadDb, config: Config, options: RenderOptions): Rendered =
      renderRailroad(model, config, options)
  }
