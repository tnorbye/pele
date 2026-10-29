// Ported from Pele src/layout/layered.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.layout

import pele.js.cmp
import pele.js.jsOr
import kotlin.math.abs
import pele.js.jsHypot
import kotlin.math.max
import kotlin.math.min

// Layered (Sugiyama-style) layout of one flat graph. Ranks run down the y axis; callers
// rotate the result for other directions.
//
// What the layout of edges aims for, most important first. Where two aims pull apart, the one
// higher up decides, and each names where it is carried out.
//
// 1. Lines can be told apart. No two touch or run along each other, and none runs through a
//    node or a label. Ends on one side of a node have places of their own (`spread`), and
//    curves that would pass within a few pixels turn at levels of their own (`assignTracks`).
// 2. Lines cross as little as the order of the nodes allows (`reduceCrossings`, and the order
//    of the levels in `assignTracks`).
// 3. An edge takes the shortest and simplest way: straight if it can, else one curve, else a
//    curve with a level run. One that passes several ranks runs in line with one of its ends
//    and turns once, at the other (`alignLabels`, `straighten`). Ends slide along their sides,
//    and nodes move a little, where that makes an edge straight (`settle`, `snap`).
// 4. An edge meets the middle of a side. An end with a side to itself stays there, and ends
//    that share a side sit around the middle in the order of where they lead. An end moves off
//    the middle only for the aims above (`settle`).
// 5. The drawing is balanced, and what is alike is drawn alike. A label sits midway along the
//    line that shows, the curves either side of it have much the same room to turn in, and a gap
//    whose curves mostly cross has them all as the same plain curve rather than each on a
//    level of its own (`headRoom` in `layered`, the crossed gaps in `assignTracks`).
// 6. An edge leaves and reaches a node square to it, and an arrowhead points straight at the
//    node (`routePath` in svg/edges.ts, which draws the routes).
// 7. Nodes keep the order they were written in, where nothing above says otherwise.
// 8. The drawing stays compact. A gap between ranks grows only to hold the levels in it, or
//    to let curves that cross do so at a clear angle.

object Kind {
  const val Node = 0
  const val Dummy = 1
  const val Label = 2
  const val StartPort = 3
  const val EndPort = 4
}

class LNode(
  var w: Double,
  var h: Double,
  var kind: Int,
  var pin: Int,
  // Port's cross-flow destination; NaN if unknown. Fully hinted port ranks keep this order.
  var hint: Double,
  // Initial cross-flow position; NaN if unknown. Used only when every node in a rank has one.
  var key: Double,
  // How much of the side that edges meet may be shared out between them; 0 keeps them all at its middle.
  var span: Double,
  var x: Double,
  var y: Double,
  var rank: Int,
)

class LEdge(
  var tail: Int,
  var head: Int,
  var minlen: Double,
  var labelW: Double,
  var labelH: Double,
  var tailDx: Double,
  var headDx: Double,
  // An end that is held where its offset puts it, and takes no part in sharing out a node's side.
  var tailFixed: Boolean,
  var headFixed: Boolean,
  var reversed: Boolean,
  var points: MutableList<Double>,
  var labelX: Double,
  var labelY: Double,
)

class LayeredGroups(val of: IntArray, val parent: IntArray)

data class LayeredOptions(
  val nodeSep: Double,
  val edgeSep: Double,
  val rankSep: Double,
  val portSep: Double,
  // Route edges at right angles, each on a track of its own across the gap between two ranks.
  val tracks: Boolean? = null,
  // Draw every edge straight from point to point: none is given a level to turn onto.
  val straight: Boolean? = null,
  // The caller sets the ends of the edges on one side of a node apart itself, so two edges
  // between the same two nodes need no bend to keep them from being drawn as one.
  val apart: Boolean? = null,
  // The room that the marker at the head of an edge takes. A rank of labels whose edges all
  // head the same way sits half of this nearer their tails, so that each label is midway along
  // the line that shows, and the curves either side of it have the same room to turn in.
  val headRoom: Double? = null,
  // Keep each group contiguous within ranks. `of` maps nodes to groups; `parent` maps groups
  // to parents. Both use -1 for none.
  val groups: LayeredGroups? = null,
  // Compute cross-flow positions in `x` without full routing.
  val orderOnly: Boolean? = null,
)

class LayeredResult(
  val width: Double,
  val height: Double,
  // With `orderOnly`: final crossings and crossings before grouping or reordering.
  val crossings: Int? = null,
  val declared: Int? = null,
)

fun lnode(w: Double, h: Double, kind: Int = Kind.Node): LNode =
  LNode(w, h, kind, pin = 0, hint = Double.NaN, key = Double.NaN, span = 0.0, x = 0.0, y = 0.0, rank = 0)

fun ledge(tail: Int, head: Int, minlen: Double = 1.0, labelW: Double = 0.0, labelH: Double = 0.0): LEdge =
  LEdge(
    tail = tail,
    head = head,
    minlen = minlen,
    labelW = labelW,
    labelH = labelH,
    tailDx = 0.0,
    headDx = 0.0,
    tailFixed = false,
    headFixed = false,
    reversed = false,
    points = ArrayList(),
    labelX = 0.0,
    labelY = 0.0,
  )

private const val MAX_SWEEPS = 24

// An edge gets a bend point in every rank it skips. Past this many, the longest edges are
// drawn as one direct curve instead, which keeps huge graphs from exhausting time and memory.
private const val MAX_DUMMIES = 20000

// The room between two edges that meet one side of a node, and the least that keeps their arrowheads apart.
private const val PORT_PITCH = 16.0
private const val MIN_PITCH = 8.0

// Where edges are not given levels, a gap between ranks grows to keep the edges that cross it at
// least this steep, up to this many times its usual height. Shallower than that, the edges of a
// wide rank are hard to tell apart.
private const val MIN_SLOPE = 0.125
private const val MAX_GAP = 3.0

// Tracks: the room kept clear between a rank and the nearest track, the room between two tracks
// when the gap has to grow to hold them, and the most they spread to when it has room to spare.
private const val TRACK_MARGIN = 18.0
private const val TRACK_PITCH = 10.0
private const val TRACK_SPREAD = 14.0
// A curve is one plain S unless it runs more than this many times as far across as the gap is
// high, and so does another that it would run along. Then each turns onto a level of its own
// to run across on. The levels of a gap keep this far from its ranks, to leave room to turn
// in, and from each other.
private const val LEVEL_WIDE = 2.0
private const val LEVEL_MARGIN = 28.0
private const val LEVEL_PITCH = 12.0
// Two plain curves that would pass too near each other turn at levels of their own as well:
// nearer than LEVEL_CLEAR where one of them runs far across, since it stays near the other for
// a long way, and otherwise nearer than LEVEL_TOUCH. LEVEL_LEAD is the room a plain curve is
// taken to keep straight at each end of its gap.
private const val LEVEL_LEAD = 12.0
private const val LEVEL_CLEAR = 8.0
private const val LEVEL_TOUCH = 4.0
// Levels tell apart curves that run alongside each other. Where the curves of a gap cross each
// other more often than that, runs along levels only make the crossings long and shallow, so
// each curve is one plain S instead, and the gap is made this many times as tall as its widest
// curve runs across (and no taller than MAX_GAP allows), for them to cross at a clear angle.
private const val CROSSED_SLOPE = 0.5
// How far out of line two points may be and still count as in line.
private const val IN_LINE = 0.5
// How far apart two edges may be at both ends and still count as twins.
private const val TWIN = 1.0
// A node, with whatever it is already in line with, moves this far at most to bring one more of
// its edges into line.
private const val SNAP = 12.0
// Bends with no node in line with them move this far at most to bring an edge into line.
private const val BEND_SNAP = 24.0
// An end that cannot be brought into line leans towards where its edge goes, but keeps this far
// from the ends of the part of its side that edges spread along. On a narrow node that leaves
// it in the middle.
private const val LEAN_CLEAR = 40.0
// Past this many edges across one gap, tracks are given out in a plain order: weighing every
// pair of edges against each other would take too long.
private const val MAX_WEIGHED = 400
// Reuse curve-sampling buffers across layouts.
private const val SAMPLES = 16
// PORT: LINE_A and LINE_B are allocated per call in assignTracks, as module-level buffers would be shared between threads.

private object End {
  const val Held = 0
  const val Leaves = 1
  const val Arrives = 2
}

private fun isDummy(kind: Int): Boolean = kind == Kind.Dummy || kind == Kind.Label

private fun sortRange(list: IntArray, from: Int, to: Int, key: IntArray) {
  for (i in from + 1 until to) {
    val item = list[i]
    val k = key[item]
    var j = i - 1
    while (j >= from && (key[list[j]] > k || (key[list[j]] == k && list[j] > item))) {
      list[j + 1] = list[j]
      j--
    }
    list[j + 1] = item
  }
}

// Sorts list[from, to) with a comparator, stably, as a typed array's sort does.
private fun sortSub(list: IntArray, from: Int, to: Int, compare: (Int, Int) -> Int) {
  val part = list.copyOfRange(from, to).toMutableList()
  part.sortWith(compare)
  for (i in part.indices) list[from + i] = part[i]
}

// Avoid the comparator overhead of built-in sort.
private fun sortByBary(list: IntArray, count: Int, bary: DoubleArray, pos: IntArray, right: Boolean, spare: IntArray) {
  val before = { a: Int, b: Int -> bary[a] < bary[b] || (bary[a] == bary[b] && (if (right) pos[a] > pos[b] else pos[a] < pos[b])) }
  val run = 8
  var s = 0
  while (s < count) {
    var i = s + 1
    val end = min(count, s + run)
    while (i < end) {
      val item = list[i]
      var j = i - 1
      while (j >= s && before(item, list[j])) {
        list[j + 1] = list[j]
        j--
      }
      list[j + 1] = item
      i++
    }
    s += run
  }
  var from = list
  var to = spare
  var width = run
  while (width < count) {
    var s2 = 0
    while (s2 < count) {
      val mid = min(count, s2 + width)
      val end = min(count, s2 + 2 * width)
      var i = s2
      var j = mid
      var k = s2
      while (i < mid && j < end) to[k++] = if (before(from[j], from[i])) from[j++] else from[i++]
      while (i < mid) to[k++] = from[i++]
      while (j < end) to[k++] = from[j++]
      s2 += 2 * width
    }
    val t = from
    from = to
    to = t
    width *= 2
  }
  if (from !== list) for (i in 0 until count) list[i] = from[i]
}

