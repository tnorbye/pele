// Ported from Pele src/diagrams/block/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign
import kotlin.math.sqrt
import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.tighten
import pele.diagrams.common.titleRoom
import pele.js.jsHypot
import pele.js.jsOr
import pele.js.jsRound
import pele.js.jsSplit
import pele.js.jsStr
import pele.js.jsTrim
import pele.svg.ARROW
import pele.svg.EdgePath
import pele.svg.Fill
import pele.svg.ResolvedStyle
import pele.svg.casingsSvg
import pele.svg.classNames
import pele.svg.drawShape
import pele.svg.edgeCasing
import pele.svg.edgeLabelSvg
import pele.svg.escText
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.overFills
import pele.svg.paints
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.setsFill
import pele.svg.shapeFilled
import pele.svg.shapeInset
import pele.svg.shapeSize
import pele.svg.svgDocument
import pele.svg.titleLabel
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel

private const val GAP = 12.0
private const val WRAP = 200.0
private const val CORNER = 8.0
private const val SHAPE_ATTRS = " fill=\"var(--_s)\" stroke=\"var(--_b)\""
private const val LINE_ATTRS = " stroke=\"var(--_b)\""
private val NO_CLASSES: List<String> = emptyList()
private val NO_BLOCKS: List<Block> = emptyList()
private val NO_HEIGHTS: MutableList<Double> =
  ArrayList() // PORT-OK: a sentinel that is never written to
private val NO_GAPS: MutableList<Double?> =
  ArrayList() // PORT-OK: a sentinel that is never written to
private val DEFAULT_CLASS = listOf("default")

// Checking every edge against every block is quadratic, so large diagrams route without it.
private const val OBSTACLE_BUDGET = 2_000_000.0
private const val LABEL_GAP = 4.0

private val SHAPES: Map<String, String> =
  mapOf(
    "square" to "rect",
    "round" to "rounded",
    "circle" to "circle",
    "doublecircle" to "dbl-circ",
    "diamond" to "diam",
    "hexagon" to "hex",
    "stadium" to "stadium",
    "subroutine" to "fr-rect",
    "cylinder" to "cyl",
    "rect_left_inv_arrow" to "odd",
    "lean_right" to "lean-r",
    "lean_left" to "lean-l",
    "trapezoid" to "trap-b",
    "inv_trapezoid" to "trap-t",
  )

// Shapes that keep their own size in a larger cell, where the others stretch to fill it.
private val FIXED: Set<String> = setOf("circle", "dbl-circ", "diam")

// Shapes that only widen, because their slants and curves are set by their height.
private val WIDE: Set<String> =
  setOf("stadium", "hex", "odd", "lean-r", "lean-l", "trap-b", "trap-t")

private object Kind {
  const val Leaf = 0
  const val Arrow = 1
  const val Composite = 2
  const val Space = 3
}

private class View(
  val block: Block,
  val kind: Int,
  val index: Int,
  val shape: String,
  val label: Label,
  val style: ResolvedStyle,
  val mask: Int,
  var dx: Double,
  var dy: Double,
  columns: Double,
  span: Double,
  space: Boolean,
  head: Double,
  nw: Double,
  nh: Double,
) :
  Cell(
    columns = columns,
    span = span,
    space = space,
    head = head,
    nw = nw,
    nh = nh,
    heights = NO_HEIGHTS,
    rowGaps = NO_GAPS,
    colGaps = NO_GAPS,
  ) {
  // Size as drawn, which for a fixed shape is smaller than its cell.
  var bw: Double = 0.0
  var bh: Double = 0.0
}

private class TitleBox(
  val x: Double,
  val y: Double,
  val bw: Double,
  val bh: Double,
)

private class Route(
  val edge: Block,
  // Two points for a straight line, three for a bend, none for a self-loop.
  val points: DoubleArray,
  val loop: Int,
  val label: Label,
  var lx: Double,
  var ly: Double,
)

private fun isRound(v: View): Boolean {
  return v.kind == Kind.Leaf && (v.shape == "circle" || v.shape == "dbl-circ")
}

// Where a vertical line at x meets the outline, below the center (sign 1) or above it (sign -1).
private fun edgeY(v: View, x: Double, sign: Double): Double {
  val hw = v.bw / 2
  val hh = v.bh / 2
  val dx = abs(x - v.x)
  var reach = hh
  if (isRound(v)) reach = sqrt(maxOf(hw * hw - dx * dx, 0.0))
  else if (v.kind == Kind.Leaf && v.shape == "diam") reach = hh * maxOf(1 - dx / hw, 0.0)
  else if (v.kind == Kind.Leaf) reach = hh - shapeInset(v.shape, v.bw, v.bh, if (sign > 0) 2 else 0)
  else if (v.kind == Kind.Arrow && arrowAxis(v.mask) == "x") reach = hh - OVERHANG
  return v.y + sign * reach
}

