// Ported from Pele src/diagrams/flowchart/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val flowchart: Diagram<FlowDb> =
  object : Diagram<FlowDb> {
    override val type = "flowchart"
    override val math: Boolean
      get() = true

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): FlowDb {
      val flow = config["flowchart"] as? Map<*, *> ?: emptyMap<String, Any?>()
      val db =
        FlowDb(FlowDbOptions(inheritDir = flow["inheritDir"] == true, maxEdges = limits.maxEdges))
      if (!title.isNullOrEmpty()) db.title = title
      parseFlowchart(source, db)
      return db
    }

    override fun render(model: FlowDb, config: Config, options: RenderOptions): Rendered =
      renderFlowchart(model, config, options)
  }
