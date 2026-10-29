// Ported from Pele src/svg/cluster.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import pele.IconResolver
import pele.js.jsRound
import pele.layout.CNode
import pele.text.Label
import kotlin.math.abs
import kotlin.math.min

// The x of every route point, by its rounded y: the edges that cross a cluster's top border.
// PORT: keys are normalized with + 0.0, as JS Map keys treat -0 and 0 as one.
typealias Crossings = HashMap<Double, MutableList<Double>>

fun markCrossings(crossings: Crossings, route: List<Double>) {
  var k = 0
  while (k < route.size) {
    val key = jsRound(route[k + 1]) + 0.0
    val list = crossings[key]
    if (list != null) list.add(route[k])
    else crossings[key] = arrayListOf(route[k])
    k += 3
  }
}

// Picks where a cluster title sits along the top edge so that edges entering there do not cross it.
fun clusterTitleX(c: CNode, width: Double, crossings: Crossings, pad: Double): Double {
  if (width == 0.0) return c.x
  val top = c.y - c.h / 2
  val left = c.x - c.w / 2
  val half = width / 2
  val candidates = doubleArrayOf(left + pad - 6 + half, c.x, left + c.w - pad + 6 - half)
  val xs = crossings[jsRound(top) + 0.0] ?: return candidates[0]
  var best = candidates[0]
  var bestGap = -1.0
  for (x in candidates) {
    var gap = Double.POSITIVE_INFINITY
    for (cx in xs) if (cx > left && cx < left + c.w) gap = min(gap, abs(cx - x) - half)
    if (gap >= 6) return x
    if (gap > bestGap + 0.5) {
      bestGap = gap
      best = x
    }
  }
  return best
}

// Whether an edge that enters at the top runs through a title placed at `x`.
fun titleCrossed(c: CNode, width: Double, x: Double, crossings: Crossings): Boolean {
  val xs = (if (width > 0) crossings[jsRound(c.y - c.h / 2) + 0.0] else null) ?: return false
  val left = c.x - c.w / 2
  for (cx in xs) if (cx > left && cx < left + c.w && abs(cx - x) < width / 2 + 4) return true
  return false
}

// A title that an edge runs through, to be drawn over the edges: the text, on an outline in the
// color of the cluster behind it, so that only the line is seen to stop short of the letters.
// `nodes` and `index` find how many clusters deep the title is, each of which tints the background.
fun struckTitle(
  label: Label,
  x: Double,
  y: Double,
  attrs: String,
  id: String,
  nodes: List<CNode>,
  index: Int,
  icons: IconResolver? = null,
): String {
  var depth = 1
  var p = nodes[index].parent
  while (p >= 0 && depth < 8) {
    depth++
    p = nodes[p].parent
  }
  // Each cluster is half its fill over what is behind it.
  val fill = "color-mix(in srgb,var(--_a) ${num(100 * (1 - StrictMath.pow(0.5, depth.toDouble())))}%,var(--_bg))"
  return "<g class=\"pele-cluster-title\" data-id=\"$id\">" +
    labelSvg(label, x, y, " fill=\"none\" stroke=\"$fill\" stroke-width=\"5\" stroke-linejoin=\"round\"", icons) +
    labelSvg(label, x, y, attrs, icons) +
    "</g>"
}