fun layered(nodes: List<LNode>, edges: List<LEdge>, opt: LayeredOptions): LayeredResult {
  val real = nodes.size
  if (real == 0) return LayeredResult(0.0, 0.0)
  val m = edges.size
  if (real == 1 && m == 0 && nodes[0].kind == Kind.Node) {
    val node = nodes[0]
    node.rank = 0
    node.x = node.w / 2
    node.y = node.h / 2
    return LayeredResult(node.w, node.h)
  }

  // Every edge gets a middle rank of its own when labels or parallel edges need the room.
  var step = 1
  if (m > 0) {
    val seen = HashSet<Long>()
    for (e in edges) {
      val a = min(e.tail, e.head)
      val b = max(e.tail, e.head)
      val key = a.toLong() * real + b
      if (e.labelW > 0 || seen.contains(key)) {
        step = 2
        break
      }
      seen.add(key)
    }
  }

  orient(nodes, edges)
  rank(nodes, edges, step)

  val span = IntArray(m)
  var dummies = 0
  for (ei in 0 until m) {
    val e = edges[ei]
    span[ei] = abs(nodes[e.head].rank - nodes[e.tail].rank)
    if (span[ei] > 1) dummies += span[ei] - 1
  }
  val direct = IntArray(m)
  if (dummies > MAX_DUMMIES) {
    val longest = ArrayList<Int>()
    for (ei in 0 until m) if (span[ei] > 1) longest.add(ei)
    longest.sortWith { a, b -> if (span[b] - span[a] != 0) span[b] - span[a] else a - b }
    for (ei in longest) {
      if (dummies <= MAX_DUMMIES) break
      direct[ei] = 1
      dummies -= span[ei] - 1
    }
  }

  val n = real + dummies
  val W = DoubleArray(n)
  val H = DoubleArray(n)
  val KIND = IntArray(n)
  val RANK = IntArray(n)
  var maxRank = 0
  for (i in 0 until real) {
    val node = nodes[i]
    W[i] = node.w
    H[i] = node.h
    KIND[i] = node.kind
    RANK[i] = node.rank
    if (node.rank > maxRank) maxRank = node.rank
  }

  // The graph that gets ordered and positioned: every edge cut into rank-to-rank segments.
  var segments = 0
  for (ei in 0 until m) if (direct[ei] == 0) segments += span[ei]
  val EF = IntArray(segments)
  val ET = IntArray(segments)
  val EFD = DoubleArray(segments)
  val ETD = DoubleArray(segments)
  val firstDummy = IntArray(m) { -1 }
  // Which way the edges with a label in each rank head: 1 down, 2 up, 3 both.
  val heads = IntArray(maxRank + 1)
  val labelNode = IntArray(m) { -1 }
  var spans = false
  for (i in 0 until real) if (nodes[i].span > 0 && nodes[i].kind == Kind.Node) spans = true
  // What each segment's end is to the node it meets, for the ends that may move along the node's side.
  val EFK = if (spans) IntArray(segments) else null
  val ETK = if (spans) IntArray(segments) else null
  val firstSeg = IntArray(m) { -1 }
  val lastSeg = IntArray(m) { -1 }
  val KEY = DoubleArray(n) { Double.NaN }
  for (i in 0 until real) KEY[i] = nodes[i].key
  var offsets = false
  run {
    var next = real
    var ce = 0
    for (ei in 0 until m) {
      if (direct[ei] != 0) continue
      val e = edges[ei]
      val a = if (e.reversed) e.head else e.tail
      val b = if (e.reversed) e.tail else e.head
      val aDx = if (e.reversed) e.headDx else e.tailDx
      val bDx = if (e.reversed) e.tailDx else e.headDx
      if (aDx != 0.0 || bDx != 0.0) offsets = true
      val ra = RANK[a]
      val rb = RANK[b]
      var labelRank = -1
      if (e.labelW > 0) {
        // The label goes halfway along. An edge that passes ranks of nodes on its way has its
        // label in one of those, beside the nodes, where it takes no height of its own: the one
        // halfway, or of the two either side of halfway, the one nearer the node the edge starts
        // from, which is the lower when the edge runs against the flow. An edge between two
        // neighbouring ranks has its label in the rank between them.
        labelRank = ra + ((rb - ra) shr 1)
        if (step == 2 && rb - ra >= 4) {
          if ((labelRank and 1) == 1) labelRank += if (e.reversed) 1 else -1
        } else if (step == 2 && (labelRank and 1) == 0) labelRank += if (e.reversed) 1 else -1
      }
      var prev = a
      if (rb - ra > 1) firstDummy[ei] = next
      firstSeg[ei] = ce
      if (EFK != null && !(if (e.reversed) e.headFixed else e.tailFixed)) EFK[ce] = if (e.reversed) End.Arrives else End.Leaves
      for (r in ra + 1 until rb) {
        val d = next++
        RANK[d] = r
        KEY[d] = KEY[a] + ((KEY[b] - KEY[a]) * (r - ra)) / (rb - ra)
        if (r == labelRank) {
          W[d] = e.labelW
          H[d] = e.labelH
          KIND[d] = Kind.Label
          labelNode[ei] = d
          heads[r] = heads[r] or (if (e.reversed) 2 else 1)
        } else {
          KIND[d] = Kind.Dummy
        }
        EF[ce] = prev
        ET[ce] = d
        EFD[ce] = if (prev == a) aDx else 0.0
        ce++
        prev = d
      }
      EF[ce] = prev
      ET[ce] = b
      EFD[ce] = if (prev == a) aDx else 0.0
      ETD[ce] = bDx
      if (ETK != null && !(if (e.reversed) e.tailFixed else e.headFixed)) ETK[ce] = if (e.reversed) End.Leaves else End.Arrives
      lastSeg[ei] = ce
      ce++
    }
  }

  // Bends belong to the innermost group containing both edge endpoints.
  var GRP: IntArray? = null
  if (opt.groups != null) {
    val of = opt.groups.of
    val parent = opt.groups.parent
    val grp = IntArray(n)
    GRP = grp
    for (i in 0 until real) grp[i] = of[i]
    val depth = { grIn: Int ->
      var gr = grIn
      var d = 0
      while (gr >= 0) {
        d++
        gr = parent[gr]
      }
      d
    }
    val lca = { xIn: Int, yIn: Int ->
      var x = xIn
      var y = yIn
      var dx = depth(x)
      var dy = depth(y)
      while (dx > dy) {
        x = parent[x]
        dx--
      }
      while (dy > dx) {
        y = parent[y]
        dy--
      }
      while (x != y) {
        x = parent[x]
        y = parent[y]
      }
      x
    }
    for (ei in 0 until m) {
      if (firstDummy[ei] < 0) continue
      val e = edges[ei]
      val gr = lca(of[e.tail], of[e.head])
      val end = firstDummy[ei] + span[ei] - 1
      for (d in firstDummy[ei] until end) grp[d] = gr
    }
  }

  val succStart = IntArray(n + 1)
  val predStart = IntArray(n + 1)
  for (e in 0 until segments) {
    succStart[EF[e] + 1]++
    predStart[ET[e] + 1]++
  }
  for (i in 0 until n) {
    succStart[i + 1] += succStart[i]
    predStart[i + 1] += predStart[i]
  }
  val succE = IntArray(segments)
  val predE = IntArray(segments)
  run {
    val sf = succStart.copyOfRange(0, n)
    val pf = predStart.copyOfRange(0, n)
    for (e in 0 until segments) {
      succE[sf[EF[e]]++] = e
      predE[pf[ET[e]]++] = e
    }
  }

  val layerStart = IntArray(maxRank + 2)
  for (i in 0 until n) layerStart[RANK[i] + 1]++
  for (r in 0..maxRank) layerStart[r + 1] += layerStart[r]
  val order = IntArray(n)
  val pos = IntArray(n)

  // Initial order: depth-first from the top so that subtrees start out contiguous.
  run {
    val byRank = IntArray(n)
    val fill = layerStart.copyOfRange(0, maxRank + 1)
    for (i in 0 until n) byRank[fill[RANK[i]]++] = i
    for (r in 0..maxRank) fill[r] = layerStart[r]
    val visited = IntArray(n)
    val stack = ArrayList<Int>()
    for (k in 0 until n) {
      if (visited[byRank[k]] != 0) continue
      stack.add(byRank[k])
      while (stack.size > 0) {
        val v = stack.removeAt(stack.size - 1)
        if (visited[v] != 0) continue
        visited[v] = 1
        order[fill[RANK[v]]++] = v
        var j = succStart[v + 1] - 1
        while (j >= succStart[v]) {
          val w = ET[succE[j]]
          if (visited[w] == 0) stack.add(w)
          j--
        }
      }
    }
  }

  val sortRank = fun(r: Int, key: (Int) -> Double): Boolean {
    val s = layerStart[r]
    val e = layerStart[r + 1]
    if (e - s < 2) return false
    for (i in s until e) if (key(order[i]).isNaN()) return false
    val layer = order.copyOfRange(s, e).toMutableList()
    val index = HashMap<Int, Int>()
    layer.forEachIndexed { i, v -> index[v] = i }
    layer.sortWith { a, b -> cmp((key(a) - key(b)).jsOr { (index[a]!! - index[b]!!).toDouble() }) }
    for (i in layer.indices) order[s + i] = layer[i]
    return true
  }
  for (r in 0..maxRank) sortRank(r) { v -> KEY[v] }

  var pinned = false
  for (i in 0 until real) if (nodes[i].pin != 0) pinned = true
  if (pinned) {
    for (r in 0..maxRank) {
      val layer = order.copyOfRange(layerStart[r], layerStart[r + 1]).toMutableList()
      val index = HashMap<Int, Int>()
      layer.forEachIndexed { i, v -> index[v] = i }
      val pinOf = { v: Int -> if (v < real) nodes[v].pin else 0 }
      layer.sortWith { a, b -> if (pinOf(a) - pinOf(b) != 0) pinOf(a) - pinOf(b) else index[a]!! - index[b]!! }
      for (i in layer.indices) order[layerStart[r] + i] = layer[i]
    }
  }
  // Preserve port order to avoid crossings outside the group.
  val fixed = IntArray(maxRank + 1)
  val hint = { v: Int -> if (v < real && (nodes[v].kind == Kind.StartPort || nodes[v].kind == Kind.EndPort)) nodes[v].hint else Double.NaN }
  for (r in intArrayOf(0, maxRank)) if (sortRank(r, hint)) fixed[r] = 1
  val setPositions = {
    for (r in 0..maxRank) {
      val s = layerStart[r]
      val e = layerStart[r + 1]
      for (i in s until e) pos[order[i]] = i - s
    }
  }
  setPositions()

  val g = Graph(n, real, maxRank, W, KIND, RANK, EF, ET, EFD, ETD, succStart, succE, predStart, predE, layerStart, order, pos, offsets, fixed, GRP, opt.groups?.parent)

  var crossings = 0
  var declared = 0
  if (segments > 0 && maxRank > 0) {
    val counted = reduceCrossings(g, nodes)
    crossings = counted[0]
    declared = counted[1]
    setPositions()
  }
  if (opt.orderOnly == true) {
    // Points are (rank, cross-flow position) pairs.
    val at = position(g, opt)
    for (i in 0 until real) nodes[i].x = at[i]
    for (ei in 0 until m) {
      val e = edges[ei]
      val a = if (e.reversed) e.head else e.tail
      val b = if (e.reversed) e.tail else e.head
      val pts = arrayListOf(RANK[a].toDouble(), at[a])
      if (direct[ei] == 0 && firstDummy[ei] >= 0) {
        val end = firstDummy[ei] + span[ei] - 1
        for (d in firstDummy[ei] until end) {
          pts.add(RANK[d].toDouble())
          pts.add(at[d])
        }
      }
      pts.add(RANK[b].toDouble())
      pts.add(at[b])
      e.points = pts
    }
    return LayeredResult(0.0, 0.0, crossings, declared)
  }

  if (EFK != null && ETK != null) spread(g, nodes, EFK, ETK)

  val xs = position(g, opt)

  val empty = emptyRanks(g)
  if (EFK != null && ETK != null) {
    val free = freeEnds(g, EFK, ETK)
    alignLabels(g, nodes, xs, free, opt, empty)
    settle(g, nodes, xs, EFK, ETK, free, empty)
    snap(g, xs, empty, opt)
    for (ei in 0 until m) {
      if (direct[ei] != 0) continue
      val e = edges[ei]
      val aDx = EFD[firstSeg[ei]]
      val bDx = ETD[lastSeg[ei]]
      e.tailDx = if (e.reversed) bDx else aDx
      e.headDx = if (e.reversed) aDx else bDx
    }
  }

  alignPorts(g, xs, empty)

  // A rank that holds nothing but the bends of edges is only there because some other edge has a
  // label or a twin. Its bends move onto the straight line between the ends of their edges, and
  // one that gets there is left out of the route, so that the edge is drawn as a single curve.
  val onLine = IntArray(n)
  if (empty != null && opt.tracks != true) straighten(g, xs, empty, firstDummy, span, if (opt.apart == true) 0.0 else opt.edgeSep, onLine)
  if (opt.tracks != true) pastLabels(g, xs, firstDummy, span, opt.edgeSep, onLine)
  // An edge is cut where it passes a rank that holds something, and each piece crosses one gap.
  // With `tracks`, every piece that does not run straight gets a track to run across on at
  // right angles. Otherwise, where edges spread along their nodes, a piece gets one only if it
  // needs a level to turn onto. `pieceStart` gives every edge's run of pieces.
  val pieceStart = IntArray(m + 1)
  val pieces: Pieces? = if (opt.tracks == true || (EFK != null && opt.straight != true)) Pieces() else null
  val trackCount = IntArray(maxRank + 1)
  if (pieces != null) {
    for (ei in 0 until m) {
      pieceStart[ei] = pieces.from.size
      if (direct[ei] != 0) continue
      val d0 = firstDummy[ei]
      // An edge that keeps a bend to stay clear of its twin is drawn through the bend, as it is.
      var twin = false
      run {
        var d = d0
        val end = if (d0 < 0) d0 else d0 + span[ei] - 1
        while (d < end) {
          // So is one that has a bend left out in a rank of labels: its curve crosses that rank.
          val bare = empty != null && empty[RANK[d]] == 1
          if (opt.tracks != true && bare != (onLine[d] == 1)) twin = true
          d++
        }
      }
      if (twin) continue
      var x = xs[EF[firstSeg[ei]]] + EFD[firstSeg[ei]]
      var rank = RANK[EF[firstSeg[ei]]]
      var d = d0
      val end = if (d0 < 0) d0 else d0 + span[ei] - 1
      while (d < end) {
        if (empty != null && empty[RANK[d]] != 0) {
          d++
          continue
        }
        pieces.from.add(x)
        pieces.to.add(xs[d])
        pieces.rank.add(rank)
        pieces.lower.add(d)
        x = xs[d]
        rank = RANK[d]
        d++
      }
      pieces.from.add(x)
      pieces.to.add(xs[ET[lastSeg[ei]]] + ETD[lastSeg[ei]])
      pieces.rank.add(rank)
      pieces.lower.add(-1)
    }
    pieceStart[m] = pieces.from.size
  }

  // Ranks that hold only border ports or their pass-through dummies need little room of their own.
  val portOnly = fun(r: Int, kind: Int): Boolean {
    var found = false
    for (i in layerStart[r] until layerStart[r + 1]) {
      val k = KIND[order[i]]
      if (k == kind) found = true
      else if (k != Kind.Dummy) return false
    }
    return found
  }
  val startRanks = if (portOnly(0, Kind.StartPort)) step else 0
  val endRanks = if (portOnly(maxRank, Kind.EndPort)) step else 0
  // The longest way across that any edge travels in the gap below each rank.
  val run = DoubleArray(maxRank + 1)
  for (e in 0 until segments) {
    val across = abs(xs[EF[e]] + EFD[e] - xs[ET[e]] - ETD[e])
    if (across > run[RANK[EF[e]]]) run[RANK[EF[e]]] = across
  }
  val bandTop = DoubleArray(maxRank + 1)
  val bandBottom = DoubleArray(maxRank + 1)
  val usual = { r: Int -> (if (r < startRanks || r >= maxRank - endRanks) opt.portSep else opt.rankSep) / step }
  // The room under each rank. Edges that are not cut into pieces need more where they run far across.
  val gapUnder = DoubleArray(maxRank + 1)
  for (r in 0 until maxRank) {
    gapUnder[r] = if (pieces != null) usual(r) else max(usual(r), min((MAX_GAP * opt.rankSep) / step, run[r] * MIN_SLOPE))
  }
  // A rank between two ranks of nodes, which holds labels and bends only.
  val between = IntArray(maxRank + 1) { 1 }
  for (i in 0 until real) between[RANK[i]] = 0
  for (r in 1 until maxRank) {
    if (opt.tracks == true || between[r] == 0 || (heads[r] != 1 && heads[r] != 2)) continue
    val shift = min((opt.headRoom ?: 0.0) / 2, gapUnder[if (heads[r] == 1) r - 1 else r] / 2)
    gapUnder[r - 1] += if (heads[r] == 1) -shift else shift
    gapUnder[r] += if (heads[r] == 1) shift else -shift
  }
  // The part of its rank that an edge runs straight through at a bend or label. A bare bend
  // beside labels keeps to the middle half, which leaves the curves either side of it more room
  // and still keeps them clear of the labels' corners.
  val inset = { d: Int -> if (KIND[d] == Kind.Dummy && between[RANK[d]] != 0 && opt.tracks != true) (bandBottom[RANK[d]] - bandTop[RANK[d]]) / 4 else 0.0 }
  // The rank that the pieces leaving each rank arrive at, past any that hold only bends, and
  // the room between the two, which grows to hold the gap's tracks or levels: these keep
  // `margin` from the ranks, and between `leastPitch` and `mostPitch` from each other.
  val margin = if (opt.tracks == true) TRACK_MARGIN else LEVEL_MARGIN
  val leastPitch = if (opt.tracks == true) TRACK_PITCH else LEVEL_PITCH
  val mostPitch = if (opt.tracks == true) TRACK_SPREAD else LEVEL_PITCH
  val below = IntArray(maxRank + 1)
  if (pieces != null) {
    val room = DoubleArray(maxRank + 1)
    for (r in 0 until maxRank) {
      if (empty != null && empty[r] != 0) continue
      var next = r + 1
      room[r] = gapUnder[r]
      while (next < maxRank && empty != null && empty[next] != 0) room[r] += gapUnder[next++]
      below[r] = next
    }
    for (k in 0 until pieces.from.size) pieces.wide.add(if (abs(pieces.from[k] - pieces.to[k]) > LEVEL_WIDE * room[pieces.rank[k]]) 1 else 0)
    val tall = DoubleArray(maxRank + 1)
    assignTracks(pieces, trackCount, opt.tracks != true, room, tall)
    for (r in 0 until maxRank) {
      if (trackCount[r] > 0) gapUnder[r] += max(0.0, 2 * margin + (trackCount[r] - 1) * leastPitch - room[r])
      else if (tall[r] > 0) gapUnder[r] += max(0.0, min(MAX_GAP * opt.rankSep, tall[r]) - room[r])
    }
  }
  var y = 0.0
  for (r in 0..maxRank) {
    var h = 0.0
    for (i in layerStart[r] until layerStart[r + 1]) if (H[order[i]] > h) h = H[order[i]]
    bandTop[r] = y
    bandBottom[r] = y + h
    y += h + gapUnder[r]
  }
  val height = y
  // Where a piece runs across its gap, or would on another track of its group: its tracks sit
  // in the middle of the gap, the first at the top.
  val trackY = fun(piece: Int, onTrack: Int): Double {
    val r = pieces!!.rank[piece]
    val top = bandBottom[r]
    val bottom = bandTop[below[r]]
    val count = if (opt.tracks == true) trackCount[r] else pieces.levels[piece]
    val pitch = if (count > 1) min(mostPitch, (bottom - top - 2 * margin) / (count - 1)) else 0.0
    return (top + bottom) / 2 + (onTrack - (count - 1) / 2.0) * pitch
  }

  var minX = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  for (i in 0 until n) {
    val half = W[i] / 2
    if (xs[i] - half < minX) minX = xs[i] - half
    if (xs[i] + half > maxX) maxX = xs[i] + half
  }
  // Where edges spread along their nodes, a rank whose nodes only send edges on stands on one
  // line, and a rank whose nodes only receive them hangs from one, so that the edges of nodes of
  // different heights set out from, or arrive at, the same level. In any other rank the nodes
  // are centered.
  val arrives = IntArray(maxRank + 1)
  val leaves = IntArray(maxRank + 1)
  for (i in 0 until real) {
    if (KIND[i] != Kind.Node || EFK == null) continue
    if (predStart[i + 1] > predStart[i]) arrives[RANK[i]] = 1
    if (succStart[i + 1] > succStart[i]) leaves[RANK[i]] = 1
  }
  for (i in 0 until real) {
    val node = nodes[i]
    val r = node.rank
    node.x = xs[i] - minX
    if (KIND[i] == Kind.Node && leaves[r] != 0 && arrives[r] == 0) node.y = bandBottom[r] - node.h / 2
    else if (KIND[i] == Kind.Node && arrives[r] != 0 && leaves[r] == 0) node.y = bandTop[r] + node.h / 2
    else node.y = (bandTop[r] + bandBottom[r]) / 2
  }

  for (ei in 0 until m) {
    val e = edges[ei]
    val first = nodes[if (e.reversed) e.head else e.tail]
    val last = nodes[if (e.reversed) e.tail else e.head]
    val pts = arrayListOf(first.x + (if (e.reversed) e.headDx else e.tailDx), first.y + first.h / 2)
    val d0 = firstDummy[ei]
    if (pieces != null && pieceStart[ei + 1] > pieceStart[ei]) {
      for (k in pieceStart[ei] until pieceStart[ei + 1]) {
        if (pieces.track[k] >= 0 && (opt.tracks == true || pieces.wide[k] == 1)) {
          val across = trackY(k, pieces.track[k])
          pts.add(pieces.from[k] - minX)
          pts.add(across)
          pts.add(pieces.to[k] - minX)
          pts.add(across)
        } else if (pieces.track[k] > 0) {
          // A short piece among levels runs straight until it is past the level before its
          // own, and turns in one S from there, so that it crosses none of them.
          pts.add(pieces.from[k] - minX)
          pts.add(trackY(k, pieces.track[k] - 1))
        }
        val d = pieces.lower[k]
        if (d < 0) continue
        pts.add(xs[d] - minX)
        pts.add(bandTop[RANK[d]] + inset(d))
        if (bandBottom[RANK[d]] > bandTop[RANK[d]]) {
          pts.add(xs[d] - minX)
          pts.add(bandBottom[RANK[d]] - inset(d))
        }
      }
    } else if (d0 >= 0) {
      val end = d0 + span[ei] - 1
      for (d in d0 until end) {
        if (onLine[d] != 0) continue
        val x = xs[d] - minX
        val top = bandTop[RANK[d]]
        val bottom = bandBottom[RANK[d]]
        pts.add(x)
        pts.add(top + inset(d))
        if (bottom > top) {
          pts.add(x)
          pts.add(bottom - inset(d))
        }
      }
    }
    pts.add(last.x + (if (e.reversed) e.tailDx else e.headDx))
    pts.add(last.y - last.h / 2)
    if (e.reversed) {
      var i = 0
      var j = pts.size - 2
      while (i < j) {
        val tx = pts[i]
        val ty = pts[i + 1]
        pts[i] = pts[j]
        pts[i + 1] = pts[j + 1]
        pts[j] = tx
        pts[j + 1] = ty
        i += 2
        j -= 2
      }
    }
    e.points = pts
    val ln = labelNode[ei]
    if (ln >= 0) {
      e.labelX = xs[ln] - minX
      e.labelY = (bandTop[RANK[ln]] + bandBottom[RANK[ln]]) / 2
    } else if (e.labelW > 0) {
      e.labelX = (pts[0] + pts[pts.size - 2]) / 2
      e.labelY = (pts[1] + pts[pts.size - 1]) / 2
    }
  }

  return LayeredResult(maxX - minX, height)
}

