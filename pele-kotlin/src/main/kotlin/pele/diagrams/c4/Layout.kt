// Ported from Pele src/diagrams/c4/layout.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSplit
import pele.js.jsTrim
import pele.svg.ARROW
import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.layoutLabel
import java.util.IdentityHashMap
import kotlin.math.floor

private const val SHAPE_W = 216.0
private const val SHAPE_MIN_H = 64.0
private const val PAD_X = 14.0
private const val PAD_Y = 12.0

// Room between two columns for the label of a line across them, clear of an arrowhead on either side,
// with an arrowhead's length to spare for a word a little wider than the label room.
private const val GAP_X = NARROW + LABEL_PAD + 3 * ARROW
private const val GAP_Y = 88.0

// Room for a relation to run along the inside of a boundary.
const val GROUP_PAD = LANE + 8
private const val GROUP_GAP = 48.0

// Where a boundary's heading starts, from its top-left corner.
const val HEAD_LEFT = 16.0
const val HEAD_TOP = 10.0

private val RE_UNDERSCORES = JsRegExp("_", "g")

// Shape keywords Mermaid accepts in $shape, $sprite or $tags, as names of the shared shapes.
private val FORMS: Map<String, String> = mapOf(
  "person" to "person",
  "box" to "rect",
  "rounded" to "rect",
  "component" to "rect",
  "cylinder" to "cyl",
  "database" to "cyl",
  "db" to "cyl",
  "queue" to "h-cyl",
  "pipe" to "h-cyl",
)

class Node(
  val shape: C4Shape,
  form: String,
  // Type, name, technology, description.
  val labels: List<Label>,
  val textH: Double,
  x: Double,
  y: Double,
  w: Double,
  h: Double,
) : Box(x, y, w, h, form)

class Group(
  val boundary: C4Boundary,
  val kids: MutableList<Group>,
  val nodes: MutableList<Node>,
  // Name, type, description.
  val labels: List<Label>,
  var head: Double,
  var headW: Double,
  var headX: Double,
  x: Double,
  y: Double,
  w: Double,
  h: Double,
  form: String,
) : Box(x, y, w, h, form)

class Layout(
  // The boundaries that are drawn, parents before children. The first is the unnamed top level.
  val order: List<Group>,
  val boxes: Map<C4Element, Box>,
)

private fun formOf(shape: C4Shape): String {
  val explicit = FORMS[(shape.shape ?: "").lowercase()] ?: FORMS[(shape.sprite ?: "").lowercase()]
  if (!explicit.isNullOrEmpty()) return explicit
  val tags = shape.tags
  if (!tags.isNullOrEmpty()) {
    for (tag in tags.jsSplit(",")) {
      val form = FORMS[tag.jsTrim().lowercase()]
      if (!form.isNullOrEmpty()) return form
    }
  }
  val kind = shape.kind
  return if (kind.endsWith("person")) "person" else if (kind.endsWith("_db")) "cyl" else if (kind.endsWith("_queue")) "h-cyl" else "rect"
}

private fun measure(shape: C4Shape, measurer: TextMeasurer, size: Double, small: Double, width: Double = SHAPE_W): Node {
  val form = formOf(shape)
  val capW = if (form == "h-cyl") 36.0 else 0.0
  val capH = if (form == "person") HEAD else if (form == "cyl") 24.0 else 0.0
  val inner = width - 2 * PAD_X - capW
  val techn = shape.techn
  val labels = listOf(
    layoutLabel("«" + shape.kind.jsReplace(RE_UNDERSCORES, " ") + "»", false, measurer, small - 1, inner),
    layoutLabel(shape.label, false, measurer, size, inner, Style.Bold),
    layoutLabel(if (!techn.isNullOrEmpty()) "[$techn]" else null, false, measurer, small, inner),
    layoutLabel(shape.descr, false, measurer, small, inner),
  )
  var tw = 0.0
  var textH = if (labels[3].height > 0) 4.0 else 0.0
  for (label in labels) {
    tw = maxOf(tw, label.width)
    textH += label.height
  }
  return Node(
    shape,
    form,
    labels,
    textH,
    0.0,
    0.0,
    maxOf(width, tw + 2 * PAD_X + capW),
    maxOf(SHAPE_MIN_H, textH + 2 * PAD_Y + capH),
  )
}

// Places boxes left to right in rows of `perRow`, from (0, 0), and returns the width and height the rows take.
// Shapes are stretched to the height of their row; boundaries keep their own.
private fun rows(boxes: List<Box>, perRow: Double, gapX: Double, gapY: Double, stretch: Boolean): Pair<Double, Double> {
  var width = 0.0
  var y = 0.0
  var from = 0.0
  while (from < boxes.size) {
    val to = minOf(boxes.size.toDouble(), from + perRow)
    var x = 0.0
    var rowH = 0.0
    for (i in from.toInt() until to.toInt()) rowH = maxOf(rowH, boxes[i].h)
    for (i in from.toInt() until to.toInt()) {
      val box = boxes[i]
      box.x = x
      box.y = y
      if (stretch) box.h = rowH
      x += box.w + gapX
    }
    width = maxOf(width, x - gapX)
    y += rowH + gapY
    from += perRow
  }
  return Pair(width, if (boxes.size > 0) y - gapY else 0.0)
}

