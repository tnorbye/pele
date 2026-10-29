// Ported from Pele src/diagrams/mindmap/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.mindmap

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val mindmap: Diagram<MindmapModel> = object : Diagram<MindmapModel> {
  override val type = "mindmap"
  override val math = true

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): MindmapModel {
    val db = MindmapDb()
    db.title = title
    parseMindmap(source, db)
    return db
  }

  override fun render(model: MindmapModel, config: Config, options: RenderOptions): Rendered = renderMindmap(model, config, options)
}