// The pieces of edges that each cross one gap: where they leave the rank above and reach the
// one below, the rank above, the bend they end at (or -1 at the edge's last node), and the
// track they run across on (or -1 when they have none). Where only some pieces are given a
// level to turn onto, `wide` marks those that run far across, `levels` is how many tracks the
// group a piece belongs to takes, or 0 for a piece that needs none, and each piece's track
// counts from its group's first. A piece that is not `wide` curves from the level above its own.
private class Pieces(
  val from: MutableList<Double> = ArrayList(),
  val to: MutableList<Double> = ArrayList(),
  val rank: MutableList<Int> = ArrayList(),
  val lower: MutableList<Int> = ArrayList(),
  val track: MutableList<Int> = ArrayList(),
  val levels: MutableList<Int> = ArrayList(),
  val wide: MutableList<Int> = ArrayList(),
)

private class Graph(
  val n: Int,
  val real: Int,
  val maxRank: Int,
  val W: DoubleArray,
  val KIND: IntArray,
  val RANK: IntArray,
  // Segment endpoints, and where each segment attaches on them.
  val EF: IntArray,
  val ET: IntArray,
  val EFD: DoubleArray,
  val ETD: DoubleArray,
  val succStart: IntArray,
  val succE: IntArray,
  val predStart: IntArray,
  val predE: IntArray,
  val layerStart: IntArray,
  val order: IntArray,
  val pos: IntArray,
  val offsets: Boolean,
  // Ranks whose order must remain fixed.
  val fixed: IntArray,
  val GRP: IntArray?,
  val groupParent: IntArray?,
)

// Reverses the edges that close a cycle, found by depth-first search in declaration order.
fun orient(nodes: List<LNode>, edges: List<LEdge>) {
  val n = nodes.size
  val m = edges.size
  val start = IntArray(n + 1)
  for (i in 0 until m) {
    val e = edges[i]
    e.reversed = nodes[e.head].kind == Kind.StartPort || nodes[e.tail].kind == Kind.EndPort
    if (!e.reversed) start[e.tail + 1]++
  }
  for (i in 0 until n) start[i + 1] += start[i]
  val out = IntArray(start[n])
  run {
    val fill = start.copyOfRange(0, n)
    for (i in 0 until m) if (!edges[i].reversed) out[fill[edges[i].tail]++] = i
  }
  val state = IntArray(n)
  val cursor = start.copyOfRange(0, n)
  val stack = ArrayList<Int>()
  for (root in 0 until n) {
    if (state[root] != 0) continue
    stack.add(root)
    state[root] = 1
    while (stack.size > 0) {
      val v = stack[stack.size - 1]
      if (cursor[v] < start[v + 1]) {
        val e = edges[out[cursor[v]++]]
        val w = e.head
        if (state[w] == 1) e.reversed = true
        else if (state[w] == 0) {
          state[w] = 1
          stack.add(w)
        }
      } else {
        state[v] = 2
        stack.removeAt(stack.size - 1)
      }
    }
  }
}

