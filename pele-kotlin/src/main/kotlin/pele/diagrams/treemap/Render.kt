// Ported from Pele src/diagrams/treemap/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treemap

import java.lang.StrictMath.log10
import kotlin.math.ceil
import kotlin.math.floor
import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.capWidth
import pele.diagrams.common.fitText
import pele.diagrams.common.titleRoom
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsTrim
import pele.svg.classNames
import pele.svg.esc
import pele.svg.escText
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel

private const val HEADER = 28.0
private const val SHORT_HEADER = 18.0
private const val SIDE = 6.0
private const val LINE = 1.25

private val RE_FILL = JsRegExp("""[";]fill:""")

private fun titlePart(raw: String): String = esc(decodeEntities(raw).jsTrim()).jsTrim()

private fun leafCount(roots: List<TreemapNode>): Int {
  var count = 0
  val stack = ArrayList(roots)
  while (stack.size > 0) {
    val node = stack.removeAt(stack.size - 1)
    if (node.children != null) stack.addAll(node.children)
    else if (node.value != null && node.value!!.isFinite() && node.value!! > 0) count++
  }
  return count
}

// Caps corners at a quarter of a small box's shorter side; 4px is the default radius.
private fun corners(attrs: String, b: Box): String {
  val cap = minOf(b.w, b.h) / 4
  return if (cap < 4) rounded(attrs).jsReplace("rx:var(--_r)", "rx:min(var(--_r),${num(cap)}px)")
  else rounded(attrs)
}

private fun option(
  config: Config,
  key: String,
  fallback: Double,
  min: Double,
  max: Double,
): Double {
  val value = (config["treemap"] as? Map<*, *>)?.get(key)
  return if (value is Double && value >= min) minOf(value, max) else fallback
}

