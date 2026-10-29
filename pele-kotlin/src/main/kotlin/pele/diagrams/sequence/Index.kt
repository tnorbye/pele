// Ported from Pele src/diagrams/sequence/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val sequence: Diagram<SeqDb> = object : Diagram<SeqDb> {
  override val type = "sequence"
  override val math = true

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): SeqDb {
    @Suppress("UNCHECKED_CAST")
    val seq = (config["sequence"] as? Map<String, Any?>) ?: emptyMap()
    val wrap = config["wrap"]
    val db = SeqDb(
      SeqDbOptions(
        wrap = if (wrap is Boolean) wrap else null,
        sequenceWrap = seq["wrap"] == true,
      ),
    )
    if (!title.isNullOrEmpty()) db.setDiagramTitle(title)
    parseSequence(source, db)
    return db
  }

  override fun render(model: SeqDb, config: Config, options: RenderOptions): Rendered = renderSequence(model, config, options)
}
