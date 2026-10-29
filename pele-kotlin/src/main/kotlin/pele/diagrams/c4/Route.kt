// Ported from Pele src/diagrams/c4/route.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign
import kotlin.math.sqrt
import pele.js.jsHypot
import pele.js.jsOr
import pele.js.truthy
import pele.svg.num

// A laid out shape or boundary: top-left corner, size, and the shared shape it is drawn as.
open class Box(
  var x: Double,
  var y: Double,
  var w: Double,
  var h: Double,
  var form: String,
)

class Route(
  val d: String,
  // Where the markers go: the tip, and the way it points.
  val sx: Double,
  val sy: Double,
  val sdx: Double,
  val sdy: Double,
  val ex: Double,
  val ey: Double,
  val edx: Double,
  val edy: Double,
  // The stretch the label may sit on.
  val x0: Double,
  val y0: Double,
  val x1: Double,
  val y1: Double,
  // The side of that stretch the label is pushed to, as a unit vector; zero centers it on the
  // stretch.
  val nx: Double,
  val ny: Double,
  // When set, the label stays over the line and moves that way by at most this much.
  val lean: Double,
  // The widest the label should be.
  val room: Double,
  // The corners of the line, as x, y pairs.
  val points: DoubleArray,
)

private const val LOOP = 28.0
const val LANE = 20.0
private const val LANE_STEP = 7.0
private const val CORNER = 8.0

// A wide label on an upright lane leans this far out from it, to the middle of the gap between
// columns.
private const val LEAN = 30.0

// Label widths that fit the gap between two columns, and where there is room to spare.
const val NARROW = 88.0
private const val WIDE = 160.0

// The space a label's background adds to the width of its text.
const val LABEL_PAD = 8.0

// The shared person shape: a head of radius 13 centered 14 below the top, over a body that starts
// 26 below it.
const val HEAD = 26.0

// The cap radii drawShape uses for the two cylinders.
fun cylRy(w: Double): Double = minOf(12.0, maxOf(6.0, w / 14))

fun queueRx(h: Double): Double = minOf(12.0, h / 4)

// How far along the segment from (px, py) by (dx, dy) it first meets the rectangle; Infinity when
// it misses.
private fun hitRect(
  x0: Double,
  y0: Double,
  x1: Double,
  y1: Double,
  px: Double,
  py: Double,
  dx: Double,
  dy: Double,
): Double {
  var enter = 0.0
  var leave = 1.0
  if (dx == 0.0) {
    if (px < x0 || px > x1) return Double.POSITIVE_INFINITY
  } else {
    val a = (x0 - px) / dx
    val b = (x1 - px) / dx
    enter = maxOf(enter, minOf(a, b))
    leave = minOf(leave, maxOf(a, b))
  }
  if (dy == 0.0) {
    if (py < y0 || py > y1) return Double.POSITIVE_INFINITY
  } else {
    val a = (y0 - py) / dy
    val b = (y1 - py) / dy
    enter = maxOf(enter, minOf(a, b))
    leave = minOf(leave, maxOf(a, b))
  }
  return if (enter <= leave) enter else Double.POSITIVE_INFINITY
}

private fun hitEllipse(
  cx: Double,
  cy: Double,
  rx: Double,
  ry: Double,
  px: Double,
  py: Double,
  dx: Double,
  dy: Double,
): Double {
  val ux = dx / rx
  val uy = dy / ry
  val vx = (px - cx) / rx
  val vy = (py - cy) / ry
  val a = ux * ux + uy * uy
  val b = 2 * (ux * vx + uy * vy)
  val c = vx * vx + vy * vy - 1
  val disc = b * b - 4 * a * c
  if (a == 0.0 || disc < 0) return Double.POSITIVE_INFINITY
  val t = (-b - sqrt(disc)) / (2 * a)
  return if (t >= 0 && t <= 1) t else if (c <= 0) 0.0 else Double.POSITIVE_INFINITY
}

