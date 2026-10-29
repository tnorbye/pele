// Ported from Pele src/diagrams/architecture/layout.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.architecture

import kotlin.math.ceil
import kotlin.math.sqrt

// Grid placement. Mermaid hands the edge sides to a force-directed solver as relative placement
// constraints. Pele applies them directly: every service and junction takes a cell, an edge
// `a:R -- L:b` puts b in the cell to the right of a, and a group is a solid rectangle of cells, so a
// group never encloses a node that is not in it. Each container (the diagram, then every group) is
// laid out on its own, innermost first, and a finished group is placed in its parent as one block.

class Grid(
  // Cell of each node, in the order of the nodes passed in.
  val col: IntArray,
  val row: IntArray,
  // Cell rectangle and parent of each group. A group with nothing in it takes one cell.
  val groupCol: IntArray,
  val groupRow: IntArray,
  val groupCols: IntArray,
  val groupRows: IntArray,
  val groupParent: IntArray,
  val groupEmpty: IntArray,
  val nodeParent: IntArray,
)

private val DX = intArrayOf(-1, 1, 0, 0)
private val DY = intArrayOf(0, 0, -1, 1)

fun sideIndex(side: Side): Int = if (side == "L") 0 else if (side == "R") 1 else if (side == "T") 2 else 3

// Blocks up to this many cells are found through the cell map; larger ones are compared one by one.
private const val SMALL = 64
private const val OFFSET = 33554432L // 2 ** 25
private const val SPAN = 67108864L // 2 ** 26

