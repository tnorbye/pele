// Ported from Pele src/diagrams/venn/layout.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.venn

import pele.js.jsHypot
import pele.js.jsonStringify
import java.lang.StrictMath.acos
import java.lang.StrictMath.atan2
import java.lang.StrictMath.cos
import java.lang.StrictMath.sin
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

class Circle(
  val id: String,
  var x: Double,
  var y: Double,
  var r: Double,
)

// Where the content of a region goes: the point inside all of `inside` and outside every other
// circle that is farthest from their outlines, and how far that is. A margin of 0 or less means
// the circles as placed leave the region empty.
class Spot(
  val x: Double,
  val y: Double,
  val margin: Double,
)

private const val TAU = Math.PI * 2

internal fun hypot(a: Double, b: Double): Double = jsHypot(a, b)

// For every overlap of three or more sets, adds the pairwise overlaps it implies and the diagram
// does not state, a quarter of the smaller set each, so that the circles are drawn overlapping.
// Returns the list it was given when there is nothing to add.
fun ensurePairwiseSubsets(subsets: List<VennSubset>): List<VennSubset> {
  val present = LinkedHashSet<String>()
  val sizes = LinkedHashMap<String, Double>()
  for (subset in subsets) {
    present.add(jsonStringify(subset.sets.sorted()))
    if (subset.sets.size == 1) sizes[subset.sets[0]] = subset.size
  }
  val added = ArrayList<VennSubset>()
  for (subset in subsets) {
    if (subset.sets.size < 3) continue
    val members = subset.sets.sorted()
    for (i in 0 until members.size - 1) {
      for (j in i + 1 until members.size) {
        val pair = listOf(members[i], members[j])
        val key = jsonStringify(pair)
        if (present.contains(key)) continue
        present.add(key)
        val a = sizes[pair[0]]
        val b = sizes[pair[1]]
        added.add(VennSubset(pair, if (a != null && b != null) minOf(a, b) / 4 else 2.5, ""))
      }
    }
  }
  return if (added.size > 0) subsets + added else subsets
}

// The area two circles share when their centers are d apart.
fun lensArea(r1: Double, r2: Double, d: Double): Double {
  if (d >= r1 + r2) return 0.0
  val small = minOf(r1, r2)
  if (d <= abs(r1 - r2)) return Math.PI * small * small
  val a1 = r1 * r1 * acos((d * d + r1 * r1 - r2 * r2) / (2 * d * r1))
  val a2 = r2 * r2 * acos((d * d + r2 * r2 - r1 * r1) / (2 * d * r2))
  val a3 = 0.5 * sqrt(maxOf((-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2), 0.0))
  return a1 + a2 - a3
}

// How far apart two circles sit for their overlap to have the given area. The area falls as the
// distance grows, so bisection finds it.
fun distanceForOverlap(r1: Double, r2: Double, area: Double): Double {
  var low = abs(r1 - r2)
  var high = r1 + r2
  if (!(area > 0)) return high
  val least = minOf(r1, r2)
  if (area >= Math.PI * (least * least)) return low
  for (i in 0 until 48) {
    val middle = (low + high) / 2
    if (lensArea(r1, r2, middle) > area) low = middle else high = middle
  }
  return (low + high) / 2
}

// How far a point is from the nearest outline it must stay within or without. Gives up, with
// some value below `floor`, as soon as the point cannot reach `floor`.
private fun margin(circles: List<Circle>, inside: List<Int>, outside: List<Int>, x: Double, y: Double, floor: Double): Double {
  var best = Double.POSITIVE_INFINITY
  for (i in inside) {
    val c = circles[i]
    val dx = x - c.x
    val dy = y - c.y
    val room = c.r - sqrt(dx * dx + dy * dy)
    if (room < best) {
      if (room < floor) return room
      best = room
    }
  }
  for (i in outside) {
    val c = circles[i]
    val dx = x - c.x
    val dy = y - c.y
    val room = sqrt(dx * dx + dy * dy) - c.r
    if (room < best) {
      if (room < floor) return room
      best = room
    }
  }
  return best
}

// Finds the roomiest point of a region by sampling the smallest of its circles on a grid and then
// walking uphill from the best sample. Ties go to the point nearest the middle of the region, so
// a symmetric arrangement gets symmetric labels.
fun findSpot(circles: List<Circle>, inside: List<Int>, outside: List<Int>): Spot {
  var cx = 0.0
  var cy = 0.0
  var smallest = circles[inside[0]]
  for (i in inside) {
    val c = circles[i]
    cx += c.x / inside.size
    cy += c.y / inside.size
    if (c.r < smallest.r) smallest = c
  }
  val r = smallest.r
  var x = cx
  var y = cy
  var best = margin(circles, inside, outside, x, y, Double.NEGATIVE_INFINITY)
  var off = 0.0
  val consider = { px: Double, py: Double ->
    val m = margin(circles, inside, outside, px, py, best - r * 1e-9)
    if (!(m > best - r * 1e-9)) {
      false
    } else {
      val distance = hypot(px - cx, py - cy)
      if (m > best + r * 1e-9 || distance < off - r * 1e-9) {
        best = m
        off = distance
        x = px
        y = py
        true
      } else {
        false
      }
    }
  }
  val GRID = 10
  for (gy in -GRID..GRID) {
    for (gx in -GRID..GRID) {
      if (gx * gx + gy * gy <= GRID * GRID) consider(smallest.x + (gx * r) / GRID, smallest.y + (gy * r) / GRID)
    }
  }
  var step = r / GRID
  var round = 0
  while (round < 40 && step > r * 1e-6) {
    var moved = false
    for (k in 0 until 8) {
      val angle = (k * TAU) / 8
      if (consider(x + cos(angle) * step, y + sin(angle) * step)) moved = true
    }
    if (!moved) step /= 2
    round++
  }
  return Spot(x, y, best)
}