fun rank(nodes: List<LNode>, edges: List<LEdge>, step: Int) {
  val n = nodes.size
  val m = edges.size
  val from = IntArray(m)
  val to = IntArray(m)
  val len = IntArray(m)
  val outStart = IntArray(n + 1)
  val inStart = IntArray(n + 1)
  for (i in 0 until m) {
    val e = edges[i]
    val a = if (e.reversed) e.head else e.tail
    val b = if (e.reversed) e.tail else e.head
    from[i] = a
    to[i] = b
    len[i] = pele.js.toInt32(max(1.0, e.minlen) * step)
    outStart[a + 1]++
    inStart[b + 1]++
  }
  for (i in 0 until n) {
    outStart[i + 1] += outStart[i]
    inStart[i + 1] += inStart[i]
  }
  val outE = IntArray(m)
  val inE = IntArray(m)
  run {
    val of = outStart.copyOfRange(0, n)
    val inf = inStart.copyOfRange(0, n)
    for (i in 0 until m) {
      outE[of[from[i]]++] = i
      inE[inf[to[i]]++] = i
    }
  }

  val r = IntArray(n)
  val indeg = IntArray(n)
  for (i in 0 until n) indeg[i] = inStart[i + 1] - inStart[i]
  val topo = IntArray(n)
  var count = 0
  for (i in 0 until n) if (indeg[i] == 0) topo[count++] = i
  var q = 0
  while (q < count) {
    val v = topo[q]
    for (k in outStart[v] until outStart[v + 1]) {
      val ei = outE[k]
      val w = to[ei]
      if (r[v] + len[ei] > r[w]) r[w] = r[v] + len[ei]
      if (--indeg[w] == 0) topo[count++] = w
    }
    q++
  }

  // Longest-path ranking leaves loosely attached nodes far from their neighbours.
  // Move each node toward the side that has more edges.
  for (pass in 0 until 8) {
    var changed = false
    for (qq in count - 1 downTo 0) {
      val v = topo[qq]
      val ins = inStart[v + 1] - inStart[v]
      val outs = outStart[v + 1] - outStart[v]
      if (ins == 0 && outs == 0) continue
      var lo = Double.NEGATIVE_INFINITY
      var hi = Double.POSITIVE_INFINITY
      for (k in inStart[v] until inStart[v + 1]) {
        val ei = inE[k]
        lo = max(lo, (r[from[ei]] + len[ei]).toDouble())
      }
      for (k in outStart[v] until outStart[v + 1]) {
        val ei = outE[k]
        hi = min(hi, (r[to[ei]] - len[ei]).toDouble())
      }
      var next = r[v]
      if (outs > ins && hi != Double.POSITIVE_INFINITY) next = hi.toInt()
      else if (ins > outs && lo != Double.NEGATIVE_INFINITY) next = lo.toInt()
      if (next != r[v]) {
        r[v] = next
        changed = true
      }
    }
    if (!changed) break
  }

  var min = Int.MAX_VALUE
  var max = Int.MIN_VALUE
  var any = false
  var startPorts = false
  for (i in 0 until n) {
    val kind = nodes[i].kind
    if (kind == Kind.StartPort) startPorts = true
    if (kind == Kind.StartPort || kind == Kind.EndPort) continue
    any = true
    if (r[i] < min) min = r[i]
    if (r[i] > max) max = r[i]
  }
  if (!any) {
    min = 0
    max = 0
  }
  for (i in 0 until n) {
    if (nodes[i].kind == Kind.StartPort) r[i] = min - step
    else if (nodes[i].kind == Kind.EndPort) r[i] = max + step
  }
  if (startPorts) min -= step
  for (i in 0 until n) nodes[i].rank = r[i] - min
}

private fun <T> setAt(list: MutableList<T>, i: Int, value: T) {
  if (i == list.size) list.add(value) else list[i] = value
}

// The ends of edges that may move along one side of a node (0 the side its edges arrive at, 1 the
// other), ordered by their opposite nodes to avoid crossings. `ids` and `room` must start empty.
// `ids` receives the ends; `room` receives their minimum gaps. Returns the sum of the gaps.
// When there is too little room for a full pitch between each pair, the edges that leave side by
// side move closer together, since they have no arrowheads to keep apart. With less room still,
// neighbours that run the same way share a place, which keeps an arrowhead apart from an edge
// that leaves beside it.
private fun sideEnds(g: Graph, v: Int, side: Int, span: Double, kinds: IntArray, ids: MutableList<Int>, room: MutableList<Double>): Double {
  val start = if (side == 0) g.predStart else g.succStart
  val list = if (side == 0) g.predE else g.succE
  val other = if (side == 0) g.EF else g.ET
  val pos = g.pos
  for (k in start[v] until start[v + 1]) if (kinds[list[k]] != End.Held) ids.add(list[k])
  if (ids.size < 2) return 0.0
  ids.sortWith { a, b -> if (pos[other[a]] - pos[other[b]] != 0) pos[other[a]] - pos[other[b]] else a - b }
  val fill = { share: (Boolean, Boolean) -> Double ->
    var total = 0.0
    setAt(room, 0, 0.0)
    for (k in 1 until ids.size) {
      val same = kinds[ids[k]] == kinds[ids[k - 1]]
      setAt(room, k, share(same && kinds[ids[k]] == End.Leaves, same))
      total += room[k]
    }
    total
  }
  var total = fill { _, _ -> 1.0 }
  if (span / total < MIN_PITCH) total = fill { leaves, _ -> if (leaves) 0.5 else 1.0 }
  if (span / total < MIN_PITCH) total = fill { _, same -> if (same) 0.0 else 1.0 }
  if (total == 0.0) return 0.0
  val pitch = min(PORT_PITCH, span / total)
  for (k in 1 until ids.size) room[k] *= pitch
  return total * pitch
}

// Gives the edges that meet one side of a node places of their own along it, around its middle.
private fun spread(g: Graph, nodes: List<LNode>, EFK: IntArray, ETK: IntArray) {
  val real = g.real
  val W = g.W
  val EFD = g.EFD
  val ETD = g.ETD
  for (v in 0 until real) {
    val span = min(nodes[v].span, W[v])
    if (span <= 0 || nodes[v].kind != Kind.Node) continue
    for (side in 0 until 2) {
      val ids = ArrayList<Int>()
      val room = ArrayList<Double>()
      val total = sideEnds(g, v, side, span, if (side == 0) ETK else EFK, ids, room)
      if (total == 0.0) continue
      val dx = if (side == 0) ETD else EFD
      var at = -total / 2
      for (k in ids.indices) {
        at += room[k]
        dx[ids[k]] = at
      }
    }
  }
}

// The segment of an edge that reaches the next point held in place, on the way up from segment
// `e` (side 0) or down: past the bends in ranks that hold nothing else.
private fun follow(g: Graph, empty: IntArray?, eIn: Int, side: Int): Int {
  var e = eIn
  if (empty == null) return e
  for (hops in 0 until 100000) {
    val u = if (side == 0) g.EF[e] else g.ET[e]
    if (g.KIND[u] != Kind.Dummy || empty[g.RANK[u]] == 0) break
    e = if (side == 0) g.predE[g.predStart[u]] else g.succE[g.succStart[u]]
  }
  return e
}

// Aligns border ports with the ends on their nodes so edges run straight across the gap.
// Ports are kept `edgeSep` apart while placed, more than ends on a short side get, so
// without this an edge turns outside the border and again in the gap.
// Leaves a rank alone unless every port can move without passing or meeting its neighbors.
private fun alignPorts(g: Graph, xs: DoubleArray, empty: IntArray?) {
  val maxRank = g.maxRank
  for ((r, kind) in listOf(0 to Kind.StartPort, maxRank to Kind.EndPort)) {
    val s = g.layerStart[r]
    val end = g.layerStart[r + 1]
    val to = ArrayList<Double>()
    for (i in s until end) {
      val v = g.order[i]
      val start = kind == Kind.StartPort
      if (g.KIND[v] != kind || (if (start) g.succStart[v + 1] - g.succStart[v] else g.predStart[v + 1] - g.predStart[v]) != 1) break
      val e = follow(g, empty, if (start) g.succE[g.succStart[v]] else g.predE[g.predStart[v]], if (start) 1 else 0)
      val at = if (start) xs[g.ET[e]] + g.ETD[e] else xs[g.EF[e]] + g.EFD[e]
      if (to.size > 0 && at < to[to.size - 1] + TWIN) break
      to.add(at)
    }
    if (to.size == 0 || to.size != end - s) continue
    for (i in s until end) xs[g.order[i]] = to[i - s]
  }
}

// How many ends that may move along it each node has on the side its edges arrive at, and on
// the other. An end that has a side to itself keeps to the middle; ends that share one cannot
// all be there, so those may move.
private fun freeEnds(g: Graph, EFK: IntArray, ETK: IntArray): Array<IntArray> {
  val free = arrayOf(IntArray(g.n), IntArray(g.n))
  for (e in 0 until g.EF.size) {
    if (ETK[e] != End.Held) free[0][g.ET[e]]++
    if (EFK[e] != End.Held) free[1][g.EF[e]]++
  }
  return free
}

// How far along the flow the handles of a plain S reach, as a share of the way it travels along
// it. Half gives an even S. One that runs more than eight times as far across as along turns
// sooner and crosses in a straighter line, down to a quarter at sixteen times. The layout uses
// this to tell how near two curves will pass, and the drawing to draw them.
fun ease(across: Double, along: Double): Double = max(0.25, min(0.5, (4 * abs(along)) / abs(across).jsOr { 1.0 }))

// Brings the labels and bends of each edge into line with an end of the edge, so that it runs
// straight from that end and turns once, at the other: with the end that has fewer edges beside
// it on its node, which is the one nearer the middle of its side, or else with the other, and
// as near to that as what is beside them in their ranks leaves room. The other end may then move
// along its node to meet the line. Bends in ranks that hold nothing else are left to `straighten`.
// An edge with no one line that all of its labels and bends have room on has each brought into
// line with the nearest point above or below that is held in place.
private fun alignLabels(g: Graph, nodes: List<LNode>, xs: DoubleArray, free: Array<IntArray>, opt: LayeredOptions, empty: IntArray?) {
  val sep = opt.edgeSep
  val n = g.n
  val real = g.real
  val maxRank = g.maxRank
  val KIND = g.KIND
  val RANK = g.RANK
  val W = g.W
  val EF = g.EF
  val ET = g.ET
  val EFD = g.EFD
  val ETD = g.ETD
  val succStart = g.succStart
  val succE = g.succE
  val predStart = g.predStart
  val predE = g.predE
  val layerStart = g.layerStart
  val order = g.order
  val pos = g.pos
  val placed = { v: Int -> KIND[v] == Kind.Label || (KIND[v] == Kind.Dummy && !(empty != null && empty[RANK[v]] == 1)) }
  // Where segment `e` ends on the way up (side 0) or down, and how many ends share that side of
  // the node there: none for a point that is not on a node.
  val end = { e: Int, side: Int ->
    val u = if (side == 0) EF[e] else ET[e]
    val x = if (side == 0) xs[u] + EFD[e] else xs[u] + ETD[e]
    doubleArrayOf(x, (if (u < real && nodes[u].kind == Kind.Node) free[1 - side][u] else 0).toDouble())
  }
  // The room the neighbours in its rank leave a label or bend: a full gap from a node or between
  // two labels, half of one between a label and a line that passes it.
  val room = { v: Int ->
    val s = layerStart[RANK[v]]
    val i = s + pos[v]
    val clear = { other: Int -> W[other] / 2 + W[v] / 2 + (if (KIND[other] == Kind.Node) opt.nodeSep / 2 + sep / 2 else if (KIND[other] == KIND[v]) sep else sep / 2) }
    doubleArrayOf(
      if (i > s) xs[order[i - 1]] + clear(order[i - 1]) else Double.NEGATIVE_INFINITY,
      if (i + 1 < layerStart[RANK[v] + 1]) xs[order[i + 1]] - clear(order[i + 1]) else Double.POSITIVE_INFINITY,
    )
  }
  // The place between `lo` and `hi` that is in line with one of two ends, or else nearest the first choice.
  val choose = { x: Double, up: DoubleArray, down: DoubleArray, lo: Double, hi: Double ->
    val upFirst = if (up[1] != down[1]) up[1] < down[1] else abs(up[0] - x) <= abs(down[0] - x)
    val choices = if (upFirst) doubleArrayOf(up[0], down[0]) else doubleArrayOf(down[0], up[0])
    choices.firstOrNull { choice -> choice >= lo && choice <= hi } ?: max(lo, min(hi, choices[0]))
  }
  val done = IntArray(n)
  val chain = ArrayList<Int>()
  for (r in 1 until maxRank) {
    for (i in layerStart[r] until layerStart[r + 1]) {
      if (!placed(order[i]) || done[order[i]] != 0) continue
      // The edge's labels and bends from its first node to its last, and the room they share.
      var top = order[i]
      while (isDummy(KIND[EF[predE[predStart[top]]]])) top = EF[predE[predStart[top]]]
      var bottom = top
      var lo = Double.NEGATIVE_INFINITY
      var hi = Double.POSITIVE_INFINITY
      chain.clear()
      var d = top
      while (isDummy(KIND[d])) {
        bottom = d
        if (placed(d)) {
          done[d] = 1
          chain.add(d)
          val (least, most) = room(d).let { it[0] to it[1] }
          if (least > lo) lo = least
          if (most < hi) hi = most
        }
        d = ET[succE[succStart[d]]]
      }
      if (lo <= hi) {
        val x = choose(xs[chain[0]], end(predE[predStart[top]], 0), end(succE[succStart[bottom]], 1), lo, hi)
        for (c in chain) xs[c] = x
        continue
      }
      for (c in chain) {
        val rm = room(c)
        val least = rm[0]
        val most = rm[1]
        if (least > most) continue
        xs[c] = choose(xs[c], end(follow(g, empty, predE[predStart[c]], 0), 0), end(follow(g, empty, succE[succStart[c]], 1), 1), least, most)
      }
    }
  }
}

