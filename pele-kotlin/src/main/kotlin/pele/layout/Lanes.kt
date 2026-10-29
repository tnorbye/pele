// Ported from Pele src/layout/lanes.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import pele.js.cmp
import pele.js.jsOr
import pele.js.jsRound

// Layout for swimlanes. Every top-level group is a lane: a band that runs the length of the
// diagram along the flow. Ranks are shared by all lanes, so a step sits level with the steps
// that happen at the same time in other lanes, and each node stays inside its own lane.

private const val LANE_HEAD = 34.0
private const val LANE_PAD = 24.0
private const val SWEEPS = 8
private const val MAX_DUMMIES = 20000
private const val MAX_HOPS = 10000
private const val ROUTE_BUDGET = 2000000.0
// Parallel lines closer than this are drawn as one.
private const val NEAR = 4.0
private const val ROUNDS = 3
private const val RELAY = 8.0
private const val TURN = 20.0
// The room a lane keeps beside a handoff along its margin, and how much of it is between the
// handoff and the lane's items.
private const val MARGIN = 20.0
private const val LEG = 14.0
private const val CLOSE = 3.0

private class LanePlaced(
  val width: Double,
  val height: Double,
  val routed: IntArray,
  val margins: IntArray,
  val work: Double,
)

private class Around(val pts: DoubleArray, val cost: Double, val lane: Int)

// JS Math.sign: keeps NaN, and the sign of zero does not matter here.
private fun jsSign(x: Double): Double = sign(x)

