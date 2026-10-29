// Ported from Pele src/diagrams/journey/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.journey

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val journey: Diagram<JourneyModel> = object : Diagram<JourneyModel> {
  override val type = "journey"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): JourneyModel {
    val db = JourneyDb()
    db.title = title
    parseJourney(source, db)
    return db
  }

  override fun render(model: JourneyModel, config: Config, options: RenderOptions): Rendered = renderJourney(model, config, options)
}
