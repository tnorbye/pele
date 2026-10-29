// Ported from Pele src/layout/components.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.layout

import kotlin.math.max
import kotlin.math.min

private class Part(
  val nodes: MutableList<CNode>,
  val edges: MutableList<CEdge>,
  var left: Double,
  var top: Double,
  var right: Double,
  var bottom: Double,
)

// Moves disconnected parts into rows without changing the layout inside a part.
fun wrapComponents(nodes: List<CNode>, edges: List<CEdge>, width: Double, gap: Double): CompoundResult? {
  val parent = IntArray(nodes.size) { it }
  val root = { iIn: Int ->
    var i = iIn
    while (parent[i] != i) {
      parent[i] = parent[parent[i]]
      i = parent[i]
    }
    i
  }
  val join = { a: Int, b: Int -> parent[root(a)] = root(b) }
  for (i in nodes.indices) if (nodes[i].parent >= 0) join(i, nodes[i].parent)
  for (e in edges) join(e.src, e.dst)

  val parts = LinkedHashMap<Int, Part>()
  val include = { p: Part, x: Double, y: Double, w: Double, h: Double ->
    p.left = min(p.left, x - w / 2)
    p.top = min(p.top, y - h / 2)
    p.right = max(p.right, x + w / 2)
    p.bottom = max(p.bottom, y + h / 2)
  }
  for (i in nodes.indices) {
    val id = root(i)
    var part = parts[id]
    if (part == null) {
      part = Part(ArrayList(), ArrayList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
      parts[id] = part
    }
    val c = nodes[i]
    part.nodes.add(c)
    include(part, c.x, c.y, c.w, c.h)
  }
  if (parts.size < 2) return null
  for (e in edges) {
    val part = parts[root(e.src)]!!
    part.edges.add(e)
    var i = 0
    while (i < e.route.size) {
      include(part, e.route[i], e.route[i + 1], 0.0, 0.0)
      i += 3
    }
    if (e.labelW > 0) include(part, e.labelX, e.labelY, e.labelW, e.labelH)
  }

  var x = 0.0
  var y = 0.0
  var rowHeight = 0.0
  var right = 0.0
  var from = Double.POSITIVE_INFINITY
  var to = Double.NEGATIVE_INFINITY
  val shifts = ArrayList<Double>()
  for (part in parts.values) {
    val w = part.right - part.left
    val h = part.bottom - part.top
    if (x > 0 && x + w > width) {
      y += rowHeight + gap
      x = 0.0
      rowHeight = 0.0
    }
    shifts.add(x - part.left)
    shifts.add(y - part.top)
    from = min(from, part.left)
    to = max(to, part.right)
    right = max(right, x + w)
    rowHeight = max(rowHeight, h)
    x += w + gap
  }
  if (right >= to - from) return null
  var k = 0
  for (part in parts.values) {
    val ox = shifts[k++]
    val oy = shifts[k++]
    shiftLayout(part.nodes, part.edges, ox, oy)
  }
  return CompoundResult(right, y + rowHeight)
}
