// Ported from Pele src/diagrams/eventmodeling/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.eventmodeling

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val eventmodeling: Diagram<EventModelingModel> =
  object : Diagram<EventModelingModel> {
    override val type = "eventmodeling"
    override val keepComments = true

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): EventModelingModel = buildEventModel(parseEventModel(source), title)

    override fun render(
      model: EventModelingModel,
      config: Config,
      options: RenderOptions,
    ): Rendered = renderEventModel(model, config, options)
  }
