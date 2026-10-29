// Ported from Pele src/diagrams/venn/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.venn

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val venn: Diagram<VennDb> =
  object : Diagram<VennDb> {
    override val type = "venn"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): VennDb {
      val db = VennDb()
      if (!title.isNullOrEmpty()) db.title = title
      parseVenn(source, db)
      return db
    }

    override fun render(model: VennDb, config: Config, options: RenderOptions): Rendered =
      renderVenn(model, config, options)
  }
