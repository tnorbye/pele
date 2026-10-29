// Ported from Pele src/diagrams/block/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val block: Diagram<BlockDb> = object : Diagram<BlockDb> {
  override val type = "block"
  override val math = true

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): BlockDb {
    val db = BlockDb()
    db.title = title
    parseBlock(source, db)
    return db
  }

  override fun render(model: BlockDb, config: Config, options: RenderOptions): Rendered = renderBlock(model, config, options)
}
