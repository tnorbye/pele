// Ported from Pele src/diagrams/er/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val er: Diagram<ErDb> =
  object : Diagram<ErDb> {
    override val type = "er"
    override val math = true

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): ErDb {
      val db = ErDb()
      if (!title.isNullOrEmpty()) db.title = title
      parseEr(source, db)
      return db
    }

    override fun render(model: ErDb, config: Config, options: RenderOptions): Rendered =
      renderEr(model, config, options)
  }
