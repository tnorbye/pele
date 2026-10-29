// Ported from Pele src/layout/compound.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.layout

import pele.js.cmp
import pele.js.jsOr
import pele.js.jsRound
import kotlin.math.abs
import kotlin.math.floor
import pele.js.jsHypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// 'TB' | 'BT' | 'LR' | 'RL'
typealias Dir = String

// The direction a diagram names, or top to bottom for anything else.
fun direction(dir: String?): Dir = if (dir == "BT" || dir == "LR" || dir == "RL") dir else "TB"

class CNode(
  var w: Double,
  var h: Double,
  var parent: Int,
  var isGroup: Boolean,
  var dir: Dir?,
  var padX: Double,
  var padTop: Double,
  var padBottom: Double,
  var minW: Double,
  // Declaration order; a group takes the smallest value among its members.
  var seq: Double,
  // How much of a side its edges may spread along, measured across the flow; 0 keeps them at its middle.
  var span: Double,
  var x: Double,
  var y: Double,
)

class CEdge(
  var src: Int,
  var dst: Int,
  var minlen: Double,
  var labelW: Double,
  var labelH: Double,
  // x, y, axis triples; axis is 0 where the edge runs vertically into the point, 1 horizontally.
  var route: MutableList<Double>,
  var labelX: Double,
  var labelY: Double,
)

class CompoundResult(val width: Double, val height: Double)

private object Side {
  const val Top = 0
  const val Right = 1
  const val Bottom = 2
  const val Left = 3
}

private class PortEdge(
  val e: Int,
  val inner: Int,
  val out: Boolean,
  var side: Int,
  // Cross-flow position outside the group from a previous layout; NaN if unknown.
  var hint: Double,
  var px: Double,
  var py: Double,
  var route: MutableList<Double>,
)

private class LevelEdge(val e: Int, val a: Int, val b: Int)

private class Level(
  val id: Int,
  val dir: Dir,
  var items: MutableList<Int>,
  val edges: MutableList<LevelEdge>,
  val ports: MutableList<PortEdge>,
  val portOf: HashMap<Int, PortEdge>,
  val routes: HashMap<Int, MutableList<Double>>,
  var x: Double,
  var y: Double,
)

private const val MAX_PORTS = 40000

// Limit repeated layouts to small graphs.
private const val MAX_ORDERED_PORTS = 400
private const val MAX_ORDERED_EDGES = 500
// Crossing tolerance before trying independent group ordering.
private const val FLAT_SLACK = 1.5
private const val FLAT_SPARE = 4

private class Snapshot(
  val nodes: DoubleArray,
  val routes: List<MutableList<Double>>,
  val labels: DoubleArray,
  val levels: DoubleArray,
  val total: CompoundResult,
)

private fun snapshot(nodes: List<CNode>, edges: List<CEdge>, levels: List<Level>, total: CompoundResult): Snapshot {
  val at = ArrayList<Double>()
  for (c in nodes) {
    at.add(c.x)
    at.add(c.y)
    at.add(c.w)
    at.add(c.h)
  }
  val labels = ArrayList<Double>()
  for (e in edges) {
    labels.add(e.labelX)
    labels.add(e.labelY)
  }
  val places = ArrayList<Double>()
  for (level in levels) {
    places.add(level.x)
    places.add(level.y)
    for (port in level.ports) {
      places.add(port.px)
      places.add(port.py)
    }
  }
  return Snapshot(at.toDoubleArray(), edges.map { e -> e.route }, labels.toDoubleArray(), places.toDoubleArray(), total)
}

private fun restore(s: Snapshot, nodes: List<CNode>, edges: List<CEdge>, levels: List<Level>): CompoundResult {
  nodes.forEachIndexed { i, c ->
    c.x = s.nodes[4 * i]
    c.y = s.nodes[4 * i + 1]
    c.w = s.nodes[4 * i + 2]
    c.h = s.nodes[4 * i + 3]
  }
  edges.forEachIndexed { i, e ->
    e.route = s.routes[i]
    e.labelX = s.labels[2 * i]
    e.labelY = s.labels[2 * i + 1]
  }
  var k = 0
  for (level in levels) {
    level.x = s.levels[k++]
    level.y = s.levels[k++]
    for (port in level.ports) {
      port.px = s.levels[k++]
      port.py = s.levels[k++]
    }
  }
  return s.total
}

// Ignore crossings within this distance of a shared node.
private const val FAN = 24.0
// Tolerance for collinear points.
private const val IN_LINE = 0.5
private const val CURVE_PIECES = 3
private const val CORNER_PIECES = 2
// Allow for a step, an S-curve, or a straight segment plus a rounded corner.
private val STEP_PIECES = maxOf(3, CURVE_PIECES, CORNER_PIECES + 1)
// Match the renderer's corner handles.
private const val HANDLE = 0.62
private const val MAX_INSERT = 128

object Style {
  const val Curved = 0
  const val Stepped = 1
  const val Linear = 2
}

fun styleOf(opt: LayeredOptions): Int = if (opt.tracks == true) Style.Stepped else if (opt.straight == true) Style.Linear else Style.Curved

