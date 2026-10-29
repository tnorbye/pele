// Ported from Pele src/svg/edges.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import pele.IconResolver
import pele.js.JsNumberArray
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsHypot
import pele.js.jsNumber
import pele.js.jsOr
import pele.js.jsParseFloat
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsTrim
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.ease
import pele.text.Label

const val ARROW = 8.0

class EdgePath(
  val d: String,
  val tokens: List<Double>? = null,
  val sx: Double,
  val sy: Double,
  val sdx: Double,
  val sdy: Double,
  val ex: Double,
  val ey: Double,
  val edx: Double,
  val edy: Double,
)

// Marker types are Mermaid's: arrow_point, arrow_circle, arrow_cross; anything else draws nothing.

// The most a corner is rounded by in a stepped route.
private const val CORNER = 12.0
// How far the handles of a wide turn reach along its two legs. A little more than a circle's,
// which makes the turn fuller.
private const val HANDLE = 0.62
// How far an edge runs straight before a marker at its end, where it has the room.
const val LEAD = 6.0

class SideReach {
  private val reach = HashMap<Int, Double>()

  fun add(node: Int, side: Int, type: String) {
    val trim = markerTrim(type)
    if (side >= 0 && trim > 0 && trim + LEAD > (reach[node * 4 + side] ?: 0.0))
      reach[node * 4 + side] = trim + LEAD
  }

  fun of(node: Int, side: Int): Double = if (side >= 0) (reach[node * 4 + side] ?: 0.0) else 0.0
}

// How far from each side of each node the edges that meet it start to turn: as far as the
// largest marker there needs, for every edge on the side, so that none turns sooner than its
// neighbour and crosses it. `add` takes a node, the side of it, and the marker of an end there.
fun sideReach(): SideReach = SideReach()