fun renderTreemap(model: TreemapModel, config: Config, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val tiny = jsRound(size * 0.75)
  val least = jsRound(size * 0.5625)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val treemap = config["treemap"] as? Map<*, *>
  val pad = options.padding ?: option(config, "diagramPadding", 8.0, 0.0, 2000.0)

  // Mermaid sizes the diagram as ten times `nodeWidth` by ten times `nodeHeight`.
  val width =
    jsRound(capWidth(options, option(config, "nodeWidth", 64.0, 1.0, 400.0) * 10, 200.0, 2 * pad))
  val height = jsRound(option(config, "nodeHeight", 40.0, 1.0, 400.0) * 10)
  val showValues = treemap?.get("showValues") != false
  val valueFormat = treemap?.get("valueFormat")
  val format = formatter(if (valueFormat is String) valueFormat else ",")
  // Reduce gaps and insets as average leaf area shrinks.
  val crowd =
    minOf(
      1.0,
      maxOf(0.0, log10((width * height) / maxOf(1.0, leafCount(model.roots).toDouble()) / 800)),
    )
  val boxes =
    layoutTreemap(
      model.roots,
      width,
      height,
      Insets(
        gap = option(config, "padding", jsRound(2 + 2 * crowd), 0.0, 200.0),
        header = HEADER,
        short = SHORT_HEADER,
        side = jsRound(3 + 3 * crowd),
      ),
    )

  val title = layoutLabel(model.title, false, measurer, size, titleRoom(options), Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 8 else 0.0
  val left = pad + maxOf(0.0, (title.width - width) / 2)
  val top = pad + titleHeight

  val sections = StringBuilder()
  val leaves = StringBuilder()
  for (b in boxes) {
    val node = b.node
    val style = resolveStyle(node.cssCompiledStyles ?: emptyList())
    val color = seriesColor(b.series)
    val x = left + b.x
    val y = top + b.y
    val name = decodeEntities(node.name).jsTrim()
    val value = if (showValues) format(b.value) else ""
    val group = "data-id=\"${escText(node.name)}\">"
    // Include headerless ancestors in tooltips because their children cover them.
    val own = titlePart(node.name)
    val path = ArrayList<String>()
    if (own != "") path.add(own)
    var p = b.parent
    while (p != null && !p.headed) {
      val section = titlePart(p.node.name)
      if (section != "") path.add(0, section)
      p = p.parent
    }
    val full = path.joinToString(" › ")
    val tooltip = { drawn: String? ->
      if (full == "" || (drawn == name && full == own)) "" else "<title>$full</title>"
    }
    val rect =
      "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(b.w)}\" height=\"${num(b.h)}\" fill=\"$color\""
    // Inline font sizes override SVG attributes, so truncate instead of shrinking.
    val fixed = style.fontSize
    val textStyle = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val ink = inkFill(style)

    if (node.children != null) {
      var text = ""
      var drawn: String? = null
      if (b.headed) {
        val short = b.header < HEADER
        val labelSize = fixed ?: (if (short) tiny else small)
        val valueSize = fixed ?: tiny
        val room = b.w - 2 * SIDE - 4
        val valueWidth =
          if (value.isNotEmpty()) measurer.width(value, valueSize, textStyle) else 0.0
        val withValue = !short && valueWidth > 0 && room - valueWidth - 12 >= 3 * labelSize
        val label =
          if (labelSize * LINE < b.header) {
            fitText(
              name,
              if (withValue) room - valueWidth - 12 else room,
              labelSize,
              labelSize,
              measurer,
              textStyle or Style.Bold,
            )
          } else {
            null
          }
        val baseline = y + b.header / 2 + 1
        drawn = label?.text
        if (label != null) {
          text +=
            "<text x=\"${num(x + SIDE + 2)}\" y=\"${num(baseline + labelSize * 0.35)}\" font-weight=\"var(--_hw)\"${if (short) " font-size=\"${jsStr(labelSize)}\"" else ""}$ink${style.text}>${esc(label.text)}</text>"
        }
        if (withValue && valueSize * LINE <= HEADER) {
          text +=
            "<text class=\"pele-value\" x=\"${num(x + b.w - SIDE - 2)}\" y=\"${num(baseline + valueSize * 0.35)}\" font-size=\"${jsStr(tiny)}\" text-anchor=\"end\"${ink.ifEmpty { " fill=\"var(--_m)\"" }}${style.text}>${esc(value)}</text>"
        }
      }
      // A section is a tint of its color, unless a class gives it a fill of its own. Headerless
      // sections draw no box, so their children blend into the grid.
      val tint = if (RE_FILL.test(style.shape)) "" else " fill-opacity=\"0.12\""
      val shape =
        if (b.headed) {
          "$rect${corners("$tint stroke=\"$color\"${style.shape}", b)}/>"
        } else {
          "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(b.w)}\" height=\"${num(b.h)}\" fill=\"none\"/>"
        }
      sections.append(
        "<g class=\"pele-node pele-section${classNames(node.classSelector ?: "")}\" $group${tooltip(drawn)}$shape$text</g>"
      )
      continue
    }

    var text = ""
    val room = b.w - 8
    val smallest = fixed ?: least
    val tallest =
      if (fixed == null) minOf(size, floor((b.h - 4) / LINE))
      else if (fixed * LINE <= b.h - 4) fixed else 0.0
    val label =
      if (tallest >= smallest) fitText(name, room, tallest, smallest, measurer, textStyle) else null
    if (label != null) {
      val cx = num(x + b.w / 2)
      val cy = y + b.h / 2
      val valueSize = fixed ?: maxOf(least, jsRound(label.size * 0.8))
      val lines = (label.size + valueSize) * LINE
      if (
        value.isNotEmpty() &&
          lines <= b.h - 4 &&
          measurer.width(value, valueSize, textStyle) <= room
      ) {
        val first = cy - lines / 2 + (label.size * LINE) / 2
        val second = cy + lines / 2 - (valueSize * LINE) / 2
        text =
          "<text x=\"$cx\" y=\"${num(first + label.size * 0.35)}\" font-size=\"${num(label.size)}\"$ink${style.text}>${esc(label.text)}</text>" +
            "<text class=\"pele-value\" x=\"$cx\" y=\"${num(second + valueSize * 0.35)}\" font-size=\"${num(valueSize)}\"$ink${style.text}>${esc(value)}</text>"
      } else {
        text =
          "<text x=\"$cx\" y=\"${num(cy + label.size * 0.35)}\" font-size=\"${num(label.size)}\"$ink${style.text}>${esc(label.text)}</text>"
      }
    }
    leaves.append(
      "<g class=\"pele-node pele-leaf${classNames(node.classSelector ?: "")}\" $group${tooltip(label?.text)}$rect${corners(style.shape, b)}/>$text</g>"
    )
  }

  val totalWidth = ceil(maxOf(width, title.width) + 2 * pad)
  val totalHeight = ceil(height + titleHeight + 2 * pad)
  val content =
    labelSvg(
      title,
      totalWidth / 2,
      pad + title.height / 2,
      " class=\"pele-title\" font-weight=\"var(--_tw)\"",
    ) +
      (if (sections.isNotEmpty())
        "<g class=\"pele-sections\" font-size=\"${jsStr(small)}\">$sections</g>"
      else "") +
      (if (leaves.isNotEmpty())
        "<g class=\"pele-leaves\" text-anchor=\"middle\" fill=\"var(--_bg)\">$leaves</g>"
      else "")

  return Rendered(
    svg = svgDocument("treemap", totalWidth, totalHeight, size, options, model, content),
    width = totalWidth,
    height = totalHeight,
    links = ArrayList(),
  )
}