// Where a horizontal line at y meets the outline, right of the center (sign 1) or left of it (sign
// -1).
private fun edgeX(v: View, y: Double, sign: Double): Double {
  val hw = v.bw / 2
  val hh = v.bh / 2
  val dy = abs(y - v.y)
  var reach = hw
  if (isRound(v)) reach = sqrt(maxOf(hh * hh - dy * dy, 0.0))
  else if (v.kind == Kind.Leaf && v.shape == "diam") reach = hw * maxOf(1 - dy / hh, 0.0)
  else if (v.kind == Kind.Leaf) reach = hw - shapeInset(v.shape, v.bw, v.bh, if (sign > 0) 1 else 3)
  else if (v.kind == Kind.Arrow && arrowAxis(v.mask) == "y") reach = hw - OVERHANG
  return v.x + sign * reach
}

// Where the line from the center toward (tx, ty) leaves the outline.
private fun border(v: View, tx: Double, ty: Double, out: MutableList<Double>) {
  val dx = tx - v.x
  val dy = ty - v.y
  val hw = v.bw / 2
  val hh = v.bh / 2
  var k: Double
  if (isRound(v)) k = hw / jsHypot(dx, dy).jsOr { 1.0 }
  else if (v.kind == Kind.Leaf && v.shape == "diam")
    k = 1 / (abs(dx) / hw + abs(dy) / hh).jsOr { 1.0 }
  else
    k =
      minOf(
        if (dx == 0.0) Double.POSITIVE_INFINITY else hw / abs(dx),
        if (dy == 0.0) Double.POSITIVE_INFINITY else hh / abs(dy),
      )
  if (k == Double.POSITIVE_INFINITY) k = 0.0
  out.add(v.x + dx * k)
  out.add(v.y + dy * k)
}

private fun crossesBox(x1: Double, y1: Double, x2: Double, y2: Double, v: View): Boolean {
  val l = v.x - v.bw / 2 + 1
  val r = v.x + v.bw / 2 - 1
  val t = v.y - v.bh / 2 + 1
  val b = v.y + v.bh / 2 - 1
  if ((x1 < l && x2 < l) || (x1 > r && x2 > r) || (y1 < t && y2 < t) || (y1 > b && y2 > b))
    return false
  if (x1 == x2 || y1 == y2) return true
  // The segment is diagonal and its bounding box overlaps: the box is hit unless all four corners
  // are on one side.
  val dx = x2 - x1
  val dy = y2 - y1
  fun side(x: Double, y: Double): Double = sign(dx * (y - y1) - dy * (x - x1))
  val first = side(l, t)
  return first == 0.0 || side(r, t) != first || side(r, b) != first || side(l, b) != first
}

private fun hits(points: DoubleArray, s: View, t: View, obstacles: List<View>): Int {
  var count = 0
  for (v in obstacles) {
    if (v === s || v === t) continue
    var i = 0
    while (i + 3 < points.size) {
      if (crossesBox(points[i], points[i + 1], points[i + 2], points[i + 3], v)) {
        count++
        break
      }
      i += 2
    }
  }
  return count
}

