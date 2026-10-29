// Ported from Pele src/diagrams/sankey/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sankey

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.fitWidth
import pele.diagrams.common.titleRoom
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsEntries
import pele.js.jsImul
import pele.js.jsRound
import pele.js.jsStr
import pele.js.toUint32
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.resolveStyle
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val LABEL_GAP = 6.0
private const val VALUE_GAP = 15.0
private const val LABEL_ROOM = 1.8
private const val CAREFUL = 300

private val RE_NODE_FILL = JsRegExp("""^ style="fill:(.*);"$""")
private val RE_ID_PREFIX = JsRegExp("""^[\w-]+$""")

// Two 32-bit hashes, so different gradients practically never share an id.
private fun contentId(text: String): String {
  var a = 0x811c9dc5u.toInt()
  var b = 5381
  for (i in 0 until text.length) {
    val c = text.charCodeAt(i)
    a = jsImul(a xor c, 0x01000193)
    b = jsImul(b, 33) + c
  }
  return java.lang.Long.toString(toUint32(a).toLong(), 36) + java.lang.Long.toString(toUint32(b).toLong(), 36)
}

private fun isRecord(value: Any?): Boolean = value is Map<*, *>

private fun numberOption(conf: Map<*, *>, key: String, fallback: Double, min: Double, max: Double): Double {
  val value = conf[key]
  return if (value is Double && value >= min) minOf(value, max) else fallback
}

private fun textOption(conf: Map<*, *>, key: String): String {
  val value = conf[key]
  return if (value is String || (value is Double && value.isFinite())) (if (value is Double) jsStr(value) else value as String) else ""
}

// Two decimals, as Mermaid shows values; numbers too large for that are left as they are.
private fun rounded(value: Double): Double = if (abs(value) < 1e15) jsRound(value * 100) / 100 else value

// A colour from the configuration, as an inline style that overrides the series colour.
private fun fillStyle(color: Any?): String = if (color is String) resolveStyle(listOf("fill:$color")).shape else ""

fun renderSankey(model: SankeyModel, config: Config, options: RenderOptions): Rendered {
  val conf: Map<*, *> = if (isRecord(config["sankey"])) config["sankey"] as Map<*, *> else emptyMap<String, Any?>()
  val natural = numberOption(conf, "width", 600.0, 1.0, 10000.0)
  return fitWidth(options, natural, 160.0) { chartWidth, _ -> draw(model, conf, options, chartWidth, natural) }
}

private class LabelBox(val value: String, val width: Double, var after: Boolean, var left: Double, var y: Double)