// Where a segment from outside first meets the outline of a shape, as a fraction of the segment.
private fun hit(box: Box, px: Double, py: Double, qx: Double, qy: Double): Double {
  val x = box.x
  val y = box.y
  val w = box.w
  val h = box.h
  val dx = qx - px
  val dy = qy - py
  if (box.form == "person") {
    return minOf(
      hitRect(x, y + HEAD, x + w, y + h, px, py, dx, dy),
      hitEllipse(x + w / 2, y + 14, 13.0, 13.0, px, py, dx, dy),
    )
  }
  if (box.form == "cyl") {
    val ry = cylRy(w)
    return minOf(
      hitRect(x, y + ry, x + w, y + h - ry, px, py, dx, dy),
      hitEllipse(x + w / 2, y + ry, w / 2, ry, px, py, dx, dy),
      hitEllipse(x + w / 2, y + h - ry, w / 2, ry, px, py, dx, dy),
    )
  }
  if (box.form == "h-cyl") {
    val rx = queueRx(h)
    return minOf(
      hitRect(x + rx, y, x + w - rx, y + h, px, py, dx, dy),
      hitEllipse(x + rx, y + h / 2, rx, h / 2, px, py, dx, dy),
      hitEllipse(x + w - rx, y + h / 2, rx, h / 2, px, py, dx, dy),
    )
  }
  return hitRect(x, y, x + w, y + h, px, py, dx, dy)
}

fun crosses(points: DoubleArray, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
  var i = 2
  while (i < points.size) {
    val px = points[i - 2]
    val py = points[i - 1]
    if (hitRect(x0, y0, x1, y1, px, py, points[i] - px, points[i + 1] - py) <= 1) return true
    i += 2
  }
  return false
}

private fun contains(outer: Box, inner: Box): Boolean {
  return (outer.x <= inner.x &&
    outer.y <= inner.y &&
    outer.x + outer.w >= inner.x + inner.w &&
    outer.y + outer.h >= inner.y + inner.h)
}

// The straight line from a to b: x, y of its start, then of its end.
private fun connect(a: Box, b: Box, shift: Double): DoubleArray {
  val ax = a.x + a.w / 2
  val ay = a.y + a.h / 2
  val bx = b.x + b.w / 2
  val by = b.y + b.h / 2
  val down = contains(a, b)
  if (down || contains(b, a)) {
    // One holds the other: a short line from the nearest side of the outer box.
    val outer = if (down) a else b
    val inner = if (down) b else a
    val ix = inner.x + inner.w / 2
    val iy = inner.y + inner.h / 2
    val left = inner.x - outer.x
    val right = outer.x + outer.w - inner.x - inner.w
    val top = inner.y - outer.y
    val bottom = outer.y + outer.h - inner.y - inner.h
    val least = minOf(left, right, top, bottom)
    val ox = if (least == left) outer.x else if (least == right) outer.x + outer.w else ix
    val oy =
      if (least == left || least == right) iy
      else if (least == bottom) outer.y + outer.h else outer.y
    val t = minOf(hit(inner, ox, oy, ix, iy), 1.0)
    val mx = ox + (ix - ox) * t
    val my = oy + (iy - oy) * t
    return if (down) doubleArrayOf(ox, oy, mx, my) else doubleArrayOf(mx, my, ox, oy)
  }

  var px = ax
  var py = ay
  var qx = bx
  var qy = by
  // Shapes that share a column or a row are joined by an upright or level line through the shared
  // span.
  val x0 = maxOf(a.x, b.x)
  val x1 = minOf(a.x + a.w, b.x + b.w)
  val y0 = maxOf(a.y, b.y)
  val y1 = minOf(a.y + a.h, b.y + b.h)
  if (x0 < x1) {
    qx = (x0 + x1) / 2
    px = qx
  } else if (y0 < y1) {
    qy = (y0 + y1) / 2
    py = qy
  }
  val len = jsHypot(qx - px, qy - py).jsOr { 1.0 }
  val nx = (-(qy - py) / len) * shift
  val ny = ((qx - px) / len) * shift
  var ta = hit(a, qx + nx, qy + ny, px + nx, py + ny)
  var tb = hit(b, px + nx, py + ny, qx + nx, qy + ny)
  if (ta <= 1 && tb <= 1) {
    px += nx
    py += ny
    qx += nx
    qy += ny
  } else {
    px = ax
    py = ay
    qx = bx
    qy = by
    ta = minOf(hit(a, qx, qy, px, py), 1.0)
    tb = minOf(hit(b, px, py, qx, qy), 1.0)
  }
  return doubleArrayOf(
    qx + (px - qx) * ta,
    qy + (py - qy) * ta,
    px + (qx - px) * tb,
    py + (qy - py) * tb,
  )
}