// Builds the path for a route of x, y, axis triples, trimming both ends to leave room for markers.
// Between two points that are not in line, a route is one S that leaves and arrives along the
// flow, or a step in a stepped route. Where the route itself turns a corner, a stepped route
// rounds it a little, and any other but a linear one turns as widely as it can: the turn takes
// the whole of a leg that ends at a node and half of a leg it shares with the next turn.
// `startReach` and `endReach` say how far from its nodes the edge starts to turn, where the
// other edges on the same side of a node need it to; by default, far enough to clear its own
// marker.
fun routePath(
  route: List<Double>,
  curve: String?,
  startTrim: Double,
  endTrim: Double,
  startReach: Double = if (startTrim > 0) startTrim + LEAD else 0.0,
  endReach: Double = if (endTrim > 0) endTrim + LEAD else 0.0,
): EdgePath {
  val linear = curve == "linear"
  val stepped = curve == "step" || curve == "stepBefore" || curve == "stepAfter"
  // PORT: a route of one point reads past the end of these, which upstream takes as NaN.
  val xs = JsNumberArray()
  val ys = JsNumberArray()
  val axes = JsNumberArray()
  val near = { ax: Double, ay: Double, bx: Double, by: Double ->
    abs(ax - bx) < 0.01 && abs(ay - by) < 0.01
  }
  var i0 = 0
  while (i0 < route.size) {
    val x = route[i0]
    val y = route[i0 + 1]
    val axis = route[i0 + 2]
    val last = xs.size - 1
    // A stepped route turns at right angles where a smooth one would curve.
    if (stepped && last >= 0 && abs(x - xs[last]) >= 0.01 && abs(y - ys[last]) >= 0.01) {
      val px = xs[last]
      val py = ys[last]
      val my = if (curve == "stepBefore") py else if (curve == "stepAfter") y else (py + y) / 2
      val mx = if (curve == "stepBefore") px else if (curve == "stepAfter") x else (px + x) / 2
      val turns = if (axis == 0.0) doubleArrayOf(px, my, x, my) else doubleArrayOf(mx, py, mx, y)
      var k = 0
      while (k < 4) {
        if (!(near(turns[k], turns[k + 1], px, py) || near(turns[k], turns[k + 1], x, y))) {
          xs.add(turns[k])
          ys.add(turns[k + 1])
          axes.add(axis)
        }
        k += 2
      }
    }
    xs.add(x)
    ys.add(y)
    axes.add(axis)
    i0 += 3
  }
  var last = xs.size - 1
  val aligned = { i: Int -> abs(xs[i] - xs[i - 1]) < 0.01 || abs(ys[i] - ys[i - 1]) < 0.01 }
  val straight = { i: Int -> linear || aligned(i) }
  // The way the route heads from point i to point j: along the flow where an S joins them.
  val unit =
    fun(i: Int, j: Int, axis: Double): DoubleArray {
      if (!straight(max(i, j))) {
        return if (axis == 0.0) doubleArrayOf(0.0, sign(ys[j] - ys[i]).jsOr { 1.0 })
        else doubleArrayOf(sign(xs[j] - xs[i]).jsOr { 1.0 }, 0.0)
      }
      val dx = xs[j] - xs[i]
      val dy = ys[j] - ys[i]
      val len = jsHypot(dx, dy).jsOr { 1.0 }
      return doubleArrayOf(dx / len, dy / len)
    }
  val su = unit(0, 1, axes[1])
  val sdx = su[0]
  val sdy = su[1]
  val eu = unit(last - 1, last, axes[last])
  val edx = eu[0]
  val edy = eu[1]
  val sx = xs[0]
  val sy = ys[0]
  val ex = xs[last]
  val ey = ys[last]
  xs[0] += sdx * startTrim
  ys[0] += sdy * startTrim
  xs[last] -= edx * endTrim
  ys[last] -= edy * endTrim
  val length = { i: Int -> abs(xs[i] - xs[i - 1]) + abs(ys[i] - ys[i - 1]) }
  // Whether the route turns a corner at point i, from running one way to running the other.
  val turns = { i: Int ->
    !linear &&
      i > 0 &&
      i < last &&
      aligned(i) &&
      aligned(i + 1) &&
      length(i) >= 0.01 &&
      length(i + 1) >= 0.01 &&
      (abs(xs[i] - xs[i - 1]) < 0.01) != (abs(xs[i + 1] - xs[i]) < 0.01)
  }
  // An edge that curves into a marker runs straight for a little way first, where it has the
  // room, so that the marker does not sit on the bend.
  val along = { i: Int, ux: Double, uy: Double ->
    abs(xs[i] - xs[i - 1]) * abs(ux) + abs(ys[i] - ys[i - 1]) * abs(uy)
  }
  // Of the way from the end of a run to where it turns, the part that makes up for a larger
  // marker beside this one is kept where it can be, and the rest is at most a quarter of what
  // is left, so that ends on one side of a node turn equally far from it.
  val lead = { reach: Double, trim: Double, run: Double ->
    val room = min(max(0.0, reach - LEAD - trim), run / 4)
    room + min(reach - trim - room, (run - room) / 4)
  }
  val arrives =
    if (endReach > endTrim && (!straight(last) || turns(last - 1)))
      lead(endReach, endTrim, along(last, edx, edy))
    else 0.0
  val leaves =
    if (startReach > startTrim && (!straight(1) || turns(1)))
      lead(startReach, startTrim, along(1, sdx, sdy))
    else 0.0
  if (arrives >= 1) {
    xs.add(last, xs[last] - edx * arrives)
    ys.add(last, ys[last] - edy * arrives)
    axes.add(last, axes[last])
    last++
  }
  if (leaves >= 1) {
    xs.add(1, xs[0] + sdx * leaves)
    ys.add(1, ys[0] + sdy * leaves)
    axes.add(1, axes[1])
    last++
  }

  // Keep tokens to avoid parsing d when checking fills.
  val tokens = ArrayList<Double>()
  val tok = { v: Double ->
    tokens.add(written(v))
    num(v)
  }
  tokens.add(77.0)
  tokens.add(2.0)
  val d = StringBuilder()
  d.append("M").append(tok(xs[0])).append(",").append(tok(ys[0]))
  var atX = xs[0]
  var atY = ys[0]
  val lineTo =
    fun(x: Double, y: Double) {
      if (near(x, y, atX, atY)) return
      tokens.add(76.0)
      tokens.add(2.0)
      d.append("L").append(tok(x)).append(",").append(tok(y))
      atX = x
      atY = y
    }
  for (i in 1..last) {
    val x = xs[i]
    val y = ys[i]
    if (!straight(i)) {
      val down = axes[i] == 0.0
      val alongRun = if (down) y - atY else x - atX
      val k = abs(alongRun) * ease(if (down) x - atX else y - atY, alongRun)
      val ux = if (down) 0.0 else sign(alongRun) * k
      val uy = if (down) sign(alongRun) * k else 0.0
      tokens.add(67.0)
      tokens.add(6.0)
      d.append("C")
        .append(tok(atX + ux))
        .append(",")
        .append(tok(atY + uy))
        .append(" ")
        .append(tok(x - ux))
        .append(",")
        .append(tok(y - uy))
        .append(" ")
        .append(tok(x))
        .append(",")
        .append(tok(y))
      atX = x
      atY = y
      continue
    }
    if (!turns(i)) {
      // A point part of the way along one straight run adds nothing to the path.
      if (
        i < last &&
          aligned(i) &&
          aligned(i + 1) &&
          (x - xs[i - 1]) * (xs[i + 1] - x) + (y - ys[i - 1]) * (ys[i + 1] - y) > 0
      )
        continue
      lineTo(x, y)
      continue
    }
    // How much of the leg before the turn and of the leg after it the turn takes: all of one
    // that ends at a node, short of the straight run into a marker, and half of one it shares.
    var before = if (turns(i - 1)) length(i) / 2 else length(i)
    var after = if (turns(i + 1)) length(i + 1) / 2 else length(i + 1)
    if (stepped) {
      after = min(CORNER, min(before, after))
      before = after
    }
    val u = unit(i - 1, i, axes[i])
    val ux = u[0]
    val uy = u[1]
    val v = unit(i, i + 1, axes[i + 1])
    val vx = v[0]
    val vy = v[1]
    lineTo(x - ux * before, y - uy * before)
    atX = x + vx * after
    atY = y + vy * after
    if (stepped) {
      tokens.add(81.0)
      tokens.add(4.0)
      d.append("Q")
        .append(tok(x))
        .append(",")
        .append(tok(y))
        .append(" ")
        .append(tok(atX))
        .append(",")
        .append(tok(atY))
    } else {
      tokens.add(67.0)
      tokens.add(6.0)
      d.append("C")
        .append(tok(x - ux * before * (1 - HANDLE)))
        .append(",")
        .append(tok(y - uy * before * (1 - HANDLE)))
        .append(" ")
        .append(tok(x + vx * after * (1 - HANDLE)))
        .append(",")
        .append(tok(y + vy * after * (1 - HANDLE)))
        .append(" ")
        .append(tok(atX))
        .append(",")
        .append(tok(atY))
    }
  }
  return EdgePath(d.toString(), tokens, sx, sy, -sdx, -sdy, ex, ey, edx, edy)
}

