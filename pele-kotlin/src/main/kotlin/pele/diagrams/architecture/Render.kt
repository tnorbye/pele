// Ported from Pele src/diagrams/architecture/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.architecture

import pele.Config
import pele.IconResolver
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.tighten
import pele.diagrams.common.titleRoom
import pele.js.charCodeAt
import pele.js.jsHypot
import pele.js.jsOr
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsStr
import pele.js.jsTrimEnd
import pele.svg.ROUNDED
import pele.svg.edgeLabelSvg
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Span
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val STUB = 12.0
private const val CORNER = 6.0
private const val LABEL_GAP = 4.0
private const val DOT = 3.0
private val DX = doubleArrayOf(-1.0, 1.0, 0.0, 0.0)
private val DY = doubleArrayOf(0.0, 0.0, -1.0, 1.0)

private fun setting(config: Config, key: String, fallback: Double, min: Double, max: Double): Double {
  val section = config["architecture"]
  val value = if (section is Map<*, *>) section[key] else null
  return if (value is Double && value.isFinite()) minOf(max, maxOf(min, value)) else fallback
}

// Maps the grid lines in use to consecutive indexes, so empty rows and columns take no room.
private fun rank(values: MutableList<Int>): Map<Int, Int> {
  val out = LinkedHashMap<Int, Int>()
  values.sort()
  for (value in values) if (!out.containsKey(value)) out[value] = out.size
  return out
}

private fun iconSlot(name: String, x: Double, y: Double, side: Double, icons: IconResolver?): String =
  "<svg class=\"pele-icon\" data-icon=\"${escText(name)}\" x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(side)}\" height=\"${num(side)}\"" +
    " viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">${
      icons?.invoke(name) ?: builtinIcon(name)
    }</svg>"

// Cuts a label down to a box, ending what is left with an ellipsis.
private fun fit(label: Label, maxWidth: Double, maxLines: Int, measurer: TextMeasurer): Label {
  val cut = label.lines.size > maxLines
  if (!cut && label.width <= maxWidth) return label
  val dots = measurer.width("…", label.size, 0)
  val lines = ArrayList<MutableList<Span>>()
  val widths = ArrayList<Double>()
  var widest = 0.0
  for (i in 0 until minOf(maxLines, label.lines.size)) {
    val line = label.lines[i].map { span -> span.copy() }.toMutableList()
    var width = label.widths[i]
    if (width > maxWidth || (cut && i == maxLines - 1)) {
      while (true) {
        val span = line.lastOrNull()
        if (span == null) {
          line.add(Span(text = "…", style = 0, width = dots))
          width = dots
          break
        }
        val rest = width - span.width
        if (span.icon == null && rest + dots < maxWidth) {
          var lo = 0
          var hi = span.text.length
          while (lo < hi) {
            val mid = (lo + hi + 1) shr 1
            if (rest + measurer.width(span.text.jsSlice(0, mid), label.size, span.style) + dots <= maxWidth) lo = mid else hi = mid - 1
          }
          val code = if (lo > 0) span.text.charCodeAt(lo - 1) else 0
          if (code >= 0xd800 && code <= 0xdbff) lo--
          span.text = span.text.jsSlice(0, lo).jsTrimEnd() + "…"
          span.width = measurer.width(span.text, label.size, span.style)
          width = rest + span.width
          break
        }
        line.removeAt(line.size - 1)
        width = rest
      }
    }
    lines.add(line)
    widths.add(width)
    widest = maxOf(widest, width)
  }
  return label.copy(lines = lines, widths = widths, width = widest, height = lines.size * label.lineHeight)
}

private class Port(
  var x: Double,
  var y: Double,
  val dx: Double,
  val dy: Double,
  // The box the route has to stay out of when it cannot leave straight.
  var x0: Double,
  var y0: Double,
  var x1: Double,
  var y1: Double,
  // How far short of the port an arrowhead stops: the radius of a junction's dot.
  var gap: Double,
  // Set where a junction is reached through the border of its group: the line carries on to the junction.
  var wire: Boolean,
)

