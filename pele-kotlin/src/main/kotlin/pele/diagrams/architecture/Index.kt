// Ported from Pele src/diagrams/architecture/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.architecture

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val architecture: Diagram<ArchitectureDb> =
  object : Diagram<ArchitectureDb> {
    override val type = "architecture"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): ArchitectureDb {
      val db = ArchitectureDb()
      if (!title.isNullOrEmpty()) db.title = title
      populate(parseArchitecture(source), db)
      return db
    }

    override fun render(model: ArchitectureDb, config: Config, options: RenderOptions): Rendered =
      renderArchitecture(model, config, options)
  }