// A straight line where the two blocks share a row or a column, otherwise one bend between facing
// sides.
private fun routeEdge(s: View, t: View, shift: Double, obstacles: List<View>?): DoubleArray {
  val sl = s.x - s.bw / 2
  val sr = s.x + s.bw / 2
  val st = s.y - s.bh / 2
  val sb = s.y + s.bh / 2
  val tl = t.x - t.bw / 2
  val tr = t.x + t.bw / 2
  val tt = t.y - t.bh / 2
  val tb = t.y + t.bh / 2
  val x1 = maxOf(sl, tl)
  val x2 = minOf(sr, tr)
  val y1 = maxOf(st, tt)
  val y2 = minOf(sb, tb)
  val overX = x2 - x1 > 6
  val overY = y2 - y1 > 6

  if (overX && overY) {
    // One block is inside the other: join the inner one to the nearest side of the outer one.
    val inner = if (s.bw * s.bh <= t.bw * t.bh) s else t
    val outer = if (inner === s) t else s
    val up = inner.y - inner.bh / 2 - (outer.y - outer.bh / 2)
    val down = outer.y + outer.bh / 2 - (inner.y + inner.bh / 2)
    val left = inner.x - inner.bw / 2 - (outer.x - outer.bw / 2)
    val right = outer.x + outer.bw / 2 - (inner.x + inner.bw / 2)
    val least = minOf(up, down, left, right)
    val points: DoubleArray
    if (least == down)
      points = doubleArrayOf(inner.x, edgeY(inner, inner.x, 1.0), inner.x, outer.y + outer.bh / 2)
    else if (least == up)
      points = doubleArrayOf(inner.x, edgeY(inner, inner.x, -1.0), inner.x, outer.y - outer.bh / 2)
    else if (least == right)
      points = doubleArrayOf(edgeX(inner, inner.y, 1.0), inner.y, outer.x + outer.bw / 2, inner.y)
    else
      points = doubleArrayOf(edgeX(inner, inner.y, -1.0), inner.y, outer.x - outer.bw / 2, inner.y)
    return if (inner === s) points else doubleArrayOf(points[2], points[3], points[0], points[1])
  }
  if (overX) {
    val margin = minOf(6.0, (x2 - x1) / 2)
    val x = minOf(maxOf((x1 + x2) / 2 + shift, x1 + margin), x2 - margin)
    val down = if (t.y > s.y) 1.0 else -1.0
    return doubleArrayOf(x, edgeY(s, x, down), x, edgeY(t, x, -down))
  }
  if (overY) {
    val margin = minOf(6.0, (y2 - y1) / 2)
    val y = minOf(maxOf((y1 + y2) / 2 + shift, y1 + margin), y2 - margin)
    val right = if (t.x > s.x) 1.0 else -1.0
    return doubleArrayOf(edgeX(s, y, right), y, edgeX(t, y, -right), y)
  }

  val right = if (t.x > s.x) 1.0 else -1.0
  val down = if (t.y > s.y) 1.0 else -1.0
  val across = doubleArrayOf(edgeX(s, s.y, right), s.y, t.x, s.y, t.x, edgeY(t, t.x, -down))
  val along = doubleArrayOf(s.x, edgeY(s, s.x, down), s.x, t.y, edgeX(t, t.y, -right), t.y)
  val wide = abs(t.x - s.x) >= abs(t.y - s.y)
  var best = if (wide) across else along
  if (obstacles == null) return best
  var least = hits(best, s, t, obstacles)
  if (least == 0) return best
  val other = if (wide) along else across
  val count = hits(other, s, t, obstacles)
  if (count < least) {
    best = other
    least = count
  }
  if (least == 0) return best
  val direct = ArrayList<Double>()
  border(s, t.x, t.y, direct)
  border(t, s.x, s.y, direct)
  val directPoints = direct.toDoubleArray()
  return if (hits(directPoints, s, t, obstacles) < least) directPoints else best
}

// Builds the path through two or three points, rounding the bend and leaving room for markers.
private fun trace(p: DoubleArray, startTrimIn: Double, endTrimIn: Double): EdgePath {
  var startTrim = startTrimIn
  var endTrim = endTrimIn
  val n = p.size
  val sx = p[0]
  val sy = p[1]
  val ex = p[n - 2]
  val ey = p[n - 1]
  var ax = p[2] - sx
  var ay = p[3] - sy
  val first = jsHypot(ax, ay)
  ax /= first.jsOr { 1.0 }
  ay /= first.jsOr { 1.0 }
  var bx = ex - p[n - 4]
  var by = ey - p[n - 3]
  val last = jsHypot(bx, by)
  bx /= last.jsOr { 1.0 }
  by /= last.jsOr { 1.0 }
  if (n == 4 && startTrim + endTrim > first) {
    val scale = first / (startTrim + endTrim)
    startTrim *= scale
    endTrim *= scale
  }
  startTrim = minOf(startTrim, first)
  endTrim = minOf(endTrim, last)
  var d = "M${num(sx + ax * startTrim)},${num(sy + ay * startTrim)}"
  if (n == 6) {
    val r = maxOf(0.0, minOf(CORNER, first - startTrim, last - endTrim))
    d +=
      "L${num(p[2] - ax * r)},${num(p[3] - ay * r)}Q${num(p[2])},${num(p[3])} ${num(p[2] + bx * r)},${num(p[3] + by * r)}"
  }
  d += "L${num(ex - bx * endTrim)},${num(ey - by * endTrim)}"
  return EdgePath(d, null, sx, sy, -ax, -ay, ex, ey, bx, by)
}

// A self-loop is an arc around the top right corner of the block, or around the matching point on a
// round or diamond outline. Gives the arc's center and radius and the angles where it leaves and
// returns.
private fun loopArc(v: View, k: Int): DoubleArray {
  val hw = v.bw / 2
  val hh = v.bh / 2
  val r = maxOf(1.0, minOf(10 + 5 * (k - 1.0), hw, hh))
  if (isRound(v)) {
    val px = v.x + hw * SQRT1_2
    val py = v.y - hw * SQRT1_2
    val half = 2 * StrictMath.asin(minOf(1.0, r / (2 * hw)))
    fun angle(at: Double): Double =
      StrictMath.atan2(v.y - hw * StrictMath.sin(at) - py, v.x + hw * StrictMath.cos(at) - px)
    return doubleArrayOf(px, py, r, angle(Math.PI / 4 + half), angle(Math.PI / 4 - half))
  }
  if (v.kind == Kind.Leaf && v.shape == "diam") {
    val slope = StrictMath.atan2(hh, hw)
    return doubleArrayOf(v.x + hw / 2, v.y - hh / 2, r, slope + Math.PI, slope)
  }
  return doubleArrayOf(v.x + hw, v.y - hh, r, Math.PI, Math.PI / 2)
}

