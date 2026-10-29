// Ported from Pele src/diagrams/packet/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.packet

import pele.Config
import pele.Diagram
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered

val packet: Diagram<PacketModel> =
  object : Diagram<PacketModel> {
    override val type = "packet"

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): PacketModel = buildPacket(parsePacket(source), config, title)

    override fun render(model: PacketModel, config: Config, options: RenderOptions): Rendered =
      renderPacket(model, config, options)
  }