fun routeCrossings(nodes: List<CNode>, edges: List<CEdge>, style: Int = Style.Curved): Int {
  var size = 0
  for (e in edges) if (e.src != e.dst && e.route.size > 3) size += (e.route.size / 3 - 1) * STEP_PIECES
  val ax = DoubleArray(size)
  val ay = DoubleArray(size)
  val bx = DoubleArray(size)
  val by = DoubleArray(size)
  val low = DoubleArray(size)
  val high = DoubleArray(size)
  val owner = IntArray(size)
  // Endpoint flags: 1 for the first segment, 2 for the last.
  val ends = IntArray(size)
  var count = 0
  var top = Double.POSITIVE_INFINITY
  var bottom = Double.NEGATIVE_INFINITY
  var fromX = Double.NaN
  var fromY = Double.NaN
  var toX = Double.NaN
  var toY = Double.NaN
  val add = { ei: Int, part: Int, x0In: Double, y0In: Double, x1: Double, y1: Double ->
    var x0 = x0In
    var y0 = y0In
    val p = count - 1
    val on = x0 == toX && y0 == toY && ((y1 == y0 && fromY == y0) || (x1 == x0 && fromX == x0))
    // PORT: owner[-1] and ends[-1] are undefined in JS; `on` is false whenever p is -1.
    if (on && owner[p] == ei && ends[p] == part && (x0 - fromX) * (x1 - x0) + (y0 - fromY) * (y1 - y0) > 0) {
      count--
      x0 = fromX
      y0 = fromY
    }
    fromX = x0
    fromY = y0
    toX = x1
    toY = y1
    ends[count] = part
    ax[count] = min(x0, x1)
    bx[count] = max(x0, x1)
    ay[count] = if (x0 <= x1) y0 else y1
    by[count] = if (x0 <= x1) y1 else y0
    low[count] = min(y0, y1)
    high[count] = max(y0, y1)
    if (low[count] < top) top = low[count]
    if (high[count] > bottom) bottom = high[count]
    owner[count++] = ei
  }
  for (ei in edges.indices) {
    val e = edges[ei]
    if (e.src == e.dst) continue
    val r = e.route
    val aligned = { k: Int -> abs(r[k] - r[k - 3]) < 0.01 || abs(r[k + 1] - r[k - 2]) < 0.01 }
    val length = { k: Int -> abs(r[k] - r[k - 3]) + abs(r[k + 1] - r[k - 2]) }
    val turns = { k: Int ->
      style == Style.Curved &&
        k > 0 &&
        k < r.size - 3 &&
        aligned(k) &&
        aligned(k + 3) &&
        length(k) >= 0.01 &&
        length(k + 3) >= 0.01 &&
        (abs(r[k] - r[k - 3]) < 0.01) != (abs(r[k + 3] - r[k]) < 0.01)
    }
    var atX = r[0]
    var atY = r[1]
    var k = 3
    while (k < r.size) {
      val part = (if (k == 3) 1 else 0) or (if (k == r.size - 3) 2 else 0)
      val x0 = atX
      val y0 = atY
      val x1 = r[k]
      val y1 = r[k + 1]
      atX = x1
      atY = y1
      if (aligned(k) || style == Style.Linear) {
        if (!turns(k)) {
          add(ei, part, x0, y0, x1, y1)
          k += 3
          continue
        }
        // Adjacent corners share a leg, so each gets half.
        val before = if (turns(k - 3)) length(k) / 2 else length(k)
        val after = if (turns(k + 3)) length(k + 3) / 2 else length(k + 3)
        val ux = (x1 - r[k - 3]) / length(k)
        val uy = (y1 - r[k - 2]) / length(k)
        val vx = (r[k + 3] - x1) / length(k + 3)
        val vy = (r[k + 4] - y1) / length(k + 3)
        val sx = x1 - ux * before
        val sy = y1 - uy * before
        add(ei, part, x0, y0, sx, sy)
        val c1x = x1 - ux * before * (1 - HANDLE)
        val c1y = y1 - uy * before * (1 - HANDLE)
        val c2x = x1 + vx * after * (1 - HANDLE)
        val c2y = y1 + vy * after * (1 - HANDLE)
        atX = x1 + vx * after
        atY = y1 + vy * after
        val turn = part or (if (k + 3 == r.size - 3) 2 else 0)
        var px = sx
        var py = sy
        for (i in 1..CORNER_PIECES) {
          val t = i.toDouble() / CORNER_PIECES
          val u = 1 - t
          val x = u * u * u * sx + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * atX
          val y = u * u * u * sy + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * atY
          add(ei, turn, px, py, x, y)
          px = x
          py = y
        }
        k += 3
        continue
      }
      if (style == Style.Stepped) {
        val down = r[k + 2] == 0.0
        val mx = if (down) x0 else (x0 + x1) / 2
        val my = if (down) (y0 + y1) / 2 else y0
        add(ei, part, x0, y0, mx, my)
        add(ei, part, mx, my, if (down) x1 else mx, if (down) my else y1)
        add(ei, part, if (down) x1 else mx, if (down) my else y1, x1, y1)
        k += 3
        continue
      }
      // Approximate the rendered S-curve with straight segments.
      val down = r[k + 2] == 0.0
      val c = if (down) ease(x1 - x0, y1 - y0) else ease(y1 - y0, x1 - x0)
      var px = x0
      var py = y0
      for (i in 1..CURVE_PIECES) {
        val t = i.toDouble() / CURVE_PIECES
        val u = 1 - t
        val across = t * t * (3 - 2 * t)
        val along = 3 * c * t * u * u + 3 * (1 - c) * t * t * u + t * t * t
        val x = x0 + (x1 - x0) * (if (down) across else along)
        val y = y0 + (y1 - y0) * (if (down) along else across)
        add(ei, part, px, py, x, y)
        px = x
        py = y
      }
      k += 3
    }
  }
  // Count each pair only in the first band shared by both segments.
  val bands = if (bottom > top) jsRound(sqrt(count.toDouble())).toInt() else 1
  val scale = if (bottom > top) bands / (bottom - top) else 0.0
  val bandOf = { y: Double -> min((bands - 1).toDouble(), floor((y - top) * scale)).toInt() }
  val first = IntArray(count)
  val bandStart = IntArray(bands + 1)
  for (p in 0 until count) {
    first[p] = bandOf(low[p])
    val last = bandOf(high[p])
    for (b in first[p]..last) bandStart[b + 1]++
  }
  for (b in 0 until bands) bandStart[b + 1] += bandStart[b]
  val fill = bandStart.copyOfRange(0, bands)
  val inBand = IntArray(bandStart[bands])
  for (p in 0 until count) {
    val last = bandOf(high[p])
    for (b in first[p]..last) inBand[fill[b]++] = p
  }
  // Edges merging into a shared endpoint run do not count as crossings.
  val runs = DoubleArray(edges.size * 8)
  val inLine = { r: List<Double>, a: Int, b: Int, c: Int ->
    (abs(r[a] - r[b]) < IN_LINE && abs(r[a] - r[c]) < IN_LINE) || (abs(r[a + 1] - r[b + 1]) < IN_LINE && abs(r[a + 1] - r[c + 1]) < IN_LINE)
  }
  for (ei in edges.indices) {
    val r = edges[ei].route
    if (r.size < 6) continue
    var k = 3
    while (k + 3 < r.size && inLine(r, 0, k, k + 3)) k += 3
    val last = r.size - 3
    var j = last - 3
    while (j >= 3 && inLine(r, last, j, j - 3)) j -= 3
    val values = doubleArrayOf(r[0], r[1], r[k], r[k + 1], r[j], r[j + 1], r[last], r[last + 1])
    values.copyInto(runs, ei * 8)
  }
  val onRun = { ei: Int, end: Int, x: Double, y: Double ->
    val o = ei * 8 + (if (end == 1) 0 else 4)
    val x0 = runs[o]
    val y0 = runs[o + 1]
    val dx = runs[o + 2] - x0
    val dy = runs[o + 3] - y0
    val length = dx * dx + dy * dy
    val t = if (length > 0) max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / length)) else 0.0
    jsHypot(x - x0 - t * dx, y - y0 - t * dy) < IN_LINE
  }
  var total = 0
  for (band in 0 until bands) {
    val bandEnd = bandStart[band + 1]
    if (bandEnd - bandStart[band] > MAX_INSERT) {
      val part = inBand.copyOfRange(bandStart[band], bandEnd).toMutableList()
      part.sortWith { p, q -> cmp((ax[p] - ax[q]).jsOr { (p - q).toDouble() }) }
      for (i in part.indices) inBand[bandStart[band] + i] = part[i]
    } else {
      for (i in bandStart[band] + 1 until bandEnd) {
        val p = inBand[i]
        var j = i
        while (j > bandStart[band] && ax[inBand[j - 1]] > ax[p]) {
          inBand[j] = inBand[j - 1]
          j--
        }
        inBand[j] = p
      }
    }
    for (s in bandStart[band] until bandEnd) {
      val p = inBand[s]
      val dx = bx[p] - ax[p]
      val dy = by[p] - ay[p]
      val here = first[p] == band
      for (t in s + 1 until bandEnd) {
        val q = inBand[t]
        if (ax[q] > bx[p]) break
        if (high[q] < low[p] || low[q] > high[p] || owner[p] == owner[q] || (!here && first[q] != band)) continue
        val d1 = dx * (ay[q] - ay[p]) - dy * (ax[q] - ax[p])
        val d2 = dx * (by[q] - ay[p]) - dy * (bx[q] - ax[p])
        // Treat points on the line as one side to count segment-boundary crossings once.
        if ((d1 > 0) == (d2 > 0)) continue
        val ex = bx[q] - ax[q]
        val ey = by[q] - ay[q]
        val d3 = ex * (ay[p] - ay[q]) - ey * (ax[p] - ax[q])
        val d4 = ex * (by[p] - ay[q]) - ey * (bx[p] - ax[q])
        if ((d3 > 0) == (d4 > 0)) continue
        val ep = edges[owner[p]]
        val eq = edges[owner[q]]
        val at = d3 / (d3 - d4)
        val x = ax[p] + at * dx
        val y = ay[p] + at * dy
        // Exclude fan-in and fan-out at shared nodes.
        val fan = { v: Int, pe: Int, qe: Int ->
          ((ends[p] and pe) != 0 && (ends[q] and qe) != 0) ||
            (abs(x - nodes[v].x) < nodes[v].w / 2 + FAN && abs(y - nodes[v].y) < nodes[v].h / 2 + FAN) ||
            (onRun(owner[p], pe, x, y) && onRun(owner[q], qe, x, y))
        }
        if (ep.src == eq.src && fan(ep.src, 1, 1)) continue
        if (ep.src == eq.dst && fan(ep.src, 1, 2)) continue
        if (ep.dst == eq.src && fan(ep.dst, 2, 1)) continue
        if (ep.dst == eq.dst && fan(ep.dst, 2, 2)) continue
        total++
      }
    }
  }
  return total
}