// An orthogonal route from one port to another, each left along its side's normal.
private fun route(s: Port, e: Port): MutableList<Double> {
  val sx = s.x
  val sy = s.y
  val sdx = s.dx
  val sdy = s.dy
  val ex = e.x
  val ey = e.y
  val edx = e.dx
  val edy = e.dy
  val s1x = sx + sdx * STUB
  val s1y = sy + sdy * STUB
  val e1x = ex + edx * STUB
  val e1y = ey + edy * STUB
  if (sdx * edx + sdy * edy == 0.0) {
    val cx = if (sdx != 0.0) ex else sx
    val cy = if (sdx != 0.0) sy else ey
    if ((cx - sx) * sdx + (cy - sy) * sdy > 0 && (cx - ex) * edx + (cy - ey) * edy > 0) return mutableListOf(sx, sy, cx, cy, ex, ey)
    return if (sdx != 0.0) {
      mutableListOf(sx, sy, s1x, s1y, s1x, e1y, e1x, e1y, ex, ey)
    } else {
      mutableListOf(sx, sy, s1x, s1y, e1x, s1y, e1x, e1y, ex, ey)
    }
  }
  val sideways = sdx != 0.0
  val offset = if (sideways) ey - sy else ex - sx
  if (sdx == -edx && sdy == -edy) {
    val ahead = (ex - sx) * sdx + (ey - sy) * sdy
    if (ahead > 0) {
      if (abs(offset) < 0.5) return mutableListOf(sx, sy, ex, ey)
      // Turn midway between the two boxes, which is clear of both titles, when there is such a place.
      if (sideways) {
        val lo = if (sdx > 0) s.x1 else e.x1
        val hi = if (sdx > 0) e.x0 else s.x0
        val mx = if (lo < hi) (lo + hi) / 2 else (sx + ex) / 2
        return mutableListOf(sx, sy, mx, sy, mx, ey, ex, ey)
      }
      val lo = if (sdy > 0) s.y1 else e.y1
      val hi = if (sdy > 0) e.y0 else s.y0
      val my = if (lo < hi) (lo + hi) / 2 else (sy + ey) / 2
      return mutableListOf(sx, sy, sx, my, ex, my, ex, ey)
    }
  } else if (abs(offset) >= 1) {
    if (sideways) {
      val x = if (sdx > 0) maxOf(sx, ex) + STUB else minOf(sx, ex) - STUB
      return mutableListOf(sx, sy, x, sy, x, ey, ex, ey)
    }
    val y = if (sdy > 0) maxOf(sy, ey) + STUB else minOf(sy, ey) - STUB
    return mutableListOf(sx, sy, sx, y, ex, y, ex, ey)
  }
  // The straight way is blocked by one of the two nodes: go between them, or around both.
  if (sideways) {
    val lane = if (s.y1 + STUB <= e.y0) (s.y1 + e.y0) / 2 else if (e.y1 + STUB <= s.y0) (e.y1 + s.y0) / 2 else maxOf(s.y1, e.y1) + STUB
    return mutableListOf(sx, sy, s1x, sy, s1x, lane, e1x, lane, e1x, ey, ex, ey)
  }
  val lane = if (s.x1 + STUB <= e.x0) (s.x1 + e.x0) / 2 else if (e.x1 + STUB <= s.x0) (e.x1 + s.x0) / 2 else maxOf(s.x1, e.x1) + STUB
  return mutableListOf(sx, sy, sx, s1y, lane, s1y, lane, e1y, ex, e1y, ex, ey)
}

// Drops repeated points and joins runs that continue in a straight line.
private fun simplify(p: List<Double>): MutableList<Double> {
  val out = mutableListOf(p[0], p[1])
  var i = 2
  while (i < p.size) {
    val x = p[i]
    val y = p[i + 1]
    val n = out.size
    val sameX = abs(x - out[n - 2]) < 0.01
    val sameY = abs(y - out[n - 1]) < 0.01
    if (sameX && sameY) {
      i += 2
      continue
    }
    if (n >= 4 && ((sameX && abs(out[n - 4] - out[n - 2]) < 0.01) || (sameY && abs(out[n - 3] - out[n - 1]) < 0.01))) {
      out[n - 2] = x
      out[n - 1] = y
    } else {
      out.add(x)
      out.add(y)
    }
    i += 2
  }
  return out
}

