// Ported from Pele src/diagrams/c4/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val c4: Diagram<C4Db> =
  object : Diagram<C4Db> {
    override val type = "c4"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): C4Db {
      val db = C4Db()
      if (!title.isNullOrEmpty()) db.setTitle(title)
      parseC4(source, db)
      return db
    }

    override fun render(model: C4Db, config: Config, options: RenderOptions): Rendered =
      renderC4(model, config, options)
  }
