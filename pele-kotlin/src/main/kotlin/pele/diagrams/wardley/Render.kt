// Ported from Pele src/diagrams/wardley/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.wardley

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.fitWidth
import pele.js.cmp
import pele.js.jsHypot
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.ROUNDED
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.sharedStyle
import pele.svg.spanStyle
import pele.svg.svgDocument
import pele.svg.textStyle
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel

private val DEFAULT_STAGES = listOf("Genesis", "Custom Built", "Product", "Commodity")

// Mermaid's canvas has this much room around the plot on every side.
private const val CANVAS_MARGIN = 48.0
private const val R = 6.0
private const val SQUARE = 10.0
private const val LABEL_OFFSET = 8.0
private const val PIPELINE_PAD = 15.0
private const val FORCE_WIDTH = 60.0
private const val FORCE_HEIGHT = 30.0
private const val FORCE_HEAD = 20.0
private const val ACCENT = "var(--_c)"
private const val LINE = "var(--_l)"

private class Placed(
  var x: Double,
  var y: Double,
  val node: WardleyNode,
)

private fun setting(
  config: Config,
  key: String,
  fallback: Double,
  min: Double,
  max: Double,
): Double {
  val value = (config["wardley-beta"] as? Map<*, *>)?.get(key)
  return if (value is Double && value >= min && value <= max) value else fallback
}

private fun clamp(value: Double, min: Double, max: Double): Double =
  if (value < min) min else if (value > max) max else value

// Writes a label with its lines starting at x and its first baseline at y.
private fun startLabel(label: Label, x: Double, y: Double, attrs: String): String {
  val shared = sharedStyle(label)
  var body = ""
  var baseline = y
  for (line in label.lines) {
    var first = true
    for (span in line) {
      if (span.icon != null) continue
      val style = spanStyle(span.style and shared.inv())
      body +=
        "<tspan${if (first) " x=\"${num(x)}\" y=\"${num(baseline)}\"" else ""}$style>${esc(span.text)}</tspan>"
      first = false
    }
    baseline += label.lineHeight
  }
  return if (body == "") ""
  else "<text${textStyle(attrs, shared)} xml:space=\"preserve\">$body</text>"
}

private fun dimension(config: Config, given: Double?, key: String, fallback: Double): Double {
  val value =
    if (given != null && given.isFinite()) given else setting(config, key, fallback, 1.0, 1e6)
  return clamp(value, 200.0, 10000.0) - 2 * CANVAS_MARGIN
}

fun renderWardley(model: WardleyModel, config: Config, options: RenderOptions): Rendered {
  val natural = dimension(config, model.size?.width, "width", 900.0)
  return fitWidth(options, natural, 160.0) { plotW, tight ->
    draw(model, config, options, plotW, natural, tight)
  }
}