// Places one circle per set, with areas in proportion to the sizes of the sets and each pair as
// far apart as its overlap asks: one, two and three sets exactly, more by relaxing a ring of
// circles toward those distances. Lengths are in units of the square root of a size.
fun layoutCircles(subsets: List<VennSubset>): List<Circle> {
  val sizes = LinkedHashMap<String, Double>()
  for (subset in subsets) for (id in subset.sets) if (!sizes.containsKey(id)) sizes[id] = 10.0
  for (subset in subsets) if (subset.sets.size == 1) sizes[subset.sets[0]] = subset.size

  val index = LinkedHashMap<String, Int>()
  val circles = ArrayList<Circle>()
  var largest = 0.0
  for (size in sizes.values) if (size.isFinite() && size > largest) largest = size
  if (largest == 0.0) largest = 10.0
  val widest = sqrt(largest / Math.PI)
  for ((id, size) in sizes) {
    index[id] = circles.size
    // A set too small to hold its name, or with no size at all, still gets a circle that shows.
    val r = if (size.isFinite() && size > 0) sqrt(size / Math.PI) else 0.0
    circles.add(Circle(id, 0.0, 0.0, maxOf(r, widest * 0.3)))
  }
  val n = circles.size
  if (n < 2) return circles

  // The overlap each pair was given, by the indexes of its circles.
  val overlaps = LinkedHashMap<Double, Double>()
  for (subset in ensurePairwiseSubsets(subsets)) {
    if (subset.sets.size != 2) continue
    val a = index[subset.sets[0]]!!
    val b = index[subset.sets[1]]!!
    if (a != b) overlaps[minOf(a, b).toDouble() * n + maxOf(a, b)] = subset.size
  }
  // How far apart each overlapping pair should be. Pairs that do not overlap only have to keep a gap.
  val wanted = LinkedHashMap<Double, Double>()
  for ((key, area) in overlaps) {
    wanted[key] = distanceForOverlap(circles[floor(key / n).toInt()].r, circles[(key % n).toInt()].r, area)
  }
  val gap = widest * 0.15
  val distance = { a: Int, b: Int -> wanted[a.toDouble() * n + b] ?: (circles[a].r + circles[b].r + gap) }

  if (n == 2) {
    circles[1].x = distance(0, 1)
    return circles
  }

  if (n == 3) {
    // Two sets side by side and the third below, the corners of a triangle whose sides are the
    // three distances. An overlap of all three has to exist, so the triangle shrinks until it does.
    var ab = distance(0, 1)
    var ac = distance(0, 2)
    var bc = distance(1, 2)
    val triple = subsets.any { subset -> subset.sets.toSet().size == 3 }
    for (attempt in 0 until 40) {
      val x = if (ab > 0) (ab * ab + ac * ac - bc * bc) / (2 * ab) else 0.0
      circles[1].x = ab
      circles[2].x = maxOf(-ac, minOf(ac, x))
      circles[2].y = sqrt(maxOf(ac * ac - x * x, 0.0))
      if (!triple || findSpot(circles, listOf(0, 1, 2), emptyList()).margin > widest * 0.12) break
      ab *= 0.94
      ac *= 0.94
      bc *= 0.94
    }
    return circles
  }

  var total = 0.0
  for (c in circles) total += 2 * c.r + gap
  val rounds = minOf(300.0, floor(3e6 / (n.toDouble() * n))).toInt()
  if (n <= 64) {
    val ring = total / TAU
    circles.forEachIndexed { i, c ->
      val angle = (i * TAU) / n - Math.PI / 2
      c.x = cos(angle) * ring
      c.y = sin(angle) * ring
    }
  } else {
    val columns = ceil(sqrt(n.toDouble()))
    val cell = 2 * widest + gap
    circles.forEachIndexed { i, c ->
      c.x = (i % columns) * cell
      c.y = floor(i / columns) * cell
    }
  }
  val dx = DoubleArray(n)
  val dy = DoubleArray(n)
  val pulls = IntArray(n)
  for (round in 0 until rounds) {
    dx.fill(0.0)
    dy.fill(0.0)
    pulls.fill(0)
    for (a in 0 until n) {
      for (b in a + 1 until n) {
        val overlap = wanted[a.toDouble() * n + b]
        val apart = circles[a].r + circles[b].r + gap
        var ux = circles[b].x - circles[a].x
        var uy = circles[b].y - circles[a].y
        var length = hypot(ux, uy)
        if (overlap == null && length >= apart) continue
        if (length < 1e-9) {
          ux = cos((a + b).toDouble())
          uy = sin((a + b).toDouble())
          length = 1.0
        }
        val move = (length - (overlap ?: apart)) / 2 / length
        dx[a] += ux * move
        dy[a] += uy * move
        dx[b] -= ux * move
        dy[b] -= uy * move
        pulls[a]++
        pulls[b]++
      }
    }
    for (i in 0 until n) {
      if (pulls[i] == 0) continue
      circles[i].x += dx[i] / pulls[i]
      circles[i].y += dy[i] / pulls[i]
    }
  }
  return circles
}