fun markerTrim(type: String): Double =
  if (type == "arrow_point") ARROW - 1
  else if (type == "arrow_circle") ARROW else if (type == "arrow_cross") 4.0 else 0.0

// Draws a marker whose tip is at (x, y), pointing along (dx, dy).
fun marker(
  type: String,
  x: Double,
  y: Double,
  dx: Double,
  dy: Double,
  color: String,
  stroke: String = "none",
): String {
  val nx = -dy
  val ny = dx
  if (type == "arrow_point") {
    val bx = x - dx * ARROW
    val by = y - dy * ARROW
    val half = ARROW * 0.42
    return "<path class=\"pele-marker\" d=\"M${num(x)},${num(y)}L${num(bx + nx * half)},${num(by + ny * half)}L${num(
      bx - nx * half
    )},${num(by - ny * half)}Z\" fill=\"$color\" stroke=\"$stroke\"/>"
  }
  if (type == "arrow_circle") {
    return "<circle class=\"pele-marker\" cx=\"${num(x - dx * 4)}\" cy=\"${num(y - dy * 4)}\" r=\"3.5\" fill=\"$color\" stroke=\"$stroke\"/>"
  }
  if (type == "arrow_cross") {
    val cx = x - dx * 4
    val cy = y - dy * 4
    val a = 3.5
    val ux = (dx + nx) * a
    val uy = (dy + ny) * a
    val vx = (dx - nx) * a
    val vy = (dy - ny) * a
    return "<path class=\"pele-marker\" d=\"M${num(cx - ux)},${num(cy - uy)}L${num(cx + ux)},${num(cy + uy)}M${num(
      cx - vx
    )},${num(cy - vy)}L${num(cx + vx)},${num(cy + vy)}\"/>"
  }
  return ""
}

