// Ported from Pele src/diagrams/venn/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.venn

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.js.JsRegExp
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsonStringify
import pele.svg.Acc
import pele.svg.ResolvedStyle
import pele.svg.escText
import pele.svg.ink
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.resolveStyle
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.ceil

private const val RADIUS = 100.0
private const val MAX_GROWTH = 1.6
private val NO_STYLE = resolveStyle(emptyList())

private val RE_FILL_OPACITY = JsRegExp("""[";]fill-opacity:""")

private class Line(
  // The id is that of a text.
  val id: String?,
  val raw: String,
  val size: Double,
  val base: Int,
  var label: Label,
)

private class Region(
  val sets: List<String>,
  val inside: List<Int>,
  val spot: Spot,
  // What is written in the region: its label first, then its texts. The id is that of a text.
  val lines: MutableList<Line>,
  val style: ResolvedStyle,
  // Whether its text is written across other circles.
  val across: Boolean,
)

// One key for a list of set ids. Ids may hold any character, so they are not simply joined.
private fun keyOf(ids: List<String>): String = if (ids.size == 1) ids[0] else jsonStringify(ids)

fun renderVenn(model: VennModel, _config: Config, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val tiny = jsRound(size * 0.8125)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val circles = layoutCircles(model.subsets)
  val index = LinkedHashMap<String, Int>()
  circles.forEachIndexed { i, c -> index[c.id] = i }

  // Styles for the same targets add up, later declarations winning.
  val styles = LinkedHashMap<String, LinkedHashMap<String, String>>()
  for (declaredStyle in model.styles) {
    val key = keyOf(declaredStyle.targets)
    var merged = styles[key]
    if (merged == null) {
      merged = LinkedHashMap()
      styles[key] = merged
    }
    for ((property, value) in declaredStyle.styles) merged[property] = value
  }
  fun styleOf(key: String): ResolvedStyle {
    val declared = styles[key] ?: return NO_STYLE
    val list = ArrayList<String>()
    for ((property, value) in declared) list.add("$property:$value")
    return resolveStyle(list)
  }

  // Circles that touch each circle; only those can cut into a region of it.
  val neighbors = circles.map { ArrayList<Int>() }
  for (a in circles.indices) {
    for (b in a + 1 until circles.size) {
      if (hypot(circles[a].x - circles[b].x, circles[a].y - circles[b].y) < circles[a].r + circles[b].r) {
        neighbors[a].add(b)
        neighbors[b].add(a)
      }
    }
  }

  // One region for each set and each stated overlap; a later statement about the same sets replaces an earlier one.
  val stated = LinkedHashMap<String, VennSubset>()
  for (subset in model.subsets) stated[keyOf(subset.sets)] = subset
  val regions = LinkedHashMap<String, Region>()
  for ((key, subset) in stated) {
    val sets = subset.sets.distinct()
    val inside = sets.map { id -> index[id]!! }
    val within = inside.toSet()
    var smallest = inside[0]
    for (i in inside) if (circles[i].r < circles[smallest].r) smallest = i
    val style = styleOf(key)
    val text = subset.label ?: (if (subset.sets.size == 1) subset.sets[0] else null)
    val base = (if (style.bold) Style.Bold else 0) or (if (style.italic) Style.Italic else 0)
    val others = neighbors[smallest].filter { !within.contains(it) }
    var spot = findSpot(circles, inside, others)
    // A region that other circles cover, or all but cover, is written across them instead.
    val across = others.size > 0 && spot.margin < circles[smallest].r * 0.2
    if (across) spot = findSpot(circles, inside, emptyList())
    val region = Region(sets, inside, spot, ArrayList(), style, across)
    if (!text.isNullOrEmpty()) {
      val fontSize = if (sets.size == 1) size else small
      region.lines.add(Line(null, text, fontSize, base, layoutLabel(text, false, measurer, fontSize, 4000.0, base)))
    }
    regions[key] = region
  }
  for (node in model.textNodes) {
    val raw = node.label ?: node.id
    regions[keyOf(node.sets)]?.lines?.add(Line(node.id, raw, tiny, 0, layoutLabel(raw, false, measurer, tiny, 4000.0)))
  }

  // Lengths so far are in units of the layout. The scale gives the largest circle a set radius,
  // and grows, within limits, until every region holds what is written in it.
  var largest = 0.0
  for (c in circles) if (c.r > largest) largest = c.r
  var scale = if (largest > 0) RADIUS / largest else 1.0
  var growth = 1.0
  for (region in regions.values) {
    if (region.spot.margin <= 0) continue
    var width = 0.0
    var height = 0.0
    for (line in region.lines) {
      width = maxOf(width, line.label.width)
      height += line.label.height
    }
    val need = hypot(width, height) / 2 + 4
    growth = maxOf(growth, minOf(need / (region.spot.margin * scale), MAX_GROWTH))
  }
  scale *= growth

  // What is still too wide for its region is wrapped to it.
  for (region in regions.values) {
    val room = maxOf(region.spot.margin * scale * 1.8, 72.0)
    for (line in region.lines) {
      if (line.label.width > room) line.label = layoutLabel(line.raw, false, measurer, line.size, room, line.base)
    }
  }

  var minX = Double.POSITIVE_INFINITY
  var minY = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  var maxY = Double.NEGATIVE_INFINITY
  for (c in circles) {
    minX = minOf(minX, (c.x - c.r) * scale)
    minY = minOf(minY, (c.y - c.r) * scale)
    maxX = maxOf(maxX, (c.x + c.r) * scale)
    maxY = maxOf(maxY, (c.y + c.r) * scale)
  }
  for (region in regions.values) {
    var wide = 0.0
    var tall = 0.0
    for (line in region.lines) {
      wide = maxOf(wide, line.label.width)
      tall += line.label.height
    }
    minX = minOf(minX, region.spot.x * scale - wide / 2)
    maxX = maxOf(maxX, region.spot.x * scale + wide / 2)
    minY = minOf(minY, region.spot.y * scale - tall / 2)
    maxY = maxOf(maxY, region.spot.y * scale + tall / 2)
  }
  if (circles.size == 0) {
    minX = 0.0
    minY = 0.0
    maxX = 0.0
    maxY = 0.0
  }

  val modelTitle = model.title
  val title = layoutLabel(if (!modelTitle.isNullOrEmpty()) normalizeText(modelTitle) else modelTitle, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = maxOf(maxX - minX, title.width)
  val ox = pad + (inner - (maxX - minX)) / 2 - minX
  val oy = pad + titleHeight - minY
  val width = ceil(inner + 2 * pad)
  val height = ceil(maxY - minY + titleHeight + 2 * pad)
  val placed = circles.map { c -> Circle(c.id, c.x * scale + ox, c.y * scale + oy, c.r * scale) }

  var sets = ""
  placed.forEachIndexed { i, c ->
    val color = seriesColor(i)
    val style = regions[c.id]?.style ?: NO_STYLE
    sets += "<circle class=\"pele-venn-set\" data-id=\"${escText(c.id)}\" cx=\"${num(c.x)}\" cy=\"${num(c.y)}\" r=\"${num(c.r)}\" fill=\"$color\" fill-opacity=\"0.2\" stroke=\"$color\"${style.shape}/>"
  }

  val painted = regions.values.filter { r -> r.sets.size > 1 && r.style.shape != "" }
  // The fill under a region's text is the last overlap drawn over all of it, or its own circle when the style sets an opacity.
  fun under(region: Region): String {
    if (region.across) return ""
    var fill = if (region.sets.size == 1 && RE_FILL_OPACITY.test(region.style.shape)) region.style.shape else ""
    for (other in painted) if (other.sets.all { id -> region.sets.contains(id) }) fill = other.style.shape
    return fill
  }

  var overlaps = ""
  var labels = ""
  for (region in regions.values) {
    val id = escText(region.sets.joinToString("|"))
    // An overlap is only drawn when a style gives it a look of its own; otherwise the circles show it.
    if (region.sets.size > 1 && region.style.shape != "") {
      val d = regionPath(region.inside.map { placed[it] }, ::num)
      if (d != "") overlaps += "<path class=\"pele-venn-overlap\" data-id=\"$id\" d=\"$d\" fill=\"none\"${region.style.shape}/>"
    }
    var total = 0.0
    for (line in region.lines) total += line.label.height
    if (total == 0.0) continue
    val x = region.spot.x * scale + ox
    var y = region.spot.y * scale + oy - total / 2
    val fill = under(region)
    var content = ""
    for (line in region.lines) {
      val own = if (line.id == null) region.style.text else styleOf(line.id).text
      val color = ink(fill, own)
      val attrs =
        if (line.id == null) {
          " class=\"pele-label\"${if (line.size == size) "" else " font-size=\"${jsStr(line.size)}\""}${if (!color.isNullOrEmpty()) " fill=\"$color\"" else ""}$own"
        } else {
          " class=\"pele-venn-text\" data-id=\"${escText(line.id)}\" font-size=\"${jsStr(tiny)}\" fill=\"${color ?: "var(--_m)"}\"$own"
        }
      content += labelSvg(line.label, x, y + line.label.height / 2, attrs)
      y += line.label.height
    }
    labels += "<g class=\"pele-venn-region\" data-id=\"$id\">$content</g>"
  }

  val svg = svgDocument(
    "venn",
    width,
    height,
    size,
    options,
    Acc(),
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g class=\"pele-venn-sets\">$sets</g>" +
      (if (overlaps != "") "<g class=\"pele-venn-overlaps\">$overlaps</g>" else "") +
      (if (labels != "") "<g class=\"pele-venn-labels\">$labels</g>" else ""),
  )
  return Rendered(svg, width, height, ArrayList())
}
