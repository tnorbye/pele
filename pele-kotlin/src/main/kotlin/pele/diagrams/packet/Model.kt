// Ported from Pele src/diagrams/packet/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.packet

import kotlin.math.floor
import pele.Config
import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsStr
import pele.svg.Accessible

data class PacketBlock(
  var start: Double,
  var end: Double,
  // As Mermaid counts it: one less than the width for the pieces of a field split across rows.
  var bits: Double,
  val label: String,
)

class PacketModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  val bitsPerRow: Double,
  val rows: List<MutableList<PacketBlock>>,
) : Accessible

private const val MAX_ROWS = 10_000

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun packetOption(config: Config, key: String, fallback: Double, min: Double, max: Double): Double {
  val value = (config["packet"] as? Map<*, *>)?.get(key)
  return if (value is Double && value >= min) minOf(value, max) else fallback
}

private fun invalid(message: String): PeleError =
  PeleError(message, PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "packet"))

fun buildPacket(ast: PacketAst, config: Config, title: String?): PacketModel {
  val bitsPerRow = floor(packetOption(config, "bitsPerRow", 32.0, 1.0, 4096.0))
  val rows = ArrayList<MutableList<PacketBlock>>()
  var lastBit = -1.0
  var word = ArrayList<PacketBlock>()
  var row = 1.0

  for (block in ast.blocks) {
    var start: Double? = block.start
    var end: Double? = block.end
    var bits: Double? = block.bits
    if (start != null && end != null && end < start) {
      throw invalid(
        "Packet block ${jsStr(start)} - ${jsStr(end)} is invalid. End must be greater than start."
      )
    }
    if (start == null) start = lastBit + 1
    if (start != lastBit + 1) {
      throw invalid(
        "Packet block ${jsStr(start)} - ${jsStr(end ?: start)} is not contiguous. It should start from ${jsStr(lastBit + 1)}."
      )
    }
    if (bits == 0.0) {
      throw invalid("Packet block ${jsStr(start)} is invalid. Cannot have a zero bit field.")
    }
    if (end == null) end = start + (bits ?: 1.0) - 1
    if (bits == null) bits = end - start + 1
    lastBit = end

    var s: Double = start
    var b: Double = bits
    while (word.size <= bitsPerRow + 1 && rows.size < MAX_ROWS) {
      val rowEnd = row * bitsPerRow - 1
      if (end <= rowEnd) {
        word.add(PacketBlock(s, end, b, block.label))
        if (end == rowEnd) {
          rows.add(word)
          word = ArrayList()
          row++
        }
        break
      }
      word.add(PacketBlock(s, rowEnd, rowEnd - s, block.label))
      rows.add(word)
      word = ArrayList()
      row++
      s = rowEnd + 1
      b = end - s
    }
  }
  if (word.size > 0) rows.add(word)

  return PacketModel(
    type = "packet",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle =
      if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr =
      if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    bitsPerRow = bitsPerRow,
    rows = rows,
  )
}