private class Corner(val x: Double, val y: Double, val a: Circle, val b: Circle)

// The outline of the region all the given circles share, as an SVG path, or '' if they share none.
fun regionPath(circles: List<Circle>, format: (value: Double) -> String): String {
  fun whole(c: Circle): String =
    "M${format(c.x - c.r)},${format(c.y)}A${format(c.r)},${format(c.r)} 0 1 1 ${format(c.x + c.r)},${format(c.y)}A${format(c.r)},${format(c.r)} 0 1 1 ${format(c.x - c.r)},${format(c.y)}Z"
  if (circles.size == 1) return whole(circles[0])
  fun within(x: Double, y: Double, skip: Circle?, other: Circle?): Boolean {
    for (c in circles) {
      if (c !== skip && c !== other && hypot(x - c.x, y - c.y) > c.r * (1 + 1e-9)) return false
    }
    return true
  }
  // The corners of the region: where two outlines cross inside all the other circles.
  val corners = ArrayList<Corner>()
  for (i in circles.indices) {
    for (j in i + 1 until circles.size) {
      val a = circles[i]
      val b = circles[j]
      val d = hypot(b.x - a.x, b.y - a.y)
      if (d >= a.r + b.r || d <= abs(a.r - b.r) || d == 0.0) continue
      val along = (a.r * a.r - b.r * b.r + d * d) / (2 * d)
      val up = sqrt(maxOf(a.r * a.r - along * along, 0.0))
      val mx = a.x + ((b.x - a.x) * along) / d
      val my = a.y + ((b.y - a.y) * along) / d
      for (sign in intArrayOf(1, -1)) {
        val x = mx + (sign * up * (b.y - a.y)) / d
        val y = my - (sign * up * (b.x - a.x)) / d
        if (within(x, y, a, b)) corners.add(Corner(x, y, a, b))
      }
    }
  }
  if (corners.size < 2) {
    // No corners: either one circle lies inside all the others, or there is nothing shared.
    var smallest = circles[0]
    for (c in circles) if (c.r < smallest.r) smallest = c
    for (c in circles) {
      if (c !== smallest && hypot(c.x - smallest.x, c.y - smallest.y) + smallest.r > c.r * (1 + 1e-9)) return ""
    }
    return whole(smallest)
  }
  var cx = 0.0
  var cy = 0.0
  for (p in corners) {
    cx += p.x / corners.size
    cy += p.y / corners.size
  }
  corners.sortWith { p, q ->
    val diff = atan2(p.y - cy, p.x - cx) - atan2(q.y - cy, q.x - cx)
    if (diff.isNaN()) 0 else if (diff < 0) -1 else if (diff > 0) 1 else 0
  }
  var d = ""
  for (i in corners.indices) {
    val from = corners[i]
    val to = corners[(i + 1) % corners.size]
    if (i == 0) d = "M${format(from.x)},${format(from.y)}"
    // The side between two corners is an arc of a circle that passes through both; of those, the
    // one whose arc stays inside the others.
    var arc: Circle? = null
    var large = 0
    var narrowest = Double.POSITIVE_INFINITY
    for (c in listOf(from.a, from.b)) {
      if (c !== to.a && c !== to.b) continue
      val start = atan2(from.y - c.y, from.x - c.x)
      var sweep = atan2(to.y - c.y, to.x - c.x) - start
      if (sweep <= 0) sweep += TAU
      val mx = c.x + c.r * cos(start + sweep / 2)
      val my = c.y + c.r * sin(start + sweep / 2)
      val width = hypot(mx - (from.x + to.x) / 2, my - (from.y + to.y) / 2)
      if (width < narrowest) {
        narrowest = width
        arc = c
        large = if (sweep > Math.PI) 1 else 0
      }
    }
    d += if (arc == null) "L${format(to.x)},${format(to.y)}" else "A${format(arc.r)},${format(arc.r)} 0 $large 1 ${format(to.x)},${format(to.y)}"
  }
  return d + "Z"
}
