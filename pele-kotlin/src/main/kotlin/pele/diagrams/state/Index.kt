// Ported from Pele src/diagrams/state/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val state: Diagram<StateDb> = object : Diagram<StateDb> {
  override val type = "state"
  override val math = true

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): StateDb {
    val db = StateDb()
    if (!title.isNullOrEmpty()) db.title = title
    parseState(source, db)
    return db
  }

  override fun render(model: StateDb, config: Config, options: RenderOptions): Rendered = renderState(model, config, options)
}