// Mermaid's grid: in each boundary, its shapes in rows of c4ShapeInRow, then the boundaries inside it
// in rows of c4BoundaryInRow, each sized around its own content.
fun layoutC4(db: C4Db, measurer: TextMeasurer, size: Double, small: Double, room: Double?, gap: Double): Layout {
  val groupPad = if (room == null) GROUP_PAD else 12.0
  val groups = LinkedHashMap<C4Boundary, Group>()
  for (boundary in db.boundaries) {
    val labelRoom = if (room == null) 4000.0 else maxOf(80.0, room - 2 * HEAD_LEFT - 2 * GROUP_PAD * 2)
    val type = boundary.type
    val labels = if (boundary.parent != null) {
      listOf(
        layoutLabel(boundary.label, false, measurer, size, labelRoom, Style.Bold),
        layoutLabel(if (!type.isNullOrEmpty()) "[$type]" else null, false, measurer, small, labelRoom),
        layoutLabel(boundary.descr, false, measurer, small, labelRoom),
      )
    } else {
      emptyList()
    }
    var head = 0.0
    var headW = 0.0
    for (label in labels) {
      head += label.height
      headW = maxOf(headW, label.width)
    }
    groups[boundary] = Group(
      boundary,
      ArrayList(),
      ArrayList(),
      labels,
      if (head > 0) head + HEAD_TOP + 6 + LANE else groupPad,
      headW,
      HEAD_LEFT,
      0.0,
      0.0,
      0.0,
      0.0,
      "rect",
    )
  }
  val root = groups[db.boundaries[0]]!!
  for (group in groups.values) {
    val parent = group.boundary.parent
    if (parent != null) groups[parent]?.kids?.add(group)
  }
  // A boundary that ends up inside itself is not reachable from the top, as in Mermaid.
  val order = arrayListOf(root)
  val depths = LinkedHashMap<C4Boundary, Double>()
  depths[root.boundary] = 0.0
  var oi = 0
  while (oi < order.size) {
    for (kid in order[oi].kids) {
      depths[kid.boundary] = depths[order[oi].boundary]!! + 1
      order.add(kid)
    }
    oi++
  }
  for (shape in db.shapes) {
    val parent = shape.parent
    if (parent != null) {
      val depth = depths[parent] ?: 0.0
      val nodeWidth = if (room == null) SHAPE_W else maxOf(100.0, minOf(SHAPE_W, room - 2 * depth * groupPad - 32))
      groups[parent]?.nodes?.add(measure(shape, measurer, size, small, nodeWidth))
    }
  }

  val perRow = maxOf(1.0, db.shapeInRow)
  val groupsPerRow = maxOf(1.0, db.boundaryInRow)
  val kidsTop = IdentityHashMap<Group, Double>()
  for (i in order.size - 1 downTo 0) {
    val group = order[i]
    val gapX = if (room == null) GAP_X else gap
    fun count(boxes: List<Box>, limit: Double, gap: Double): Double {
      if (room == null) return limit
      var widest = 1.0
      for (box in boxes) widest = maxOf(widest, box.w)
      return maxOf(1.0, minOf(limit, floor((room + gap - 2 * groupPad) / (widest + gap))))
    }
    val (gridW, gridH) = rows(group.nodes, count(group.nodes, perRow, gapX), gapX, GAP_Y, true)
    val (kidsW, kidsH) = rows(group.kids, count(group.kids, groupsPerRow, GROUP_GAP), GROUP_GAP, GROUP_GAP, false)
    val between = if (gridH > 0 && kidsH > 0) GROUP_GAP else 0.0
    val frame = if (group === root) 0.0 else groupPad
    kidsTop[group] = gridH + between
    group.w = maxOf(gridW, kidsW, group.headW + 2 * HEAD_LEFT - 2 * frame) + 2 * frame
    group.h = (if (frame != 0.0) group.head else frame) + gridH + between + kidsH + frame
  }

  val boxes = IdentityHashMap<C4Element, Box>()
  for (group in order) {
    val left = group.x + (if (group === root) 0.0 else groupPad)
    val top = group.y + (if (group === root) 0.0 else group.head)
    for (node in group.nodes) {
      node.x += left
      node.y += top
      boxes[node.shape] = node
    }
    for (kid in group.kids) {
      kid.x += left
      kid.y += top + kidsTop[group]!!
    }
    if (group !== root) boxes[group.boundary] = group
  }
  return Layout(order, boxes)
}