// Match Mermaid: groups crossed by edges inherit their parent's direction.
fun inheritCrossedDirections(nodes: List<CNode>, ends: Iterable<Pair<Int, Int>>) {
  val ancestors = { i: Int ->
    val set = LinkedHashSet<Int>()
    var p = nodes[i].parent
    while (p >= 0 && !set.contains(p)) {
      set.add(p)
      p = nodes[p].parent
    }
    set
  }
  for ((src, dst) in ends) {
    if (src < 0 || dst < 0 || src == dst) continue
    val a = ancestors(src)
    val b = ancestors(dst)
    for (g in a) if (!b.contains(g)) nodes[g].dir = null
    for (g in b) if (!a.contains(g)) nodes[g].dir = null
  }
}

fun shiftLayout(nodes: List<CNode>, edges: List<CEdge>, ox: Double, oy: Double) {
  for (c in nodes) {
    c.x += ox
    c.y += oy
  }
  for (e in edges) {
    var k = 0
    while (k < e.route.size) {
      e.route[k] += ox
      e.route[k + 1] += oy
      k += 3
    }
    e.labelX += ox
    e.labelY += oy
  }
}

fun cnode(w: Double, h: Double, parent: Int = -1): CNode =
  CNode(w, h, parent, isGroup = false, dir = null, padX = 0.0, padTop = 0.0, padBottom = 0.0, minW = 0.0, seq = 0.0, span = 0.0, x = 0.0, y = 0.0)