private fun pathData(p: List<Double>, startTrim: Double, endTrim: Double): String {
  val n = p.size / 2
  val xs = DoubleArray(n)
  val ys = DoubleArray(n)
  for (i in 0 until n) {
    xs[i] = p[i * 2]
    ys[i] = p[i * 2 + 1]
  }
  fun trim(at: Int, toward: Int, amount: Double) {
    val len = jsHypot(xs[toward] - xs[at], ys[toward] - ys[at])
    val t = minOf(amount, maxOf(0.0, len - 1))
    if (len > 0 && t > 0) {
      xs[at] += ((xs[toward] - xs[at]) / len) * t
      ys[at] += ((ys[toward] - ys[at]) / len) * t
    }
  }
  trim(0, 1, startTrim)
  trim(n - 1, n - 2, endTrim)
  var d = "M${num(xs[0])},${num(ys[0])}"
  for (i in 1 until n - 1) {
    val before = jsHypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    val after = jsHypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
    val r = minOf(CORNER, before / 2, after / 2)
    if (r < 0.5) {
      d += "L${num(xs[i])},${num(ys[i])}"
      continue
    }
    val inX = xs[i] - ((xs[i] - xs[i - 1]) / before) * r
    val inY = ys[i] - ((ys[i] - ys[i - 1]) / before) * r
    val outX = xs[i] + ((xs[i + 1] - xs[i]) / after) * r
    val outY = ys[i] + ((ys[i + 1] - ys[i]) / after) * r
    d += "L${num(inX)},${num(inY)}Q${num(xs[i])},${num(ys[i])} ${num(outX)},${num(outY)}"
  }
  return d + "L${num(xs[n - 1])},${num(ys[n - 1])}"
}

fun renderArchitecture(model: ArchitectureModel, config: Config, options: RenderOptions): Rendered =
  tighten(options) { tight -> draw(model, config, options, tight) }

