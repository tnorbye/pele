// Ported from Pele src/diagrams/sankey/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sankey

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val sankey: Diagram<SankeyDb> = object : Diagram<SankeyDb> {
  override val type = "sankey"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): SankeyDb {
    val db = SankeyDb()
    if (!title.isNullOrEmpty()) db.title = title
    parseSankey(prepareTextForParsing(source), db, source)
    return db
  }

  override fun render(model: SankeyDb, config: Config, options: RenderOptions): Rendered = renderSankey(model, config, options)
}
