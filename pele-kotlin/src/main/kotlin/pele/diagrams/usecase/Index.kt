// Ported from Pele src/diagrams/usecase/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val usecase: Diagram<UsecaseModel> = object : Diagram<UsecaseModel> {
  override val type = "usecase"
  override val math = true

  override fun parse(source: String, config: Config, title: String?, lineOffset: Int, limits: ParseLimits): UsecaseModel =
    parseUsecase(source, title)

  override fun render(model: UsecaseModel, config: Config, options: RenderOptions): Rendered = renderUsecase(model, config, options)
}
