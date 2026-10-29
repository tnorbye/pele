// Ported from Pele src/diagrams/class/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val classDiagram: Diagram<ClassDb> =
  object : Diagram<ClassDb> {
    override val type = "class"
    override val math = true

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): ClassDb {
      val db = ClassDb()
      if (!title.isNullOrEmpty()) db.title = title
      parseClassDiagram(source, db)
      return db
    }

    override fun render(model: ClassDb, config: Config, options: RenderOptions): Rendered =
      renderClass(model, config, options)
  }