private fun draw(model: ArchitectureModel, config: Config, options: RenderOptions, tight: Boolean): Rendered {
  val iconSize = setting(config, "iconSize", 48.0, 8.0, 512.0)
  val size = setting(config, "fontSize", options.fontSize ?: 16.0, 4.0, 128.0)
  val groupPad = minOf(setting(config, "padding", 20.0, 0.0, 256.0), if (tight) 12.0 else Double.POSITIVE_INFINITY)
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = options.icons
  val half = iconSize / 2

  val nodes = model.nodes.values.toList()
  val groups = model.groups.values.toList()
  val N = nodes.size
  val G = groups.size
  val grid = placeOnGrid(nodes, groups, model.edges, model.layoutHints)
  val index = LinkedHashMap<String, Int>()
  nodes.forEachIndexed { i, node -> index[node.id] = i }

  // A service is its icon square with the title beneath; a junction takes the same room.
  val labels = ArrayList<Label>(N)
  val boxW = DoubleArray(N)
  val boxH = DoubleArray(N)
  val wrap = if (tight) maxOf(iconSize, 64.0) else maxOf(iconSize * 2.25, 96.0)
  for (i in 0 until N) {
    val node = nodes[i]
    val label = layoutLabel(if (node is ArchService) node.title else null, true, measurer, size, wrap)
    labels.add(label)
    boxW[i] = maxOf(iconSize, 2 * ceil(label.width / 2))
    boxH[i] = iconSize + (if (label.height > 0) LABEL_GAP + label.height else 0.0)
  }

  val colValues = ArrayList<Int>()
  val rowValues = ArrayList<Int>()
  for (i in 0 until N) {
    colValues.add(grid.col[i])
    rowValues.add(grid.row[i])
  }
  for (g in 0 until G) {
    if (grid.groupEmpty[g] == 0) continue
    colValues.add(grid.groupCol[g])
    rowValues.add(grid.groupRow[g])
  }
  val colOf = rank(colValues)
  val rowOf = rank(rowValues)
  val C = colOf.size
  val R = rowOf.size
  val colW = DoubleArray(C)
  val rowH = DoubleArray(R)
  val gap = if (tight) maxOf(24.0, 2 * STUB) else maxOf(40.0, jsRound(iconSize * 0.75))
  val gapX = DoubleArray(C) { gap }
  val gapY = DoubleArray(R) { gap }
  val nodeCol = IntArray(N)
  val nodeRow = IntArray(N)
  for (i in 0 until N) {
    val c = colOf[grid.col[i]] ?: 0
    nodeCol[i] = c
    val r = rowOf[grid.row[i]] ?: 0
    nodeRow[i] = r
    colW[c] = maxOf(colW[c], boxW[i])
    rowH[r] = maxOf(rowH[r], boxH[i])
  }
  for (g in 0 until G) {
    if (grid.groupEmpty[g] == 0) continue
    val c = colOf[grid.groupCol[g]] ?: 0
    val r = rowOf[grid.groupRow[g]] ?: 0
    colW[c] = maxOf(colW[c], iconSize)
    rowH[r] = maxOf(rowH[r], half)
  }

  // An edge label needs the gap its edge crosses to be wide enough to hold it.
  val E = model.edges.size
  val edgeLabels = ArrayList<Label>(E)
  val ends = IntArray(E * 2)
  for (k in 0 until E) {
    val edge = model.edges[k]
    val a = index[edge.lhsId] ?: -1
    ends[k * 2] = a
    val b = index[edge.rhsId] ?: -1
    ends[k * 2 + 1] = b
    val label = layoutLabel(edge.title, true, measurer, small, 160.0)
    edgeLabels.add(label)
    if (label.width == 0.0 || a < 0 || b < 0) continue
    if (nodeCol[a] != nodeCol[b]) {
      val c = minOf(nodeCol[a], nodeCol[b])
      gapX[c] = maxOf(gapX[c], ceil(label.width) + 28)
    } else {
      colW[nodeCol[a]] = maxOf(colW[nodeCol[a]], 2 * ceil(label.width / 2) + 8)
      if (nodeRow[a] != nodeRow[b]) {
        val r = minOf(nodeRow[a], nodeRow[b])
        gapY[r] = maxOf(gapY[r], ceil(label.height) + 24)
      }
    }
  }

  // Room around each group, kept on the rows and columns at its edges. A group that shares an
  // edge with a group inside it stands that much further out. Children come after their parents,
  // so walking backwards settles every group before the one that holds it.
  val groupLabels = ArrayList<Label>(G)
  val c0 = IntArray(G)
  val c1 = IntArray(G)
  val r0 = IntArray(G)
  val r1 = IntArray(G)
  val insL = DoubleArray(G)
  val insR = DoubleArray(G)
  val insT = DoubleArray(G)
  val insB = DoubleArray(G)
  val headW = DoubleArray(G)
  val headH = DoubleArray(G)
  val colL = DoubleArray(C)
  val colR = DoubleArray(C)
  val rowT = DoubleArray(R)
  val rowB = DoubleArray(R)
  val badge = jsRound(size * 1.25)
  for (g in 0 until G) {
    c0[g] = colOf[grid.groupCol[g]] ?: 0
    c1[g] = colOf[grid.groupCol[g] + grid.groupCols[g] - 1] ?: 0
    r0[g] = rowOf[grid.groupRow[g]] ?: 0
    r1[g] = rowOf[grid.groupRow[g] + grid.groupRows[g] - 1] ?: 0
    val label = layoutLabel(groups[g].title, true, measurer, size, if (tight) maxOf(80.0, titleRoom(options) / 2) else 320.0)
    groupLabels.add(label)
    val icon = if (!groups[g].icon.isNullOrEmpty()) badge else 0.0
    headW[g] = label.width + icon + (if (label.width > 0 && icon > 0) 6.0 else 0.0)
    headH[g] = maxOf(label.height, icon)
  }
  for (g in G - 1 downTo 0) {
    val left = groupPad + insL[g]
    var right = groupPad + insR[g]
    val top = (if (headH[g] > 0) maxOf(groupPad, headH[g] + 12) else groupPad) + insT[g]
    val bottom = groupPad + insB[g]
    if (headW[g] > 0) {
      var inner = left + right
      for (c in c0[g]..c1[g]) inner += colW[c] + (if (c < c1[g]) colR[c] + gapX[c] + colL[c + 1] else 0.0)
      if (headW[g] + 20 > inner) right += ceil(headW[g] + 20 - inner)
    }
    insL[g] = left
    insR[g] = right
    insT[g] = top
    insB[g] = bottom
    colL[c0[g]] = maxOf(colL[c0[g]], left)
    colR[c1[g]] = maxOf(colR[c1[g]], right)
    rowT[r0[g]] = maxOf(rowT[r0[g]], top)
    rowB[r1[g]] = maxOf(rowB[r1[g]], bottom)
    val p = grid.groupParent[g]
    if (p < 0) continue
    if (c0[p] == c0[g]) insL[p] = maxOf(insL[p], left)
    if (c1[p] == c1[g]) insR[p] = maxOf(insR[p], right)
    if (r0[p] == r0[g]) insT[p] = maxOf(insT[p], top)
    if (r1[p] == r1[g]) insB[p] = maxOf(insB[p], bottom)
  }

  val X = DoubleArray(C)
  val Y = DoubleArray(R)
  var innerW = 0.0
  for (c in 0 until C) {
    X[c] = innerW + colL[c]
    innerW = X[c] + colW[c] + colR[c] + (if (c < C - 1) gapX[c] else 0.0)
  }
  var innerH = 0.0
  for (r in 0 until R) {
    Y[r] = innerH + rowT[r]
    innerH = Y[r] + rowH[r] + rowB[r] + (if (r < R - 1) gapY[r] else 0.0)
  }
  // What is drawn, which an edge that has to go around a node can push past the grid.
  var minX = 0.0
  var minY = 0.0
  var maxX = innerW
  var maxY = innerH

  fun left(g: Int): Double = X[c0[g]] - insL[g]
  fun right(g: Int): Double = X[c1[g]] + colW[c1[g]] + insR[g]
  fun top(g: Int): Double = Y[r0[g]] - insT[g]
  fun bottom(g: Int): Double = Y[r1[g]] + rowH[r1[g]] + insB[g]
  fun centerX(i: Int): Double = X[nodeCol[i]] + colW[nodeCol[i]] / 2

  fun port(i: Int, side: Int, viaGroup: Boolean?): Port {
    val dx = DX[side]
    val dy = DY[side]
    val cx = centerX(i)
    val y = Y[nodeRow[i]]
    val p = Port(cx, y + half, dx, dy, cx - boxW[i] / 2, y, cx + boxW[i] / 2, y + boxH[i], 0.0, false)
    if (nodes[i].type == "junction") {
      p.x1 = cx
      p.x0 = p.x1
      p.y1 = p.y
      p.y0 = p.y1
      p.gap = DOT
    } else if (dx != 0.0) {
      p.x = cx + dx * half
    } else {
      p.y = if (dy < 0) y else y + boxH[i] + (if (labels[i].height > 0) 2 else 0)
    }
    val g = if (viaGroup == true) grid.nodeParent[i] else -1
    if (g >= 0) {
      p.x0 = left(g)
      p.x1 = right(g)
      p.y0 = top(g)
      p.y1 = bottom(g)
      p.gap = 0.0
      p.wire = nodes[i].type == "junction"
      if (dx != 0.0) p.x = if (dx < 0) p.x0 else p.x1
      else p.y = if (dy < 0) p.y0 else p.y1
    }
    return p
  }

  var edgesOut = ""
  var labelsOut = ""
  // Vertical runs of the edges, to keep group titles out from under them.
  val runs = ArrayList<Double>()
  val trimLength = markerTrim("arrow_point")
  for (k in 0 until E) {
    val edge = model.edges[k]
    val a = ends[k * 2]
    val b = ends[k * 2 + 1]
    if (a < 0 || b < 0) continue
    val s = port(a, sideIndex(edge.lhsDir), edge.lhsGroup)
    val e = port(b, sideIndex(edge.rhsDir), edge.rhsGroup)
    if (s.x == e.x && s.y == e.y && s.dx == e.dx) {
      // An edge from a side back to the same side: part the two ends so it shows as a loop.
      s.x -= 8 * abs(s.dy)
      s.y -= 8 * abs(s.dx)
      e.x += 8 * abs(s.dy)
      e.y += 8 * abs(s.dx)
    }
    val routed = route(s, e)
    if (s.wire) {
      routed.add(0, Y[nodeRow[a]] + half)
      routed.add(0, centerX(a))
    }
    if (e.wire) {
      routed.add(centerX(b))
      routed.add(Y[nodeRow[b]] + half)
    }
    val points = simplify(routed)
    if (points.size < 4) continue
    val id = escText("${edge.lhsId}-${edge.rhsId}")
    edgesOut +=
      "<g class=\"pele-edge\" data-id=\"$id\">" +
      "<path d=\"${pathData(points, if (edge.lhsInto == true && !s.wire) trimLength + s.gap else 0.0, if (edge.rhsInto == true && !e.wire) trimLength + e.gap else 0.0)}\"/>" +
      (if (edge.lhsInto == true) marker("arrow_point", s.x + s.dx * s.gap, s.y + s.dy * s.gap, -s.dx, -s.dy, "var(--_l)") else "") +
      (if (edge.rhsInto == true) marker("arrow_point", e.x + e.dx * e.gap, e.y + e.dy * e.gap, -e.dx, -e.dy, "var(--_l)") else "") +
      "</g>"
    var best = -1
    var bestLength = -1.0
    var flat = -1
    var flatLength = -1.0
    val label = edgeLabels[k]
    var i = 0
    while (i < points.size) {
      minX = minOf(minX, points[i] - 4)
      maxX = maxOf(maxX, points[i] + 4)
      minY = minOf(minY, points[i + 1] - 4)
      maxY = maxOf(maxY, points[i + 1] + 4)
      i += 2
    }
    i = 0
    while (i + 3 < points.size) {
      val horizontal = abs(points[i + 1] - points[i + 3]) < 0.01
      val length = abs(points[i] - points[i + 2]) + abs(points[i + 1] - points[i + 3])
      if (!horizontal) {
        runs.add(points[i])
        runs.add(minOf(points[i + 1], points[i + 3]))
        runs.add(maxOf(points[i + 1], points[i + 3]))
      }
      if (length > bestLength) {
        best = i
        bestLength = length
      }
      if (horizontal && length > flatLength) {
        flat = i
        flatLength = length
      }
      i += 2
    }
    if (label.width > 0) {
      val at = if (flatLength >= label.width + 8) flat else best
      var x = (points[at] + points[at + 2]) / 2
      var y = (points[at + 1] + points[at + 3]) / 2
      val w = label.width + 8
      val upright = abs(points[at] - points[at + 2]) < 0.01
      // On a short run next to a node the label would land on the node, so it moves off to the side.
      for (p in listOf(s, e)) {
        if (x + w / 2 <= p.x0 || x - w / 2 >= p.x1 || y + label.height / 2 <= p.y0 || y - label.height / 2 >= p.y1) continue
        if (upright) x = if (x >= (p.x0 + p.x1) / 2) p.x1 + w / 2 + 4 else p.x0 - w / 2 - 4
        else y = if (y >= (p.y0 + p.y1) / 2) p.y1 + label.height / 2 + 4 else p.y0 - label.height / 2 - 4
      }
      minX = minOf(minX, x - w / 2)
      maxX = maxOf(maxX, x + w / 2)
      minY = minOf(minY, y - label.height / 2)
      maxY = maxOf(maxY, y + label.height / 2)
      labelsOut += edgeLabelSvg("${edge.lhsId}-${edge.rhsId}", label, x, y, "", icons)
    }
  }

  var clusters = ""
  for (g in 0 until G) {
    val group = groups[g]
    val x = left(g)
    val y = top(g)
    val w = right(g) - x
    val label = groupLabels[g]
    var head = ""
    if (headW[g] > 0) {
      // The title starts at the left, or after the edges that come down through that corner.
      val band = y + headH[g] + 12
      val crossings = ArrayList<Double>()
      var i = 0
      while (i < runs.size) {
        if (runs[i + 1] < band && runs[i + 2] > y && runs[i] > x && runs[i] < x + w) crossings.add(runs[i])
        i += 3
      }
      crossings.sort()
      crossings.add(x + w - 4)
      var start = x + 10
      for (crossing in crossings) {
        if (crossing - 6 - start >= headW[g]) break
        start = maxOf(start, crossing + 6)
      }
      if (start + headW[g] > x + w - 4) start = x + 10
      val cy = y + 6 + headH[g] / 2
      var textX = start
      if (!group.icon.isNullOrEmpty()) {
        head += iconSlot(group.icon, start, cy - badge / 2, badge, icons)
        textX += badge + 6
      }
      head += labelSvg(label, textX + label.width / 2, cy, " class=\"pele-cluster-label\" fill=\"var(--_m)\"", icons)
    }
    clusters +=
      "<g class=\"pele-cluster\" data-id=\"${escText(group.id)}\">" +
      "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(w)}\" height=\"${num(bottom(g) - y)}\" fill=\"var(--_a)\" fill-opacity=\"0.5\" stroke=\"var(--_b)\"$ROUNDED/>" +
      head +
      "</g>"
  }

  var nodesOut = ""
  val glyph = iconSize * 0.5
  val textSize = maxOf(6.0, jsRound(size * 0.75))
  for (i in 0 until N) {
    val node = nodes[i]
    val at = "data-id=\"${escText(node.id)}\" transform=\"translate(${num(centerX(i))},${num(Y[nodeRow[i]])})\""
    if (node is ArchJunction) {
      nodesOut += "<g class=\"pele-node pele-junction\" $at><circle cy=\"${num(half)}\" r=\"${jsStr(DOT)}\" fill=\"var(--_l)\"/></g>"
      continue
    }
    node as ArchService
    var inner = ""
    if (!node.icon.isNullOrEmpty()) {
      inner = iconSlot(node.icon, -glyph / 2, (iconSize - glyph) / 2, glyph, icons)
    } else if (!node.iconText.isNullOrEmpty()) {
      val text = layoutLabel(node.iconText, false, measurer, textSize, iconSize - 8)
      val lines = maxOf(1.0, floor((iconSize - 4) / text.lineHeight.jsOr { 1.0 })).toInt()
      inner = labelSvg(fit(text, iconSize - 8, lines, measurer), 0.0, half, " class=\"pele-icon-text\" font-size=\"${jsStr(textSize)}\"", icons)
    }
    nodesOut +=
      "<g class=\"pele-node pele-service\" $at>" +
      "<rect x=\"${num(-half)}\" width=\"${num(iconSize)}\" height=\"${num(iconSize)}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"$ROUNDED/>" +
      inner +
      labelSvg(labels[i], 0.0, iconSize + LABEL_GAP + labels[i].height / 2, " class=\"pele-label\"", icons) +
      "</g>"
  }

  val title = layoutLabel(model.title, false, measurer, size, if (tight) titleRoom(options) else 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val width = ceil(maxOf(maxX - minX, title.width) + 2 * pad)
  val height = ceil(maxY - minY + titleHeight + 2 * pad)
  val ox = jsRound((width - (maxX - minX)) / 2 - minX)
  val oy = jsRound(pad + titleHeight - minY)
  val body =
    (if (clusters != "") "<g class=\"pele-clusters\">$clusters</g>" else "") +
      (if (edgesOut != "") "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$edgesOut</g>" else "") +
      (if (labelsOut != "") "<g class=\"pele-edge-labels\" font-size=\"${jsStr(small)}\">$labelsOut</g>" else "") +
      (if (nodesOut != "") "<g class=\"pele-nodes\">$nodesOut</g>" else "")
  val svg = svgDocument(
    "architecture",
    width,
    height,
    size,
    options,
    model,
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (body != "") "<g transform=\"translate(${jsStr(ox)},${jsStr(oy)})\">$body</g>" else ""),
  )
  return Rendered(svg, width, height, ArrayList())
}