private val TENS = DoubleArray(23) { i -> jsNumber("1e$i") }

// Samples curves and arcs; NaN separates subpaths.
private fun pathPoints(d: String): List<Double> = sample(tokenize(d))

// Each command stores its character code, argument count, and arguments.
private fun tokenize(d: String): List<Double> {
  val out = ArrayList<Double>()
  var at = -1
  var i = 0
  while (i < d.length) {
    val c = d.charCodeAt(i)
    if (c >= 65 && c <= 90 && c != 69) {
      at = out.size + 1
      out.add(c.toDouble())
      out.add(0.0)
      i++
    } else if (c == 45 || c == 46 || (c >= 48 && c <= 57)) {
      // Scale exact integers to match Number() rounding; otherwise use Number().
      var j = if (c == 45) i + 1 else i
      var m = 0.0
      var places = -1
      var k = d.charCodeAt(j)
      while ((k >= 48 && k <= 57) || (k == 46 && places < 0)) {
        if (k == 46) places = 0
        else {
          m = m * 10 + k - 48
          if (places >= 0) places++
        }
        k = d.charCodeAt(++j)
      }
      var v: Double
      if ((d.charCodeAt(j) or 32) == 101 || m > 9007199254740991.0 || places > 22) {
        k = d.charCodeAt(j)
        while (
          k == 46 ||
            k == 101 ||
            k == 69 ||
            (k >= 48 && k <= 57) ||
            ((k == 45 || k == 43) && (d.charCodeAt(j - 1) or 32) == 101)
        ) k = d.charCodeAt(++j)
        v = jsNumber(d.jsSlice(i, j))
      } else {
        v = if (places > 0) m / TENS[places] else m
        if (c == 45) v = -v
      }
      if (at >= 0) {
        out.add(v)
        out[at] = out[at] + 1
      }
      i = j
    } else {
      i++
    }
  }
  return out
}

// Match the value parsed from num() output.
private fun written(v: Double): Double {
  val n = jsRound(v * 100)
  return if (n == 0.0) 0.0 else n / 100
}

private fun sample(tokens: List<Double>): List<Double> {
  val out = ArrayList<Double>()
  var x = 0.0
  var y = 0.0
  var i = 0
  while (i < tokens.size) {
    val cmd = tokens[i].toInt()
    val count = tokens[i + 1].toInt()
    val s = i + 2
    val e = s + count
    i = e
    if (cmd == 72 || cmd == 86) {
      for (k in s until e) {
        if (cmd == 72) x = tokens[k] else y = tokens[k]
        out.add(x)
        out.add(y)
      }
    } else if (cmd == 67 || cmd == 81) {
      val cubic = cmd == 67
      val step = if (cubic) 6 else 4
      var k = s
      while (k + step <= e) {
        val x1 = tokens[k]
        val y1 = tokens[k + 1]
        val x2 = tokens[k + 2]
        val y2 = tokens[k + 3]
        val x3 = if (cubic) tokens[k + 4] else x2
        val y3 = if (cubic) tokens[k + 5] else y2
        var t = 0.125
        while (t <= 1) {
          // Preserve the original De Casteljau operation order to keep rounding unchanged.
          var ax = x + (x1 - x) * t
          var ay = y + (y1 - y) * t
          var bx = x1 + (x2 - x1) * t
          var by = y1 + (y2 - y1) * t
          if (cubic) {
            val cx = x2 + (x3 - x2) * t
            val cy = y2 + (y3 - y2) * t
            ax += (bx - ax) * t
            ay += (by - ay) * t
            bx += (cx - bx) * t
            by += (cy - by) * t
          }
          out.add(ax + (bx - ax) * t)
          out.add(ay + (by - ay) * t)
          t += 0.125
        }
        x = x3
        y = y3
        k += step
      }
    } else if (cmd == 65) {
      var k = s
      while (k + 7 <= e) {
        arc(
          out,
          x,
          y,
          tokens[k],
          tokens[k + 1],
          tokens[k + 3],
          tokens[k + 4],
          tokens[k + 5],
          tokens[k + 6],
        )
        x = tokens[k + 5]
        y = tokens[k + 6]
        k += 7
      }
    } else {
      var k = s
      while (k + 2 <= e) {
        if (cmd == 77 && k == s && out.size > 0) {
          out.add(Double.NaN)
          out.add(Double.NaN)
        }
        x = tokens[k]
        y = tokens[k + 1]
        out.add(x)
        out.add(y)
        k += 2
      }
    }
  }
  return out
}