// Math.SQRT1_2
private const val SQRT1_2 = 0.7071067811865476

private fun loopPath(v: View, k: Int, startTrim: Double, endTrim: Double): EdgePath {
  val arc = loopArc(v, k)
  val px = arc[0]
  val py = arc[1]
  val r = arc[2]
  val a1 = arc[3]
  val a2 = arc[4]
  val from = a1 + startTrim / r
  val to = a2 - endTrim / r
  var sweep = (to - from) % (2 * Math.PI)
  if (sweep <= 0) sweep += 2 * Math.PI
  val x1 = px + r * StrictMath.cos(from)
  val y1 = py + r * StrictMath.sin(from)
  val x2 = px + r * StrictMath.cos(to)
  val y2 = py + r * StrictMath.sin(to)
  val sx = px + r * StrictMath.cos(a1)
  val sy = py + r * StrictMath.sin(a1)
  val ex = px + r * StrictMath.cos(a2)
  val ey = py + r * StrictMath.sin(a2)
  // Each marker lies along the chord it covers, so it follows the curve instead of the tangent at
  // its tip.
  val sl = jsHypot(sx - x1, sy - y1)
  val el = jsHypot(ex - x2, ey - y2)
  return EdgePath(
    d =
      "M${num(x1)},${num(y1)}A${num(r)},${num(r)} 0 ${if (sweep > Math.PI) 1 else 0} 1 ${num(x2)},${num(y2)}",
    sx = sx,
    sy = sy,
    sdx = if (sl > 0) (sx - x1) / sl else StrictMath.sin(a1),
    sdy = if (sl > 0) (sy - y1) / sl else -StrictMath.cos(a1),
    ex = ex,
    ey = ey,
    edx = if (el > 0) (ex - x2) / el else -StrictMath.sin(a2),
    edy = if (el > 0) (ey - y2) / el else StrictMath.cos(a2),
  )
}

private val SPOTS = listOf(0.5, 0.3, 0.7, 0.15, 0.85)

// PORT: obstacles are Views and TitleBoxes.
private fun covers(
  x: Double,
  y: Double,
  w: Double,
  h: Double,
  s: View,
  t: View,
  obstacles: List<Any>?,
): Boolean {
  if (obstacles == null) return false
  for (v in obstacles) {
    if (v === s || v === t) continue
    val vx: Double
    val vy: Double
    val vw: Double
    val vh: Double
    if (v is View) {
      vx = v.x
      vy = v.y
      vw = v.bw
      vh = v.bh
    } else {
      v as TitleBox
      vx = v.x
      vy = v.y
      vw = v.bw
      vh = v.bh
    }
    if (abs(x - vx) < (w + vw) / 2 && abs(y - vy) < (h + vh) / 2) return true
  }
  return false
}

// Picks the point on a leg where a label of the given size covers no block other than the two it
// joins.
private fun labelSpot(
  p: DoubleArray,
  at: Int,
  w: Double,
  h: Double,
  s: View,
  t: View,
  obstacles: List<View>?,
): Double {
  if (obstacles == null) return 0.5
  for (spot in SPOTS) {
    val x = p[at] + (p[at + 2] - p[at]) * spot
    val y = p[at + 1] + (p[at + 3] - p[at + 1]) * spot
    if (!covers(x, y, w, h, s, t, obstacles)) return spot
  }
  return 0.5
}

// Stacks overlapping labels along their shared line, if they fit between its markers and cover no
// other block or title.
// Returns the extra length needed to fit the labels.
private fun stackLabels(
  stack: List<Int>,
  routes: List<Route>,
  legs: Array<DoubleArray?>,
  s: View,
  t: View,
  obstacles: List<Any>?,
): Double {
  val vertical = legs[stack[0]]!![0] == legs[stack[0]]!![2]
  var lo = Double.NEGATIVE_INFINITY
  var hi = Double.POSITIVE_INFINITY
  var need = -LABEL_GAP
  var crowded = false
  for (a in 0 until stack.size) {
    val leg = legs[stack[a]]!!
    if (if (vertical) leg[0] != leg[2] else leg[1] != leg[3]) return 0.0
    lo = maxOf(lo, if (vertical) minOf(leg[1], leg[3]) else minOf(leg[0], leg[2]))
    hi = minOf(hi, if (vertical) maxOf(leg[1], leg[3]) else maxOf(leg[0], leg[2]))
    val p = routes[stack[a]]
    need += (if (vertical) p.label.height else p.label.width + 8) + LABEL_GAP
    for (b in 0 until a) {
      val q = routes[stack[b]]
      if (
        abs(p.lx - q.lx) < (p.label.width + q.label.width) / 2 + 8 &&
          abs(p.ly - q.ly) < (p.label.height + q.label.height) / 2
      )
        crowded = true
    }
  }
  if (!crowded) return 0.0
  if (need > hi - lo - 2 * ARROW) return need - (hi - lo - 2 * ARROW)
  val along = ArrayList<Double>()
  var next = (lo + hi - need) / 2
  for (i in stack) {
    val label = routes[i].label
    val lx = routes[i].lx
    val ly = routes[i].ly
    val size = if (vertical) label.height else label.width + 8
    val at = next + size / 2
    if (
      covers(
        if (vertical) lx else at,
        if (vertical) at else ly,
        label.width + 8,
        label.height,
        s,
        t,
        obstacles,
      )
    )
      return 0.0
    along.add(at)
    next += size + LABEL_GAP
  }
  stack.forEachIndexed { k, i ->
    if (vertical) routes[i].ly = along[k] else routes[i].lx = along[k]
  }
  return 0.0
}