// Parallel stretches closer than this read as one line, and a line this close to a boundary's frame
// reads as part of it.
private const val NEAR = 6.0
private const val FRAME_NEAR = 3.0
private const val PORT_STEP = 12.0

// What the routes drawn so far take up, so that the next keeps apart from them: upright stretches
// as x, top, bottom; level ones as y, left, right; and where lines leave or reach each side of a
// shape.
class Lanes {
  private val upright = ArrayList<Double>()
  private val level = ArrayList<Double>()
  private val frames: Array<ArrayList<Double>> = arrayOf(ArrayList(), ArrayList())
  private val taken = IdentityHashMap<Box, ArrayList<Double>>()

  // Routes that found no lane of their own: a line left straight behind a shape, or a lane that
  // runs along another.
  var squeezed = 0

  fun free(upright: Boolean, at: Double, a0: Double, a1: Double): Boolean {
    val lo = minOf(a0, a1)
    val hi = maxOf(a0, a1)
    fun near(list: List<Double>, gap: Double): Boolean {
      var i = 0
      while (i < list.size) {
        if (abs(list[i] - at) < gap && list[i + 1] < hi && list[i + 2] > lo) return true
        i += 3
      }
      return false
    }
    return !near(if (upright) this.upright else this.level, NEAR) &&
      !near(this.frames[if (upright) 0 else 1], FRAME_NEAR)
  }

  fun crossings(points: DoubleArray): Int {
    var count = 0
    var i = 2
    while (i < points.size) {
      val upright = points[i] == points[i - 2]
      val at = if (upright) points[i] else points[i + 1]
      val lo =
        minOf(
          if (upright) points[i - 1] else points[i - 2],
          if (upright) points[i + 1] else points[i],
        )
      val hi =
        maxOf(
          if (upright) points[i - 1] else points[i - 2],
          if (upright) points[i + 1] else points[i],
        )
      val list = if (upright) this.level else this.upright
      var j = 0
      while (j < list.size) {
        if (list[j] > lo && list[j] < hi && list[j + 1] < at && list[j + 2] > at) count++
        j += 3
      }
      i += 2
    }
    return count
  }

  fun frame(box: Box) {
    this.frames[0].addAll(listOf(box.x, box.y, box.y + box.h, box.x + box.w, box.y, box.y + box.h))
    this.frames[1].addAll(listOf(box.y, box.x, box.x + box.w, box.y + box.h, box.x, box.x + box.w))
  }

  fun take(points: DoubleArray) {
    var i = 2
    while (i < points.size) {
      if (points[i] == points[i - 2]) {
        this.upright.addAll(
          listOf(
            points[i],
            minOf(points[i - 1], points[i + 1]),
            maxOf(points[i - 1], points[i + 1]),
          )
        )
      } else if (points[i + 1] == points[i - 1]) {
        this.level.addAll(
          listOf(points[i + 1], minOf(points[i - 2], points[i]), maxOf(points[i - 2], points[i]))
        )
      }
      i += 2
    }
  }