// Samples an unrotated arc from (x1, y1), after SVG's conversion from end points to a center.
private fun arc(
  out: MutableList<Double>,
  x1: Double,
  y1: Double,
  rxIn: Double,
  ryIn: Double,
  large: Double,
  sweep: Double,
  x2: Double,
  y2: Double,
) {
  var rx = rxIn
  var ry = ryIn
  val hx = (x1 - x2) / 2
  val hy = (y1 - y2) / 2
  if (rx == 0.0 || ry == 0.0 || (hx == 0.0 && hy == 0.0)) {
    out.add(x2)
    out.add(y2)
    return
  }
  val scale = max(1.0, sqrt((hx * hx) / (rx * rx) + (hy * hy) / (ry * ry)))
  rx *= scale
  ry *= scale
  val k =
    (if (large == sweep) -1 else 1) *
      sqrt(
        max(
          0.0,
          (rx * rx * ry * ry - rx * rx * hy * hy - ry * ry * hx * hx) /
            (rx * rx * hy * hy + ry * ry * hx * hx),
        )
      )
  val cx = (k * rx * hy) / ry + (x1 + x2) / 2
  val cy = (-k * ry * hx) / rx + (y1 + y2) / 2
  val from = StrictMath.atan2((y1 - cy) / ry, (x1 - cx) / rx)
  var turn = StrictMath.atan2((y2 - cy) / ry, (x2 - cx) / rx) - from
  // PORT: `sweep` is a number in JS, truthy unless 0 or NaN.
  val swept = sweep != 0.0 && !sweep.isNaN()
  if (swept && turn < 0) turn += 2 * PI else if (!swept && turn > 0) turn -= 2 * PI
  for (i in 1..8) {
    out.add(cx + rx * StrictMath.cos(from + (turn * i) / 8))
    out.add(cy + ry * StrictMath.sin(from + (turn * i) / 8))
  }
}

// Test segment overlap with the box using Liang-Barsky clipping.
private fun clips(
  x: Double,
  y: Double,
  dx: Double,
  dy: Double,
  l: Double,
  t: Double,
  r: Double,
  b: Double,
): Boolean {
  var lo = 0.0
  var hi = 1.0
  for (side in 0 until 4) {
    val p = if (side == 0) -dx else if (side == 1) dx else if (side == 2) -dy else dy
    val q = if (side == 0) x - l else if (side == 1) r - x else if (side == 2) y - t else b - y
    if (p == 0.0) {
      if (q < 0) hi = -1.0
    } else if (p < 0) lo = max(lo, q / p) else hi = min(hi, q / p)
  }
  return lo < hi
}

private fun enters(points: List<Double>, l: Double, t: Double, r: Double, b: Double): Boolean {
  var k = 0
  while (k + 2 < points.size) {
    val x = points[k]
    val y = points[k + 1]
    val x2 = points[k + 2]
    val y2 = points[k + 3]
    k += 2
    if ((x < l && x2 < l) || (x > r && x2 > r) || (y < t && y2 < t) || (y > b && y2 > b)) continue
    if (clips(x, y, x2 - x, y2 - y, l, t, r, b)) return true
  }
  return false
}

