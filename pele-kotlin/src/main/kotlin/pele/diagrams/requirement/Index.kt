// Ported from Pele src/diagrams/requirement/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val requirement: Diagram<RequirementDb> = object : Diagram<RequirementDb> {
  override val type = "requirement"
  override val math = true

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): RequirementDb {
    val db = RequirementDb()
    if (!title.isNullOrEmpty()) db.title = title
    parseRequirement(source, db)
    return db
  }

  override fun render(model: RequirementDb, config: Config, options: RenderOptions): Rendered =
    renderRequirement(model, config, options)
}
