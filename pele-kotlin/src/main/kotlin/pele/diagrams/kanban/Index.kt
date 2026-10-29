// Ported from Pele src/diagrams/kanban/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.kanban

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val kanban: Diagram<KanbanModel> =
  object : Diagram<KanbanModel> {
    override val type = "kanban"
    override val math = true

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): KanbanModel {
      val db = KanbanDb()
      db.title = title
      parseKanban(source, db)
      return db
    }

    override fun render(model: KanbanModel, config: Config, options: RenderOptions): Rendered =
      renderKanban(model, config, options)
  }
