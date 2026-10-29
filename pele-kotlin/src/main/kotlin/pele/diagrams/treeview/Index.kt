// Ported from Pele src/diagrams/treeview/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treeview

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val treeView: Diagram<TreeViewModel> = object : Diagram<TreeViewModel> {
  override val type = "treeView"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): TreeViewModel =
    buildTreeView(readTreeView(source), title)

  override fun render(model: TreeViewModel, config: Config, options: RenderOptions): Rendered = renderTreeView(model, config, options)
}
