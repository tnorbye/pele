// Ported from Pele src/diagrams/git/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.git

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val gitGraph: Diagram<GitModel> = object : Diagram<GitModel> {
  override val type = "gitGraph"

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): GitModel =
    buildGit(parseGit(source), config, title)

  override fun render(model: GitModel, config: Config, options: RenderOptions): Rendered = renderGit(model, config, options)
}