// Fill bounds are [left, top, right, bottom].
class Fill(val box: DoubleArray, val shape: String)

// The innermost fill holding the whole edge and its marker tips, '' when it crosses a border, or
// undefined off fills.
// PORT: returns a Fill, the string "", or null.
fun fillUnder(d: String, tips: DoubleArray, fills: List<Fill>, tokens: List<Double>? = null): Any? {
  if (fills.isEmpty()) return null
  val points = if (tokens != null) sample(tokens) else pathPoints(d)
  var minX = Double.POSITIVE_INFINITY
  var minY = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  var maxY = Double.NEGATIVE_INFINITY
  var k = 0
  while (k < points.size + tips.size) {
    val x = if (k < points.size) points[k] else tips[k - points.size]
    val y = if (k < points.size) points[k + 1] else tips[k + 1 - points.size]
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
    k += 2
  }
  var holder: Fill? = null
  var least = Double.POSITIVE_INFINITY
  for (fill in fills) {
    val l = fill.box[0]
    val t = fill.box[1]
    val r = fill.box[2]
    val b = fill.box[3]
    if (maxX < l + 0.5 || minX > r - 0.5 || maxY < t + 0.5 || minY > b - 0.5) continue
    var touched = enters(points, l + 0.5, t + 0.5, r - 0.5, b - 0.5)
    var j = 0
    while (j < tips.size && !touched) {
      touched =
        tips[j] > l + 0.5 && tips[j] < r - 0.5 && tips[j + 1] > t + 0.5 && tips[j + 1] < b - 0.5
      j += 2
    }
    if (!touched) continue
    if (minX < l || maxX > r || minY < t || maxY > b) return ""
    val area = (r - l) * (b - t)
    if (area < least) {
      least = area
      holder = fill
    }
  }
  return holder
}

private val RE_STROKE_DECL = JsRegExp("""[";]stroke:""")

// The fill's ink for an edge on it, or '' for a casing when unknown or when the line style sets a
// stroke.
fun edgeInk(fill: Fill, line: String = ""): String =
  (if (RE_STROKE_DECL.test(line)) null else ink(fill.shape)) ?: ""

