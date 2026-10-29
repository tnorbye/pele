// Ported from Pele src/diagrams/treemap/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treemap

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val treemap: Diagram<TreemapModel> = object : Diagram<TreemapModel> {
  override val type = "treemap"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): TreemapModel =
    buildTreemap(parseTreemap(source), title)

  override fun render(model: TreemapModel, config: Config, options: RenderOptions): Rendered = renderTreemap(model, config, options)
}