  // Where on one side of a shape (0 left, 1 right, 2 top, 3 bottom) a line can leave or arrive,
  // between lo and hi: the places clear of the lines already there, nearest `want` first.
  fun ports(box: Box, side: Int, want: Double, lo: Double, hi: Double): List<Double> {
    val used = this.taken[box] ?: ArrayList()
    val out = ArrayList<Double>()
    var k = 0
    while (k < 9 && out.size < 4) {
      val at = want + (if ((k and 1) != 0) 1.0 else -1.0) * ceil(k / 2.0) * PORT_STEP
      k++
      if (at < lo || at > hi) continue
      var clear = true
      var i = 0
      while (i < used.size) {
        if (used[i] == side.toDouble() && abs(used[i + 1] - at) < PORT_STEP) clear = false
        i += 2
      }
      if (clear) out.add(at)
    }
    return if (out.size > 0) out else listOf(want)
  }

  fun claim(box: Box, side: Int, at: Double) {
    val used = this.taken[box]
    if (used != null) {
      used.add(side.toDouble())
      used.add(at)
    } else {
      this.taken[box] = arrayListOf(side.toDouble(), at)
    }
  }
}

private fun path(points: DoubleArray, startTrim: Double, endTrim: Double): String {
  val n = points.size
  val ux = sign(points[2] - points[0])
  val uy = sign(points[3] - points[1])
  var d = "M${num(points[0] + ux * startTrim)},${num(points[1] + uy * startTrim)}"
  var i = 2
  while (i < n - 2) {
    val x = points[i]
    val y = points[i + 1]
    val ix = sign(x - points[i - 2])
    val iy = sign(y - points[i - 1])
    val ox = sign(points[i + 2] - x)
    val oy = sign(points[i + 3] - y)
    val r =
      minOf(
        CORNER,
        abs(x - points[i - 2] + y - points[i - 1]) / 2,
        abs(points[i + 2] - x + points[i + 3] - y) / 2,
      )
    d +=
      "${if (truthy(ix)) "H" + num(x - ix * r) else "V" + num(y - iy * r)}Q${num(x)},${num(y)} ${num(x + ox * r)},${num(y + oy * r)}"
    i += 2
  }
  val lx = sign(points[n - 2] - points[n - 4])
  val ly = sign(points[n - 1] - points[n - 3])
  return d +
    (if (truthy(lx)) "H" + num(points[n - 2] - lx * endTrim)
    else "V" + num(points[n - 1] - ly * endTrim))
}

private fun clearOf(points: DoubleArray, others: List<Box>, a: Box, b: Box): Boolean {
  for (other in others) {
    if (
      other !== a &&
        other !== b &&
        crosses(points, other.x, other.y, other.x + other.w, other.y + other.h)
    )
      return false
  }
  return true
}

// The route for a line with corners through `points`, from a's outline to b's. `along` is the
// stretch its label sits on.
private fun bent(
  points: DoubleArray,
  along: Int,
  startTrim: Double,
  endTrim: Double,
  nx: Double = 0.0,
  lean: Double = 0.0,
  room: Double = WIDE,
): Route {
  val n = points.size
  return Route(
    d = path(points, startTrim, endTrim),
    sx = points[0],
    sy = points[1],
    sdx = -sign(points[2] - points[0]),
    sdy = -sign(points[3] - points[1]),
    ex = points[n - 2],
    ey = points[n - 1],
    edx = sign(points[n - 2] - points[n - 4]),
    edy = sign(points[n - 1] - points[n - 3]),
    x0 = points[along],
    y0 = points[along + 1],
    x1 = points[along + 2],
    y1 = points[along + 3],
    nx = nx,
    ny = 0.0,
    lean = lean,
    room = room,
    points = points,
  )
}

private fun across(lo: Double, hi: Double): List<Double> {
  val out = ArrayList<Double>()
  val mid = (lo + hi) / 2
  val step = maxOf(LANE_STEP, (hi - lo) / 4)
  for (k in 0 until 9) {
    val at = mid + (if ((k and 1) != 0) 1.0 else -1.0) * ceil(k / 2.0) * step
    if (at >= lo + NEAR && at <= hi - NEAR) out.add(at)
  }
  return out
}

private class Best(
  val points: DoubleArray,
  val cost: Double,
  val upright: Boolean,
  val sides: IntArray,
)