private fun startSide(dir: Dir): Int = if (dir == "TB") Side.Top else if (dir == "BT") Side.Bottom else if (dir == "LR") Side.Left else Side.Right

private fun opposite(side: Int): Int = (side + 2) and 3

private class Built(val lnodes: MutableList<LNode>, val ledges: MutableList<LEdge>, val dummies: IntArray)

private class Laid(val given: List<Double>, val lnodes: List<LNode>, val ledges: List<LEdge>, val size: LayeredResult)

// Lays out a graph whose nodes may be nested in groups. Each group is laid out on its own,
// in its own direction, and then placed in its parent as a single box. An edge that crosses
// a group's border passes through a port on the side facing its other end.
fun compoundLayout(nodes: List<CNode>, edges: List<CEdge>, rootDir: Dir, opt: LayeredOptions): CompoundResult {
  val n = nodes.size
  val levelOf = HashMap<Int, Level>()
  val order = ArrayList<Level>()
  val makeLevel = { id: Int, dir: Dir ->
    val level = Level(id, dir, ArrayList(), ArrayList(), ArrayList(), HashMap(), HashMap(), 0.0, 0.0)
    levelOf[id] = level
    level
  }
  val root = makeLevel(-1, rootDir)

  val children = ArrayList<MutableList<Int>>()
  for (i in 0 until n) children.add(ArrayList())
  val rootItems = ArrayList<Int>()
  for (i in 0 until n) {
    val p = nodes[i].parent
    if (p >= 0 && p < n && p != i && nodes[p].isGroup) children[p].add(i)
    else {
      nodes[i].parent = -1
      rootItems.add(i)
    }
  }
  root.items = rootItems
  order.add(root)
  val depth = IntArray(n)
  val placed = IntArray(n)
  val descend = { from: Int ->
    var q = from
    while (q < order.size) {
      val level = order[q]
      for (item in level.items) {
        placed[item] = 1
        depth[item] = if (level.id < 0) 0 else depth[level.id] + 1
        if (nodes[item].isGroup) {
          val child = makeLevel(item, nodes[item].dir ?: level.dir)
          child.items = children[item]
          order.add(child)
        }
      }
      q++
    }
  }
  descend(0)
  // Groups that contain each other are unreachable from the root; lift them out.
  for (i in 0 until n) {
    if (placed[i] != 0) continue
    val former = nodes[i].parent
    children[former] = children[former].filter { c -> c != i }.toMutableList()
    nodes[i].parent = -1
    root.items.add(i)
    placed[i] = 1
    depth[i] = 0
    if (nodes[i].isGroup) {
      val from = order.size
      val child = makeLevel(i, nodes[i].dir ?: rootDir)
      child.items = children[i]
      order.add(child)
      descend(from)
    }
  }

  for (q in order.size - 1 downTo 1) {
    val level = order[q]
    var seq = Double.POSITIVE_INFINITY
    for (item in level.items) if (nodes[item].seq < seq) seq = nodes[item].seq
    if (seq != Double.POSITIVE_INFINITY) nodes[level.id].seq = seq
  }
  for (level in order) level.items.sortWith { a, b -> cmp((nodes[a].seq - nodes[b].seq).jsOr { (a - b).toDouble() }) }

  // Every group border an edge crosses costs a port. Past the budget, an edge is drawn between
  // the groups that hold its ends, at the level they share.
  var ports = 0
  for (e in edges) {
    var a = e.src
    var b = e.dst
    var crossed = 0
    while (depth[a] > depth[b]) {
      a = nodes[a].parent
      crossed++
    }
    while (depth[b] > depth[a]) {
      b = nodes[b].parent
      crossed++
    }
    while (a != b && nodes[a].parent != nodes[b].parent) {
      a = nodes[a].parent
      b = nodes[b].parent
      crossed += 2
    }
    if (a == b) continue
    if (ports + crossed > MAX_PORTS) {
      e.src = a
      e.dst = b
    } else {
      ports += crossed
    }
  }

  val loops = ArrayList<Int>()
  val stray = ArrayList<Int>()
  for (ei in edges.indices) {
    val e = edges[ei]
    e.route = ArrayList()
    if (e.src == e.dst) {
      loops.add(ei)
      continue
    }
    var a = e.src
    var b = e.dst
    val aPath = ArrayList<Int>()
    val bPath = ArrayList<Int>()
    while (depth[a] > depth[b]) {
      aPath.add(a)
      a = nodes[a].parent
    }
    while (depth[b] > depth[a]) {
      bPath.add(b)
      b = nodes[b].parent
    }
    if (a == b) {
      stray.add(ei)
      continue
    }
    while (nodes[a].parent != nodes[b].parent) {
      aPath.add(a)
      bPath.add(b)
      a = nodes[a].parent
      b = nodes[b].parent
    }
    levelOf[nodes[a].parent]!!.edges.add(LevelEdge(ei, a, b))
    for ((path, out) in listOf(aPath to true, bPath to false)) {
      for (inner in path) {
        val level = levelOf[nodes[inner].parent]!!
        val port = PortEdge(ei, inner, out, Side.Top, Double.NaN, 0.0, 0.0, ArrayList())
        level.ports.add(port)
        level.portOf[ei * 2 + (if (out) 0 else 1)] = port
      }
    }
  }

  val local = IntArray(n)
  val endpointBelow = { item: Int, e: CEdge, out: Boolean -> (if (out) e.src else e.dst) != item }

  val ordered = ports > 1 && ports <= MAX_ORDERED_PORTS && edges.size <= MAX_ORDERED_EDGES

  // Use a flat layout to coordinate node and port order across groups.
  val key = DoubleArray(n) { Double.NaN }
  var keyed = false
  var flatCrossings = 0
  var farHint: ((Level, PortEdge) -> Double)? = null

  val build = { level: Level, sized: Boolean ->
    val vertical = level.dir == "TB" || level.dir == "BT"
    val start = startSide(level.dir)
    val lnodes = ArrayList<LNode>()
    val ledges = ArrayList<LEdge>()
    level.items.forEachIndexed { i, item ->
      local[item] = i
      val node = nodes[item]
      val ln = if (sized) lnode(if (vertical) node.w else node.h, if (vertical) node.h else node.w) else lnode(0.0, 0.0)
      ln.span = if (node.isGroup) min(node.span, ln.w - 2 * node.padX) else node.span
      ln.key = if (keyed) key[item] else Double.NaN
      lnodes.add(ln)
    }
    val held = { item: Int, ei: Int, out: Boolean -> nodes[item].isGroup && endpointBelow(item, edges[ei], out) }
    val offset = fun(item: Int, ei: Int, out: Boolean): Double {
      if (!sized || !nodes[item].isGroup || !endpointBelow(item, edges[ei], out)) return 0.0
      val port = levelOf[item]!!.portOf[ei * 2 + (if (out) 0 else 1)]!!
      return if (vertical) port.px - nodes[item].w / 2 else port.py - nodes[item].h / 2
    }
    for (le in level.edges) {
      val e = edges[le.e]
      val edge = ledge(local[le.a], local[le.b], e.minlen)
      if (sized) {
        edge.labelW = if (vertical) e.labelW else e.labelH
        edge.labelH = if (vertical) e.labelH else e.labelW
        edge.tailDx = offset(le.a, le.e, true)
        edge.headDx = offset(le.b, le.e, false)
        edge.tailFixed = held(le.a, le.e, true)
        edge.headFixed = held(le.b, le.e, false)
      }
      ledges.add(edge)
    }
    val dummies = IntArray(level.ports.size) { -1 }
    level.ports.forEachIndexed { i, port ->
      if (port.side == start || port.side == opposite(start)) {
        val d = lnodes.size
        val ln = lnode(0.0, 0.0, if (port.side == start) Kind.StartPort else Kind.EndPort)
        ln.key = if (keyed) key[if (port.out) edges[port.e].dst else edges[port.e].src] else Double.NaN
        val far = farHint
        ln.hint = if (port.hint.isNaN() && keyed && far != null) far(level, port) else port.hint
        lnodes.add(ln)
        dummies[i] = d
        val edge = if (port.out) ledge(local[port.inner], d) else ledge(d, local[port.inner])
        if (port.out) {
          edge.tailDx = offset(port.inner, port.e, true)
          edge.tailFixed = held(port.inner, port.e, true)
        } else {
          edge.headDx = offset(port.inner, port.e, false)
          edge.headFixed = held(port.inner, port.e, false)
        }
        ledges.add(edge)
      } else {
        lnodes[local[port.inner]].pin = if (port.side == Side.Top || port.side == Side.Left) -1 else 1
      }
    }
    Built(lnodes, ledges, dummies)
  }

  var flat = order.size > 1 && ordered
  for (level in order) if (level.dir != rootDir) flat = false
  if (flat) {
    val vertical = rootDir == "TB" || rootDir == "BT"
    val leaf = IntArray(n) { -1 }
    val group = IntArray(n) { -1 }
    val lnodes = ArrayList<LNode>()
    val of = ArrayList<Int>()
    val parent = ArrayList<Int>()
    for (i in 0 until n) {
      if (!nodes[i].isGroup) continue
      group[i] = parent.size
      parent.add(-1)
    }
    for (i in 0 until n) {
      val p = nodes[i].parent
      if (nodes[i].isGroup) parent[group[i]] = if (p >= 0) group[p] else -1
      else {
        leaf[i] = lnodes.size
        lnodes.add(lnode(if (vertical) nodes[i].w else nodes[i].h, if (vertical) nodes[i].h else nodes[i].w))
        of.add(if (p >= 0) group[p] else -1)
      }
    }
    val ledges = ArrayList<LEdge>()
    val ledgeOf = IntArray(edges.size) { -1 }
    edges.forEachIndexed { ei, e ->
      if (e.src == e.dst || leaf[e.src] < 0 || leaf[e.dst] < 0) return@forEachIndexed
      ledgeOf[ei] = ledges.size
      ledges.add(ledge(leaf[e.src], leaf[e.dst], e.minlen, if (vertical) e.labelW else e.labelH, if (vertical) e.labelH else e.labelW))
    }
    val flatLayout = layered(lnodes, ledges, opt.copy(groups = LayeredGroups(of.toIntArray(), parent.toIntArray()), orderOnly = true))
    flatCrossings = min(flatLayout.crossings ?: 0, flatLayout.declared ?: 0)
    val sum = DoubleArray(n)
    val count = IntArray(n)
    // Last rank occupied by each group.
    val last = IntArray(n) { -1 }
    for (i in 0 until n) {
      if (leaf[i] < 0) continue
      key[i] = lnodes[leaf[i]].x
      var p = nodes[i].parent
      while (p >= 0) {
        sum[p] += key[i]
        count[p]++
        last[p] = max(last[p], lnodes[leaf[i]].rank)
        p = nodes[p].parent
      }
    }
    for (i in 0 until n) if (count[i] > 0) key[i] = sum[i] / count[i]
    // Order outgoing ports at the group's last rank; break ties using the next rank.
    farHint = fun(level: Level, port: PortEdge): Double {
      val le = ledgeOf[port.e]
      if (le < 0 || level.id < 0 || !port.out || port.side != opposite(startSide(level.dir))) return Double.NaN
      val points = ledges[le].points
      var inside = key[edges[port.e].src]
      var outside = 0.0
      var k = 0
      while (k < points.size) {
        if (points[k] == last[level.id].toDouble()) inside = points[k + 1]
        if (points[k] == (last[level.id] + 1).toDouble()) outside = points[k + 1]
        k += 2
      }
      return inside + outside * 1e-6
    }
  }

  // Reuse unchanged levels across layout attempts.
  val laid = HashMap<Int, MutableList<Laid>>()
  val layOut = fun(level: Level, lnodes: List<LNode>, ledges: List<LEdge>): Laid {
    if (!ordered) return Laid(emptyList(), lnodes, ledges, layered(lnodes, ledges, opt))
    val given = ArrayList<Double>()
    for (ln in lnodes) {
      given.add(ln.w)
      given.add(ln.h)
      given.add(ln.kind.toDouble())
      given.add(ln.pin.toDouble())
      given.add(ln.hint)
      given.add(ln.key)
      given.add(ln.span)
    }
    for (le in ledges) {
      given.add(le.tail.toDouble())
      given.add(le.head.toDouble())
      given.add(le.minlen)
      given.add(le.labelW)
      given.add(le.labelH)
      given.add(le.tailDx)
      given.add(le.headDx)
      given.add(if (le.tailFixed) 1.0 else 0.0)
      given.add(if (le.headFixed) 1.0 else 0.0)
    }
    val past = laid.getOrPut(level.id) { ArrayList() }
    for (p in past) {
      var same = p.given.size == given.size
      var i = 0
      // Object.is: NaN is NaN, and -0 is not 0.
      while (same && i < given.size) {
        same = p.given[i].toBits() == given[i].toBits()
        i++
      }
      if (same) return p
    }
    val done = Laid(given, lnodes, ledges, layered(lnodes, ledges, opt))
    past.add(done)
    return done
  }

  var total = CompoundResult(0.0, 0.0)
  val place = {
    // Rank parents first to determine which group sides edges cross.
    for (level in order) {
      for (port in level.ports) {
        if (nodes[port.inner].isGroup && endpointBelow(port.inner, edges[port.e], port.out)) {
          levelOf[port.inner]!!.portOf[port.e * 2 + (if (port.out) 0 else 1)]!!.side = port.side
        }
      }
      if (level.edges.size == 0) continue
      var needed = false
      for (le in level.edges) {
        val e = edges[le.e]
        if ((nodes[le.a].isGroup && e.src != le.a) || (nodes[le.b].isGroup && e.dst != le.b)) needed = true
      }
      if (!needed) continue
      val built = build(level, false)
      val lnodes = built.lnodes
      val ledges = built.ledges
      orient(lnodes, ledges)
      rank(lnodes, ledges, 1)
      val start = startSide(level.dir)
      level.edges.forEachIndexed { i, le ->
        val e = edges[le.e]
        val aAbove = !ledges[i].reversed
        if (nodes[le.a].isGroup && e.src != le.a) {
          levelOf[le.a]!!.portOf[le.e * 2]!!.side = if (aAbove) opposite(start) else start
        }
        if (nodes[le.b].isGroup && e.dst != le.b) {
          levelOf[le.b]!!.portOf[le.e * 2 + 1]!!.side = if (aAbove) start else opposite(start)
        }
      }
    }

    // Lay out children first so their sizes are known.
    for (q in order.size - 1 downTo 0) {
      val level = order[q]
      val group = if (level.id >= 0) nodes[level.id] else null
      val vertical = level.dir == "TB" || level.dir == "BT"
      val flip = level.dir == "BT" || level.dir == "RL"
      val built = build(level, true)
      val laidOut = layOut(level, built.lnodes, built.ledges)
      val lnodes = laidOut.lnodes
      val ledges = laidOut.ledges
      val size = laidOut.size
      val dummies = built.dummies
      val contentW = if (vertical) size.width else size.height
      val contentH = if (vertical) size.height else size.width
      val padX = group?.padX ?: 0.0
      val padTop = group?.padTop ?: 0.0
      val boxW = max(contentW + 2 * padX, group?.minW ?: 0.0)
      val boxH = contentH + padTop + (group?.padBottom ?: 0.0)
      val offX = (boxW - contentW) / 2
      val offY = padTop
      val tx = { lx: Double, ly: Double -> offX + (if (vertical) lx else if (flip) size.height - ly else ly) }
      val ty = { lx: Double, ly: Double -> offY + (if (vertical) (if (flip) size.height - ly else ly) else lx) }
      val axis = if (vertical) 0.0 else 1.0
      val convert = { points: List<Double> ->
        val out = ArrayList<Double>()
        var i = 0
        while (i < points.size) {
          out.add(tx(points[i], points[i + 1]))
          out.add(ty(points[i], points[i + 1]))
          out.add(axis)
          i += 2
        }
        out
      }

      level.items.forEachIndexed { i, item ->
        nodes[item].x = tx(lnodes[i].x, lnodes[i].y)
        nodes[item].y = ty(lnodes[i].x, lnodes[i].y)
      }
      level.edges.forEachIndexed { i, le ->
        val edge = ledges[i]
        level.routes[le.e] = convert(edge.points)
        val e = edges[le.e]
        if (e.labelW > 0) {
          e.labelX = tx(edge.labelX, edge.labelY)
          e.labelY = ty(edge.labelX, edge.labelY)
        }
      }
      var k = level.edges.size
      level.ports.forEachIndexed { i, port ->
        val item = nodes[port.inner]
        if (dummies[i] >= 0) {
          val route = convert(ledges[k++].points)
          val at = if (port.out) route.size - 3 else 0
          var bx = route[at]
          var by = route[at + 1]
          if (port.side == Side.Top) by = 0.0
          else if (port.side == Side.Bottom) by = boxH
          else if (port.side == Side.Left) bx = 0.0
          else bx = boxW
          if (port.out) {
            route.add(bx)
            route.add(by)
            route.add(axis)
          } else {
            route.addAll(0, listOf(bx, by, axis))
          }
          port.px = bx
          port.py = by
          port.route = route
          return@forEachIndexed
        }
        var ix = item.x
        var iy = item.y
        if (item.isGroup && endpointBelow(port.inner, edges[port.e], port.out)) {
          val inner = levelOf[port.inner]!!.portOf[port.e * 2 + (if (port.out) 0 else 1)]!!
          ix += inner.px - item.w / 2
          iy += inner.py - item.h / 2
        } else if (port.side == Side.Top) iy -= item.h / 2
        else if (port.side == Side.Bottom) iy += item.h / 2
        else if (port.side == Side.Left) ix -= item.w / 2
        else ix += item.w / 2
        var bx = ix
        var by = iy
        if (port.side == Side.Top) by = 0.0
        else if (port.side == Side.Bottom) by = boxH
        else if (port.side == Side.Left) bx = 0.0
        else bx = boxW
        val a = if (port.side == Side.Top || port.side == Side.Bottom) 0.0 else 1.0
        port.px = bx
        port.py = by
        port.route = if (port.out) arrayListOf(ix, iy, a, bx, by, a) else arrayListOf(bx, by, a, ix, iy, a)
      }

      if (group != null) {
        group.w = boxW
        group.h = boxH
      } else {
        total = CompoundResult(boxW, boxH)
      }
    }

    for (level in order) {
      val ox = level.x
      val oy = level.y
      for (item in level.items) {
        val node = nodes[item]
        node.x += ox
        node.y += oy
        if (node.isGroup) {
          val child = levelOf[item]!!
          child.x = node.x - node.w / 2
          child.y = node.y - node.h / 2
        }
      }
    }

    val append = { route: MutableList<Double>, part: List<Double>, ox: Double, oy: Double ->
      var i = 0
      while (i < part.size) {
        val x = part[i] + ox
        val y = part[i + 1] + oy
        val len = route.size
        if (!(len >= 3 && abs(route[len - 3] - x) < 0.01 && abs(route[len - 2] - y) < 0.01)) {
          route.add(x)
          route.add(y)
          route.add(part[i + 2])
        }
        i += 3
      }
    }

    for (level in order) {
      for (le in level.edges) {
        val e = edges[le.e]
        val route = ArrayList<Double>()
        val outward = ArrayList<Level>()
        var item = e.src
        while (nodes[item].parent != level.id) {
          outward.add(levelOf[nodes[item].parent]!!)
          item = nodes[item].parent
        }
        for (lv in outward) append(route, lv.portOf[le.e * 2]!!.route, lv.x, lv.y)
        append(route, level.routes[le.e]!!, level.x, level.y)
        val inward = ArrayList<Level>()
        item = e.dst
        while (nodes[item].parent != level.id) {
          inward.add(levelOf[nodes[item].parent]!!)
          item = nodes[item].parent
        }
        for (i in inward.size - 1 downTo 0) {
          append(route, inward[i].portOf[le.e * 2 + 1]!!.route, inward[i].x, inward[i].y)
        }
        e.route = route
        if (e.labelW > 0) {
          e.labelX += level.x
          e.labelY += level.y
        }
      }
    }
  }
  // The flat layout ignores group boxes, so independent ordering may yield fewer crossings.
  val style = styleOf(opt)
  keyed = flat
  place()
  var crossings = -1
  if (flat) {
    crossings = routeCrossings(nodes, edges, style)
    if (crossings > flatCrossings * FLAT_SLACK + FLAT_SPARE) {
      val first = snapshot(nodes, edges, order, total)
      keyed = false
      place()
      val own = routeCrossings(nodes, edges, style)
      if (own >= crossings) {
        keyed = true
        total = restore(first, nodes, edges, order)
      } else crossings = own
    }
  }

  // Refine port order using routes outside each group. Skip this if independent ordering won;
  // it rarely helps enough to justify another layout.
  if (keyed == flat && ordered) {
    var again = false
    for (level in order) {
      if (level.id < 0) continue
      val box = nodes[level.id]
      val vertical = level.dir == "TB" || level.dir == "BT"
      val end = opposite(startSide(level.dir))
      val seen = ArrayList<DoubleArray>()
      for (port in level.ports) {
        if (port.side != end) continue
        // Skip the straight run out of the port to find where the edge turns.
        val route = edges[port.e].route
        val bx = level.x + port.px
        val by = level.y + port.py
        var at = -1
        var k = 0
        while (k < route.size && (at < 0 || port.out)) {
          if (abs(route[k] - bx) < 0.01 && abs(route[k + 1] - by) < 0.01) at = k
          k += 3
        }
        if (at < 0) continue
        val a = if (vertical) 0 else 1
        var next = if (port.out) at + 3 else at - 3
        while (next >= 0 && next < route.size && abs(route[next + a] - route[at + a]) < IN_LINE) next += if (port.out) 3 else -3
        if (next < 0 || next >= route.size) continue
        port.hint = route[next + a] - (if (vertical) box.x - box.w / 2 else box.y - box.h / 2)
        seen.add(doubleArrayOf(if (vertical) port.px else port.py, port.hint))
      }
      seen.sortWith { p, q -> cmp(p[0] - q[0]) }
      for (i in 1 until seen.size) if (seen[i - 1][1] > seen[i][1]) again = true
    }
    if (again) {
      val before = if (crossings >= 0) crossings else routeCrossings(nodes, edges, style)
      val first = if (before > 0) snapshot(nodes, edges, order, total) else null
      if (first != null) {
        place()
        if (routeCrossings(nodes, edges, style) >= before) total = restore(first, nodes, edges, order)
      }
    }
  }

  for (ei in stray) {
    val e = edges[ei]
    val a = nodes[e.src]
    val b = nodes[e.dst]
    e.route = arrayListOf(a.x, a.y, 0.0, b.x, b.y, 0.0)
    e.labelX = (a.x + b.x) / 2
    e.labelY = (a.y + b.y) / 2
  }
  for (ei in loops) {
    val e = edges[ei]
    val node = nodes[e.src]
    e.labelX = node.x + node.w / 2 + 28 + e.labelW / 2
    e.labelY = node.y
  }

  return total
}