private class SideEnds(val v: Int, val side: Int, val span: Double, val ids: MutableList<Int>, val room: MutableList<Double>, val all: MutableList<Int>)

// Once the nodes have their places, moves an end along its side when that lets its edge run
// straight: when the point the edge runs to, or some part of the side it runs to, is in line
// with the end's own side. Any other end leans towards the point its edge runs to, where its
// side is long enough, and an end with a side to itself stays in the middle. On a group, the
// ends keep clear of the points where other edges pass through its border.
private fun settle(g: Graph, nodes: List<LNode>, xs: DoubleArray, EFK: IntArray, ETK: IntArray, free: Array<IntArray>, empty: IntArray?) {
  val real = g.real
  val W = g.W
  val EF = g.EF
  val ET = g.ET
  val EFD = g.EFD
  val ETD = g.ETD
  val succStart = g.succStart
  val succE = g.succE
  val predStart = g.predStart
  val predE = g.predE
  val pos = g.pos
  // Where the end of segment `e` on node `v` should be, from the middle of its side. The edge is
  // followed up from the segment (side 0) or down, past the bends in ranks that hold nothing
  // else, to where it next comes to a point that is held in place or to another node's side.
  val wanted = fun(eIn: Int, side: Int, v: Int, span: Double, again: Boolean): Double {
    if (free[side][v] < 2) return 0.0
    val e = follow(g, empty, eIn, side)
    val u = if (side == 0) EF[e] else ET[e]
    val point = if (side == 0) xs[u] + EFD[e] else xs[u] + ETD[e]
    // Once the other end has its place, this one comes into line with it if it can.
    if (again && abs(point - xs[v]) <= span / 2 + IN_LINE) return max(-span / 2, min(span / 2, point - xs[v]))
    // The part of the other side that its end may move along, if it may move.
    var middle = point
    var width = 0.0
    if (u < real && nodes[u].kind == Kind.Node && (if (side == 0) EFK else ETK)[e] != End.Held && free[1 - side][u] >= 2) {
      width = max(0.0, min(nodes[u].span, W[u]))
      if (width > 0) middle = xs[u]
    }
    val lo = max(xs[v] - span / 2, middle - width / 2)
    val hi = min(xs[v] + span / 2, middle + width / 2)
    // Two sides with no part in line: the end leans towards the other, if its side is long enough.
    if (lo > hi + IN_LINE) {
      val lean = max(0.0, span / 2 - LEAN_CLEAR)
      return max(-lean, min(lean, point - xs[v]))
    }
    // Both ends settle on one place in the part the two sides share: nearer the middle of the narrower.
    val place = (xs[v] * width + middle * span) / (width + span)
    return max(lo, min(hi, place)) - xs[v]
  }
  // End order and spacing stay fixed across passes; held ends divide free ends into runs.
  val sides = ArrayList<SideEnds>()
  for (v in 0 until real) {
    val span = min(nodes[v].span, W[v])
    if (span <= 0 || nodes[v].kind != Kind.Node) continue
    for (side in 0 until 2) {
      val other = if (side == 0) EF else ET
      val start = if (side == 0) predStart else succStart
      val list = if (side == 0) predE else succE
      val ids = ArrayList<Int>()
      val room = ArrayList<Double>()
      sideEnds(g, v, side, span, if (side == 0) ETK else EFK, ids, room)
      if (ids.size == 0) continue
      if (ids.size == 1) setAt(room, 0, 0.0)
      val all = ArrayList<Int>()
      for (k in start[v] until start[v + 1]) all.add(list[k])
      all.sortWith { a, b -> if (pos[other[a]] - pos[other[b]] != 0) pos[other[a]] - pos[other[b]] else a - b }
      sides.add(SideEnds(v, side, span, ids, room, all))
    }
  }
  // The ends of an edge follow each other, so the nodes are gone over until none moves: a few
  // times at most, in practice.
  var pass = 0
  var moved = true
  while (moved && pass < 6) {
    moved = false
    for (sd in sides) {
      val v = sd.v
      val side = sd.side
      val span = sd.span
      val ids = sd.ids
      val room = sd.room
      val all = sd.all
      val kinds = if (side == 0) ETK else EFK
      val dx = if (side == 0) ETD else EFD
      var k = 0
      var lo = -span / 2
      for (i in 0..all.size) {
        val held = i < all.size && kinds[all[i]] == End.Held
        if (i < all.size && !held) continue
        // Free ends since the last held end occupy ids[k..k + size).
        var size = 0
        var jj = i - 1
        while (jj >= 0 && kinds[all[jj]] != End.Held) {
          size++
          jj--
        }
        val hi = if (held) dx[all[i]] - PORT_PITCH else span / 2
        if (size > 0) {
          // Isotonic fitting preserves order and spacing by pooling neighbours that are too close.
          val sum = ArrayList<Double>()
          val count = ArrayList<Int>()
          var before = 0.0
          for (j in 0 until size) {
            if (j > 0) before += room[k + j]
            sum.add(wanted(ids[k + j], side, v, span, pass > 0) - before)
            count.add(1)
            // Keep ends together when spacing forced them to share a place.
            var together = j > 0 && room[k + j] == 0.0
            while (sum.size > 1 && (together || sum[sum.size - 2] / count[count.size - 2] > sum[sum.size - 1] / count[count.size - 1])) {
              together = false
              val top = sum.removeAt(sum.size - 1)
              val pooled = count.removeAt(count.size - 1)
              sum[sum.size - 1] += top
              count[count.size - 1] += pooled
            }
          }
          val total = before
          var j = 0
          before = 0.0
          for (b in sum.indices) {
            // Center the run if it cannot fit between held ends.
            val level = if (hi - total < lo) (lo + hi - total) / 2 else max(lo, min(hi - total, sum[b] / count[b]))
            for (c in 0 until count[b]) {
              if (j > 0) before += room[k + j]
              if (abs(dx[ids[k + j]] - level - before) > 0.01) moved = true
              dx[ids[k + j]] = level + before
              j++
            }
          }
          k += size
        }
        if (held) lo = dx[all[i]] + PORT_PITCH
      }
    }
    pass++
  }
}

// Brings edges that are nearly straight into line, by moving a node or bend a little across the
// flow along with everything it is in line with already, where the nodes beside them leave room.
// What is in line stays in line, so each move leaves one more edge straight.
private fun snap(g: Graph, xs: DoubleArray, empty: IntArray?, opt: LayeredOptions) {
  val n = g.n
  val KIND = g.KIND
  val RANK = g.RANK
  val EF = g.EF
  val ET = g.ET
  val EFD = g.EFD
  val ETD = g.ETD
  val layerStart = g.layerStart
  val order = g.order
  val pos = g.pos
  val passed = { u: Int -> empty != null && KIND[u] == Kind.Dummy && empty[RANK[u]] == 1 }
  // Each edge from one point that is held in place to the next, past the bends in ranks that hold nothing else.
  val a = ArrayList<Int>()
  val b = ArrayList<Int>()
  val da = ArrayList<Double>()
  val db = ArrayList<Double>()
  for (e in 0 until EF.size) {
    if (passed(EF[e])) continue
    val f = follow(g, empty, e, 1)
    a.add(EF[e])
    b.add(ET[f])
    da.add(EFD[e])
    db.add(ETD[f])
  }
  val off = { k: Int -> xs[b[k]] + db[k] - xs[a[k]] - da[k] }
  // The nodes in line with each other move as one.
  val block = IntArray(n)
  val members = ArrayList<MutableList<Int>>()
  for (i in 0 until n) {
    block[i] = i
    members.add(arrayListOf(i))
  }
  val join = fun(x: Int, y: Int) {
    var keep = block[x]
    var drop = block[y]
    if (keep == drop) return
    if (members[keep].size < members[drop].size) {
      val t = keep
      keep = drop
      drop = t
    }
    for (v in members[drop]) {
      block[v] = keep
      members[keep].add(v)
    }
    members[drop] = ArrayList()
  }
  val near = ArrayList<Int>()
  for (k in 0 until a.size) {
    val d = abs(off(k))
    if (d < 0.01) join(a[k], b[k])
    else if (d <= BEND_SNAP) near.add(k)
  }
  if (near.size == 0) return
  near.sortWith { x, y -> cmp((abs(off(x)) - abs(off(y))).jsOr { (x - y).toDouble() }) }
  val gap = { v: Int -> halfRoom(g, opt, v) }
  // Whether every node of a block can move by `shift` and keep its room from those beside it.
  val free = fun(id: Int, shift: Double): Boolean {
    for (v in members[id]) {
      val s = layerStart[RANK[v]]
      val end = layerStart[RANK[v] + 1]
      val i = s + pos[v]
      if (i > s && block[order[i - 1]] != id && xs[v] + shift - xs[order[i - 1]] < gap(v) + gap(order[i - 1]) - 0.01) return false
      if (i + 1 < end && block[order[i + 1]] != id && xs[order[i + 1]] - xs[v] - shift < gap(v) + gap(order[i + 1]) - 0.01) return false
    }
    return true
  }
  for (k in near) {
    val d = off(k)
    if (abs(d) < 0.01) {
      join(a[k], b[k])
      continue
    }
    if (abs(d) > BEND_SNAP || block[a[k]] == block[b[k]]) continue
    // The smaller of the two blocks moves, if it can; else the other.
    val upper = block[a[k]]
    val lower = block[b[k]]
    val first = if (members[upper].size <= members[lower].size) upper else lower
    for (id in intArrayOf(first, if (first == upper) lower else upper)) {
      val shift = if (id == upper) d else -d
      if (abs(d) > SNAP && !members[id].all { v -> KIND[v] == Kind.Dummy }) continue
      if (!free(id, shift)) continue
      for (v in members[id]) xs[v] += shift
      join(a[k], b[k])
      break
    }
  }
}

// Marks the ranks that hold nothing but the bends of edges, if there are any.
private fun emptyRanks(g: Graph): IntArray? {
  val maxRank = g.maxRank
  val empty = IntArray(maxRank + 1)
  var any = false
  for (r in 1 until maxRank) {
    var only = g.layerStart[r + 1] > g.layerStart[r]
    var i = g.layerStart[r]
    while (only && i < g.layerStart[r + 1]) {
      if (g.KIND[g.order[i]] != Kind.Dummy) only = false
      i++
    }
    if (only) {
      empty[r] = 1
      any = true
    }
  }
  return if (any) empty else null
}