private fun elbow(
  a: Box,
  b: Box,
  others: List<Box>,
  lanes: Lanes,
  startTrim: Double,
  endTrim: Double,
): Route? {
  val ax = a.x + a.w / 2
  val ay = a.y + a.h / 2
  val bx = b.x + b.w / 2
  val by = b.y + b.h / 2
  var best: Best? = null
  fun consider(points: DoubleArray, upright: Boolean, sides: IntArray) {
    if (!clearOf(points, others, a, b)) return
    var i = 2
    while (i < points.size) {
      val up = points[i] == points[i - 2]
      if (
        !lanes.free(
          up,
          if (up) points[i] else points[i + 1],
          if (up) points[i - 1] else points[i - 2],
          if (up) points[i + 1] else points[i],
        )
      )
        return
      i += 2
    }
    var cost = lanes.crossings(points) * 1e6
    i = 2
    while (i < points.size) {
      cost += abs(points[i] - points[i - 2]) + abs(points[i + 1] - points[i - 1])
      i += 2
    }
    val current = best
    if (current == null || cost < current.cost) best = Best(points, cost, upright, sides)
  }
  if (a.x + a.w <= b.x || b.x + b.w <= a.x) {
    val right = bx > ax
    val down = sign(by - ay)
    val sides = if (right) intArrayOf(1, 0) else intArrayOf(0, 1)
    val lanesAcross = across(if (right) a.x + a.w else b.x + b.w, if (right) b.x else a.x)
    search@ for (y0 in lanes.ports(a, sides[0], ay + (down * a.h) / 4, a.y + 8, a.y + a.h - 8)) {
      for (y1 in lanes.ports(b, sides[1], by - (down * b.h) / 4, b.y + 8, b.y + b.h - 8)) {
        for (lane in lanesAcross) {
          val x0 = lane + (ax - lane) * minOf(hit(a, lane, y0, ax, y0), 1.0)
          val x1 = lane + (bx - lane) * minOf(hit(b, lane, y1, bx, y1), 1.0)
          val before = best
          consider(doubleArrayOf(x0, y0, lane, y0, lane, y1, x1, y1), true, sides)
          if (best !== before) break@search
        }
      }
    }
  }
  if (a.y + a.h <= b.y || b.y + b.h <= a.y) {
    val below = by > ay
    val sideways = sign(bx - ax)
    val sides = if (below) intArrayOf(3, 2) else intArrayOf(2, 3)
    val lanesAcross = across(if (below) a.y + a.h else b.y + b.h, if (below) b.y else a.y)
    search@ for (x0 in
      lanes.ports(a, sides[0], ax + (sideways * a.w) / 4, a.x + 8, a.x + a.w - 8)) {
      for (x1 in lanes.ports(b, sides[1], bx - (sideways * b.w) / 4, b.x + 8, b.x + b.w - 8)) {
        for (lane in lanesAcross) {
          val y0 = lane + (ay - lane) * minOf(hit(a, x0, lane, x0, ay), 1.0)
          val y1 = lane + (by - lane) * minOf(hit(b, x1, lane, x1, by), 1.0)
          val before = best
          consider(doubleArrayOf(x0, y0, x0, lane, x1, lane, x1, y1), false, sides)
          if (best !== before) break@search
        }
      }
    }
  }
  val chosen = best ?: return null
  val points = chosen.points
  val upright = chosen.upright
  val sides = chosen.sides
  lanes.take(points)
  lanes.claim(a, sides[0], if (upright) points[1] else points[0])
  lanes.claim(b, sides[1], if (upright) points[7] else points[6])
  // The label sits on the middle stretch, across the gap.
  return bent(points, 2, startTrim, endTrim, room = if (upright) NARROW else WIDE)
}

private class Way(
  val points: DoubleArray,
  val side: Int,
  val p0: Double,
  val p1: Double,
  val step: Int,
)

