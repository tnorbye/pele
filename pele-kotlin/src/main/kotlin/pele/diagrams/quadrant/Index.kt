// Ported from Pele src/diagrams/quadrant/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.quadrant

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val quadrantChart: Diagram<QuadrantDb> = object : Diagram<QuadrantDb> {
  override val type = "quadrantChart"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): QuadrantDb {
    val db = QuadrantDb()
    db.title = title
    parseQuadrant(source, db)
    return db
  }

  override fun render(model: QuadrantDb, config: Config, options: RenderOptions): Rendered = renderQuadrant(model, config, options)
}