// Gives every piece that does not run straight down a track across its gap, and counts the
// tracks of each gap. Two pieces in one gap share a track only where they cannot be mistaken
// for each other: where they do not overlap, or where they leave from or arrive at the same
// point, as the branches of a tree do. Which of two goes above the other is whichever makes
// them cross less. With `curved`, the tracks are levels for curves to turn onto, and only the
// pieces that would run along or too near another get one: each group of those counts its
// tracks from its own first. `room` is the height of the gap under each rank, and `tall`
// receives the height that a gap whose curves mostly cross each other should have instead of levels.
private fun assignTracks(pieces: Pieces, trackCount: IntArray, curved: Boolean, room: DoubleArray, tall: DoubleArray) {
  val from = pieces.from
  val to = pieces.to
  val rank = pieces.rank
  val track = pieces.track
  val levels = pieces.levels
  val wide = pieces.wide
  val total = from.size
  val byRank = LinkedHashMap<Int, MutableList<Int>>()
  for (k in 0 until total) {
    track.add(-1)
    levels.add(0)
    if (abs(from[k] - to[k]) < IN_LINE) continue
    val list = byRank[rank[k]]
    if (list != null) list.add(k)
    else byRank[rank[k]] = arrayListOf(k)
  }
  if (byRank.size == 0) return
  // The pieces are compared pair by pair, so where each begins and ends across the flow is worked out once.
  val LO = DoubleArray(total)
  val HI = DoubleArray(total)
  for (k in 0 until total) {
    LO[k] = min(from[k], to[k])
    HI[k] = max(from[k], to[k])
  }
  val within = { x: Double, k: Int -> if (x >= LO[k] - IN_LINE && x <= HI[k] + IN_LINE) 1 else 0 }
  val mates = { a: Int, b: Int -> abs(from[a] - from[b]) < IN_LINE || abs(to[a] - to[b]) < IN_LINE }
  val overlap = { a: Int, b: Int -> LO[a] < HI[b] - IN_LINE && LO[b] < HI[a] - IN_LINE }
  // Whether a and b need tracks of their own.
  val clash = { a: Int, b: Int -> overlap(a, b) && !mates(a, b) }
  // Whether one of a and b could cross the other, depending on which is above.
  val meet = { a: Int, b: Int -> overlap(a, b) || within(to[a], b) + within(from[b], a) + within(to[b], a) + within(from[a], b) > 0 }
  // How often a and b cross with a on the higher track: a's way down through b, and b's way down to itself through a.
  val crossings = { a: Int, b: Int -> within(to[a], b) + within(from[b], a) }
  // Returns pairs of list indices, smaller first. Omitted pairs cannot meet or clash.
  val nearPairs = { list: List<Int> ->
    val byLo = list.indices.toMutableList()
    byLo.sortWith { a, b -> cmp(LO[list[a]] - LO[list[b]]) }
    val pairs = ArrayList<Int>()
    for (x in byLo.indices) {
      val i = byLo[x]
      val reach = HI[list[i]] + 4 * IN_LINE
      var y = x + 1
      while (y < byLo.size && LO[list[byLo[y]]] <= reach) {
        val j = byLo[y]
        pairs.add(min(i, j))
        pairs.add(max(i, j))
        y++
      }
    }
    pairs
  }

  // Gives the pieces of one gap in `list` their tracks, and each the number of tracks that the
  // pieces it has to do with take between them, counting its own from the first of those.
  // Returns the number of tracks. The pieces come without tracks.
  val lay = fun(list: MutableList<Int>): Int {
    // A plain order to start from, and to keep where nothing says otherwise: the pieces that
    // reach furthest go first, so that those of one fan nest.
    list.sortWith { a, b -> cmp((HI[b] - LO[b] - (HI[a] - LO[a])).jsOr { LO[a] - LO[b] }.jsOr { (a - b).toDouble() }) }
    val count = list.size
    val above = ArrayList<MutableList<Int>>()
    val group = IntArray(count) { it }
    val find = { iIn: Int ->
      var i = iIn
      while (group[i] != i) {
        group[i] = group[group[i]]
        i = group[i]
      }
      i
    }
    val weighed = count <= MAX_WEIGHED
    // Avoid storing quadratically many pairs in dense gaps.
    val pairs = if (weighed) nearPairs(list) else ArrayList()
    val clashes: List<MutableList<Int>> = if (weighed) list.map { ArrayList<Int>() } else emptyList()
    var p = 0
    while (p < pairs.size) {
      val i = pairs[p]
      val j = pairs[p + 1]
      p += 2
      if (!clash(list[i], list[j])) continue
      clashes[i].add(j)
      clashes[j].add(i)
    }
    var sequence: MutableList<Int> = list.indices.toMutableList()
    if (weighed) {
      // above[j] lists the pieces that should be above list[j]. The order follows as many of
      // these as it can: a piece is placed once all that should be above it are, and when
      // none is free, the one with the fewest still waiting.
      val waiting = IntArray(count)
      val after = ArrayList<MutableList<Int>>()
      for (i in 0 until count) {
        above.add(ArrayList())
        after.add(ArrayList())
      }
      p = 0
      while (p < pairs.size) {
        val i = pairs[p]
        val j = pairs[p + 1]
        p += 2
        val a = list[i]
        val b = list[j]
        if (!meet(a, b)) continue
        group[find(i)] = find(j)
        val ab = crossings(a, b)
        val ba = crossings(b, a)
        if (ab == ba) continue
        val first = if (ab < ba) i else j
        val second = if (ab < ba) j else i
        above[second].add(first)
        after[first].add(second)
        waiting[second]++
      }
      val placed = IntArray(count)
      sequence = ArrayList()
      for (done in 0 until count) {
        var pick = -1
        for (i in 0 until count) {
          if (placed[i] != 0) continue
          if (pick < 0 || waiting[i] < waiting[pick]) pick = i
          if (waiting[i] == 0) break
        }
        placed[pick] = 1
        sequence.add(pick)
        for (j in after[pick]) waiting[j]--
      }
    }
    val taken = HashSet<Int>()
    val onTrack = HashMap<Int, MutableList<Int>>()
    var rows = 0
    for (at in sequence) {
      val k = list[at]
      var t = 0
      for (i in above.getOrNull(at) ?: emptyList()) if (track[list[i]] >= t) t = track[list[i]] + 1
      if (weighed) {
        taken.clear()
        for (i in clashes[at]) taken.add(track[list[i]])
        while (taken.contains(t)) t++
      } else {
        while (onTrack[t]?.any { other -> clash(k, other) } == true) t++
        onTrack.getOrPut(t) { ArrayList() }.add(k)
      }
      track[k] = t
      if (t >= rows) rows = t + 1
    }
    val first = IntArray(count) { rows }
    val last = IntArray(count) { -1 }
    for (i in 0 until count) {
      val root = find(i)
      first[root] = min(first[root], track[list[i]])
      last[root] = max(last[root], track[list[i]])
    }
    for (i in 0 until count) {
      val root = find(i)
      levels[list[i]] = last[root] - first[root] + 1
      if (curved) track[list[i]] -= first[root]
    }
    return rows
  }

  // PORT: per-call buffers in place of upstream's module-level LINE_A and LINE_B.
  val lineA = DoubleArray(2 * SAMPLES + 2)
  val lineB = DoubleArray(2 * SAMPLES + 2)
  val sample = { k: Int, h: Double, out: DoubleArray ->
    val c = ease(to[k] - from[k], h)
    for (i in 0..SAMPLES) {
      val t = i.toDouble() / SAMPLES
      val u = 1 - t
      out[2 * i] = from[k] + (to[k] - from[k]) * t * t * (3 - 2 * t)
      out[2 * i + 1] = h * (3 * c * t * u * u + 3 * (1 - c) * t * t * u + t * t * t)
    }
  }
  val reaches = fun(points: DoubleArray, line: DoubleArray, limit: Double): Boolean {
    // Allow rounding error in the cheap distance check before calling Math.hypot.
    val bound = limit * limit * (1 + 1e-9)
    for (i in 0..SAMPLES) {
      val px = points[2 * i]
      val py = points[2 * i + 1]
      for (j in 0 until SAMPLES) {
        val dx = line[2 * j + 2] - line[2 * j]
        val dy = line[2 * j + 3] - line[2 * j + 1]
        val t = max(0.0, min(1.0, ((px - line[2 * j]) * dx + (py - line[2 * j + 1]) * dy) / (dx * dx + dy * dy).jsOr { 1.0 }))
        val ex = px - line[2 * j] - t * dx
        val ey = py - line[2 * j + 1] - t * dy
        if (ex * ex + ey * ey < bound && jsHypot(ex, ey) < limit) return true
      }
    }
    return false
  }
  val near = { a: Int, b: Int, height: Double, limit: Double ->
    val h = max(height / 2, height - 2 * LEVEL_LEAD)
    sample(a, h, lineA)
    sample(b, h, lineB)
    reaches(lineA, lineB, limit) || reaches(lineB, lineA, limit)
  }

  for ((r, all) in byRank) {
    if (!curved) {
      trackCount[r] = lay(all)
      continue
    }
    if (all.size > MAX_WEIGHED) continue
    // The pieces that turn onto levels: first those that run far across and have another such
    // piece to be told apart from, then any two that would pass too near each other as plain
    // curves.
    val far = all.filter { k -> wide[k] == 1 }.toMutableList()
    lay(far)
    val level = LinkedHashSet(far.filter { k -> levels[k] > 1 })
    val pairs = nearPairs(all)
    var p = 0
    while (p < pairs.size) {
      val a = all[pairs[p]]
      val b = all[pairs[p + 1]]
      p += 2
      if ((level.contains(a) && level.contains(b)) || !clash(a, b) || (from[a] < to[a]) != (from[b] < to[b])) continue
      if (near(a, b, room[r], if (wide[a] == 1 || wide[b] == 1) LEVEL_CLEAR else LEVEL_TOUCH)) {
        level.add(a)
        level.add(b)
      }
    }
    for (k in far) {
      track[k] = -1
      levels[k] = 0
    }
    if (level.size == 0) continue
    var crossing = 0
    var alongside = 0
    val beside = HashSet<Int>()
    p = 0
    while (p < pairs.size) {
      val a = all[pairs[p]]
      val b = all[pairs[p + 1]]
      p += 2
      if (level.contains(a) && level.contains(b)) {
        if (!overlap(a, b)) continue
        if ((from[a] < to[a]) != (from[b] < to[b])) crossing++
        else if (!mates(a, b)) alongside++
      } else if ((level.contains(a) || level.contains(b)) && meet(a, b)) {
        beside.add(a)
        beside.add(b)
      }
    }
    if (crossing > alongside) {
      for (k in all) if ((HI[k] - LO[k]) * CROSSED_SLOPE > tall[r]) tall[r] = (HI[k] - LO[k]) * CROSSED_SLOPE
      continue
    }
    // With them go the short pieces that could cross one of them, so that each has its place
    // among the levels and crosses none.
    val members = all.filter { k -> level.contains(k) || (wide[k] != 1 && beside.contains(k)) }.toMutableList()
    lay(members)
    var most = 0
    for (k in members) if (levels[k] > most) most = levels[k]
    trackCount[r] = most
  }
}

// Moves the bends in ranks that hold only bends onto the straight line between the nearest points
// of their edges that are held in place, and marks the ones that can be left out of the route.
// `firstDummy` and `span` give each edge's run of bends, one in every rank. `sep` is the room
// kept between the bends of twin edges, or 0 to leave those out as well.
private fun straighten(g: Graph, xs: DoubleArray, empty: IntArray, firstDummy: IntArray, span: IntArray, sep: Double, onLine: IntArray) {
  val maxRank = g.maxRank
  val EF = g.EF
  val ET = g.ET
  val EFD = g.EFD
  val ETD = g.ETD
  val succStart = g.succStart
  val succE = g.succE
  val predStart = g.predStart
  val predE = g.predE
  val layerStart = g.layerStart
  val order = g.order

  val want = DoubleArray(xs.size)
  // The points that each bend's edge runs between, across the ranks that hold only bends.
  val from = DoubleArray(xs.size)
  val to = DoubleArray(xs.size)
  for (ei in firstDummy.indices) {
    val d0 = firstDummy[ei]
    if (d0 < 0) continue
    val end = d0 + span[ei] - 1
    // A bend's rank is its place in the run, counted from the rank after the edge's first node.
    val base = g.RANK[d0]
    var d = d0
    while (d < end) {
      if (empty[base + d - d0] == 0) {
        d++
        continue
      }
      var last = d
      while (last + 1 < end && empty[base + last + 1 - d0] != 0) last++
      val above = predE[predStart[d]]
      val below = succE[succStart[last]]
      val x0 = xs[EF[above]] + EFD[above]
      val x1 = xs[ET[below]] + ETD[below]
      val steps = last - d + 2
      for (k in d..last) {
        want[k] = x0 + ((x1 - x0) * (k - d + 1)) / steps
        from[k] = x0
        to[k] = x1
      }
      d = last
      d++
    }
  }

  // Nothing in such a rank is in a bend's way, so each one goes onto its line and out of the
  // route. Only twins stay: bends of edges that run between the same two points, which would
  // otherwise be drawn as one. They take places a little apart, around the line.
  val twins = ArrayList<Int>()
  for (r in 1 until maxRank) {
    if (empty[r] == 0) continue
    val s = layerStart[r]
    val end = layerStart[r + 1]
    val row = order.copyOfRange(s, end).toMutableList()
    row.sortWith { a, b -> cmp((from[a] - from[b]).jsOr { to[a] - to[b] }.jsOr { (a - b).toDouble() }) }
    var i = 0
    while (i < row.size) {
      twins.clear()
      var j = i
      while (j < row.size && abs(from[row[j]] - from[row[i]]) < TWIN && abs(to[row[j]] - to[row[i]]) < TWIN) {
        twins.add(row[j])
        j++
      }
      // The twins keep the order the rank gave them, so that two edges do not swap sides on the way.
      twins.sortWith { a, b -> g.pos[a] - g.pos[b] }
      for (k in twins.indices) {
        val d = twins[k]
        xs[d] = want[d] + (k - (twins.size - 1) / 2.0) * sep
        if (twins.size == 1 || sep == 0.0) onLine[d] = 1
      }
      i = j
    }
  }
}

