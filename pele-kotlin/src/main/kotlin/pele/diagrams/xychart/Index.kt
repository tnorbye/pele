// Ported from Pele src/diagrams/xychart/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.xychart

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val xychart: Diagram<XyChartDb> = object : Diagram<XyChartDb> {
  override val type = "xychart"
  override val section: String? = "xyChart"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): XyChartDb {
    val db = XyChartDb()
    db.title = title
    parseXyChart(source, db)
    return db
  }

  override fun render(model: XyChartDb, config: Config, options: RenderOptions): Rendered = renderXyChart(model, config, options)
}