fun placeOnGrid(nodes: List<ArchNode>, groups: List<ArchGroup>, edges: List<ArchEdge>, hints: List<ArchLayoutHint>): Grid {
  val N = nodes.size
  val G = groups.size
  val root = G
  val nodeIndex = LinkedHashMap<String, Int>()
  val groupIndex = LinkedHashMap<String, Int>()
  // Items are nodes (0..N-1) then groups (N..N+G-1). Containers are groups (0..G-1) then the root.
  val parent = IntArray(N + G) { root }
  val depth = IntArray(G + 1)
  val kids = ArrayList<MutableList<Int>>()
  for (c in 0..G) kids.add(ArrayList())
  groups.forEachIndexed { g, group ->
    groupIndex[group.id] = g
    val p = if (group.`in` == null) null else groupIndex[group.`in`]
    if (p != null && p != g) {
      parent[N + g] = p
      depth[g] = depth[p] + 1
    } else {
      depth[g] = 1
    }
  }
  nodes.forEachIndexed { i, node ->
    nodeIndex[node.id] = i
    val p = if (node.`in` == null) null else groupIndex[node.`in`!!]
    if (p != null) parent[i] = p
    kids[parent[i]].add(i)
  }
  for (g in 0 until G) kids[parent[N + g]].add(N + g)

  // A link asks for one node to sit beside another. It belongs to the innermost container that
  // holds both ends, and there it relates the two children of that container the ends are in.
  val linkA = ArrayList<Int>()
  val linkB = ArrayList<Int>()
  val sideA = ArrayList<Int>()
  val sideB = ArrayList<Int>()
  val itemA = ArrayList<Int>()
  val itemB = ArrayList<Int>()
  val hintLinks = arrayOfNulls<MutableList<Int>>(N + G)
  val edgeLinks = arrayOfNulls<MutableList<Int>>(N + G)
  fun addLink(a: Int, b: Int, sa: Int, sb: Int, lists: Array<MutableList<Int>?>) {
    if (a == b) return
    var ia = a
    var ib = b
    var ca = parent[a]
    var cb = parent[b]
    while (ca != cb) {
      if (depth[ca] >= depth[cb]) {
        ia = N + ca
        ca = parent[ia]
      } else {
        ib = N + cb
        cb = parent[ib]
      }
    }
    val k = linkA.size
    linkA.add(a)
    linkB.add(b)
    sideA.add(sa)
    sideB.add(sb)
    itemA.add(ia)
    itemB.add(ib)
    (lists[ia] ?: ArrayList<Int>().also { lists[ia] = it }).add(k)
    (lists[ib] ?: ArrayList<Int>().also { lists[ib] = it }).add(k)
  }
  for (hint in hints) {
    val row = hint.direction == "row"
    for (i in 1 until hint.members.size) {
      val a = nodeIndex[hint.members[i - 1]]
      val b = nodeIndex[hint.members[i]]
      if (a != null && b != null) addLink(a, b, if (row) 1 else 3, if (row) 0 else 2, hintLinks)
    }
  }
  // An edge between two like sides says least about where its ends go, so those come last.
  for (likeSides in listOf(false, true)) {
    for (edge in edges) {
      if ((edge.lhsDir == edge.rhsDir) != likeSides) continue
      val a = nodeIndex[edge.lhsId]
      val b = nodeIndex[edge.rhsId]
      if (a != null && b != null) addLink(a, b, sideIndex(edge.lhsDir), sideIndex(edge.rhsDir), edgeLinks)
    }
  }

  // Position of each item in its container, and its size in cells.
  val x = IntArray(N + G)
  val y = IntArray(N + G)
  val w = IntArray(N + G) { 1 }
  val h = IntArray(N + G) { 1 }
  val placed = IntArray(N + G)

  val order = ArrayList<Int>()
  val cells = LinkedHashMap<Long, Int>()
  val large = ArrayList<Int>()
  val reach = LinkedHashMap<String, Int>()
  var first = 0
  var ax = 0
  var ay = 0

  fun anchor(node: Int, item: Int) {
    ax = 0
    ay = 0
    var cur = node
    while (cur != item) {
      ax += x[cur]
      ay += y[cur]
      cur = N + parent[cur]
    }
  }

  fun put(item: Int, px: Int, py: Int) {
    x[item] = px
    y[item] = py
    placed[item] = 1
    order.add(item)
    if (w[item] * h[item] > SMALL) {
      large.add(item)
      return
    }
    for (cy in py until py + h[item]) {
      for (cx in px until px + w[item]) cells[(cx + OFFSET) * SPAN + cy + OFFSET] = item
    }
  }

  fun overlaps(item: Int, rx: Int, ry: Int, rw: Int, rh: Int): Boolean =
    x[item] < rx + rw && rx < x[item] + w[item] && y[item] < ry + rh && ry < y[item] + h[item]

  fun collide(rx: Int, ry: Int, rw: Int, rh: Int): Int {
    if (rw * rh > SMALL) {
      for (i in first until order.size) if (overlaps(order[i], rx, ry, rw, rh)) return order[i]
      return -1
    }
    for (cy in ry until ry + rh) {
      for (cx in rx until rx + rw) {
        val hit = cells[(cx + OFFSET) * SPAN + cy + OFFSET]
        if (hit != null) return hit
      }
    }
    for (item in large) if (overlaps(item, rx, ry, rw, rh)) return item
    return -1
  }

  // How far a block must slide along (sx, sy) to be clear. Each step jumps past what it hit, and
  // the result is kept, because a later block starting from the same place cannot stop sooner.
  fun slide(rx: Int, ry: Int, rw: Int, rh: Int, sx: Int, sy: Int): Int {
    val key = "$rx,$ry,$rw,$rh,$sx,$sy"
    var k = reach[key] ?: 0
    while (true) {
      val hit = collide(rx + k * sx, ry + k * sy, rw, rh)
      if (hit < 0) break
      k = if (sx > 0) x[hit] + w[hit] - rx else if (sx < 0) rx + rw - x[hit] else if (sy > 0) y[hit] + h[hit] - ry else ry + rh - y[hit]
    }
    reach[key] = k
    return k
  }

  fun follow(from: Int, k: Int) {
    val forward = itemA[k] == from
    val to = if (forward) itemB[k] else itemA[k]
    if (placed[to] != 0) return
    val sp = if (forward) sideA[k] else sideB[k]
    val sq = if (forward) sideB[k] else sideA[k]
    anchor(if (forward) linkA[k] else linkB[k], from)
    val pax = ax
    val pay = ay
    anchor(if (forward) linkB[k] else linkA[k], to)
    var dx = DX[sp]
    var dy = DY[sp]
    var shiftX = 0
    var shiftY = 0
    val facing = DX[sp] + DX[sq] == 0 && DY[sp] + DY[sq] == 0
    if (sp == sq) {
      // Like sides: the edge loops around, so the nodes stack across the direction it leaves in.
      dx = if (DX[sp] == 0) 1 else 0
      dy = 1 - dx
    } else if (!facing) {
      // A bend: one step along the side the edge leaves, one step back from the side it enters.
      shiftX = -DX[sq]
      shiftY = -DY[sq]
    }
    var bx: Int
    var by: Int
    if (dx != 0) {
      bx = if (dx > 0) x[from] + w[from] else x[from] - w[to]
      by = y[from] + pay + shiftY - ay
    } else {
      by = if (dy > 0) y[from] + h[from] else y[from] - h[to]
      bx = x[from] + pax + shiftX - ax
    }
    if (facing) {
      // When several placed nodes reach this one through the same pair of sides, line it up
      // with the middle one, not with whichever happened to be visited first.
      val list = edgeLinks[to]
      if (list != null && list.size > 1) {
        val wanted = ArrayList<Int>()
        for (j in list) {
          val toIsB = itemB[j] == to
          val other = if (toIsB) itemA[j] else itemB[j]
          if (placed[other] == 0 || (if (toIsB) sideA[j] else sideB[j]) != sp || (if (toIsB) sideB[j] else sideA[j]) != sq) continue
          anchor(if (toIsB) linkA[j] else linkB[j], other)
          val at = if (dx != 0) y[other] + ay else x[other] + ax
          anchor(if (toIsB) linkB[j] else linkA[j], to)
          wanted.add(at - (if (dx != 0) ay else ax))
        }
        if (wanted.size > 1) {
          wanted.sort()
          if (dx != 0) by = wanted[(wanted.size - 1) shr 1] else bx = wanted[(wanted.size - 1) shr 1]
        }
      }
      // Taken: move sideways, to whichever side is nearer, so the edge can still leave straight.
      val px = if (dx == 0) 1 else 0
      val py = 1 - px
      val forth = slide(bx, by, w[to], h[to], px, py)
      val back = if (forth == 0) 0 else slide(bx, by, w[to], h[to], -px, -py)
      val near = if (forth <= back) forth else -back
      bx += px * near
      by += py * near
    } else {
      val far = slide(bx, by, w[to], h[to], dx, dy)
      bx += dx * far
      by += dy * far
    }
    put(to, bx, by)
  }

  fun layout(c: Int) {
    val items = kids[c]
    if (items.size == 0) return
    order.clear()
    val starts = ArrayList<Int>()
    for (start in items) {
      if (placed[start] != 0) continue
      first = order.size
      starts.add(first)
      cells.clear()
      large.clear()
      reach.clear()
      put(start, 0, 0)
      // Declared alignments are followed as soon as a node is placed, ahead of any edge.
      var hintAt = first
      var edgeAt = first
      var linkAt = 0
      while (true) {
        if (hintAt < order.size) {
          val from = order[hintAt++]
          val list = hintLinks[from]
          if (list != null) for (k in list) follow(from, k)
          continue
        }
        if (edgeAt >= order.size) break
        val list = edgeLinks[order[edgeAt]]
        if (list == null || linkAt >= list.size) {
          edgeAt++
          linkAt = 0
          continue
        }
        follow(order[edgeAt], list[linkAt++])
      }
    }
    starts.add(order.size)

    // Unconnected parts go side by side, wrapping into rows that keep the whole roughly square.
    val count = starts.size - 1
    val minX = IntArray(count)
    val minY = IntArray(count)
    val partW = IntArray(count)
    val partH = IntArray(count)
    var area = 0.0
    var widest = 0
    for (p in 0 until count) {
      var x0 = Int.MAX_VALUE
      var y0 = Int.MAX_VALUE
      var x1 = Int.MIN_VALUE
      var y1 = Int.MIN_VALUE
      for (i in starts[p] until starts[p + 1]) {
        val item = order[i]
        x0 = minOf(x0, x[item])
        y0 = minOf(y0, y[item])
        x1 = maxOf(x1, x[item] + w[item])
        y1 = maxOf(y1, y[item] + h[item])
      }
      minX[p] = x0
      minY[p] = y0
      partW[p] = x1 - x0
      partH[p] = y1 - y0
      area += partW[p].toDouble() * partH[p]
      widest = maxOf(widest, partW[p])
    }
    val limit = maxOf(widest.toDouble(), ceil(sqrt(area * 2)))
    var cx = 0
    var cy = 0
    var shelf = 0
    var width = 0
    for (p in 0 until count) {
      if (cx > 0 && cx + partW[p] > limit) {
        cy += shelf
        cx = 0
        shelf = 0
      }
      for (i in starts[p] until starts[p + 1]) {
        x[order[i]] += cx - minX[p]
        y[order[i]] += cy - minY[p]
      }
      cx += partW[p]
      shelf = maxOf(shelf, partH[p])
      width = maxOf(width, cx)
    }
    if (c < G) {
      w[N + c] = width
      h[N + c] = cy + shelf
    }
  }
  for (c in G - 1 downTo 0) layout(c)
  layout(root)

  val groupCol = IntArray(G)
  val groupRow = IntArray(G)
  val groupParent = IntArray(G)
  val groupEmpty = IntArray(G)
  for (g in 0 until G) {
    val p = parent[N + g]
    groupParent[g] = if (p == root) -1 else p
    groupCol[g] = x[N + g] + (if (p == root) 0 else groupCol[p])
    groupRow[g] = y[N + g] + (if (p == root) 0 else groupRow[p])
    if (kids[g].size == 0) groupEmpty[g] = 1
  }
  val col = IntArray(N)
  val row = IntArray(N)
  val nodeParent = IntArray(N)
  for (i in 0 until N) {
    val p = parent[i]
    nodeParent[i] = if (p == root) -1 else p
    col[i] = x[i] + (if (p == root) 0 else groupCol[p])
    row[i] = y[i] + (if (p == root) 0 else groupRow[p])
  }
  return Grid(
    col,
    row,
    groupCol,
    groupRow,
    w.copyOfRange(N, N + G),
    h.copyOfRange(N, N + G),
    groupParent,
    groupEmpty,
    nodeParent,
  )
}