// Widens the gap in the blocks' nearest shared container.
// `base` keeps the gaps as they were when the labels were measured.
private fun widen(
  parents: Map<Int, View>,
  s: View,
  t: View,
  vertical: Boolean,
  short: Double,
  base: IdentityHashMap<MutableList<Double?>, MutableList<Double?>>,
): Boolean {
  val chain = HashSet<View>()
  var cv: View? = s
  while (cv != null) {
    chain.add(cv)
    cv = parents[cv.index]
  }
  var tb: View? = null
  var box: View? = t
  while (box != null && !chain.contains(box)) {
    tb = box
    box = parents[box.index]
  }
  if (box == null || tb == null || box === s) return false
  var sb = s
  while (parents[sb.index] !== box) sb = parents[sb.index]!!
  val at: Int
  if (vertical) {
    if (sb.row == tb.row) return false
    at = (maxOf(sb.row, tb.row) - 1).toInt()
    if (box.rowGaps === NO_GAPS) box.rowGaps = ArrayList()
  } else {
    val l = if (sb.col < tb.col) sb else tb
    val r = if (sb.col < tb.col) tb else sb
    if (r.col < l.col + l.span) return false
    at = (r.col - 1).toInt()
    if (box.colGaps === NO_GAPS) box.colGaps = ArrayList()
  }
  val gaps = if (vertical) box.rowGaps else box.colGaps
  if (!base.containsKey(gaps)) base[gaps] = ArrayList(gaps)
  // PORT: a JS array grows with holes; null stands for a hole.
  val value = maxOf(gaps.getOrNull(at) ?: 0.0, (base[gaps]!!.getOrNull(at) ?: 0.0) + short)
  while (gaps.size <= at) gaps.add(null)
  gaps[at] = value
  return true
}

fun renderBlock(model: BlockModel, config: Config, options: RenderOptions): Rendered {
  return tighten(options) { tight -> draw(model, config, options, tight) }
}