// In a rank that holds labels and bends but no nodes, a bend is there to take its edge straight
// through between the labels. Where the edge would pass clear of every label anyway, the bend
// is left out, and the edge turns in the whole height between the ranks on either side.
private fun pastLabels(g: Graph, xs: DoubleArray, firstDummy: IntArray, span: IntArray, clear: Double, onLine: IntArray) {
  val maxRank = g.maxRank
  val W = g.W
  val KIND = g.KIND
  val RANK = g.RANK
  val EF = g.EF
  val ET = g.ET
  val EFD = g.EFD
  val ETD = g.ETD
  val succStart = g.succStart
  val succE = g.succE
  val predStart = g.predStart
  val predE = g.predE
  val layerStart = g.layerStart
  val order = g.order
  val labelled = IntArray(maxRank + 1)
  var any = false
  for (r in 1 until maxRank) {
    var labels = false
    var others = false
    for (i in layerStart[r] until layerStart[r + 1]) {
      val kind = KIND[order[i]]
      if (kind == Kind.Label) labels = true
      else if (kind != Kind.Dummy) others = true
    }
    if (labels && !others) {
      labelled[r] = 1
      any = true
    }
  }
  if (!any) return
  for (ei in firstDummy.indices) {
    val d0 = firstDummy[ei]
    if (d0 < 0) continue
    val end = d0 + span[ei] - 1
    for (d in d0 until end) {
      if (KIND[d] != Kind.Dummy || labelled[RANK[d]] == 0) continue
      // The points on either side that the edge is held at, past any bends already left out.
      var first = d
      var last = d
      while (first > d0 && onLine[first - 1] != 0) first--
      while (last + 1 < end && onLine[last + 1] != 0) last++
      val above = predE[predStart[first]]
      val below = succE[succStart[last]]
      val from = xs[EF[above]] + EFD[above]
      val to = xs[ET[below]] + ETD[below]
      val lo = min(from, to) - clear
      val hi = max(from, to) + clear
      var free = true
      val r = RANK[d]
      var i = layerStart[r]
      while (free && i < layerStart[r + 1]) {
        val label = order[i]
        if (KIND[label] == Kind.Label && xs[label] + W[label] / 2 > lo && xs[label] - W[label] / 2 < hi) free = false
        i++
      }
      if (!free) continue
      onLine[d] = 1
      // The bends left out between the two points lie on the line between them.
      val steps = last - first + 2
      for (k in first..last) xs[k] = from + ((to - from) * (k - first + 1)) / steps
    }
  }
}

// Returns [final crossings, crossings before grouping or reordering].
private fun reduceCrossings(g: Graph, nodes: List<LNode>): IntArray {
  val n = g.n
  val real = g.real
  val maxRank = g.maxRank
  val W = g.W
  val EF = g.EF
  val ET = g.ET
  val EFD = g.EFD
  val ETD = g.ETD
  val succStart = g.succStart
  val succE = g.succE
  val predStart = g.predStart
  val predE = g.predE
  val layerStart = g.layerStart
  val order = g.order
  val pos = g.pos
  val offsets = g.offsets
  val fixed = g.fixed
  val GRP = g.GRP
  val groupParent = g.groupParent
  var widest = 0
  for (r in 0..maxRank) widest = max(widest, layerStart[r + 1] - layerStart[r])
  if (widest < 2) return intArrayOf(0, 0)
  // With offsets, edges that reach one node at different offsets take separate places along it.
  val place = if (offsets) IntArray(EF.size) else null
  // Local offset indices and counts do not depend on node order.
  val placeIn = IntArray(if (place != null) EF.size else 0)
  val places = IntArray(if (place != null) n else 0)
  val leaving = if (place != null) succE.copyOf() else succE
  val mixed = IntArray(if (place != null) n else 0)
  if (place != null) {
    val arriving = predE.copyOf()
    val byOffset = fun(list: IntArray, from: Int, to: Int, dx: DoubleArray): Boolean {
      for (k in from + 1 until to) {
        if (dx[list[k]] == dx[list[from]]) continue
        sortSub(list, from, to) { a, b -> cmp(dx[a] - dx[b]) }
        return true
      }
      return false
    }
    for (v in 0 until n) {
      places[v] = 1
      if (byOffset(arriving, predStart[v], predStart[v + 1], ETD)) {
        for (k in predStart[v] + 1 until predStart[v + 1]) {
          if (ETD[arriving[k]] != ETD[arriving[k - 1]]) places[v]++
          placeIn[arriving[k]] = places[v] - 1
        }
      }
      if (byOffset(leaving, succStart[v], succStart[v + 1], EFD)) mixed[v] = 1
    }
  }
  var size = 1
  while (size < (if (place != null) widest + EF.size else widest)) size = size shl 1
  val tree = IntArray(2 * size)
  val targets = IntArray(EF.size)

  val count = fun(): Int {
    var total = 0
    for (r in 0 until maxRank) {
      var southLen = layerStart[r + 2] - layerStart[r + 1]
      if (place != null) {
        var next = 0
        for (i in layerStart[r + 1] until layerStart[r + 2]) {
          val v = order[i]
          for (k in predStart[v] until predStart[v + 1]) place[predE[k]] = next + placeIn[predE[k]]
          next += places[v]
        }
        southLen = next
      }
      if (southLen < 2) continue
      var t = 0
      for (i in layerStart[r] until layerStart[r + 1]) {
        val v = order[i]
        val from = succStart[v]
        val to = succStart[v + 1]
        val t0 = t
        for (k in from until to) targets[t++] = if (place != null) place[leaving[k]] else pos[ET[succE[k]]]
        val d = t - t0
        if (d < 2) continue
        if (place != null && mixed[v] != 0) {
          // Sort equal-offset edges by destination to avoid counting shared endpoints as crossings.
          for (a in t0 + 1 until t) {
            val item = targets[a]
            val dx = EFD[leaving[from + a - t0]]
            var b = a - 1
            while (b >= t0 && EFD[leaving[from + b - t0]] == dx && targets[b] > item) {
              targets[b + 1] = targets[b]
              b--
            }
            targets[b + 1] = item
          }
        } else if (d <= 12) {
          for (a in t0 + 1 until t) {
            val item = targets[a]
            var b = a - 1
            while (b >= t0 && targets[b] > item) {
              targets[b + 1] = targets[b]
              b--
            }
            targets[b + 1] = item
          }
        } else {
          targets.sort(t0, t)
        }
      }
      if (t < 2) continue
      var leaves = 1
      while (leaves < southLen) leaves = leaves shl 1
      tree.fill(0, 0, 2 * leaves)
      for (k in 0 until t) {
        var i = targets[k] + leaves
        tree[i]++
        while (i > 1) {
          if ((i and 1) == 0) total += tree[i + 1]
          i = i shr 1
          tree[i]++
        }
      }
    }
    return total
  }

  val declared = count()
  val bary = DoubleArray(n)
  val moving = IntArray(widest)
  val spare = IntArray(widest)

  // Keep groups contiguous, ordering each by its mean key.
  val groups = groupParent?.size ?: 0
  val depth = IntArray(groups)
  for (gr in 0 until groups) {
    var p = gr
    while (p >= 0) {
      depth[gr]++
      p = groupParent!![p]
    }
  }
  val groupSum = DoubleArray(groups)
  val groupCount = IntArray(groups)
  val groupFirst = IntArray(groups)
  val arrange = { r: Int, key: DoubleArray ->
    val grp = GRP!!
    val parent = groupParent!!
    val s = layerStart[r]
    val e = layerStart[r + 1]
    for (i in s until e) {
      var gr = grp[order[i]]
      while (gr >= 0) {
        groupSum[gr] = 0.0
        groupCount[gr] = 0
        gr = parent[gr]
      }
    }
    for (i in s until e) {
      val v = order[i]
      var gr = grp[v]
      while (gr >= 0) {
        if (groupCount[gr] == 0) groupFirst[gr] = i
        groupSum[gr] += key[v]
        groupCount[gr]++
        gr = parent[gr]
      }
    }
    // Compare siblings in their nearest common group; group items use n + group ID.
    val value = { item: Int -> if (item >= n) groupSum[item - n] / groupCount[item - n] else key[item] }
    val first = { item: Int -> if (item >= n) groupFirst[item - n] else s + pos[item] }
    val compare = { a: Int, b: Int ->
      var ia = a
      var ib = b
      var ca = grp[a]
      var cb = grp[b]
      var da = if (ca < 0) 0 else depth[ca]
      var db = if (cb < 0) 0 else depth[cb]
      while (da > db) {
        ia = n + ca
        ca = parent[ca]
        da--
      }
      while (db > da) {
        ib = n + cb
        cb = parent[cb]
        db--
      }
      while (ca != cb) {
        ia = n + ca
        ca = parent[ca]
        ib = n + cb
        cb = parent[cb]
      }
      cmp((value(ia) - value(ib)).jsOr { (first(ia) - first(ib)).toDouble() })
    }
    val row = order.copyOfRange(s, e).toMutableList()
    row.sortWith(compare)
    for (i in row.indices) order[s + i] = row[i]
    for (i in s until e) pos[order[i]] = i - s
  }
  if (GRP != null) {
    val at = DoubleArray(n)
    for (r in 0..maxRank) {
      for (i in layerStart[r] until layerStart[r + 1]) at[order[i]] = (i - layerStart[r]).toDouble()
      arrange(r, at)
    }
  }
  var best = if (GRP != null) count() else declared
  if (best == 0) return intArrayOf(0, declared)
  val bestOrder = order.copyOf()
  // Require improvements over both the grouped and ungrouped starting orders.
  val startCount = min(best, declared)

  val sweep = fun(down: Boolean, biasRight: Boolean) {
    val start = if (down) predStart else succStart
    val list = if (down) predE else succE
    val other = if (down) EF else ET
    val otherDx = if (down) EFD else ETD
    for (step in 1..maxRank) {
      val r = if (down) step else maxRank - step
      if (fixed[r] != 0) continue
      val s = layerStart[r]
      val e = layerStart[r + 1]
      var movable = 0
      for (i in s until e) {
        val v = order[i]
        val pin = if (v < real) nodes[v].pin else 0
        if (pin != 0) {
          bary[v] = pin * 1e9
          moving[movable++] = v
          continue
        }
        val from = start[v]
        val to = start[v + 1]
        if (from == to) {
          if (GRP != null) {
            // Preserve relative position when there are no neighbours in the adjacent rank.
            val across = if (down) layerStart[r] - layerStart[r - 1] else layerStart[r + 2] - layerStart[r + 1]
            bary[v] = ((i - s).toDouble() * across) / (e - s)
            continue
          }
          bary[v] = -1.0
          continue
        }
        var sum = 0.0
        for (k in from until to) {
          val u = other[list[k]]
          sum += pos[u]
          if (offsets && W[u] > 0) sum += otherDx[list[k]] / W[u]
        }
        bary[v] = sum / (to - from)
        moving[movable++] = v
      }
      if (GRP != null) {
        arrange(r, bary)
        continue
      }
      if (movable < 2) continue
      sortByBary(moving, movable, bary, pos, biasRight, spare)
      var k = 0
      for (i in s until e) if (bary[order[i]] != -1.0) order[i] = moving[k++]
      for (i in s until e) pos[order[i]] = i - s
    }
  }

  // How many pairs of nodes in one rank are not in the order they were given.
  val outOfOrder = fun(): Int {
    var total = 0
    for (r in 0..maxRank) {
      for (i in layerStart[r] until layerStart[r + 1]) {
        if (order[i] >= real) continue
        for (j in i + 1 until layerStart[r + 1]) if (order[j] < order[i]) total++
      }
    }
    return total
  }
  val settlePositions = {
    for (r in 0..maxRank) for (i in layerStart[r] until layerStart[r + 1]) pos[order[i]] = i - layerStart[r]
  }

  // Adjacent swaps can remove crossings missed by barycenter sweeps.
  val pinOf = { v: Int -> if (v < real) nodes[v].pin else 0 }
  val at = { e: Int, up: Boolean ->
    val u = if (up) EF[e] else ET[e]
    if (offsets && W[u] > 0) pos[u] + (if (up) EFD[e] else ETD[e]) / W[u] else pos[u].toDouble()
  }
  // Crossings between a and b, assuming a is left of b.
  val between = { a: Int, b: Int ->
    var c = 0
    for (k in predStart[a] until predStart[a + 1]) {
      val x = at(predE[k], true)
      for (j in predStart[b] until predStart[b + 1]) if (x > at(predE[j], true)) c++
    }
    for (k in succStart[a] until succStart[a + 1]) {
      val x = at(succE[k], false)
      for (j in succStart[b] until succStart[b + 1]) if (x > at(succE[j], false)) c++
    }
    c
  }
  val transpose = {
    for (pass in 0 until 8) {
      var improved = false
      for (r in 0..maxRank) {
        if (fixed[r] != 0) continue
        val s = layerStart[r]
        var i = s
        while (i + 1 < layerStart[r + 1]) {
          val a = order[i]
          val b = order[i + 1]
          if (pinOf(a) != pinOf(b) || (GRP != null && GRP[a] != GRP[b])) {
            i++
            continue
          }
          if (between(b, a) >= between(a, b)) {
            i++
            continue
          }
          order[i] = b
          order[i + 1] = a
          pos[b] = i - s
          pos[a] = i + 1 - s
          improved = true
          i++
        }
      }
      if (!improved) break
    }
  }

  // The sweeps are run twice from the same start, going down first and going up first. They
  // often end with as few crossings either way but a different order, and then the one that
  // keeps more of the nodes in the order they were given is the less surprising.
  // Only for graphs small enough to be read node by node: a second run costs as much as the first.
  val initial = order.copyOf()
  val small = n <= 600
  var chosen = bestOrder
  var chosenOut = Int.MAX_VALUE
  for (attempt in 0 until (if (small) 2 else 1)) {
    if (attempt > 0) {
      initial.copyInto(order)
      settlePositions()
    }
    var found = startCount
    val foundOrder = initial.copyOf()
    var i = 0
    var stale = 0
    while (stale < 4 && i < MAX_SWEEPS) {
      sweep((i + attempt) % 2 == 0, i % 4 >= 2)
      val c = count()
      if (c < found) {
        found = c
        order.copyInto(foundOrder)
        stale = 0
        if (c == 0) break
      }
      i++
      stale++
    }
    foundOrder.copyInto(order)
    if (small && found > 0) {
      settlePositions()
      transpose()
      val c = count()
      if (c < found) {
        found = c
        order.copyInto(foundOrder)
      }
    }
    val out = if (small) outOfOrder() else 0
    if (attempt == 0 || found < best || (found == best && out < chosenOut)) {
      best = found
      chosen = foundOrder
      chosenOut = out
    }
  }
  chosen.copyInto(order)
  settlePositions()
  chosen.copyInto(bestOrder)
  // The retained grouped order may exceed the ungrouped threshold; recount it.
  if (GRP != null) best = count()

  // A drawing and its mirror image cross as often too. Of those two, likewise.
  if (!small) return intArrayOf(best, declared)
  for (i in 0 until real) if (nodes[i].pin != 0) return intArrayOf(best, declared)
  if (chosenOut == 0 || fixed[0] != 0 || fixed[maxRank] != 0) return intArrayOf(best, declared)
  for (r in 0..maxRank) order.reverse(layerStart[r], layerStart[r + 1])
  settlePositions()
  if (count() > best || outOfOrder() >= chosenOut) bestOrder.copyInto(order)
  return intArrayOf(best, declared)
}

