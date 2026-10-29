// Ported from Pele src/diagrams/block/layout.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.isInteger
import pele.js.jsStr
import kotlin.math.floor

open class Cell(
  val children: MutableList<Cell> = ArrayList(),
  // Grid width of this container: a positive count, or -1 to put every child on one row.
  var columns: Double = -1.0,
  var span: Double = 1.0,
  var space: Boolean = false,
  // Height reserved above a container's grid for its title.
  var head: Double = 0.0,
  // Size the content needs. Set by the caller for leaves, computed here for containers.
  var nw: Double = 0.0,
  var nh: Double = 0.0,
  // Placement: grid position, then center and size in pixels.
  var col: Double = 0.0,
  var row: Double = 0.0,
  var cols: Double = 0.0,
  var heights: MutableList<Double> = ArrayList(),
  // Extra space after each row or column. PORT: null stands for a hole in a JS array.
  var rowGaps: MutableList<Double?> = ArrayList(),
  var colGaps: MutableList<Double?> = ArrayList(),
  var x: Double = 0.0,
  var y: Double = 0.0,
  var w: Double = 0.0,
  var h: Double = 0.0,
)

private const val MAX_SPAN = 1000.0

fun cell(nw: Double, nh: Double, span: Double = 1.0): Cell = Cell(span = span, nw = nw, nh = nh)

class BlockPosition(val px: Double, val py: Double)

fun calculateBlockPosition(columns: Double, position: Double): BlockPosition {
  if (columns == 0.0 || !isInteger(columns)) {
    throw PeleError("Columns must be an integer !== 0.", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "block"))
  }
  if (position < 0 || !isInteger(position)) {
    throw PeleError("Position must be a non-negative integer." + jsStr(position), PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "block"))
  }
  if (columns < 0) return BlockPosition(position, 0.0)
  return BlockPosition(position % columns, floor(position / columns))
}

// Puts each child in its grid cell and sizes the container. Every column is as wide as the widest
// block needs. Every row that holds a plain block is as tall as the tallest block in the container,
// as in Mermaid; a row of nothing but containers is only as tall as they need.
private fun measure(box: Cell, gap: Double, inset: Double) {
  val columns = box.columns
  val heights = ArrayList<Double>()
  var position = 0.0
  var cols = 0.0
  var cw = 0.0
  var tallest = 0.0
  for (child in box.children) {
    val pos = calculateBlockPosition(columns, position)
    val px = pos.px
    val py = pos.py
    // Mermaid lets a block wider than the rest of its row stick out of the grid. Here it stops at the last column.
    var span = if (child.span >= 1) minOf(child.span, MAX_SPAN) else 1.0
    if (columns > 0 && span > columns - px) span = columns - px
    child.span = span
    child.col = px
    child.row = py
    position += span
    if (px + span > cols) cols = px + span
    if (py == heights.size.toDouble()) heights.add(0.0)
    val row = py.toInt()
    if (child.children.size == 0) heights[row] = -1.0
    else if (heights[row] >= 0 && child.nh > heights[row]) heights[row] = child.nh
    if (child.space) continue
    val width = (child.nw - gap * (span - 1)) / span
    if (width > cw) cw = width
    if (child.nh > tallest) tallest = child.nh
  }
  var height = (heights.size - 1) * gap + total(box.rowGaps)
  for (row in 0 until heights.size) {
    if (heights[row] < 0) heights[row] = tallest
    height += heights[row]
  }
  box.cols = cols
  box.heights = heights
  box.nw = maxOf(box.nw, cols * cw + (cols - 1) * gap + total(box.colGaps) + 2 * inset)
  box.nh = height + 2 * inset + box.head
}

// Gives each child its cell, sharing out any room the container has beyond what it needs.
private fun arrange(box: Cell, gap: Double, inset: Double) {
  val cols = box.cols
  val heights = box.heights
  val rowGaps = box.rowGaps
  val colGaps = box.colGaps
  val rows = heights.size
  val cw = (box.w - 2 * inset - (cols - 1) * gap - total(colGaps)) / cols
  val room = box.h - 2 * inset - box.head - (rows - 1) * gap - total(rowGaps)
  val offsets = ArrayList<Double>()
  if (colGaps.size > 0) {
    var sum = 0.0
    var col = 0
    while (col < cols) {
      offsets.add(sum)
      sum += colGaps.getOrNull(col) ?: 0.0
      col++
    }
  }
  var needed = 0.0
  for (height in heights) needed += height
  val scale = if (needed > 0) room / needed else 0.0
  val share = if (needed > 0) 0.0 else room / rows
  val left = box.x - box.w / 2 + inset
  var top = box.y - box.h / 2 + inset + box.head
  var row = 0
  for (child in box.children) {
    while (row < child.row) {
      top += heights[row] * scale + share + gap + (rowGaps.getOrNull(row) ?: 0.0)
      row++
    }
    val before = gapAtOffsets(offsets, child.col) ?: 0.0
    child.w = cw * child.span + gap * (child.span - 1) + (gapAtOffsets(offsets, child.col + child.span - 1) ?: 0.0) - before
    child.h = heights[row] * scale + share
    child.x = left + child.col * (cw + gap) + before + child.w / 2
    child.y = top + child.h / 2
  }
}

private fun gapAtOffsets(offsets: List<Double>, at: Double): Double? {
  if (at < 0 || at >= offsets.size || at != floor(at)) return null
  return offsets[at.toInt()]
}

private fun total(gaps: List<Double?>): Double {
  var sum = 0.0
  for (gap in gaps) sum += gap ?: 0.0
  return sum
}

class Content(val width: Double, val height: Double)

// Lays out a tree of cells in place. The root has no border, so its grid starts at the origin.
fun layout(root: Cell, gap: Double): Content {
  val order = arrayListOf(root)
  var i = 0
  while (i < order.size) {
    for (child in order[i].children) order.add(child)
    i++
  }
  for (j in order.size - 1 downTo 0) {
    val box = order[j]
    if (box.children.size > 0) measure(box, gap, if (j == 0) 0.0 else gap)
  }
  root.w = root.nw
  root.h = root.nh
  root.x = root.w / 2
  root.y = root.h / 2
  for (j in 0 until order.size) {
    val box = order[j]
    if (box.children.size > 0) arrange(box, gap, if (j == 0) 0.0 else gap)
  }
  return Content(root.w, root.h)
}