fun laneLayout(
  nodes: List<CNode>,
  edges: List<CEdge>,
  dir: Dir,
  opt: LayeredOptions,
  lanePad: Double = LANE_PAD,
): CompoundResult {
  val n = nodes.size
  if (n == 0) return CompoundResult(0.0, 0.0)
  val sideways = dir == "LR" || dir == "RL"
  val flipped = dir == "BT" || dir == "RL"

  // Lanes in the order they were written. The graph lists groups last-declared first.
  val lanes = ArrayList<Int>()
  for (i in n - 1 downTo 0) if (nodes[i].parent < 0 && nodes[i].isGroup) lanes.add(i)
  val laneCount = max(1, lanes.size)
  val laneSlot = IntArray(n) { -1 }
  lanes.forEachIndexed { k, id -> laneSlot[id] = k }
  val hasChild = IntArray(n)
  for (i in 0 until n) if (nodes[i].parent >= 0) hasChild[nodes[i].parent] = 1
  // Room a lane keeps around its items, so that the groups drawn around them stay inside it.
  val lanePads = DoubleArray(laneCount) { lanePad }
  var endPad = lanePad
  val laneOf = { i: Int ->
    var t = i
    var room = lanePad
    var hops = 0
    while (nodes[t].parent >= 0 && hops < MAX_HOPS) {
      t = nodes[t].parent
      if (laneSlot[t] < 0) room += max(nodes[t].padX, max(nodes[t].padTop, nodes[t].padBottom))
      hops++
    }
    val lane = max(0, laneSlot[t])
    if (room > lanePads[lane]) lanePads[lane] = room
    if (room > endPad) endPad = room
    lane
  }

  // What gets ranked and placed: nodes, and groups that are empty and so act as nodes.
  val itemOf = IntArray(n) { -1 }
  val real = ArrayList<Int>()
  for (i in 0 until n) {
    if (laneSlot[i] >= 0) continue
    if (!nodes[i].isGroup || hasChild[i] == 0) {
      itemOf[i] = real.size
      real.add(i)
    }
  }
  val along = { c: CNode -> if (sideways) c.w else c.h }
  val cross = { c: CNode -> if (sideways) c.h else c.w }

  val lnodes = real.map { i -> lnode(cross(nodes[i]), along(nodes[i])) }
  val ledges = ArrayList<LEdge>()
  val edgeOf = ArrayList<Int>()
  var step = 1
  run {
    val seen = HashSet<Long>()
    for (ei in edges.indices) {
      val e = edges[ei]
      val a = itemOf[e.src]
      val b = itemOf[e.dst]
      if (a < 0 || b < 0 || a == b) continue
      val key = min(a, b).toLong() * real.size + max(a, b)
      if (e.labelW > 0 || seen.contains(key)) step = 2
      seen.add(key)
      ledges.add(
        ledge(
          a,
          b,
          e.minlen,
          if (sideways) e.labelH else e.labelW,
          if (sideways) e.labelW else e.labelH,
        )
      )
      edgeOf.add(ei)
    }
  }
  orient(lnodes, ledges)
  rank(lnodes, ledges, step)

  val m = ledges.size
  val itemLane = IntArray(real.size) { k -> laneOf(real[k]) }
  // Lays the ranked items out and routes the relations. A plain relation is a handoff that takes
  // a simple route of its own, and so keeps no room through the ranks it passes.
  val place =
    fun(plain: IntArray, margins: IntArray): LanePlaced {
      val pad =
        DoubleArray(laneCount) { lane ->
          if (margins[lane] != 0) max(lanePads[lane], MARGIN) else lanePads[lane]
        }
      val used = IntArray(laneCount)
      // Items: the real ones, then a dummy for every rank an edge passes through.
      var dummies = 0
      val span = IntArray(m)
      for (k in 0 until m) {
        span[k] = abs(lnodes[ledges[k].head].rank - lnodes[ledges[k].tail].rank)
        if (plain[k] == 0) dummies += span[k] - 1
      }
      val direct = dummies > MAX_DUMMIES
      val total = real.size + (if (direct) 0 else dummies)
      val RANK = IntArray(total)
      val LANE = IntArray(total)
      val SIZE = DoubleArray(total)
      val DEPTH = DoubleArray(total)
      val DUMMY = IntArray(total)
      var maxRank = 0
      for (k in real.indices) {
        RANK[k] = lnodes[k].rank
        LANE[k] = itemLane[k]
        SIZE[k] = lnodes[k].w
        DEPTH[k] = lnodes[k].h
        if (RANK[k] > maxRank) maxRank = RANK[k]
      }
      val firstDummy = IntArray(m) { -1 }
      val labelItem = IntArray(m) { -1 }
      val links = ArrayList<Int>()
      run {
        var next = real.size
        for (k in 0 until m) {
          val e = ledges[k]
          val a = if (e.reversed) e.head else e.tail
          val b = if (e.reversed) e.tail else e.head
          if (direct || span[k] < 2 || plain[k] != 0) {
            links.add(a)
            links.add(b)
            continue
          }
          val ra = RANK[a]
          val rb = RANK[b]
          var labelRank = -1
          if (e.labelW > 0) {
            labelRank = ra + ((rb - ra) shr 1)
            if (step == 2 && (labelRank and 1) == 0) labelRank--
          }
          firstDummy[k] = next
          var prev = a
          for (r in ra + 1 until rb) {
            val d = next++
            RANK[d] = r
            // The edge crosses from its first lane to its last one evenly along the way.
            LANE[d] =
              LANE[a] + jsRound(((LANE[b] - LANE[a]).toDouble() * (r - ra)) / (rb - ra)).toInt()
            DUMMY[d] = 1
            if (r == labelRank) {
              SIZE[d] = e.labelW
              DEPTH[d] = e.labelH
              labelItem[k] = d
            }
            links.add(prev)
            links.add(d)
            prev = d
          }
          links.add(prev)
          links.add(b)
        }
      }

      val upStart = IntArray(total + 1)
      val downStart = IntArray(total + 1)
      run {
        var k = 0
        while (k < links.size) {
          downStart[links[k] + 1]++
          upStart[links[k + 1] + 1]++
          k += 2
        }
      }
      for (i in 0 until total) {
        upStart[i + 1] += upStart[i]
        downStart[i + 1] += downStart[i]
      }
      val up = IntArray(links.size / 2)
      val down = IntArray(links.size / 2)
      run {
        val uf = upStart.copyOfRange(0, total)
        val df = downStart.copyOfRange(0, total)
        var k = 0
        while (k < links.size) {
          down[df[links[k]]++] = links[k + 1]
          up[uf[links[k + 1]]++] = links[k]
          k += 2
        }
      }

      // Order within each rank: by lane first, then by where the item's neighbours sit.
      val rows = ArrayList<MutableList<Int>>()
      for (r in 0..maxRank) rows.add(ArrayList())
      for (i in 0 until total) rows[RANK[i]].add(i)
      val pos = DoubleArray(total)
      val key = DoubleArray(total)
      val settle = { row: MutableList<Int> ->
        row.sortWith { a, b ->
          cmp((LANE[a] - LANE[b]).toDouble().jsOr { key[a] - key[b] }.jsOr { (a - b).toDouble() })
        }
        for (k in row.indices) pos[row[k]] = k.toDouble()
      }
      for (row in rows) {
        for (k in row.indices) key[row[k]] = k.toDouble()
        settle(row)
      }
      var sweep = 0
      while (sweep < SWEEPS && maxRank > 0) {
        val downward = (sweep and 1) == 0
        for (s in 1..maxRank) {
          val row = rows[if (downward) s else maxRank - s]
          val start = if (downward) upStart else downStart
          val list = if (downward) up else down
          for (i in row) {
            var sum = 0.0
            val count = start[i + 1] - start[i]
            for (k in start[i] until start[i + 1]) sum += pos[list[k]]
            key[i] = if (count > 0) sum / count else pos[i]
          }
          settle(row)
        }
        sweep++
      }

      // The items of one rank that share a lane sit side by side as a run.
      val gap = { a: Int, b: Int ->
        if (DUMMY[a] != 0 || DUMMY[b] != 0) opt.edgeSep else opt.nodeSep
      }
      val eachRun = { row: MutableList<Int>, visit: (MutableList<Int>, Int, Int) -> Unit ->
        var from = 0
        while (from < row.size) {
          var to = from
          while (to + 1 < row.size && LANE[row[to + 1]] == LANE[row[from]]) to++
          visit(row, from, to)
          from = to + 1
        }
      }
      val runSize = { row: List<Int>, from: Int, to: Int ->
        var size = SIZE[row[from]]
        for (k in from until to) size += gap(row[k], row[k + 1]) + SIZE[row[k + 1]]
        size
      }

      // Lane breadth: enough for its fullest rank, and for its title.
      val breadth = DoubleArray(laneCount)
      var head = if (lanes.size > 0) LANE_HEAD else 0.0
      for (k in lanes.indices) {
        val lane = nodes[lanes[k]]
        breadth[k] = max(0.0, lane.minW - 2 * pad[k])
        if (lane.padTop > head) head = lane.padTop
      }
      for (row in rows) {
        eachRun(row) { _, from, to ->
          val size = runSize(row, from, to)
          if (size > breadth[LANE[row[from]]]) breadth[LANE[row[from]]] = size
        }
      }
      val laneStart = DoubleArray(laneCount + 1)
      for (k in 0 until laneCount) laneStart[k + 1] = laneStart[k] + breadth[k] + 2 * pad[k]

      // Across the flow: each lane's items start centered, then line up with their neighbours in
      // the same lane as far as the lane and the items beside them allow.
      val C = DoubleArray(total)
      val want = DoubleArray(total)
      val center = { row: MutableList<Int>, from: Int, to: Int ->
        val lane = LANE[row[from]]
        var at =
          (laneStart[lane] + pad[lane] + (laneStart[lane + 1] - pad[lane]) -
            runSize(row, from, to)) / 2
        for (k in from..to) {
          C[row[k]] = at + SIZE[row[k]] / 2
          if (k < to) at += SIZE[row[k]] + gap(row[k], row[k + 1])
        }
      }
      val align = { row: MutableList<Int>, from: Int, to: Int ->
        val lane = LANE[row[from]]
        var floor = laneStart[lane] + pad[lane]
        for (k in from..to) {
          val i = row[k]
          C[i] = max(want[i], floor + SIZE[i] / 2)
          if (k < to) floor = C[i] + SIZE[i] / 2 + gap(i, row[k + 1])
        }
        var ceil = laneStart[lane + 1] - pad[lane]
        for (k in to downTo from) {
          val i = row[k]
          C[i] = min(C[i], ceil - SIZE[i] / 2)
          if (k > from) ceil = C[i] - SIZE[i] / 2 - gap(row[k - 1], i)
        }
      }
      for (row in rows) eachRun(row, center)
      var pass = 0
      while (pass < 4 && maxRank > 0) {
        val downward = (pass and 1) == 0
        for (s in 1..maxRank) {
          val row = rows[if (downward) s else maxRank - s]
          val start = if (downward) upStart else downStart
          val list = if (downward) up else down
          for (i in row) {
            var sum = 0.0
            var count = 0
            for (k in start[i] until start[i + 1]) {
              if (LANE[list[k]] != LANE[i]) continue
              sum += C[list[k]]
              count++
            }
            want[i] = if (count > 0) sum / count else C[i]
          }
          eachRun(row, align)
        }
        pass++
      }

      // Along the flow: one band per rank, after the lane titles.
      val bandStart = DoubleArray(maxRank + 1)
      val bandEnd = DoubleArray(maxRank + 1)
      // The room after a rank is for the relations that turn there or are only as long as they
      // were asked to be. Where just plain handoffs and straight lines pass, the ranks close up,
      // as far as the label of a handoff between them leaves it a line to sit on.
      val sep = DoubleArray(maxRank + 1) { opt.rankSep / step }
      if (plain.any { on -> on == 1 }) {
        val full = opt.rankSep / step
        sep.fill(full / CLOSE)
        for (k in 0 until m) {
          val e = ledges[k]
          val a = if (e.reversed) e.head else e.tail
          val b = if (e.reversed) e.tail else e.head
          if (plain[k] != 0) {
            if (e.labelH <= 0 || span[k] > step) continue
            val room = min(full, (e.labelH + 2 * TURN - min(DEPTH[a], DEPTH[b]) / 2) / span[k])
            for (r in RANK[a] until RANK[b]) if (room > sep[r]) sep[r] = room
            continue
          }
          val d0 = firstDummy[k]
          if (d0 < 0 || span[k] <= step * e.minlen) {
            for (r in RANK[a] until RANK[b]) sep[r] = full
            continue
          }
          for (d in d0 - 1 until d0 + span[k] - 1) {
            val u = if (d < d0) a else d
            val v = if (d + 1 < d0 + span[k] - 1) d + 1 else b
            if (LANE[u] != LANE[v] || abs(C[u] - C[v]) >= 0.5) sep[RANK[u]] = full
          }
        }
      }
      var at = head + endPad
      for (r in 0..maxRank) {
        var depth = 0.0
        for (i in rows[r]) if (DEPTH[i] > depth) depth = DEPTH[i]
        bandStart[r] = at
        bandEnd[r] = at + depth
        at += depth + (if (r == maxRank) 0.0 else sep[r])
      }
      val length = at + endPad
      val breadthTotal = laneStart[laneCount]
      val flip = { a: Double -> if (flipped) head + length - a else a }
      val px = { c: Double, a: Double -> if (sideways) flip(a) else c }
      val py = { c: Double, a: Double -> if (sideways) c else flip(a) }
      val axis = if (sideways) 1.0 else 0.0

      for (k in real.indices) {
        val node = nodes[real[k]]
        val a = (bandStart[RANK[k]] + bandEnd[RANK[k]]) / 2
        node.x = px(C[k], a)
        node.y = py(C[k], a)
      }
      for (k in lanes.indices) {
        val lane = nodes[lanes[k]]
        val c = (laneStart[k] + laneStart[k + 1]) / 2
        val size = laneStart[k + 1] - laneStart[k]
        lane.x = if (sideways) length / 2 else c
        lane.y = if (sideways) c else length / 2
        lane.w = if (sideways) length else size
        lane.h = if (sideways) size else length
        lane.padTop = head
      }

      // Groups inside a lane are drawn around their members, deepest first.
      val depthOf = IntArray(n)
      val inner = ArrayList<Int>()
      for (i in 0 until n) {
        if (!nodes[i].isGroup || laneSlot[i] >= 0 || itemOf[i] >= 0) continue
        var d = 0
        var p = nodes[i].parent
        while (p >= 0 && d < MAX_HOPS) {
          d++
          p = nodes[p].parent
        }
        depthOf[i] = d
        inner.add(i)
      }
      inner.sortWith { a, b ->
        if (depthOf[b] - depthOf[a] != 0) depthOf[b] - depthOf[a] else a - b
      }
      val box = DoubleArray(n * 4)
      for (i in 0 until n) {
        box[i * 4] = Double.POSITIVE_INFINITY
        box[i * 4 + 1] = Double.POSITIVE_INFINITY
        box[i * 4 + 2] = Double.NEGATIVE_INFINITY
        box[i * 4 + 3] = Double.NEGATIVE_INFINITY
      }
      val grow = { g: Int, c: CNode ->
        if (c.x - c.w / 2 < box[g * 4]) box[g * 4] = c.x - c.w / 2
        if (c.y - c.h / 2 < box[g * 4 + 1]) box[g * 4 + 1] = c.y - c.h / 2
        if (c.x + c.w / 2 > box[g * 4 + 2]) box[g * 4 + 2] = c.x + c.w / 2
        if (c.y + c.h / 2 > box[g * 4 + 3]) box[g * 4 + 3] = c.y + c.h / 2
      }
      for (i in real) if (nodes[i].parent >= 0 && laneSlot[nodes[i].parent] < 0)
        grow(nodes[i].parent, nodes[i])
      for (g in inner) {
        val c = nodes[g]
        if (box[g * 4] == Double.POSITIVE_INFINITY) continue
        val w = max(box[g * 4 + 2] - box[g * 4] + 2 * c.padX, c.minW)
        c.x = (box[g * 4] + box[g * 4 + 2]) / 2
        c.w = w
        c.y = (box[g * 4 + 1] - c.padTop + box[g * 4 + 3] + c.padBottom) / 2
        c.h = box[g * 4 + 3] - box[g * 4 + 1] + c.padTop + c.padBottom
        if (c.parent >= 0 && laneSlot[c.parent] < 0) grow(c.parent, c)
      }

      for (e in edges) e.route = ArrayList()
      for (k in 0 until m) {
        val e = ledges[k]
        val out = edges[edgeOf[k]]
        val a = if (e.reversed) e.head else e.tail
        val b = if (e.reversed) e.tail else e.head
        val pts =
          arrayListOf(
            C[a],
            bandEnd[RANK[a]] - (bandEnd[RANK[a]] - bandStart[RANK[a]] - DEPTH[a]) / 2,
          )
        val d0 = firstDummy[k]
        if (d0 >= 0) {
          for (d in d0 until d0 + span[k] - 1) {
            pts.add(C[d])
            pts.add(bandStart[RANK[d]])
            if (bandEnd[RANK[d]] > bandStart[RANK[d]]) {
              pts.add(C[d])
              pts.add(bandEnd[RANK[d]])
            }
          }
        }
        pts.add(C[b])
        pts.add(bandStart[RANK[b]] + (bandEnd[RANK[b]] - bandStart[RANK[b]] - DEPTH[b]) / 2)
        val route = ArrayList<Double>()
        var p = 0
        while (p < pts.size) {
          route.add(px(pts[p], pts[p + 1]))
          route.add(py(pts[p], pts[p + 1]))
          route.add(axis)
          p += 2
        }
        if (e.reversed) {
          var i = 0
          var j = route.size - 3
          while (i < j) {
            for (t in 0 until 2) {
              val keep = route[i + t]
              route[i + t] = route[j + t]
              route[j + t] = keep
            }
            i += 3
            j -= 3
          }
        }
        out.route = route
        val label = labelItem[k]
        if (label >= 0) {
          val mid = (bandStart[RANK[label]] + bandEnd[RANK[label]]) / 2
          out.labelX = px(C[label], mid)
          out.labelY = py(C[label], mid)
        } else {
          out.labelX = (route[0] + route[route.size - 3]) / 2
          out.labelY = (route[1] + route[route.size - 2]) / 2
        }
      }

      // An edge that starts or ends at a lane or a group runs straight to its border.
      for (e in edges) {
        if (e.route.size > 0 || e.src == e.dst) continue
        val a = nodes[e.src]
        val b = nodes[e.dst]
        val (ax, ay) = border(a, b)
        val (bx, by) = border(b, a)
        val flat = if (abs(bx - ax) > abs(by - ay)) 1.0 else 0.0
        e.route = arrayListOf(ax, ay, flat, bx, by, flat)
        e.labelX = (ax + bx) / 2
        e.labelY = (ay + by) / 2
      }

      // A handoff can leave towards the other lane, then arrive along the flow (or vice versa).
      // In particular, a decision's return branch must not leave through its incoming side and
      // retrace the path past the preceding step. Try the two single-corner routes, keeping the
      // ranked route when a node or another label blocks them.
      val routed = IntArray(m)
      var routePoints = 0.0
      for (edge in edges) routePoints += edge.route.size / 3.0
      val work = m * (real.size + edges.size + routePoints)
      if (work <= ROUTE_BUDGET) {
        val amid = { i: Int -> (bandStart[RANK[i]] + bandEnd[RANK[i]]) / 2 }
        val labels =
          edges
            .map { e ->
              doubleArrayOf(
                if (sideways) e.labelY else e.labelX,
                flip(if (sideways) e.labelX else e.labelY),
              )
            }
            .toMutableList()
        val overlaps =
          {
            c0: Double,
            a0: Double,
            c1: Double,
            a1: Double,
            lc: Double,
            la: Double,
            cw: Double,
            ah: Double ->
            c1 > lc - cw / 2 && c0 < lc + cw / 2 && a1 > la - ah / 2 && a0 < la + ah / 2
          }
        val clear =
          fun(
            c0: Double,
            a0: Double,
            c1: Double,
            a1: Double,
            src: Int,
            dst: Int,
            ei: Int,
          ): Boolean {
            for (i in real.indices) {
              if (
                i != src &&
                  i != dst &&
                  overlaps(c0, a0, c1, a1, C[i], amid(i), SIZE[i] + 8, DEPTH[i] + 8)
              )
                return false
            }
            for (i in edges.indices) {
              val e = edges[i]
              // A step's own loop keeps its label inside the step's box, and has no place here.
              if (
                i != ei &&
                  e.labelW > 0 &&
                  e.src != e.dst &&
                  overlaps(
                    c0,
                    a0,
                    c1,
                    a1,
                    labels[i][0],
                    labels[i][1],
                    if (sideways) e.labelH else e.labelW,
                    if (sideways) e.labelW else e.labelH,
                  )
              )
                return false
            }
            return true
          }
        // How many other relations have a stretch inside a box. A stretch's whole box counts,
        // including its curve.
        val crossings = { x0: Double, y0: Double, x1: Double, y1: Double, ei: Int ->
          var count = 0
          for (i in edges.indices) {
            if (i == ei) continue
            val route = edges[i].route
            var j = 3
            while (j < route.size) {
              if (
                max(route[j - 3], route[j]) > x0 &&
                  min(route[j - 3], route[j]) < x1 &&
                  max(route[j - 2], route[j + 1]) > y0 &&
                  min(route[j - 2], route[j + 1]) < y1
              ) {
                count++
                break
              }
              j += 3
            }
          }
          count
        }
        val meets = { x: Double, y: Double, ei: Int ->
          var count = 0
          for (i in edges.indices) {
            val route = edges[i].route
            if (i == ei || route.size == 0) continue
            val last = route.size - 3
            if (
              (abs(route[0] - x) < 1 && abs(route[1] - y) < 1) ||
                (abs(route[last] - x) < 1 && abs(route[last + 1] - y) < 1)
            )
              count++
          }
          count
        }
        // Two relations drawn along one line read as a single path. Those that leave the same step
        // or arrive at the same step may share a trunk; any other pair must not.
        val retraces =
          fun(x0: Double, y0: Double, x1: Double, y1: Double, ei: Int): Boolean {
            val upright = abs(x1 - x0) < 0.5
            val lo = if (upright) min(y0, y1) else min(x0, x1)
            val hi = if (upright) max(y0, y1) else max(x0, x1)
            val line = if (upright) x0 else y0
            for (i in edges.indices) {
              val other = edges[i]
              if (i == ei || other.src == edges[ei].src || other.dst == edges[ei].dst) continue
              val route = other.route
              var j = 3
              while (j < route.size) {
                val u0 = route[j - 3 + (if (upright) 0 else 1)]
                val u1 = route[j + (if (upright) 0 else 1)]
                if (abs(u0 - line) >= NEAR || abs(u1 - line) >= NEAR) {
                  j += 3
                  continue
                }
                val v0 = route[j - 3 + (if (upright) 1 else 0)]
                val v1 = route[j + (if (upright) 1 else 0)]
                if (min(hi, max(v0, v1)) - max(lo, min(v0, v1)) > 1) return true
                j += 3
              }
            }
            return false
          }
        for (k in 0 until m) {
          val e = ledges[k]
          val ei = edgeOf[k]
          val out = edges[ei]
          val a = e.tail
          val b = e.head
          if (LANE[a] == LANE[b] || abs(C[b] - C[a]) < 1) continue
          val ac = C[a]
          val bc = C[b]
          val aa = amid(a)
          val ba = amid(b)
          val dc = jsSign(bc - ac)
          val da = jsSign(ba - aa)
          val attempt =
            fun(pts: DoubleArray, margin: Double): Boolean {
              var p = 2
              while (p < pts.size) {
                if (
                  !clear(
                    min(pts[p - 2], pts[p]) - margin,
                    min(pts[p - 1], pts[p + 1]) - margin,
                    max(pts[p - 2], pts[p]) + margin,
                    max(pts[p - 1], pts[p + 1]) + margin,
                    a,
                    b,
                    ei,
                  )
                )
                  return false
                if (
                  retraces(
                    px(pts[p - 2], pts[p - 1]),
                    py(pts[p - 2], pts[p - 1]),
                    px(pts[p], pts[p + 1]),
                    py(pts[p], pts[p + 1]),
                    ei,
                  )
                )
                  return false
                p += 2
              }
              var lc = 0.0
              var la = 0.0
              var longest = -1.0
              if (out.labelW > 0) {
                val cw = e.labelW
                val ah = e.labelH
                p = 2
                while (p < pts.size) {
                  val cc = (pts[p - 2] + pts[p]) / 2
                  val ca = (pts[p - 1] + pts[p + 1]) / 2
                  val across = abs(pts[p] - pts[p - 2])
                  val alongRun = abs(pts[p + 1] - pts[p - 1])
                  val run = across + alongRun
                  p += 2
                  if (run <= longest || (if (across > 0) across < cw + 8 else alongRun < ah + 8))
                    continue
                  if (
                    cc - cw / 2 < 0 ||
                      cc + cw / 2 > breadthTotal ||
                      ca - ah / 2 < 0 ||
                      ca + ah / 2 > length
                  )
                    continue
                  // Include the ends here: the label must clear both shapes it connects.
                  if (!clear(cc - cw / 2, ca - ah / 2, cc + cw / 2, ca + ah / 2, -1, -1, ei))
                    continue
                  // A label moved onto a handoff must not cover a return path that keeps its
                  // ranked route. A segment's whole box is kept clear, including its curve.
                  val lx = px(cc, ca)
                  val ly = py(cc, ca)
                  if (
                    crossings(
                      lx - out.labelW / 2,
                      ly - out.labelH / 2,
                      lx + out.labelW / 2,
                      ly + out.labelH / 2,
                      ei,
                    ) > 0
                  )
                    continue
                  lc = cc
                  la = ca
                  longest = run
                }
                if (longest < 0) return false
              }
              // A point short of each corner and one past it keep the turn close to the corner, so
              // that the line stays under its label and the handoff takes little room.
              val way = arrayListOf(pts[0], pts[1])
              p = 2
              while (p < pts.size) {
                val dx = pts[p] - pts[p - 2]
                val dy = pts[p + 1] - pts[p - 1]
                val run = abs(dx) + abs(dy)
                val first = if (p > 2) TURN else 0.0
                val last = if (p + 2 < pts.size) TURN else 0.0
                if (run > first + last + 1) {
                  if (first != 0.0) {
                    way.add(pts[p - 2] + (dx / run) * first)
                    way.add(pts[p - 1] + (dy / run) * first)
                  }
                  if (last != 0.0) {
                    way.add(pts[p] - (dx / run) * last)
                    way.add(pts[p + 1] - (dy / run) * last)
                  }
                }
                way.add(pts[p])
                way.add(pts[p + 1])
                p += 2
              }
              val newRoute = ArrayList<Double>()
              out.route = newRoute
              p = 0
              while (p < way.size) {
                val crosswise = if (p == 0) way[0] != way[2] else way[p] != way[p - 2]
                newRoute.add(px(way[p], way[p + 1]))
                newRoute.add(py(way[p], way[p + 1]))
                newRoute.add(if (crosswise) 1 - axis else axis)
                p += 2
              }
              if (out.labelW > 0) {
                out.labelX = px(lc, la)
                out.labelY = py(lc, la)
                labels[ei] = doubleArrayOf(lc, la)
              }
              routed[k] = 1
              return true
            }
          if (
            attempt(
              doubleArrayOf(ac + dc * SIZE[a] / 2, aa, bc, aa, bc, ba - da * DEPTH[b] / 2),
              2.0,
            )
          )
            continue
          if (
            attempt(
              doubleArrayOf(ac, aa + da * DEPTH[a] / 2, ac, ba, bc - dc * SIZE[b] / 2, ba),
              2.0,
            )
          )
            continue
          // With both corners blocked, as for a return past the steps between, the handoff goes
          // around: out to the margin of a lane, along it, and back in level with its end. It
          // takes the margin that crosses the fewest other relations and shares the fewest ends
          // with them, then the shortest way.
          if (work > ROUTE_BUDGET / RELAY) continue
          val around = ArrayList<Around>()
          for (lane in
            if (LANE[a] < LANE[b]) intArrayOf(LANE[b], LANE[a]) else intArrayOf(LANE[a], LANE[b])) {
            // A lane that keeps no margin yet has less room there, and the handoff stays inside it.
            val inset = max(pad[lane] - LEG, min(pad[lane] / 2, MARGIN - LEG))
            for (ch in doubleArrayOf(laneStart[lane] + inset, laneStart[lane + 1] - inset)) {
              if (abs(ch - ac) < SIZE[a] / 2 + 4 || abs(ch - bc) < SIZE[b] / 2 + 4) continue
              val pts =
                doubleArrayOf(
                  ac + jsSign(ch - ac) * SIZE[a] / 2,
                  aa,
                  ch,
                  aa,
                  ch,
                  ba,
                  bc + jsSign(ch - bc) * SIZE[b] / 2,
                  ba,
                )
              var cost =
                (meets(px(pts[0], pts[1]), py(pts[0], pts[1]), ei) +
                  meets(px(pts[6], pts[7]), py(pts[6], pts[7]), ei)) * 1e6
              var p = 2
              while (p < pts.size) {
                val x0 = px(pts[p - 2], pts[p - 1])
                val y0 = py(pts[p - 2], pts[p - 1])
                val x1 = px(pts[p], pts[p + 1])
                val y1 = py(pts[p], pts[p + 1])
                cost +=
                  crossings(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), ei) * 1e6 +
                    abs(x1 - x0) +
                    abs(y1 - y0)
                p += 2
              }
              around.add(Around(pts, cost, lane))
            }
          }
          around.sortWith { p, q -> cmp(p.cost - q.cost) }
          for (way in around) {
            if (!attempt(way.pts, 1.0)) continue
            used[way.lane] = 1
            break
          }
        }
      }

      return LanePlaced(
        if (sideways) length else breadthTotal,
        if (sideways) breadthTotal else length,
        routed,
        used,
        work,
      )
    }

  var plain = IntArray(m)
  var margins = IntArray(laneCount)
  var placed = place(plain, margins)
  // A handoff that was given a simple route needs no room kept for it through the ranks it
  // passes, so the lanes are laid out again without it. A handoff that then loses its route
  // goes back to the ranked one. Routing is most of the work, so a large graph is laid out once.
  var round = 0
  while (round < ROUNDS && placed.work <= ROUTE_BUDGET / RELAY) {
    val routedNow = placed.routed
    val next = if (round == 0) routedNow else IntArray(m) { k -> plain[k] and routedNow[k] }
    if (
      next.indices.all { k -> next[k] == plain[k] } &&
        placed.margins.indices.all { k -> placed.margins[k] == margins[k] }
    )
      break
    plain = next
    margins = placed.margins
    placed = place(plain, margins)
    round++
  }
  if (plain.indices.any { k -> plain[k] != 0 && placed.routed[k] == 0 })
    placed = place(IntArray(m), IntArray(laneCount))
  return CompoundResult(placed.width, placed.height)
}

// The point where the line from one box's center to another's leaves the first box. When the
// other center is inside the box, the point is on the nearest side instead.
private fun border(from: CNode, to: CNode): Pair<Double, Double> {
  val dx = to.x - from.x
  val dy = to.y - from.y
  val hw = max(from.w / 2, 0.5)
  val hh = max(from.h / 2, 0.5)
  if (abs(dx) < hw && abs(dy) < hh) {
    val toSide = hw - abs(dx)
    val toEnd = hh - abs(dy)
    return if (toSide < toEnd) Pair(from.x + (if (dx < 0) -hw else hw), to.y)
    else Pair(to.x, from.y + (if (dy < 0) -hh else hh))
  }
  val scale =
    min(
      if (dx != 0.0) hw / abs(dx) else Double.POSITIVE_INFINITY,
      if (dy != 0.0) hh / abs(dy) else Double.POSITIVE_INFINITY,
    )
  return Pair(from.x + dx * scale, from.y + dy * scale)
}
