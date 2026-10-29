// Ported from Pele src/diagrams/treemap/layout.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treemap

import pele.js.jsRound

class Box(
  val node: TreemapNode,
  val parent: Box?,
  // Position among the top-level nodes of the branch this box belongs to, which picks its color.
  val series: Int,
  // A leaf's own value, or the sum of the leaves under a section.
  var value: Double,
  val children: MutableList<Box> = ArrayList(),
  // Small sections give their header space to their children.
  var headed: Boolean = false,
  var header: Double = 0.0,
  var x: Double = 0.0,
  var y: Double = 0.0,
  var w: Double = 0.0,
  var h: Double = 0.0,
)

class Insets(
  val gap: Double,
  // Space a section keeps for its header, and around its children on the other three sides.
  val header: Double,
  val short: Double,
  val side: Double,
)

private fun box(node: TreemapNode, parent: Box?, series: Int): Box {
  val nodeValue = node.value
  val value = if (node.children == null && nodeValue != null && nodeValue.isFinite() && nodeValue > 0) nodeValue else 0.0
  return Box(node, parent, series, value)
}

private fun clamp(v: Double, low: Double, high: Double): Double = if (v >= low) minOf(v, high) else low

// Lays out boxes[from..to) in the rectangle as one squarified treemap: rows are added along the
// shorter side, and a row stops growing when another box would make its boxes less square.
private fun squarify(boxes: List<Box>, total: Double, xIn: Double, yIn: Double, wIn: Double, hIn: Double, gap: Double) {
  var x = xIn
  var y = yIn
  var w = wIn
  var h = hIn
  var rest = total
  var from = 0
  while (from < boxes.size) {
    val scale = (w * h) / rest
    val side = minOf(w, h)
    var sum = boxes[from].value * scale
    val largest = sum
    var worst = maxOf((side * side * largest) / (sum * sum), (sum * sum) / (side * side * largest))
    var to = from + 1
    while (to < boxes.size) {
      val smallest = boxes[to].value * scale
      val next = sum + smallest
      val ratio = maxOf((side * side * largest) / (next * next), (next * next) / (side * side * smallest))
      if (ratio > worst) break
      worst = ratio
      sum = next
      to++
    }

    val thickness = sum / side
    var offset = 0.0
    for (i in from until to) {
      val b = boxes[i]
      val length = if (i == to - 1) side - offset else (b.value * scale) / thickness
      val bx = if (w >= h) x else x + offset
      val by = if (w >= h) y + offset else y
      val bw = if (w >= h) thickness else length
      val bh = if (w >= h) length else thickness
      // Omit the gap if it would erase the box.
      val gx = if (jsRound(bx + bw - gap / 2) > jsRound(bx + gap / 2)) gap / 2 else 0.0
      val gy = if (jsRound(by + bh - gap / 2) > jsRound(by + gap / 2)) gap / 2 else 0.0
      b.x = jsRound(bx + gx)
      b.y = jsRound(by + gy)
      b.w = jsRound(bx + bw - gx) - b.x
      b.h = jsRound(by + bh - gy) - b.y
      offset += length
      rest -= b.value
    }
    if (w >= h) {
      x += thickness
      w -= thickness
    } else {
      y += thickness
      h -= thickness
    }
    from = to
  }
}

// Returns boxes with positive values, including zero-size boxes, parents before children.
fun layoutTreemap(roots: List<TreemapNode>, width: Double, height: Double, insets: Insets): List<Box> {
  val top = roots.mapIndexed { index, node -> box(node, null, index) }
  val all = ArrayList(top)
  var i = 0
  while (i < all.size) {
    val parent = all[i]
    for (node in parent.node.children ?: emptyList()) {
      val child = box(node, parent, parent.series)
      parent.children.add(child)
      all.add(child)
    }
    i++
  }
  for (k in all.size - 1 downTo 0) {
    val b = all[k]
    if (b.parent != null) b.parent.value += b.value
  }

  val gap = insets.gap
  val header = insets.header
  val short = insets.short
  val side = insets.side
  val placed = ArrayList<Box>()
  val place = fun(children: List<Box>, x: Double, y: Double, wIn: Double, hIn: Double) {
    // PORT: a NaN difference counts as equal, like a NaN comparator result in JS.
    val shown = children.filter { b -> b.value > 0 }.sortedWith { a, b ->
      val d = b.value - a.value
      if (d.isNaN()) 0 else if (d < 0) -1 else if (d > 0) 1 else 0
    }
    var total = 0.0
    for (b in shown) total += b.value
    if (shown.isEmpty() || !total.isFinite()) return
    val w = maxOf(0.0, wIn)
    val h = maxOf(0.0, hIn)
    if (w > 0 && h > 0) squarify(shown, total, x - gap / 2, y - gap / 2, w + gap, h + gap, gap)
    for (b in shown) {
      val x0 = clamp(b.x, x, x + w)
      val y0 = clamp(b.y, y, y + h)
      b.w = clamp(b.x + b.w, x0, x + w) - x0
      b.h = clamp(b.y + b.h, y0, y + h) - y0
      b.x = x0
      b.y = y0
      placed.add(b)
    }
  }

  place(top, 0.0, 0.0, width, height)
  var p = 0
  while (p < placed.size) {
    val b = placed[p]
    p++
    if (b.children.size == 0) continue
    val headerRoom = if (b.h - header - side >= 12) header else short
    b.headed = b.w > maxOf(24.0, 2 * side) && b.h - headerRoom - side >= 8
    b.header = if (b.headed) headerRoom else 0.0
    if (b.headed) place(b.children, b.x + side, b.y + headerRoom, b.w - 2 * side, b.h - headerRoom - side)
    else place(b.children, b.x, b.y, b.w, b.h)
  }
  return placed
}