// Half the room a node, bend or port takes across the flow, with its share of the gap beside it.
// A port on a group's border is a point that an edge passes through, and needs no more than a bend.
private fun halfRoom(g: Graph, opt: LayeredOptions, v: Int): Double =
  g.W[v] / 2 + (if (g.KIND[v] == Kind.Node) opt.nodeSep else opt.edgeSep) / 2

// Brandes-Köpf horizontal coordinate assignment: four aligned layouts, balanced.
private fun position(g: Graph, opt: LayeredOptions): DoubleArray {
  val n = g.n
  val maxRank = g.maxRank
  val W = g.W
  val KIND = g.KIND
  val EF = g.EF
  val ET = g.ET
  val EFD = g.EFD
  val ETD = g.ETD
  val succStart = g.succStart
  val succE = g.succE
  val predStart = g.predStart
  val predE = g.predE
  val layerStart = g.layerStart
  val order = g.order
  val pos = g.pos
  val segments = EF.size
  val gap = DoubleArray(n)
  for (i in 0 until n) gap[i] = halfRoom(g, opt, i)

  // Neighbours in left-to-right order, so the medians can be read off directly.
  val key = IntArray(segments)
  for (e in 0 until segments) key[e] = pos[ET[e]]
  for (v in 0 until n) if (succStart[v + 1] - succStart[v] > 1) sortRange(succE, succStart[v], succStart[v + 1], key)
  for (e in 0 until segments) key[e] = pos[EF[e]]
  for (v in 0 until n) if (predStart[v + 1] - predStart[v] > 1) sortRange(predE, predStart[v], predStart[v + 1], key)

  // An edge between two real nodes may not cross a dummy-to-dummy segment; mark those that would.
  val conflict = IntArray(segments)
  for (r in 1..maxRank) {
    val prevLen = layerStart[r] - layerStart[r - 1]
    val s = layerStart[r]
    val end = layerStart[r + 1]
    var k0 = 0
    var scan = s
    for (i in s until end) {
      val v = order[i]
      var inner = -1
      if (isDummy(KIND[v])) {
        val u = EF[predE[predStart[v]]]
        if (isDummy(KIND[u])) inner = u
      }
      val k1 = if (inner >= 0) pos[inner] else prevLen
      if (inner >= 0 || i == end - 1) {
        for (j in scan..i) {
          val w = order[j]
          val wDummy = isDummy(KIND[w])
          for (k in predStart[w] until predStart[w + 1]) {
            val e = predE[k]
            val up = pos[EF[e]]
            if ((up < k0 || k1 < up) && !(wDummy && isDummy(KIND[EF[e]]))) conflict[e] = 1
          }
        }
        scan = i + 1
        k0 = k1
      }
    }
  }

  val results = ArrayList<DoubleArray>()
  val root = IntArray(n)
  val align = IntArray(n)
  // Offset of each node from its block's root, so that aligned edges meet at their attachment points.
  val shift = DoubleArray(n)
  val eFrom = IntArray(n)
  val eTo = IntArray(n)
  val eW = DoubleArray(n)
  val indeg = IntArray(n)
  val outStart = IntArray(n + 1)
  val outE = IntArray(n)
  val fill = IntArray(n)
  val topo = IntArray(n)
  val x = DoubleArray(n)

  for (variant in 0 until 4) {
    val up = variant < 2
    val right = (variant and 1) == 1
    val start = if (up) predStart else succStart
    val list = if (up) predE else succE
    val other = if (up) EF else ET
    val ownDx = if (up) ETD else EFD
    val otherDx = if (up) EFD else ETD
    for (i in 0 until n) {
      root[i] = i
      align[i] = i
      shift[i] = 0.0
      indeg[i] = 0
      outStart[i + 1] = 0
      x[i] = 0.0
    }

    for (step in 0..maxRank) {
      val r = if (up) step else maxRank - step
      val s = layerStart[r]
      val end = layerStart[r + 1]
      val adjacent = if (up) r - 1 else r + 1
      val adjacentLast = if (adjacent >= 0 && adjacent <= maxRank) layerStart[adjacent + 1] - layerStart[adjacent] - 1 else 0
      var prevIdx = -1
      var i = if (right) end - 1 else s
      while (if (right) i >= s else i < end) {
        val v = order[i]
        val from = start[v]
        val d = start[v + 1] - from
        if (d != 0) {
          val lo = from + ((d - 1) shr 1)
          val hi = from + (d shr 1)
          for (pass in 0 until 2) {
            if (pass == 1 && lo == hi) break
            val e = list[if ((pass == 0) != right) lo else hi]
            val w = other[e]
            val idx = if (right) adjacentLast - pos[w] else pos[w]
            if (align[v] == v && prevIdx < idx && conflict[e] == 0) {
              align[w] = v
              root[v] = root[w]
              align[v] = root[v]
              shift[v] = shift[w] + otherDx[e] - ownDx[e]
              prevIdx = idx
            }
          }
        }
        if (right) i-- else i++
      }
    }

    var blockEdges = 0
    for (r in 0..maxRank) {
      val s = layerStart[r]
      val end = layerStart[r + 1]
      for (i in s + 1 until end) {
        val a = if (right) order[i] else order[i - 1]
        val b = if (right) order[i - 1] else order[i]
        val sep = gap[a] + gap[b]
        eFrom[blockEdges] = root[a]
        eTo[blockEdges] = root[b]
        eW[blockEdges] = if (right) sep - shift[a] + shift[b] else sep + shift[a] - shift[b]
        indeg[root[b]]++
        outStart[root[a] + 1]++
        blockEdges++
      }
    }
    for (i in 0 until n) outStart[i + 1] += outStart[i]
    for (i in 0 until n) fill[i] = outStart[i]
    for (e in 0 until blockEdges) outE[fill[eFrom[e]]++] = e

    var count = 0
    for (i in 0 until n) if (root[i] == i && indeg[i] == 0) topo[count++] = i
    var q = 0
    while (q < count) {
      val v = topo[q]
      for (k in outStart[v] until outStart[v + 1]) {
        val e = outE[k]
        val w = eTo[e]
        if (x[v] + eW[e] > x[w]) x[w] = x[v] + eW[e]
        if (--indeg[w] == 0) topo[count++] = w
      }
      q++
    }
    for (qq in count - 1 downTo 0) {
      val v = topo[qq]
      var min = Double.POSITIVE_INFINITY
      for (k in outStart[v] until outStart[v + 1]) {
        val e = outE[k]
        val cand = x[eTo[e]] - eW[e]
        if (cand < min) min = cand
      }
      if (min != Double.POSITIVE_INFINITY && min > x[v]) x[v] = min
    }
    val xs = DoubleArray(n)
    for (i in 0 until n) xs[i] = (if (right) -x[root[i]] else x[root[i]]) + shift[i]
    results.add(xs)
  }

  var narrowest = 0
  var bestWidth = Double.POSITIVE_INFINITY
  val mins = DoubleArray(4)
  val maxs = DoubleArray(4)
  for (k in 0 until 4) {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    val xs = results[k]
    for (i in 0 until n) {
      val half = W[i] / 2
      if (xs[i] - half < lo) lo = xs[i] - half
      if (xs[i] + half > hi) hi = xs[i] + half
    }
    mins[k] = lo
    maxs[k] = hi
    if (hi - lo < bestWidth) {
      bestWidth = hi - lo
      narrowest = k
    }
  }
  for (k in 0 until 4) {
    val delta = if ((k and 1) == 0) mins[narrowest] - mins[k] else maxs[narrowest] - maxs[k]
    if (delta != 0.0) {
      val xs = results[k]
      for (i in 0 until n) xs[i] += delta
    }
  }

  val out = DoubleArray(n)
  for (i in 0 until n) {
    var a = results[0][i]
    var b = results[1][i]
    var c = results[2][i]
    var d = results[3][i]
    var t: Double
    if (a > b) {
      t = a; a = b; b = t
    }
    if (c > d) {
      t = c; c = d; d = t
    }
    if (a > c) {
      t = a; a = c; c = t
    }
    if (b > d) {
      t = b; b = d; d = t
    }
    if (b > c) {
      t = b; b = c; c = t
    }
    out[i] = (b + c) / 2
  }
  return out
}
