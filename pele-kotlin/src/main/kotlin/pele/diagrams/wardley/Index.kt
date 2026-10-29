// Ported from Pele src/diagrams/wardley/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.wardley

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val wardley: Diagram<WardleyModel> =
  object : Diagram<WardleyModel> {
    override val type = "wardley"
    override val section: String? = "wardley-beta"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): WardleyModel = buildWardley(parseWardley(source), title)

    override fun render(model: WardleyModel, config: Config, options: RenderOptions): Rendered =
      renderWardley(model, config, options)
  }