private fun draw(model: SankeyModel, conf: Map<*, *>, options: RenderOptions, chartWidth: Double, natural: Double): Rendered {
  val size = options.fontSize ?: 16.0
  val least = jsRound(size * 0.625)
  // Labels shrink with a chart narrowed to fit, as they would if the whole picture were scaled down.
  var small = maxOf(least, jsRound(size * 0.875 * minOf(1.0, chartWidth / natural)))
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val chartHeight = numberOption(conf, "height", 400.0, 1.0, 10000.0)
  val nodeWidth = minOf(numberOption(conf, "nodeWidth", 10.0, 1.0, 200.0), chartWidth)
  val showValues = conf["showValues"] != false
  val prefix = decodeEntities(textOption(conf, "prefix"))
  val suffix = decodeEntities(textOption(conf, "suffix"))
  val outlined = conf["labelStyle"] == "outlined"
  val alignment: Alignment =
    if (conf["nodeAlignment"] == "left" || conf["nodeAlignment"] == "right" || conf["nodeAlignment"] == "center") {
      conf["nodeAlignment"] as String
    } else {
      "justify"
    }
  val linkColorValue = conf["linkColor"]
  val linkColor = if (linkColorValue is String) linkColorValue else "gradient"

  val lay = { labelSize: Double ->
    layoutSankey(
      model,
      LayoutOptions(
        width = chartWidth,
        height = chartHeight,
        nodeWidth = nodeWidth,
        nodePadding = numberOption(conf, "nodePadding", 12.0, 0.0, 200.0) + (if (showValues) VALUE_GAP else 0.0),
        labelHeight = labelSize * 1.15,
        alignment = alignment,
      ),
    )
  }
  var layout = lay(small)
  // Labels also shrink with the most crowded column, so each keeps about LABEL_ROOM lines.
  val rows = LinkedHashMap<Int, Int>()
  var fullest = 1
  for (node in layout.nodes) {
    val count = (rows[node.layer] ?: 0) + 1
    rows[node.layer] = count
    if (count > fullest) fullest = count
  }
  val crowded = maxOf(least, jsRound(chartHeight / fullest / LABEL_ROOM))
  if (crowded < small) {
    small = crowded
    layout = lay(small)
  }
  val nodes = layout.nodes
  val links = layout.links
  val lineHeight = small * 1.5

  val custom = LinkedHashMap<String, String>()
  val nodeColors = conf["nodeColors"]
  if (isRecord(nodeColors)) {
    @Suppress("UNCHECKED_CAST")
    for ((id, color) in jsEntries(nodeColors as Map<String, Any?>)) {
      val style = fillStyle(color)
      if (style.isNotEmpty()) custom[id] = style
    }
  }
  val nodeStyle = { i: Int -> custom[model.nodes[i].id] ?: "" }
  // Match the whole fill to preserve semicolons in escaped entities.
  val nodeColor = { i: Int -> RE_NODE_FILL.exec(nodeStyle(i))?.get(1) ?: seriesColor(i) }

  // Labels sit beside the bars, on the side that faces the middle of the chart unless that
  // side is crowded.
  var central = 0
  var most = 0.0
  nodes.forEach { node ->
    if (node.value > most) {
      most = node.value
      central = node.layer
    }
  }
  var minX = 0.0
  var maxX = chartWidth
  var minY = 0.0
  var maxY = chartHeight
  val labels = nodes.mapIndexed { i, node ->
    val name = decodeEntities(model.nodes[i].id)
    val value = if (showValues) "$prefix${jsStr(rounded(node.value))}$suffix" else ""
    val width = measurer.width(if (value.isNotEmpty()) "$name $value" else name, small, 0)
    val after = if (outlined) node.layer >= central else node.x < chartWidth / 2
    val x = if (after) node.x + nodeWidth + LABEL_GAP else node.x - LABEL_GAP
    val y = node.y + node.height / 2
    minX = minOf(minX, if (after) x else x - width)
    maxX = maxOf(maxX, if (after) x + width else x)
    minY = minOf(minY, y - small * 0.75)
    maxY = maxOf(maxY, y + small * 0.75)
    LabelBox(value, width, after, if (after) x else x - width, y)
  }
  // A label moves at most one line; retain two bands on each side.
  val bands = LinkedHashMap<Double, MutableList<LabelBox>>()
  if (nodes.size <= CAREFUL) for (label in labels) {
    val band = floor(label.y / lineHeight)
    for (key in listOf(band - 2, band - 1, band, band + 1, band + 2)) {
      val nearby = bands[key + 0.0]
      if (nearby != null) nearby.add(label)
      else bands[key + 0.0] = arrayListOf(label)
    }
  }
  var labelsOut = ""
  var nodesOut = ""
  nodes.forEachIndexed { i, node ->
    val id = model.nodes[i].id
    val name = decodeEntities(id)
    val height = maxOf(node.height, 1.0)
    nodesOut += "<rect class=\"pele-sankey-node\" data-id=\"${escText(id)}\" x=\"${num(node.x)}\" y=\"${num(node.y)}\" width=\"${num(
      nodeWidth,
    )}\" height=\"${num(height)}\" rx=\"${num(minOf(2.0, height / 2))}\" fill=\"${seriesColor(i)}\"${nodeStyle(i)}/>"

    val label = labels[i]
    val overlapsLabel = { left: Double, y: Double ->
      (bands[floor(y / lineHeight) + 0.0] ?: emptyList<LabelBox>()).any { other ->
        other !== label && abs(other.y - y) < lineHeight && left < other.left + other.width + LABEL_GAP && left + label.width > other.left - LABEL_GAP
      }
    }
    // Try the other side, then an adjacent line when both sides are crowded.
    if (nodes.size <= CAREFUL && overlapsLabel(label.left, label.y)) {
      place@ for (dy in listOf(0.0, -lineHeight, lineHeight)) {
        for (after in listOf(label.after, !label.after)) {
          val left = if (after) node.x + nodeWidth + LABEL_GAP else node.x - LABEL_GAP - label.width
          val y = label.y + dy
          if (overlapsLabel(left, y) || left < minX || left + label.width > maxX || y - small * 0.75 < minY || y + small * 0.75 > maxY) continue
          var blocked = false
          for ((j, bar) in nodes.withIndex()) {
            if (j != i && left < bar.x + nodeWidth + LABEL_GAP && left + label.width > bar.x - LABEL_GAP && y - small * 0.75 < bar.y + bar.height && y + small * 0.75 > bar.y) {
              blocked = true
              break
            }
          }
          if (blocked) continue
          // A label stays nearer its own bar than any other in the column, so it can't name that one.
          val own = maxOf(0.0, node.y - y, y - node.y - node.height)
          for ((j, bar) in nodes.withIndex()) {
            if (j != i && bar.x == node.x && maxOf(0.0, bar.y - y, y - bar.y - bar.height) <= own) {
              blocked = true
              break
            }
          }
          if (blocked) continue
          label.left = left
          label.y = y
          label.after = after
          break@place
        }
      }
    }
    val value = label.value
    val after = label.after
    val y = label.y
    val x = if (after) node.x + nodeWidth + LABEL_GAP else node.x - LABEL_GAP
    val position = " x=\"${num(x)}\" y=\"${num(y + small * 0.35)}\" text-anchor=\"${if (after) "start" else "end"}\""
    val text = esc(name) + (if (value.isNotEmpty()) " <tspan class=\"pele-sankey-value\" fill=\"var(--_m)\">${esc(value)}</tspan>" else "")
    labelsOut +=
      "<g class=\"pele-label\" data-id=\"${escText(id)}\">" +
      (if (outlined) {
        "<text$position fill=\"none\" stroke=\"var(--_bg)\" stroke-width=\"3\" stroke-linejoin=\"round\">${esc(name)}${
          if (value.isNotEmpty()) " " + esc(value) else ""
        }</text>"
      } else {
        ""
      }) +
      "<text$position>$text</text></g>"
  }

  val fixed = if (linkColor == "gradient" || linkColor == "source" || linkColor == "target") "" else fillStyle(linkColor)
  // Without an idPrefix diagrams share gradient ids, and Chrome then uses a hidden or differently
  // themed copy's gradient. Other characters would end the url() and void the whole fill.
  val idPrefix = options.idPrefix ?: ""
  val gradient = linkColor == "gradient" && RE_ID_PREFIX.test(idPrefix)
  val gradients = LinkedHashMap<String, String>()
  var linksOut = ""
  for (link in links) {
    val end = if (linkColor == "target") link.target else link.source
    val half = maxOf(link.thickness, 1.0) / 2
    val x0 = nodes[link.source].x + nodeWidth
    val x1 = nodes[link.target].x
    var paint = "fill=\"${seriesColor(end)}\"${if (fixed.isNotEmpty()) fixed else nodeStyle(end)}"
    if (gradient) {
      // Ids come from the gradient itself, so diagrams sharing a prefix agree on them. The colour
      // after the url() is the fallback.
      val stops =
        "x1=\"${num(x0)}\" x2=\"${num(x1)}\"><stop offset=\"0\" stop-color=\"${nodeColor(link.source)}\"/>" +
          "<stop offset=\"1\" stop-color=\"${nodeColor(link.target)}\"/>"
      val id = "$idPrefix-sankey-${contentId(stops)}"
      gradients[id] = "<linearGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" $stops</linearGradient>"
      paint = "fill=\"url(#$id) ${nodeColor(link.source)}\""
    }
    val mid = num((x0 + x1) / 2)
    val from = escText(model.nodes[link.source].id)
    val to = escText(model.nodes[link.target].id)
    linksOut +=
      "<path class=\"pele-sankey-link\" data-id=\"$from-&gt;$to\" d=\"M${num(x0)},${num(link.sourceY - half)}C$mid,${num(
        link.sourceY - half,
      )} $mid,${num(link.targetY - half)} ${num(x1)},${num(link.targetY - half)}L${num(x1)},${num(
        link.targetY + half,
      )}C$mid,${num(link.targetY + half)} $mid,${num(link.sourceY + half)} ${num(x0)},${num(
        link.sourceY + half,
      )}Z\" $paint>" +
      "<title>$from → $to: ${esc(prefix)}${jsStr(rounded(link.value))}${esc(suffix)}</title></path>"
  }

  val title = layoutLabel(model.title, false, measurer, size, titleRoom(options), Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = maxOf(maxX - minX, title.width)
  val ox = pad - minX + (inner - (maxX - minX)) / 2
  val oy = pad + titleHeight - minY
  val width = ceil(inner + 2 * pad)
  val height = ceil(maxY - minY + titleHeight + 2 * pad)

  val svg = svgDocument(
    "sankey",
    width,
    height,
    size,
    options,
    model,
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g transform=\"translate(${num(ox)},${num(oy)})\">" +
      (if (gradients.size > 0) "<defs>${gradients.values.joinToString("")}</defs>" else "") +
      "<g class=\"pele-sankey-links\" fill-opacity=\"0.4\">$linksOut</g>" +
      "<g class=\"pele-sankey-nodes\">$nodesOut</g>" +
      "<g class=\"pele-labels\" font-size=\"${jsStr(small)}\">$labelsOut</g>" +
      "</g>",
  )

  return Rendered(svg, width, height, ArrayList())
}