private fun draw(
  model: BlockModel,
  config: Config,
  options: RenderOptions,
  tight: Boolean,
): Rendered {
  val size = options.fontSize ?: 16.0
  val edgeSize = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = options.icons
  val configured = (config["block"] as? Map<*, *>)?.get("padding")
  val gap =
    minOf(
      if (configured is Double && configured >= 0) minOf(configured, 200.0) else GAP,
      if (tight) 6.0 else Double.POSITIVE_INFINITY,
    )
  val wrap = if (tight) 80.0 else WRAP

  fun styleOf(block: Block): ResolvedStyle {
    val declarations = ArrayList<String>()
    // A style list is split on commas only, so one entry can still hold several declarations.
    fun add(styles: List<String>) {
      for (style in styles) for (part in style.jsSplit(";")) declarations.add(part)
    }
    val blockClasses = block.classes
    for (name in
      if (blockClasses != null && blockClasses.size > 0) blockClasses else DEFAULT_CLASS) {
      val def = model.classes[name]
      if (def != null) add(def.styles)
    }
    val blockStyles = block.styles
    if (blockStyles != null) add(blockStyles)
    return resolveStyle(declarations)
  }

  fun view(block: Block, index: Int): View {
    val type = block.type
    val style = if (type == "space") resolveStyle(NO_CLASSES) else styleOf(block)
    val textStyle = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val fontSize = style.fontSize ?: size
    var kind = Kind.Leaf
    var shape = ""
    val label: Label
    var nw = 0.0
    var nh = 0.0
    var head = 0.0
    var mask = 0
    var dx = 0.0
    var dy = 0.0
    if (type == "space") {
      kind = Kind.Space
      label = layoutLabel(null, false, measurer, fontSize, wrap)
    } else if (type == "composite") {
      kind = Kind.Composite
      label =
        layoutLabel(
          block.label,
          false,
          measurer,
          fontSize,
          if (tight) titleRoom(options) else 4000.0,
          textStyle,
        )
      head = if (label.height > 0) label.height + 4 else 0.0
      nw = label.width + 2 * gap
      nh = head + 2 * gap
    } else if (type == "block_arrow") {
      kind = Kind.Arrow
      label = layoutLabel(block.label, false, measurer, fontSize, wrap, textStyle)
      var blank = true
      for (line in label.lines) for (part in line) if (
        part.icon != null || part.text.jsTrim() != ""
      )
        blank = false
      mask = arrowMask(block.directions)
      val s = if (blank) arrowSize(mask, 0.0, 0.0) else arrowSize(mask, label.width, label.height)
      nw = s.w
      nh = s.h
      dx = s.dx
      dy = s.dy
    } else {
      shape = SHAPES[type ?: ""] ?: "rect"
      label = layoutLabel(block.label, false, measurer, fontSize, wrap, textStyle)
      val s = shapeSize(shape, label.width, label.height)
      nw = s.w
      // A cylinder's caps deepen as it widens, so it is sized for the deepest ones.
      nh = if (shape == "cyl") maxOf(s.h, label.height + 40) else s.h
      dy = s.dy
    }
    return View(
      block,
      kind,
      index,
      shape,
      label,
      style,
      mask,
      dx,
      dy,
      if (kind == Kind.Composite) (block.columns ?: -1.0) else -1.0,
      if (kind == Kind.Space) 1.0 else (block.widthInColumns ?: 1.0),
      kind == Kind.Space,
      head,
      nw,
      nh,
    )
  }

  val root = view(model.root, 0)
  root.nw = 0.0
  root.nh = 0.0
  val order = arrayListOf(root)
  val parents = HashMap<Int, View>()
  val byId = HashMap<String, View>()
  var oi = 0
  while (oi < order.size) {
    val parent = order[oi]
    oi++
    if (parent.kind != Kind.Composite) continue
    for (child in parent.block.children ?: NO_BLOCKS) {
      val v = view(child, order.size)
      parent.children.add(v)
      parents[v.index] = parent
      order.add(v)
      if (v.kind != Kind.Space) byId[child.id!!] = v
    }
  }

  val obstacles = ArrayList<View>()
  val titles = ArrayList<TitleBox>()
  // Layout keeps the widest a container has been, so each run starts from the widths it needs.
  val widths = order.map { it.nw }
  fun place(): Content {
    for (v in order) v.nw = widths[v.index]
    val content = layout(root, gap)
    obstacles.clear()
    titles.clear()
    for (v in order) {
      if (v.kind == Kind.Leaf && v.shape in FIXED) {
        v.bw = v.nw
        v.bh = v.nh
      } else if (v.kind == Kind.Arrow) {
        v.bw = if (v.span > 1) v.w else v.nw
        v.bh = v.nh
      } else {
        v.bw = v.w
        v.bh = if (v.kind == Kind.Leaf && v.shape in WIDE) v.nh else v.h
      }
      if (v.kind == Kind.Leaf && v.shape == "cyl") v.dy = minOf(12.0, maxOf(6.0, v.bw / 14)) / 2
      if (v.kind == Kind.Leaf || v.kind == Kind.Arrow) obstacles.add(v)
      else if (v.head > 0)
        titles.add(
          TitleBox(v.x, v.y - v.h / 2 + (v.head + gap) / 2 + 2, v.label.width, v.label.height)
        )
    }
    return content
  }
  var content = place()

  // Edges between the same two blocks are spread apart instead of drawn on top of each other.
  val pairs = HashMap<Long, Int>()
  val labelled = HashSet<Long>()
  val ends = ArrayList<Pair<View, View>>()
  val drawn = ArrayList<Block>()
  for (edge in model.edges) {
    val s = byId[edge.start ?: ""]
    val t = byId[edge.end ?: ""]
    if (s == null || t == null) continue
    val key = minOf(s.index, t.index).toLong() * order.size + maxOf(s.index, t.index)
    pairs[key] = (pairs[key] ?: 0) + 1
    if (!edge.label.isNullOrEmpty()) labelled.add(key)
    ends.add(Pair(s, t))
    drawn.add(edge)
  }
  val check: List<View>? =
    if (drawn.size.toDouble() * obstacles.size <= OBSTACLE_BUDGET) obstacles else null
  val routes = ArrayList<Route>()
  val stacks = LinkedHashMap<Long, MutableList<Int>>()
  val legs = arrayOfNulls<DoubleArray>(drawn.size)
  fun route() {
    val seen = HashMap<Long, Int>()
    routes.clear()
    stacks.clear()
    for (i in 0 until drawn.size) {
      val s = ends[i].first
      val t = ends[i].second
      val key = minOf(s.index, t.index).toLong() * order.size + maxOf(s.index, t.index)
      val total = pairs[key] ?: 1
      val k = seen[key] ?: 0
      seen[key] = k + 1
      val label = layoutLabel(drawn[i].label, false, measurer, edgeSize, WRAP)
      val points: DoubleArray
      val lx: Double
      val ly: Double
      if (s === t) {
        val arc = loopArc(s, k + 1)
        points = DoubleArray(0)
        lx = arc[0] + arc[2] + 6 + label.width / 2
        ly = arc[1] - arc[2] / 2
      } else {
        val step = if (labelled.contains(key)) jsRound(edgeSize * 1.5) + 2 else 14.0
        points = routeEdge(s, t, (k - (total - 1) / 2.0) * step, check)
        val n = points.size
        // The label goes on the longer leg.
        val firstLeg = abs(points[2] - points[0]) + abs(points[3] - points[1])
        val lastLeg = abs(points[n - 2] - points[n - 4]) + abs(points[n - 1] - points[n - 3])
        val at = if (n == 6 && lastLeg > firstLeg) 2 else 0
        val spot =
          if (label.width > 0) labelSpot(points, at, label.width + 8, label.height, s, t, check)
          else 0.5
        lx = points[at] + (points[at + 2] - points[at]) * spot
        ly = points[at + 1] + (points[at + 3] - points[at + 1]) * spot
        if (label.width > 0 && total > 1) {
          legs[i] = points.copyOfRange(at, at + 4)
          val stack = stacks[key]
          if (stack != null) stack.add(i) else stacks[key] = arrayListOf(i)
        }
      }
      routes.add(Route(drawn[i], points, if (s === t) k + 1 else 0, label, lx, ly))
    }
  }

  fun settle(grow: Boolean): Boolean {
    route()
    val blocked: List<Any>? =
      if (check != null) ArrayList<Any>(check).also { it.addAll(titles) } else null
    val base = IdentityHashMap<MutableList<Double?>, MutableList<Double?>>()
    var grown = false
    for (stack in stacks.values) {
      if (stack.size < 2) continue
      val s = ends[stack[0]].first
      val t = ends[stack[0]].second
      val short = stackLabels(stack, routes, legs, s, t, blocked)
      val vertical = legs[stack[0]]!![0] == legs[stack[0]]!![2]
      // Keep columns narrow to fit the host in tight layouts.
      if (
        grow &&
          short > 0 &&
          (vertical || !tight) &&
          widen(parents, s, t, vertical, ceil(short), base)
      )
        grown = true
    }
    return grown
  }
  fun overlap(): Double {
    var area = 0.0
    for (i in 0 until routes.size) {
      val p = routes[i]
      for (j in i + 1 until routes.size) {
        val q = routes[j]
        val pw = (p.label.width + 8) / 2
        val qw = (q.label.width + 8) / 2
        val w = minOf(p.lx + pw, q.lx + qw) - maxOf(p.lx - pw, q.lx - qw)
        val h =
          minOf(p.ly + p.label.height / 2, q.ly + q.label.height / 2) -
            maxOf(p.ly - p.label.height / 2, q.ly - q.label.height / 2)
        if (p.label.width > 0 && q.label.width > 0 && w > 0 && h > 0) area += w * h
      }
    }
    return area
  }
  var minX = 0.0
  var minY = 0.0
  var maxX = 0.0
  var maxY = 0.0
  fun grow(x: Double, y: Double, w: Double, h: Double) {
    if (x - w / 2 < minX) minX = x - w / 2
    if (x + w / 2 > maxX) maxX = x + w / 2
    if (y - h / 2 < minY) minY = y - h / 2
    if (y + h / 2 > maxY) maxY = y + h / 2
  }
  // Finds the drawing's extent and gives its width.
  fun bound(): Double {
    minX = 0.0
    minY = 0.0
    maxX = content.width
    maxY = content.height
    for (i in 0 until routes.size) {
      val route = routes[i]
      if (route.loop > 0) {
        val arc = loopArc(ends[i].first, route.loop)
        grow(arc[0], arc[1], 2 * arc[2] + 2, 2 * arc[2] + 2)
      }
      if (route.label.width > 0) grow(route.lx, route.ly, route.label.width + 8, route.label.height)
    }
    return maxX - minX
  }
  if (settle(drawn.size.toDouble() * drawn.size <= OBSTACLE_BUDGET)) {
    val before = overlap()
    val narrow = bound()
    // A container stretched by its row gives some of a new gap back, so the gap may take a few
    // rounds.
    var grown = true
    var round = 0
    while (round < 3 && grown) {
      content = place()
      grown = settle(round < 2)
      round++
    }
    // The drawing only grows if that leaves less of the labels covering each other, and within
    // maxWidth.
    val wide = bound()
    val maxWidth = options.maxWidth
    val room = if (maxWidth != null && maxWidth > 0) maxWidth else Double.POSITIVE_INFINITY
    if (overlap() > before - 1 || (wide > narrow && ceil(wide + 2 * pad) > room)) {
      for (v in order) {
        v.rowGaps = NO_GAPS
        v.colGaps = NO_GAPS
      }
      content = place()
      settle(false)
    }
  }

  bound()

  val title = titleLabel(model.title, measurer, size, if (tight) titleRoom(options) else 4000.0)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = maxOf(maxX - minX, title.width)
  val ox = pad - minX + (inner - (maxX - minX)) / 2
  val oy = pad + titleHeight - minY
  for (v in order) {
    v.x += ox
    v.y += oy
  }
  val width = ceil(inner + 2 * pad)
  val height = ceil(maxY - minY + titleHeight + 2 * pad)

  var clusters = ""
  var nodes = ""
  val fills = ArrayList<Fill>()
  for (i in 1 until order.size) {
    val v = order[i]
    if (v.kind == Kind.Space) continue
    val classes = classNames((v.block.classes ?: NO_CLASSES).joinToString(" "))
    val id = escText(v.block.id!!)
    if (v.kind == Kind.Composite) {
      val top = v.y - v.h / 2
      if (paints(v.style.shape))
        fills.add(Fill(doubleArrayOf(v.x - v.w / 2, top, v.x + v.w / 2, top + v.h), v.style.shape))
      clusters +=
        "<g class=\"pele-cluster$classes\" data-id=\"$id\">" +
          "<rect x=\"${num(v.x - v.w / 2)}\" y=\"${num(top)}\" width=\"${num(v.w)}\" height=\"${num(v.h)}\" fill=\"var(--_a)\"${if (setsFill(v.style.shape)) "" else " fill-opacity=\"0.5\""} stroke=\"var(--_b)\"${rounded(v.style.shape)}/>" +
          labelSvg(
            v.label,
            v.x,
            top + (v.head + gap) / 2 + 2,
            " class=\"pele-cluster-label\"${inkFill(v.style, "var(--_m)")}${v.style.text}",
            icons,
          ) +
          "</g>"
      continue
    }
    val arrow = v.kind == Kind.Arrow
    nodes +=
      "<g class=\"pele-node pele-shape-${if (arrow) "block-arrow" else v.shape}$classes\" data-id=\"$id\" transform=\"translate(${num(v.x)},${num(v.y)})\">" +
        (if (arrow) drawArrow(v.mask, v.bw, v.bh, SHAPE_ATTRS + v.style.shape)
        else
          drawShape(v.shape, v.bw, v.bh, SHAPE_ATTRS + v.style.shape, LINE_ATTRS + v.style.line)) +
        labelSvg(
          v.label,
          v.dx,
          v.dy,
          " class=\"pele-label\"${if (arrow || shapeFilled(v.shape)) inkFill(v.style) else ""}${v.style.text}",
          icons,
        ) +
        "</g>"
  }

  var casings = ""
  var edges = ""
  var labels = ""
  for (i in 0 until routes.size) {
    val edge = routes[i].edge
    val points = routes[i].points
    val loop = routes[i].loop
    val label = routes[i].label
    val startType = edge.arrowTypeStart ?: ""
    val endType = edge.arrowTypeEnd ?: ""
    val path: EdgePath
    if (loop > 0) {
      path = loopPath(ends[i].first, loop, markerTrim(startType), markerTrim(endType))
    } else {
      var k = 0
      while (k < points.size) {
        points[k] += ox
        points[k + 1] += oy
        k += 2
      }
      path = trace(points, markerTrim(startType), markerTrim(endType))
    }
    val over = overFills(path.d, doubleArrayOf(path.sx, path.sy, path.ex, path.ey), fills)
    if (over == "") casings += edgeCasing(path, startType, endType, "", edge.thickness == "thick")
    val id = escText(edge.id!!)
    edges +=
      "<g class=\"pele-edge\" data-id=\"$id\"${if (!over.isNullOrEmpty()) " stroke=\"$over\"" else ""}>" +
        "<path d=\"${path.d}\"${if (edge.thickness == "thick") " stroke-width=\"2.5\"" else ""}${if (edge.pattern == "dotted") " stroke-dasharray=\"3 4\"" else ""}/>" +
        marker(
          startType,
          path.sx,
          path.sy,
          path.sdx,
          path.sdy,
          if (!over.isNullOrEmpty()) over else "var(--_l)",
        ) +
        marker(
          endType,
          path.ex,
          path.ey,
          path.edx,
          path.edy,
          if (!over.isNullOrEmpty()) over else "var(--_l)",
        ) +
        "</g>"
    if (label.width > 0) {
      val x = routes[i].lx + ox
      val y = routes[i].ly + oy
      labels += edgeLabelSvg(edge.id!!, label, x, y, "", icons)
    }
  }

  val svg =
    svgDocument(
      "block",
      width,
      height,
      size,
      options,
      model,
      labelSvg(
        title,
        width / 2,
        pad + title.height / 2,
        " class=\"pele-title\" font-weight=\"var(--_tw)\"",
      ) +
        (if (clusters != "") "<g class=\"pele-clusters\">$clusters</g>" else "") +
        casingsSvg(casings) +
        (if (edges != "")
          "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edges</g>"
        else "") +
        "<g class=\"pele-nodes\">$nodes</g>" +
        (if (labels != "")
          "<g class=\"pele-edge-labels\" font-size=\"${jsStr(edgeSize)}\">$labels</g>"
        else ""),
    )

  return Rendered(svg, width, height, ArrayList())
}