private val RE_FILL_DECL = JsRegExp("""[";]fill:([^;"]*)""")
private val RE_BG_FILL = JsRegExp(""" fill="var\(--_bg\)"""", "g")

// Markers with the page color inside them, with the fill's color there instead.
fun hollowOn(markers: String, fill: Fill): String {
  val color = RE_FILL_DECL.exec(fill.shape)?.get(1)?.jsTrim()
  return if (!color.isNullOrEmpty()) markers.jsReplace(RE_BG_FILL, " fill=\"$color\"") else markers
}

// The ink of the innermost fill that holds the whole edge, '' for a casing where it crosses a
// border, or undefined off fills.
fun overFills(
  d: String,
  tips: DoubleArray,
  fills: List<Fill>,
  line: String = "",
  tokens: List<Double>? = null,
): String? {
  val fill = fillUnder(d, tips, fills, tokens)
  return if (fill is Fill) edgeInk(fill, line) else fill as String?
}

private const val CASE = 4.0

private val RE_DECLS =
  listOf("stroke", "stroke-width", "opacity", "stroke-opacity").associateWith { prop ->
    JsRegExp("[\";]$prop:([^;\"]+);")
  }
private val RE_PX_WIDTH = JsRegExp("""^[\d.]+(?:px)?$""")
private val RE_MARKER_CLASS = JsRegExp(""" class="pele-marker"""", "g")

// A wider page-colored stroke under an edge, as faint as it; markers drop their class so marker
// styles skip it.
fun casing(d: String, markers: String, line: String = "", thick: Boolean = false): String {
  val decl = { prop: String ->
    if (line.contains(prop)) RE_DECLS.getValue(prop).exec(line)?.get(1)?.jsTrim() else null
  }
  val stroke = decl("stroke")
  if (stroke == "none" || stroke == "transparent") return ""
  val width = if (thick) "2.5" else decl("stroke-width")
  var attrs = ""
  var style = ""
  if (width != null && RE_PX_WIDTH.test(width)) {
    if (jsParseFloat(width) != 1.0) attrs = " stroke-width=\"${num(jsParseFloat(width) + CASE)}\""
  } else if (width != null) {
    style += "stroke-width:calc($width + ${CASE.toInt()}px);"
  }
  for (prop in listOf("opacity", "stroke-opacity")) {
    val value = decl(prop)
    if (value != null) style += "$prop:$value;"
  }
  val body = "<path d=\"$d\"/>" + markers.jsReplace(RE_MARKER_CLASS, "")
  return if (attrs != "" || style != "")
    "<g$attrs${if (style != "") " style=\"$style\"" else ""}>$body</g>"
  else body
}

fun edgeCasing(
  path: EdgePath,
  startType: String,
  endType: String,
  line: String = "",
  thick: Boolean = false,
): String =
  casing(
    path.d,
    marker(startType, path.sx, path.sy, path.sdx, path.sdy, "var(--_bg)", "var(--_bg)") +
      marker(endType, path.ex, path.ey, path.edx, path.edy, "var(--_bg)", "var(--_bg)"),
    line,
    thick,
  )

fun casingsSvg(casings: String): String =
  if (casings != "") {
    "<g class=\"pele-casings\" fill=\"none\" stroke=\"var(--_bg)\" stroke-width=\"${(1 + CASE).toInt()}\" stroke-linecap=\"round\" stroke-linejoin=\"round\" pointer-events=\"none\">$casings</g>"
  } else {
    ""
  }

// An edge label on a patch of the background, so the line does not run through the text.
fun edgeLabelSvg(
  id: String,
  label: Label,
  x: Double,
  y: Double,
  attrs: String,
  icons: IconResolver? = null,
): String {
  val w = label.width + 8
  val h = label.height
  return "<g class=\"pele-edge-label\" data-id=\"${escText(id)}\">" +
    "<rect x=\"${num(x - w / 2)}\" y=\"${num(y - h / 2)}\" width=\"${num(w)}\" height=\"${num(h)}\" rx=\"3\" fill=\"var(--_bg)\"/>" +
    labelSvg(label, x, y, attrs, icons) +
    "</g>"
}

// A self-loop beside a node of drawn size w by h: out of the side that lies across the flow and
// back in. `k` counts the loops already on the node, so each one reaches a little further.
// Also places the edge's label beyond the loop.
fun loopPath(
  c: CNode,
  w: Double,
  h: Double,
  k: Int,
  sideways: Boolean,
  base: Double,
  trim: Double,
  e: CEdge,
): EdgePath {
  val half = (if (sideways) w else h) / 2
  val spread = min(half - 4, 8.0 + k * 6)
  val reach = base + k * 8
  val len = jsHypot(reach, spread).jsOr { 1.0 }
  // Local frame: `out` points away from the node, `along` runs along its side.
  val at = { out: Double, along: Double ->
    if (sideways) "${num(c.x + along)},${num(c.y + h / 2 + out)}"
    else "${num(c.x + w / 2 + out)},${num(c.y + along)}"
  }
  val tx = (reach / len) * trim
  val ty = (spread / len) * trim
  if (sideways) {
    e.labelX = c.x
    e.labelY = c.y + h / 2 + reach + 4 + e.labelH / 2
  } else {
    e.labelX = c.x + w / 2 + reach + 4 + e.labelW / 2
    e.labelY = c.y
  }
  return EdgePath(
    d =
      "M${at(0.0, -spread)}C${at(reach, -spread * 2)} ${at(reach, spread * 2)} ${at(tx, spread + ty)}",
    sx = if (sideways) c.x - spread else c.x + w / 2,
    sy = if (sideways) c.y + h / 2 else c.y - spread,
    sdx = if (sideways) 0.0 else -1.0,
    sdy = if (sideways) -1.0 else 0.0,
    ex = if (sideways) c.x + spread else c.x + w / 2,
    ey = if (sideways) c.y + h / 2 else c.y + spread,
    edx = if (sideways) -spread / len else -reach / len,
    edy = if (sideways) -reach / len else -spread / len,
  )
}
