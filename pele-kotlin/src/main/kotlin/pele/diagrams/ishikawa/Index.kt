// Ported from Pele src/diagrams/ishikawa/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.ishikawa

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val ishikawa: Diagram<IshikawaDb> =
  object : Diagram<IshikawaDb> {
    override val type = "ishikawa"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): IshikawaDb {
      val db = IshikawaDb()
      db.title = title
      parseIshikawa(source, db)
      return db
    }

    override fun render(model: IshikawaDb, config: Config, options: RenderOptions): Rendered =
      renderIshikawa(model, config, options)
  }
