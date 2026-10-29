// Ported from Pele src/diagrams/agentflow/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val agentflow: Diagram<AgentflowDb> = object : Diagram<AgentflowDb> {
  override val type = "agentflow"
  override val keepComments = true

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): AgentflowDb {
    // Mermaid reads this setting from the flowchart section for agentflow too.
    val flow = config["flowchart"] as? Map<*, *> ?: emptyMap<String, Any?>()
    val dir = config["direction"]
    val db = AgentflowDb(
      AgentflowDbOptions(
        inheritDir = flow["inheritDir"] == true,
        direction = if (dir is String) dir else null,
        lineOffset = lineOffset,
        source = source,
        maxEdges = limits.maxEdges,
      ),
    )
    if (!title.isNullOrEmpty()) db.title = title
    parseAgentflow(source, db)
    agentGraph(db, config)
    return db
  }

  override fun render(model: AgentflowDb, config: Config, options: RenderOptions): Rendered = renderAgentflow(model, config, options)
}
