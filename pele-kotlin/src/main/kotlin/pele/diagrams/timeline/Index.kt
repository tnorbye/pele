// Ported from Pele src/diagrams/timeline/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.timeline

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val timeline: Diagram<TimelineModel> = object : Diagram<TimelineModel> {
  override val type = "timeline"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): TimelineModel {
    val db = TimelineDb()
    db.title = title
    parseTimeline(source, db)
    return db
  }

  override fun render(model: TimelineModel, config: Config, options: RenderOptions): Rendered = renderTimeline(model, config, options)
}
