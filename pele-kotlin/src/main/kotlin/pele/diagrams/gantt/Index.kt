// Ported from Pele src/diagrams/gantt/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val gantt: Diagram<GanttDb> = object : Diagram<GanttDb> {
  override val type = "gantt"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): GanttDb {
    val db = GanttDb()
    if (!title.isNullOrEmpty()) db.title = title
    val mode = (config["gantt"] as? Map<*, *>)?.get("displayMode")
    if (mode is String) db.displayMode = mode
    parseGantt(source, db)
    return db
  }

  override fun render(model: GanttDb, config: Config, options: RenderOptions): Rendered = renderGantt(model, config, options)
}