private fun draw(
  model: WardleyModel,
  config: Config,
  options: RenderOptions,
  plotW: Double,
  naturalW: Double,
  tight: Boolean,
): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.8125)
  val tiny = jsRound(size * 0.6875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  // A narrower map is not as much shorter, so that components keep some room between them.
  val plotH = dimension(config, model.size?.height, "height", 600.0) * maxOf(0.75, plotW / naturalW)
  val showGrid = (config["wardley-beta"] as? Map<*, *>)?.get("showGrid") == true

  var minX = 0.0
  var minY = 0.0
  var maxX = plotW
  var maxY = plotH
  fun grow(x0: Double, y0: Double, x1: Double, y1: Double) {
    if (x0 < minX) minX = x0
    if (y0 < minY) minY = y0
    if (x1 > maxX) maxX = x1
    if (y1 > maxY) maxY = y1
  }
  fun centered(label: Label, cx: Double, cy: Double, attrs: String): String {
    grow(cx - label.width / 2, cy - label.height / 2, cx + label.width / 2, cy + label.height / 2)
    return labelSvg(label, cx, cy, attrs, options.icons)
  }
  fun starting(label: Label, x: Double, y: Double, attrs: String): String {
    grow(x, y - label.size, x + label.width, y - label.size + label.height + 4)
    return startLabel(label, x, y, attrs)
  }
  fun text(
    raw: String,
    fontSize: Double,
    maxWidth: Double = if (tight) maxOf(64.0, minOf(120.0, plotW / 2)) else 4000.0,
    style: Int = 0,
  ): Label = layoutLabel(raw, false, measurer, fontSize, maxWidth, style)
  fun projectX(value: Double): Double = (value / 100) * plotW
  fun projectY(value: Double): Double = plotH - (value / 100) * plotH

  val stages =
    if (model.axes.stages != null && model.axes.stages!!.size > 0) model.axes.stages!!
    else DEFAULT_STAGES
  val boundaries = model.axes.stageBoundaries
  val custom = boundaries != null && boundaries.size == stages.size
  var frame = ""
  var stageLabels = ""
  var stageBottom = plotH
  var previous = 0.0
  stages.forEachIndexed { index, stage ->
    val start = if (custom) previous else index.toDouble() / stages.size
    val end =
      if (custom) clamp(boundaries!![index], 0.0, 1.0) else (index + 1).toDouble() / stages.size
    previous = end
    if (index > 0) {
      frame +=
        "<path class=\"pele-wardley-stage\" d=\"M${num(start * plotW)},0V${num(plotH)}\" stroke=\"var(--_b)\" stroke-dasharray=\"4 4\"/>"
    }
    val label = text(stage, small, maxOf(48.0, abs(end - start) * plotW - 12))
    stageLabels +=
      centered(
        label,
        ((start + end) / 2) * plotW,
        plotH + 8 + label.height / 2,
        " class=\"pele-wardley-stage-label\" font-size=\"${jsStr(small)}\" fill=\"var(--_m)\"",
      )
    stageBottom = maxOf(stageBottom, plotH + 8 + label.height)
  }
  if (showGrid) {
    for (i in 1 until 4) {
      frame +=
        "<path class=\"pele-wardley-grid\" d=\"M${num((plotW * i) / 4)},0V${num(plotH)}M0,${num((plotH * i) / 4)}H${num(plotW)}\" stroke=\"var(--_b)\" stroke-dasharray=\"2 6\"/>"
    }
  }
  frame +=
    "<path class=\"pele-wardley-axis\" d=\"M0,0V${num(plotH)}H${num(plotW)}\" stroke=\"$LINE\"/>"
  val xTitle = text(model.axes.xLabel ?: "Evolution", small)
  val yTitle = text(model.axes.yLabel ?: "Visibility", small)
  val axisAttrs =
    " class=\"pele-wardley-axis-label\" font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\""
  val axisLabels =
    centered(xTitle, plotW / 2, stageBottom + 6 + xTitle.height / 2, axisAttrs) +
      labelSvg(
        yTitle,
        -16.0,
        plotH / 2,
        "$axisAttrs transform=\"rotate(-90 -16 ${num(plotH / 2)})\"",
      )
  grow(-16 - yTitle.height / 2, 0.0, 0.0, 0.0)
  val legendTop = stageBottom + 6 + xTitle.height + 10

  val positions = LinkedHashMap<String, Placed>()
  for (node in model.nodes) positions[node.id] =
    Placed(projectX(node.x!!), projectY(node.y!!), node)

  // Pipelines: a box around the components, with the parent as a square on its top edge.
  var pipelines = ""
  val members = LinkedHashMap<String, Set<String>>()
  for (pipeline in model.pipelines) {
    members[pipeline.nodeId] = LinkedHashSet(pipeline.componentIds)
    val parts = ArrayList<Placed>()
    for (id in pipeline.componentIds) {
      val part = positions[id]
      if (part != null) parts.add(part)
    }
    if (parts.size == 0) continue
    parts.sortWith { a, b -> cmp(a.x - b.x) }
    val left = parts[0].x
    val right = parts[parts.size - 1].x
    val y = parts[0].y
    val top = y - 2 * R
    val parent = positions[pipeline.nodeId]
    if (parent != null) {
      parent.x = (left + right) / 2
      parent.y = top - SQUARE / 6
    }
    grow(left - PIPELINE_PAD, top, right + PIPELINE_PAD, top + 4 * R)
    pipelines +=
      "<g class=\"pele-wardley-pipeline\" data-id=\"${escText(pipeline.nodeId)}\">" +
        "<rect x=\"${num(left - PIPELINE_PAD)}\" y=\"${num(top)}\" width=\"${num(right - left + 2 * PIPELINE_PAD)}\" height=\"${num(
        4 * R
      )}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"$ROUNDED/>" +
        (if (parts.size > 1)
          "<path d=\"M${num(left)},${num(y)}H${num(right)}\" stroke=\"$LINE\" stroke-dasharray=\"4 4\"/>"
        else "") +
        "</g>"
  }

  // How far from a node's center a line to it stops.
  val anchorLabels = HashMap<WardleyNode, Label>()
  fun reach(at: Placed, ux: Double, uy: Double): Double {
    val node = at.node
    if (node.className == "anchor") {
      var label = anchorLabels[node]
      if (label == null) {
        label = text(node.label, small, if (tight) maxOf(64.0, plotW / 2) else 4000.0, Style.Bold)
        anchorLabels[node] = label
      }
      return 1 / jsHypot(ux / (label.width / 2 + 5), uy / (small * 0.8))
    }
    if (node.isPipelineParent == true) return SQUARE / sqrt(2.0)
    return if (!node.sourceStrategy.isNullOrEmpty()) 2 * R else R
  }

  // Quadrants around a node that a link runs through: 1 up and right, 2 down and right, 4 up and
  // left, 8 down and left.
  val taken = HashMap<Placed, Int>()
  fun occupy(at: Placed, ux: Double, uy: Double) {
    if (abs(ux) < 0.25 || abs(uy) < 0.25) return
    taken[at] = (taken[at] ?: 0) or (if (ux > 0) (if (uy < 0) 1 else 2) else if (uy < 0) 4 else 8)
  }

  var links = ""
  for (link in model.links) {
    val from = positions[link.source]
    val to = positions[link.target]
    if (from == null || to == null || from === to) continue
    // A pipeline component belongs to its parent already.
    if (members[link.target]?.contains(link.source) == true) continue
    val dx = to.x - from.x
    val dy = to.y - from.y
    val distance = jsHypot(dx, dy)
    if (distance < 0.01) continue
    val ux = dx / distance
    val uy = dy / distance
    val start = reach(from, ux, uy)
    val end = reach(to, ux, uy)
    if (start + end >= distance) continue
    occupy(from, ux, uy)
    occupy(to, -ux, -uy)
    var x1 = from.x + ux * start
    var y1 = from.y + uy * start
    var x2 = to.x - ux * end
    var y2 = to.y - uy * end
    var heads = ""
    if (link.flow == "forward" || link.flow == "bidirectional") {
      heads += marker("arrow_point", x2, y2, ux, uy, LINE)
      x2 -= ux * markerTrim("arrow_point")
      y2 -= uy * markerTrim("arrow_point")
    }
    if (link.flow == "backward" || link.flow == "bidirectional") {
      heads += marker("arrow_point", x1, y1, -ux, -uy, LINE)
      x1 += ux * markerTrim("arrow_point")
      y1 += uy * markerTrim("arrow_point")
    }
    var caption = ""
    if (!link.label.isNullOrEmpty()) {
      val label = text(link.label, tiny + 1, 200.0)
      // Beside the middle of the line, on its upper side.
      val side = if (ux >= 0) 1.0 else -1.0
      val px = uy * side
      val py = -ux * side
      val away = 5 + (abs(px) * label.width) / 2 + (abs(py) * label.height) / 2
      caption =
        centered(
          label,
          (from.x + to.x) / 2 + px * away,
          (from.y + to.y) / 2 + py * away,
          " class=\"pele-edge-label\" font-size=\"${jsStr(tiny + 1)}\" fill=\"var(--_m)\"",
        )
    }
    links +=
      "<g class=\"pele-edge${if (link.dashed == true) " pele-wardley-dashed" else ""}\" data-id=\"${escText(link.source)}-${escText(link.target)}\">" +
        "<path d=\"M${num(x1)},${num(y1)}L${num(x2)},${num(y2)}\" stroke=\"$LINE\"${if (link.dashed == true) " stroke-dasharray=\"6 6\"" else ""}/>" +
        heads +
        caption +
        "</g>"
  }

  // Evolution: a dashed arrow to where the component is heading, drawn as an open circle.
  var trends = ""
  for (trend in model.trends) {
    val origin = positions[trend.nodeId] ?: continue
    val tx = projectX(trend.targetX)
    val ty = projectY(trend.targetY)
    val dx = tx - origin.x
    val dy = ty - origin.y
    val distance = jsHypot(dx, dy)
    var arrow = ""
    val start = if (distance > 0) reach(origin, dx / distance, dy / distance) else 0.0
    if (distance > start + R + 4) {
      val ux = dx / distance
      val uy = dy / distance
      val ex = tx - ux * (R + 2)
      val ey = ty - uy * (R + 2)
      arrow =
        "<path d=\"M${num(origin.x + ux * start)},${num(origin.y + uy * start)}L${num(ex - ux * markerTrim("arrow_point"))},${num(
          ey - uy * markerTrim("arrow_point")
        )}\" stroke=\"$ACCENT\" stroke-dasharray=\"4 4\"/>" +
          marker("arrow_point", ex, ey, ux, uy, ACCENT)
    }
    grow(tx - R, ty - R, tx + R, ty + R)
    trends +=
      "<g class=\"pele-wardley-trend\" data-id=\"${escText(trend.nodeId)}\">$arrow" +
        "<circle cx=\"${num(tx)}\" cy=\"${num(ty)}\" r=\"${jsStr(R)}\" fill=\"var(--_bg)\" stroke=\"$ACCENT\"/></g>"
  }

  var nodes = ""
  val tri = 1.2 * R
  val dot = 0.7 * R
  for (at in positions.values) {
    val node = at.node
    val x = at.x
    val y = at.y
    val anchor = node.className == "anchor"
    val strategy = node.sourceStrategy
    var shape = ""
    if (!strategy.isNullOrEmpty()) {
      val fill =
        if (strategy == "outsource") "var(--_m)"
        else if (strategy == "buy") "var(--_a)"
        else if (strategy == "build") "var(--_s)" else "var(--_bg)"
      shape +=
        "<circle class=\"pele-wardley-$strategy\" cx=\"${num(x)}\" cy=\"${num(y)}\" r=\"${jsStr(2 * R)}\" fill=\"$fill\" stroke=\"$LINE\"/>"
    }
    if (strategy == "market") {
      val bx = tri * StrictMath.cos(PI / 6)
      val by = y + tri * StrictMath.sin(PI / 6)
      shape +=
        "<path d=\"M${num(x)},${num(y - tri)}L${num(x - bx)},${num(by)}H${num(x + bx)}Z\" fill=\"none\" stroke=\"$LINE\"/>"
      for ((cx, cy) in listOf(Pair(x, y - tri), Pair(x - bx, by), Pair(x + bx, by))) {
        shape +=
          "<circle cx=\"${num(cx)}\" cy=\"${num(cy)}\" r=\"${num(dot)}\" fill=\"var(--_bg)\" stroke=\"$LINE\"/>"
      }
    } else if (node.isPipelineParent == true) {
      shape +=
        "<rect x=\"${num(x - SQUARE / 2)}\" y=\"${num(y - SQUARE / 2)}\" width=\"${jsStr(SQUARE)}\" height=\"${jsStr(SQUARE)}\" fill=\"var(--_bg)\" stroke=\"$LINE\"/>"
    } else if (!anchor) {
      shape +=
        "<circle cx=\"${num(x)}\" cy=\"${num(y)}\" r=\"${jsStr(R)}\" fill=\"var(--_bg)\" stroke=\"$LINE\"/>"
    }
    if (node.inertia == true) {
      val offset =
        (if (node.isPipelineParent == true) SQUARE / 2 else R) +
          10 +
          (if (!strategy.isNullOrEmpty()) R + 4 else 0.0)
      shape +=
        "<rect class=\"pele-wardley-inertia\" x=\"${num(x + offset)}\" y=\"${num(y - R)}\" width=\"4\" height=\"${jsStr(2 * R)}\" fill=\"$LINE\"/>"
    }
    val ox = if (node.labelOffsetX == null) null else clamp(node.labelOffsetX!!, -5000.0, 5000.0)
    val oy = if (node.labelOffsetY == null) null else clamp(node.labelOffsetY!!, -5000.0, 5000.0)
    val caption: String
    if (anchor) {
      val label =
        anchorLabels[node]
          ?: text(node.label, small, if (tight) maxOf(64.0, plotW / 2) else 4000.0, Style.Bold)
      caption =
        centered(
          label,
          x + (ox ?: 0.0),
          y + (oy ?: -3.0),
          " class=\"pele-label\" font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\"",
        )
    } else {
      val label = text(node.label, small)
      val attrs = " class=\"pele-label\" font-size=\"${jsStr(small)}\""
      val lift = LABEL_OFFSET + (if (!strategy.isNullOrEmpty()) 10 else 0)
      if (ox != null || oy != null) {
        caption = starting(label, x + (ox ?: lift), y + (oy ?: -lift), attrs)
      } else if (node.inPipeline == true) {
        caption = centered(label, x, y + 2 * R + 4 + label.height / 2, attrs)
      } else {
        // The label goes up and to the right unless a link leaves that way; then the next free
        // corner.
        val busy = taken[at] ?: 0
        val corner =
          if ((busy and 1) == 0) 1
          else if ((busy and 2) == 0) 2
          else if ((busy and 4) == 0) 4 else if ((busy and 8) == 0) 8 else 1
        val right = corner == 1 || corner == 2
        val up = corner == 1 || corner == 4
        caption =
          starting(
            label,
            if (right) x + lift else x - lift - label.width,
            if (up) y - lift else y + lift + small * 0.75,
            attrs,
          )
      }
    }
    grow(x - 2 * R, y - 2 * R, x + 2 * R + 20, y + 2 * R)
    nodes +=
      "<g class=\"pele-node pele-wardley-${if (anchor) "anchor" else if (node.isPipelineParent == true) "pipeline-parent" else if (node.inPipeline == true) "pipeline-component" else "component"}\" data-id=\"${escText(
      node.id
    )}\">$shape$caption</g>"
  }

  var annotations = ""
  for (annotation in model.annotations) {
    val label = text(jsStr(annotation.number), tiny, 4000.0, Style.Bold)
    var marks = ""
    for (coordinate in annotation.coordinates) {
      val x = projectX(coordinate.x)
      val y = projectY(coordinate.y)
      val r = maxOf(10.0, label.width / 2 + 4)
      grow(x - r, y - r, x + r, y + r)
      marks +=
        "<circle cx=\"${num(x)}\" cy=\"${num(y)}\" r=\"${num(r)}\" fill=\"var(--_bg)\" stroke=\"$LINE\"/>" +
          labelSvg(label, x, y, " font-size=\"${jsStr(tiny)}\" font-weight=\"var(--_hw)\"")
    }
    annotations +=
      "<g class=\"pele-wardley-annotation\" data-id=\"${escText(jsStr(annotation.number))}\">$marks</g>"
  }
  val listed =
    model.annotations
      .filter { a -> !a.text.isNullOrEmpty() }
      .sortedWith { a, b -> cmp(a.number - b.number) }
  if (listed.size > 0) {
    val lines = listed.map { a ->
      text("${jsStr(a.number)}. ${a.text!!}", small, if (tight) maxOf(80.0, plotW - 20) else 320.0)
    }
    var width = 0.0
    var height = 0.0
    for (line in lines) {
      width = maxOf(width, line.width)
      height += line.height
    }
    width += 20
    height += 14
    // Without a position the list goes under the map, where it covers nothing.
    val box = model.annotationsBox
    val bx = if (box != null) clamp(projectX(box.x), 0.0, maxOf(0.0, plotW - width)) else 0.0
    val by = if (box != null) clamp(projectY(box.y), 0.0, maxOf(0.0, plotH - height)) else legendTop
    grow(bx, by, bx + width, by + height)
    var rows = ""
    var y = by + 7
    for (line in lines) {
      rows +=
        startLabel(
          line,
          bx + 10,
          y + line.lineHeight / 2 + small * 0.35,
          " font-size=\"${jsStr(small)}\"",
        )
      y += line.height
    }
    annotations +=
      "<g class=\"pele-wardley-annotations\"><rect x=\"${num(bx)}\" y=\"${num(by)}\" width=\"${num(width)}\" height=\"${num(
        height
      )}\" fill=\"var(--_bg)\" stroke=\"var(--_b)\"$ROUNDED/>$rows</g>"
  }

  var notes = ""
  for (note in model.notes) {
    notes +=
      starting(
        text(note.text, small, minOf(360.0, plotW * 0.6), Style.Bold),
        projectX(note.x),
        projectY(note.y),
        " class=\"pele-wardley-note\" font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\"",
      )
  }

  // Accelerators point right, deaccelerators left.
  var forces = ""
  fun force(name: String, px: Double, py: Double, forward: Boolean) {
    val x = projectX(px)
    val y = projectY(py)
    val half = FORCE_HEIGHT / 2
    val tip = if (forward) x + FORCE_WIDTH else x
    val tail = if (forward) x else x + FORCE_WIDTH
    val neck = if (forward) x + FORCE_WIDTH - FORCE_HEAD else x + FORCE_HEAD
    val d =
      "M${num(tail)},${num(y - half)}H${num(neck)}V${num(y - half - 8)}L${num(tip)},${num(y)}L${num(neck)},${num(y + half + 8)}V${num(y + half)}H${num(tail)}Z"
    grow(x, y - half - 8, x + FORCE_WIDTH, y + half + 8)
    forces +=
      "<g class=\"pele-wardley-${if (forward) "accelerator" else "deaccelerator"}\" data-id=\"${escText(name)}\">" +
        "<path d=\"$d\" fill=\"var(--_s)\" stroke=\"$LINE\"/>" +
        centered(
          text(name, tiny + 1, 160.0, Style.Bold),
          x + FORCE_WIDTH / 2,
          y + half + 8 + 12,
          " font-size=\"${jsStr(tiny + 1)}\" font-weight=\"var(--_hw)\"",
        ) +
        "</g>"
  }
  for (a in model.accelerators) force(a.name, a.x, a.y, true)
  for (d in model.deaccelerators) force(d.name, d.x, d.y, false)

  val title =
    layoutLabel(
      model.title,
      false,
      measurer,
      size,
      if (tight) maxOf(80.0, plotW) else 4000.0,
      Style.Bold,
    )
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val left = minOf(minX, plotW / 2 - title.width / 2)
  val right = maxOf(maxX, plotW / 2 + title.width / 2)
  val tx = pad - left
  val ty = pad + titleHeight - minY
  val totalWidth = ceil(right - left + 2 * pad)
  val totalHeight = ceil(maxY - minY + titleHeight + 2 * pad)
  val content =
    labelSvg(
      title,
      tx + plotW / 2,
      pad + title.height / 2,
      " class=\"pele-title\" font-weight=\"var(--_tw)\"",
    ) +
      "<g transform=\"translate(${num(tx)},${num(ty)})\">" +
      "<g class=\"pele-wardley-frame\" fill=\"none\">$frame</g>" +
      stageLabels +
      axisLabels +
      pipelines +
      (if (links != "") "<g class=\"pele-edges\" fill=\"none\">$links</g>" else "") +
      (if (trends != "") "<g class=\"pele-wardley-trends\" fill=\"none\">$trends</g>" else "") +
      nodes +
      annotations +
      notes +
      forces +
      "</g>"

  return Rendered(
    svgDocument("wardley", totalWidth, totalHeight, size, options, model, content),
    totalWidth,
    totalHeight,
    ArrayList(),
  )
}
