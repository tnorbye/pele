// Ported from Pele src/diagrams/packet/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.packet

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.Fitted
import pele.diagrams.common.fitText
import pele.diagrams.common.literalLabel
import pele.diagrams.common.titleRoom
import pele.diagrams.common.turnToFit
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsTrim
import pele.svg.ROUNDED
import pele.svg.esc
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor

fun renderPacket(model: PacketModel, config: Config, options: RenderOptions): Rendered =
  turnToFit(options) { down -> draw(model, config, options, down) }

// PORT: Math.log2 is exact for powers of two; count is a whole number >= 1, so floor(log2) is its exponent.
private fun floorLog2(count: Double): Double = if (count.isInfinite()) count else Math.getExponent(count).toDouble()

private fun draw(model: PacketModel, config: Config, options: RenderOptions, down: Boolean): Rendered {
  val size = options.fontSize ?: 16.0
  val labelSize = jsRound(size * 0.875)
  val bitSize = jsRound(size * 0.625)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val packet = config["packet"] as? Map<*, *>
  val rowHeight = packetOption(config, "rowHeight", 32.0, 1.0, 400.0)
  var bitWidth = packetOption(config, "bitWidth", 32.0, 1.0, 400.0)
  val paddingX = packetOption(config, "paddingX", 5.0, 0.0, 400.0)
  val paddingY = packetOption(config, "paddingY", 5.0, 0.0, 400.0)
  val showBits = packet?.get("showBits") != false
  val descending = packet?.get("bitOrder") == "descending"
  var bitsPerRow = model.bitsPerRow
  var rows: List<MutableList<PacketBlock>> = model.rows
  if (down) {
    val count = maxOf(1.0, floor(((options.maxWidth ?: 320.0) - 2 * pad) / minOf(bitWidth, 18.0)))
    // A byte is the shortest row. Below that every label is one character to a line, and a long
    // packet is a row to a bit.
    bitsPerRow = minOf(bitsPerRow, maxOf(8.0, StrictMath.pow(2.0, floorLog2(count))))
    val lastRow = model.rows.lastOrNull()
    val last = lastRow?.lastOrNull()?.end ?: 0.0
    if (ceil((last + 1) / bitsPerRow) > 10000) bitsPerRow = model.bitsPerRow
    bitWidth = minOf(bitWidth, maxOf(18.0, ((options.maxWidth ?: 320.0) - 2 * pad) / bitsPerRow))
    // The model has split each field at its own rows. Its pieces are joined again first, so that
    // a field is only split where one of the new rows ends. A piece counts one bit short.
    val piece = { block: PacketBlock -> block.bits == block.end - block.start }
    val whole = ArrayList<PacketBlock>()
    for (row in model.rows) for (block in row) {
      val prev = whole.lastOrNull()
      if (prev != null && piece(prev) && piece(block) && prev.label == block.label && block.start == prev.end + 1 && block.start % model.bitsPerRow == 0.0) {
        prev.end = block.end
        prev.bits = prev.end - prev.start
      } else {
        whole.add(block.copy())
      }
    }
    val built = ArrayList<MutableList<PacketBlock>>()
    for (block in whole) {
      var start = block.start
      while (start <= block.end) {
        val end = minOf(block.end, (floor(start / bitsPerRow) + 1) * bitsPerRow - 1)
        val index = floor(start / bitsPerRow).toInt()
        while (built.size <= index) built.add(ArrayList())
        val target = built[index]
        target.add(block.copy(start = start, end = end))
        start = end + 1
      }
    }
    rows = built
  }
  val labels = IdentityHashMap<PacketBlock, Label>()
  if (down) for (row in rows) for (block in row) {
    val room = maxOf(1.0, (block.end - block.start + 1) * bitWidth - minOf(paddingX, bitWidth - 1) - 6)
    val label = literalLabel(decodeEntities(block.label).jsTrim(), measurer, labelSize, room)
    labels[block] = label
  }
  // Bit numbers sit above each row and need room of their own.
  val numbers = if (showBits) bitSize + 4 else 0.0
  val rowHeights = rows.map { row ->
    var height = rowHeight
    if (down) for (block in row) height = maxOf(height, labels[block]!!.height + 10)
    height
  }
  val tops = ArrayList<Double>()
  var rowsHeight = 0.0
  for (height in rowHeights) {
    tops.add(rowsHeight)
    rowsHeight += numbers + height + paddingY
  }
  if (rows.isNotEmpty()) rowsHeight -= paddingY

  val gap = minOf(paddingX, bitWidth - 1)
  val width = if (rows.isNotEmpty()) bitsPerRow * bitWidth - gap else 0.0
  val title = layoutLabel(model.title, false, measurer, size, if (down) titleRoom(options) else 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + (if (rowsHeight > 0) 12 else 0) else 0.0

  // The pieces of a field split across rows share its label, so each width is fitted once.
  val fits = HashMap<String, HashMap<Double, Fitted?>>()
  val fit = { label: String, max: Double ->
    val byWidth = fits.getOrPut(label) { HashMap() }
    val key = max + 0.0
    if (!byWidth.containsKey(key)) {
      byWidth[key] = fitText(decodeEntities(label).jsTrim(), max, labelSize, jsRound(size * 0.5), measurer)
    }
    byWidth[key]
  }

  val left = pad + maxOf(0.0, (title.width - width) / 2)
  val fields = StringBuilder()
  val bits = StringBuilder()
  rows.forEachIndexed { index, row ->
    val y = pad + tops[index] + numbers
    val rowHeight = rowHeights[index]
    for (block in row) {
      val count = block.end - block.start + 1
      val first = block.start % bitsPerRow
      val x = left + (if (descending) bitsPerRow - first - count else first) * bitWidth
      val w = count * bitWidth - gap
      val text = if (down) null else fit(block.label, w - 6)
      fields.append(
        "<g class=\"pele-node\" data-id=\"${jsStr(block.start)}-${jsStr(block.end)}\">" +
          "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(w)}\" height=\"${num(rowHeight)}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"$ROUNDED/>" +
          (
            if (down) {
              labelSvg(labels[block]!!, x + w / 2, y + rowHeight / 2, "")
            } else if (text != null && text.size <= rowHeight) {
              "<text x=\"${num(x + w / 2)}\" y=\"${num(y + rowHeight / 2 + text.size * 0.35)}\"${
                if (text.size == labelSize) "" else " font-size=\"${num(text.size)}\""
              }>${esc(text.text)}</text>"
            } else {
              ""
            }
            ) +
          "</g>",
      )
      if (!showBits) continue
      val leading = if (descending) block.end else block.start
      val trailing = if (descending) block.start else block.end
      if (count == 1.0) {
        bits.append("<text x=\"${num(x + w / 2)}\" y=\"${num(y - 4)}\" text-anchor=\"middle\">${jsStr(leading)}</text>")
      } else {
        bits.append(
          "<text x=\"${num(x)}\" y=\"${num(y - 4)}\">${jsStr(leading)}</text>" +
            "<text x=\"${num(x + w)}\" y=\"${num(y - 4)}\" text-anchor=\"end\">${jsStr(trailing)}</text>",
        )
      }
    }
  }

  val totalWidth = ceil(maxOf(width, title.width) + 2 * pad)
  val totalHeight = ceil(rowsHeight + titleHeight + 2 * pad)
  val content =
    (if (fields.isNotEmpty()) "<g class=\"pele-packet-fields\" font-size=\"${jsStr(labelSize)}\" text-anchor=\"middle\">$fields</g>" else "") +
      (if (bits.isNotEmpty()) "<g class=\"pele-packet-bits\" font-size=\"${jsStr(bitSize)}\" fill=\"var(--_m)\">$bits</g>" else "") +
      // Mermaid puts a packet's title under it.
      labelSvg(title, totalWidth / 2, totalHeight - pad - title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"")

  return Rendered(
    svg = svgDocument("packet", totalWidth, totalHeight, size, options, model, content),
    width = totalWidth,
    height = totalHeight,
    links = ArrayList(),
  )
}
