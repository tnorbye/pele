// Ported from Pele src/diagrams/sankey/layout.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sankey

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import java.lang.StrictMath.pow

// 'justify' | 'left' | 'right' | 'center'
typealias Alignment = String

class LayoutOptions(
  val width: Double,
  val height: Double,
  val nodeWidth: Double,
  val nodePadding: Double,
  // Height of a label line; thin nodes are kept at least this far apart where there is room.
  val labelHeight: Double,
  val alignment: Alignment,
)

class PlacedNode(
  val layer: Int,
  // Total flow through the node, in the diagram's units.
  val value: Double,
  var x: Double,
  var y: Double,
  var height: Double,
)

class PlacedLink(
  val source: Int,
  val target: Int,
  val value: Double,
  var thickness: Double,
  // Vertical centers of the band where it leaves its source and where it enters its target.
  var sourceY: Double,
  var targetY: Double,
)

class SankeyLayout(val nodes: List<PlacedNode>, val links: List<PlacedLink>)

private const val PASSES = 6

// Most nodes have a handful of bands, and the built-in sort is slow to start on short lists.
private fun sort(list: MutableList<Int>, compare: (Int, Int) -> Double) {
  if (list.size > 8) {
    list.sortWith { a, b ->
      val r = compare(a, b)
      if (r > 0) 1 else if (r < 0) -1 else 0
    }
    return
  }
  for (i in 1 until list.size) {
    val item = list[i]
    var j = i - 1
    while (j >= 0 && compare(list[j], item) > 0) {
      list[j + 1] = list[j]
      j--
    }
    list[j + 1] = item
  }
}

// `a || b` on numbers
private fun orElse(a: Double, b: Double): Double = if (a != 0.0 && !a.isNaN()) a else b