// Routes a relation from a to b. `shift` moves the line sideways when another runs the opposite
// way.
// `others` are the shapes to steer clear of; null skips that check. `lanes` holds what the
// routes before it take up, so that this one keeps apart from them.
fun route(
  a: Box,
  b: Box,
  shift: Double,
  others: List<Box>?,
  lanes: Lanes,
  startTrim: Double,
  endTrim: Double,
): Route {
  if (a === b) {
    val spread = minOf(a.h / 2 - 4, 10.0)
    val len = jsHypot(LOOP, spread)
    val x = a.x + a.w
    val cy = a.y + a.h / 2
    val ux = LOOP / len
    val uy = spread / len
    return Route(
      d =
        "M${num(x + ux * startTrim)},${num(cy - spread - uy * startTrim)}C${num(x + LOOP)},${num(cy - spread * 2)} " +
          "${num(x + LOOP)},${num(cy + spread * 2)} ${num(x + ux * endTrim)},${num(cy + spread + uy * endTrim)}",
      sx = x,
      sy = cy - spread,
      sdx = -ux,
      sdy = uy,
      ex = x,
      ey = cy + spread,
      edx = -ux,
      edy = -uy,
      x0 = x + LOOP + 4,
      y0 = cy,
      x1 = x + LOOP + 4,
      y1 = cy,
      nx = 1.0,
      ny = 0.0,
      lean = 0.0,
      room = 64.0,
      points = DoubleArray(0),
    )
  }

  val conn = connect(a, b, shift)
  val sx = conn[0]
  val sy = conn[1]
  val ex = conn[2]
  val ey = conn[3]
  val upright = sx == ex
  var blocked = false
  if (others != null && !contains(a, b) && !contains(b, a)) {
    for (other in others) {
      if (
        other !== a &&
          other !== b &&
          hitRect(
            other.x,
            other.y,
            other.x + other.w,
            other.y + other.h,
            sx,
            sy,
            ex - sx,
            ey - sy,
          ) <= 1
      )
        blocked = true
    }
  }
  if (others != null && blocked && !upright && sy != ey) {
    val around = elbow(a, b, others, lanes, startTrim, endTrim)
    if (around != null) return around
    lanes.squeezed++
  }
  if (others != null && blocked && (upright || sy == ey)) {
    fun laneOn(forward: Boolean): Double {
      var lane = if (forward) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
      fun widen(box: Box) {
        val edge = if (upright) box.x else box.y
        lane =
          if (forward) maxOf(lane, edge + (if (upright) box.w else box.h)) else minOf(lane, edge)
      }
      widen(a)
      widen(b)
      for (other in others) {
        if (
          other !== a &&
            other !== b &&
            hitRect(
              other.x,
              other.y,
              other.x + other.w,
              other.y + other.h,
              sx,
              sy,
              ex - sx,
              ey - sy,
            ) <= 1
        )
          widen(other)
      }
      return lane
    }
    val lo = if (upright) minOf(a.y, b.y) else minOf(a.x, b.x)
    val hi = if (upright) maxOf(a.y + a.h, b.y + b.h) else maxOf(a.x + a.w, b.x + b.w)
    fun outside(forward: Boolean, lane: Double): Boolean {
      for (other in others) {
        if (other === a || other === b) continue
        val near =
          if (upright) other.y < hi && other.y + other.h > lo
          else other.x < hi && other.x + other.w > lo
        val start = if (upright) other.x else other.y
        val end = start + (if (upright) other.w else other.h)
        if (near && (if (forward) end > lane else start < lane)) return false
      }
      return true
    }
    val ax = a.x + a.w / 2
    val ay = a.y + a.h / 2
    val bx = b.x + b.w / 2
    val by = b.y + b.h / 2
    val towards = if (upright) sign(by - ay) else sign(bx - ax)
    // The route on one side: each end leaves its shape a quarter of the way from the middle towards
    // the other, clear of the straight lines that meet the middle of that side and of other routes
    // there. The lane runs a little further out for each route already along that gap, and never
    // behind a shape. `step` is how many lanes out it went; 5 when none was free.
    fun along(forward: Boolean): Way {
      val lane = laneOn(forward)
      val sgn = if (forward) 1.0 else -1.0
      val side = if (upright) (if (forward) 1 else 0) else if (forward) 3 else 2
      val p0 =
        if (upright) {
          lanes.ports(a, side, ay + (towards * a.h) / 4, a.y + 8, a.y + a.h - 8)[0]
        } else {
          lanes.ports(a, side, ax + (towards * a.w) / 4, a.x + 8, a.x + a.w - 8)[0]
        }
      val p1 =
        if (upright) {
          lanes.ports(b, side, by - (towards * b.h) / 4, b.y + 8, b.y + b.h - 8)[0]
        } else {
          lanes.ports(b, side, bx - (towards * b.w) / 4, b.x + 8, b.x + b.w - 8)[0]
        }
      fun pointsAt(at: Double): DoubleArray {
        if (upright) {
          val x0 = at + (ax - at) * minOf(hit(a, at, p0, ax, p0), 1.0)
          val x1 = at + (bx - at) * minOf(hit(b, at, p1, bx, p1), 1.0)
          return doubleArrayOf(x0, p0, at, p0, at, p1, x1, p1)
        }
        val y0 = at + (ay - at) * minOf(hit(a, p0, at, p0, ay), 1.0)
        val y1 = at + (by - at) * minOf(hit(b, p1, at, p1, by), 1.0)
        return doubleArrayOf(p0, y0, p0, at, p1, at, p1, y1)
      }
      var points = pointsAt(lane + sgn * LANE)
      for (k in 0..4) {
        val at = lane + sgn * (LANE + k * LANE_STEP)
        val tried = pointsAt(at)
        if (!clearOf(tried, others, a, b)) continue
        points = tried
        if (lanes.free(upright, at, p0, p1)) return Way(points, side, p0, p1, k)
      }
      return Way(points, side, p0, p1, 5)
    }
    // A detour takes the outside of the drawing when only one side is outside, leaving the gaps
    // between columns and rows to the relations that cross them. Otherwise it takes the side where
    // it crosses fewer lines, then the one where it runs nearer its shapes, so that detours spread
    // over both sides.
    val natural = if (upright) ey > sy else ex < sx
    val out = outside(natural, laneOn(natural))
    val back = outside(!natural, laneOn(!natural))
    val first = along(natural)
    val other = if (out == back) along(!natural) else null
    fun cost(way: Way): Double = (lanes.crossings(way.points) * 8 + way.step).toDouble()
    val forward =
      if (back && !out) !natural
      else if (other != null && cost(other) < cost(first)) !natural else natural
    val chosen = if (forward == natural) first else (other ?: along(!natural))
    val points = chosen.points
    val side = chosen.side
    val p0 = chosen.p0
    val p1 = chosen.p1
    val sgn = if (forward) 1.0 else -1.0
    if (chosen.step > 4) lanes.squeezed++
    lanes.take(points)
    lanes.claim(a, side, p0)
    lanes.claim(b, side, p1)
    return if (upright) bent(points, 2, startTrim, endTrim, nx = sgn, lean = LEAN, room = NARROW)
    else bent(points, 2, startTrim, endTrim, room = WIDE)
  }

  val len = jsHypot(ex - sx, ey - sy).jsOr { 1.0 }
  val dx = (ex - sx) / len
  val dy = (ey - sy) / len
  if (upright || sy == ey) lanes.take(doubleArrayOf(sx, sy, ex, ey))
  return Route(
    d =
      "M${num(sx + dx * startTrim)},${num(sy + dy * startTrim)}L${num(ex - dx * endTrim)},${num(ey - dy * endTrim)}",
    sx = sx,
    sy = sy,
    sdx = -dx,
    sdy = -dy,
    ex = ex,
    ey = ey,
    edx = dx,
    edy = dy,
    x0 = sx,
    y0 = sy,
    x1 = ex,
    y1 = ey,
    nx = if (truthy(shift)) -dy else 0.0,
    ny = if (truthy(shift)) dx else 0.0,
    lean = 0.0,
    room = if (abs(dx) > abs(dy)) NARROW else WIDE,
    points = doubleArrayOf(sx, sy, ex, ey),
  )
}