// Places nodes in columns by their distance from a source, sizes them by their flow, and
// then lets each column settle next to its neighbours so that the bands cross less.
fun layoutSankey(model: SankeyModel, options: LayoutOptions): SankeyLayout {
  val n = model.nodes.size
  val links: List<PlacedLink> = model.links.map { link ->
    PlacedLink(
      source = link.source.index,
      target = link.target.index,
      value = if (link.value.isFinite() && link.value > 0) link.value else 0.0,
      thickness = 0.0,
      sourceY = 0.0,
      targetY = 0.0,
    )
  }
  val outgoing = ArrayList<MutableList<Int>>()
  val incoming = ArrayList<MutableList<Int>>()
  for (i in 0 until n) {
    outgoing.add(ArrayList())
    incoming.add(ArrayList())
  }
  links.forEachIndexed { k, link ->
    outgoing[link.source].add(k)
    incoming[link.target].add(k)
  }

  // Longest distance from a source, in topological order; what is left over lies on a cycle.
  val depth = IntArray(n)
  val pending = IntArray(n)
  val order = ArrayList<Int>()
  for (i in 0 until n) {
    pending[i] = incoming[i].size
    if (pending[i] == 0) order.add(i)
  }
  var q = 0
  while (q < order.size) {
    val i = order[q]
    for (k in outgoing[i]) {
      val t = links[k].target
      if (depth[t] < depth[i] + 1) depth[t] = depth[i] + 1
      if (--pending[t] == 0) order.add(t)
    }
    q++
  }
  if (order.size < n) {
    // Every node left over has a predecessor that is left over too; walking back finds the loop.
    var stuck = 0
    while (pending[stuck] == 0) stuck++
    val seen = ByteArray(n)
    while (seen[stuck].toInt() == 0) {
      seen[stuck] = 1
      for (k in incoming[stuck]) {
        if (pending[links[k].source] > 0) {
          stuck = links[k].source
          break
        }
      }
    }
    throw PeleError(
      "Sankey diagrams cannot have circular links, and \"${model.nodes[stuck].id}\" is part of one.",
      PeleErrorCode.SEMANTIC,
      PeleErrorInfo(type = "sankey"),
    )
  }
  var layers = 1
  for (i in 0 until n) if (depth[i] + 1 > layers) layers = depth[i] + 1
  val reach = IntArray(n)
  for (qq in n - 1 downTo 0) {
    val i = order[qq]
    for (k in outgoing[i]) if (reach[i] < reach[links[k].target] + 1) reach[i] = reach[links[k].target] + 1
  }

  // Flows are scaled to the largest one, so that sums of huge values stay finite.
  var largest = 0.0
  for (link in links) if (link.value > largest) largest = link.value
  val unit = if (largest > 0) 1 / largest else 0.0

  val nodes = ArrayList<PlacedNode>()
  val flow = DoubleArray(n)
  val columns = ArrayList<MutableList<Int>>()
  for (l in 0 until layers) columns.add(ArrayList())
  val step = if (layers > 1) (options.width - options.nodeWidth) / (layers - 1) else 0.0
  for (i in 0 until n) {
    var layer: Int
    if (options.alignment == "left") {
      layer = depth[i]
    } else if (options.alignment == "right") {
      layer = layers - 1 - reach[i]
    } else if (options.alignment == "center") {
      layer = depth[i]
      if (incoming[i].size == 0 && outgoing[i].size > 0) {
        layer = layers
        for (k in outgoing[i]) if (depth[links[k].target] - 1 < layer) layer = depth[links[k].target] - 1
      }
    } else {
      layer = if (outgoing[i].size > 0) depth[i] else layers - 1
    }
    var into = 0.0
    var out = 0.0
    var value = 0.0
    var valueOut = 0.0
    for (k in incoming[i]) {
      into += links[k].value * unit
      value += links[k].value
    }
    for (k in outgoing[i]) {
      out += links[k].value * unit
      valueOut += links[k].value
    }
    flow[i] = maxOf(into, out)
    nodes.add(PlacedNode(layer, maxOf(value, valueOut), layer * step, 0.0, 0.0))
    columns[layer].add(i)
  }

  var tallest = 1
  for (column in columns) if (column.size > tallest) tallest = column.size
  val gap = if (tallest > 1) minOf(options.nodePadding, options.height / (tallest - 1)) else options.nodePadding
  var scale = Double.POSITIVE_INFINITY
  for (column in columns) {
    var sum = 0.0
    for (i in column) sum += flow[i]
    if (sum > 0) scale = minOf(scale, (options.height - (column.size - 1) * gap) / sum)
  }
  if (!scale.isFinite() || scale < 0) scale = 0.0

  // A thin node still needs room for its label. Where every column can afford it, each node
  // claims at least a label's height, and the flows are scaled down to make that fit.
  var claim = maxOf(0.0, options.labelHeight - gap)
  val fits = { s: Double ->
    var ok = true
    for (column in columns) {
      var extent = (column.size - 1) * gap
      for (i in column) extent += maxOf(flow[i] * s, claim)
      if (extent > options.height + 1e-6) {
        ok = false
        break
      }
    }
    ok
  }
  if (claim > 0 && !fits(scale)) {
    if (fits(0.0)) {
      var low = 0.0
      for (k in 0 until 40) {
        val mid = (low + scale) / 2
        if (fits(mid)) low = mid
        else scale = mid
      }
      scale = low
    } else {
      claim = 0.0
    }
  }

  // The room a node keeps clear above and below itself.
  val margin = DoubleArray(n)
  for (link in links) link.thickness = link.value * unit * scale
  for (column in columns) {
    var y = 0.0
    for (i in column) {
      nodes[i].height = flow[i] * scale
      margin[i] = maxOf(0.0, claim - nodes[i].height) / 2
      nodes[i].y = y + margin[i]
      y += nodes[i].height + 2 * margin[i] + gap
    }
    // Spare room is shared out between the nodes of a column.
    val spare = (options.height - y + gap) / (column.size + 1)
    column.forEachIndexed { k, i -> nodes[i].y += spare * (k + 1) }
  }

  val center = { i: Int -> nodes[i].y + nodes[i].height / 2 }

  // Bands leave and enter a node in the order of where they go, so they do not cross at it.
  // These are the offsets of each band's middle from the top of the node at either end.
  val sourceOffset = DoubleArray(links.size)
  val targetOffset = DoubleArray(links.size)
  val byTarget = { a: Int, b: Int -> orElse(center(links[a].target) - center(links[b].target), (a - b).toDouble()) }
  val bySource = { a: Int, b: Int -> orElse(center(links[a].source) - center(links[b].source), (a - b).toDouble()) }
  val byCenter = { a: Int, b: Int -> orElse(center(a) - center(b), (a - b).toDouble()) }
  val stackOut = { i: Int ->
    var y = 0.0
    sort(outgoing[i], byTarget)
    for (k in outgoing[i]) {
      sourceOffset[k] = y + links[k].thickness / 2
      y += links[k].thickness
    }
  }
  val stackIn = { i: Int ->
    var y = 0.0
    sort(incoming[i], bySource)
    for (k in incoming[i]) {
      targetOffset[k] = y + links[k].thickness / 2
      y += links[k].thickness
    }
  }
  val stackAll = {
    for (i in 0 until n) {
      stackOut(i)
      stackIn(i)
    }
  }

  // Moves each node of a column towards where its bands would run level, then restores the
  // order and the gaps. `forward` looks at the bands coming in, otherwise at those going out.
  val settle = { column: MutableList<Int>, pull: Double, forward: Boolean ->
    for (i in column) {
      val list = if (forward) incoming[i] else outgoing[i]
      if (list.size == 0) continue
      if (forward) stackIn(i)
      else stackOut(i)
      var weighted = 0.0
      var weight = 0.0
      var plain = 0.0
      for (k in list) {
        val level =
          if (forward) nodes[links[k].source].y + sourceOffset[k] - targetOffset[k]
          else nodes[links[k].target].y + targetOffset[k] - sourceOffset[k]
        weighted += level * links[k].thickness
        weight += links[k].thickness
        plain += center(if (forward) links[k].source else links[k].target) - nodes[i].height / 2
      }
      val wanted = if (weight > 0) weighted / weight else plain / list.size
      nodes[i].y += (wanted - nodes[i].y) * pull
    }
    sort(column, byCenter)
    var y = 0.0
    for (i in column) {
      if (nodes[i].y - margin[i] < y) nodes[i].y = y + margin[i]
      y = nodes[i].y + nodes[i].height + margin[i] + gap
    }
    y = options.height
    for (k in column.size - 1 downTo 0) {
      val i = column[k]
      if (nodes[i].y + nodes[i].height + margin[i] > y) nodes[i].y = y - nodes[i].height - margin[i]
      y = nodes[i].y - margin[i] - gap
    }
  }

  stackAll()
  for (pass in 0 until PASSES) {
    val pull = pow(0.99, pass.toDouble())
    for (l in layers - 2 downTo 0) settle(columns[l], pull, false)
    stackAll()
    for (l in 1 until layers) settle(columns[l], pull, true)
    stackAll()
  }
  for (k in 0 until links.size) {
    links[k].sourceY = nodes[links[k].source].y + sourceOffset[k]
    links[k].targetY = nodes[links[k].target].y + targetOffset[k]
  }

  return SankeyLayout(nodes, links)
}
