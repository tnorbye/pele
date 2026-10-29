// Ported from Pele src/diagrams/treeview/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treeview

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.leftLabelSvg
import pele.diagrams.common.literalLabel
import pele.diagrams.common.tighten
import pele.diagrams.common.titleRoom
import pele.js.JsRegExp
import pele.js.jsRound
import pele.js.jsSplitStrings
import pele.svg.FONT_MONO
import pele.svg.ROUNDED
import pele.svg.classNames
import pele.svg.esc
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import java.util.IdentityHashMap
import kotlin.math.ceil

private const val ICON_GAP = 6.0
private const val DESC_GAP = 16.0
private const val GUIDE_INSET = 7.0
private const val ELBOW = 4.0
private const val HIGHLIGHT_OVERHANG = 4.0

private val RE_SPACES = JsRegExp("""\s+""")

private fun setting(config: Config, key: String, fallback: Double, min: Double, max: Double): Double {
  val value = (config["treeView"] as? Map<*, *>)?.get(key)
  return if (value is Double && value >= min && value <= max) value else fallback
}

class TreeSettings(val paddingX: Double, val paddingY: Double, val rowIndent: Double, val lineThickness: Double)

// Mermaid's layout options, with Pele's defaults.
fun treeSettings(config: Config): TreeSettings =
  TreeSettings(
    paddingX = setting(config, "paddingX", 5.0, 0.0, 100.0),
    paddingY = setting(config, "paddingY", 2.0, 0.0, 100.0),
    rowIndent = setting(config, "rowIndent", 15.0, 0.0, 400.0),
    lineThickness = setting(config, "lineThickness", 1.0, 0.25, 12.0),
  )

private class Row(
  val node: TreeNode,
  val x: Double,
  val cy: Double,
  val icon: String?,
  val text: String,
  val labelX: Double,
  val highlighted: Boolean,
  val label: Label?,
  val description: Label?,
  val top: Double,
  val height: Double,
)

fun renderTreeView(model: TreeViewModel, config: Config, options: RenderOptions): Rendered =
  tighten(options) { tight -> draw(model, config, options, tight) }

private class Pending(val node: TreeNode, val depth: Int, val slot: Boolean)

private fun draw(model: TreeViewModel, config: Config, options: RenderOptions, tight: Boolean): Rendered {
  val size = options.fontSize ?: 16.0
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = iconConfig(config)
  val mono = options.monospace?.contains("files") == true
  val family = if (mono) " font-family=\"$FONT_MONO\"" else ""

  val settings = treeSettings(config)
  val paddingX = settings.paddingX
  val paddingY = settings.paddingY
  val rowIndent = settings.rowIndent
  val lineThickness = settings.lineThickness
  var maxDepth = 0
  val depths = ArrayList<Pair<TreeNode, Int>>()
  for (node in model.root.children) depths.add(Pair(node, 0))
  var d = 0
  while (tight && d < depths.size) {
    val (node, depth) = depths[d]
    maxDepth = maxOf(maxDepth, depth)
    for (child in node.children) depths.add(Pair(child, depth + 1))
    d++
  }
  val step = if (tight) minOf(rowIndent + paddingX, maxOf(2.0, ((options.maxWidth ?: 320.0) - 120) / maxOf(1, maxDepth))) else rowIndent + paddingX
  val rowHeight = jsRound(size * 1.5) + 2 * paddingY
  val iconSize = size

  val title = layoutLabel(model.title, false, measurer, size, if (tight) titleRoom(options) else 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0

  // Siblings line up: when one entry of a folder has an icon, the others leave room for one.
  val hasIcon = { list: List<TreeNode> -> list.any { child -> getNodeIcon(child, icons) != null } }
  val rows = ArrayList<Row>()
  val rowOf = IdentityHashMap<TreeNode, Row>()
  val pending = ArrayList<Pending>()
  val push = { list: List<TreeNode>, depth: Int ->
    val slot = hasIcon(list)
    for (i in list.size - 1 downTo 0) pending.add(Pending(list[i], depth, slot))
  }
  push(model.root.children, 0)
  var labelRight = 0.0
  var anyHighlight = false
  var rowsHeight = 0.0
  while (pending.size > 0) {
    val (node, depth, slot) = pending.removeAt(pending.size - 1).let { Triple(it.node, it.depth, it.slot) }
    val x = depth * step
    val text = decodeEntities(node.name)
    val labelX = x + (if (slot) iconSize + ICON_GAP else 0.0)
    val room = maxOf(40.0, (options.maxWidth ?: 320.0) - 2 * pad - 2 * HIGHLIGHT_OVERHANG - labelX)
    val style = (if (node.nodeType == "directory") Style.Bold else 0) or (if (mono) Style.Mono else 0)
    val label = if (tight) literalLabel(text, measurer, size, room, style) else null
    val description =
      if (tight && !node.description.isNullOrEmpty()) literalLabel(decodeEntities(node.description), measurer, size, room, Style.Italic) else null
    val width = if (label != null) label.width else measurer.width(text, size, style)
    if (labelX + width > labelRight) labelRight = labelX + width
    val highlighted = node.cssClass != null && node.cssClass.jsSplitStrings(RE_SPACES).contains("highlight")
    if (highlighted) anyHighlight = true
    val height = if (tight) maxOf(rowHeight, (label?.height ?: 0.0) + (description?.height ?: 0.0) + 2 * paddingY) else rowHeight
    val cy = rowsHeight + (if (tight) paddingY + maxOf(iconSize, label?.height ?: 0.0) / 2 else height / 2)
    val row = Row(node, x, cy, getNodeIcon(node, icons), text, labelX, highlighted, label, description, rowsHeight, height)
    rowsHeight += height
    rows.add(row)
    rowOf[node] = row
    push(node.children, depth + 1)
  }

  val descX = labelRight + DESC_GAP
  var width = labelRight
  for (row in rows) {
    val description = row.node.description
    if (description.isNullOrEmpty()) continue
    val w = if (row.description != null) row.description.width else measurer.width(decodeEntities(description), size, Style.Italic)
    width = maxOf(width, (if (tight) row.labelX else descX) + w)
  }
  width = maxOf(width, title.width)
  val inset = pad + (if (anyHighlight) HIGHLIGHT_OVERHANG else 0.0)
  val top = pad + titleHeight
  val totalWidth = ceil(width + 2 * inset)
  val totalHeight = ceil(top + rowsHeight + pad)

  val guides = StringBuilder()
  for (row in rows) {
    val children = row.node.children
    if (children.size == 0) continue
    val gx = inset + row.x + GUIDE_INSET
    val tick = maxOf(gx, inset + row.x + step - paddingX)
    val last = top + rowOf[children[children.size - 1]]!!.cy
    val r = minOf(ELBOW, tick - gx)
    var path = "M${num(gx)},${num(top + row.top + row.height - 2)}V${num(last - r)}"
    if (r > 0) path += "Q${num(gx)},${num(last)} ${num(gx + r)},${num(last)}H${num(tick)}"
    var i = 0
    while (i < children.size - 1 && tick > gx) {
      path += "M${num(gx)},${num(top + rowOf[children[i]]!!.cy)}H${num(tick)}"
      i++
    }
    guides.append("<path d=\"$path\"/>")
  }

  val baseline = size * 0.35
  val body = StringBuilder()
  for (row in rows) {
    val node = row.node
    val cy = top + row.cy
    val directory = node.nodeType == "directory"
    var inner = ""
    if (row.highlighted) {
      val x = inset + row.x - HIGHLIGHT_OVERHANG
      inner += "<rect class=\"pele-tree-highlight\" x=\"${num(x)}\" y=\"${num(top + row.top + 1)}\" width=\"${num(
        totalWidth - inset + HIGHLIGHT_OVERHANG - x,
      )}\" height=\"${num(row.height - 2)}\" fill=\"var(--_c)\" fill-opacity=\"0.14\"$ROUNDED/>"
    }
    if (row.icon != null) {
      val name = if (row.icon.startsWith("$BUILTIN_PACK:")) row.icon.substring(BUILTIN_PACK.length + 1) else ""
      val glyph = GLYPHS[name] ?: GLYPHS[if (directory) "folder" else "file"]!!
      val drawn = options.icons?.invoke(row.icon) ?: "<path d=\"$glyph\" stroke=\"var(--_m)\" stroke-width=\"1.5\"/>"
      inner += "<svg class=\"pele-icon\" data-icon=\"${escText(row.icon)}\" x=\"${num(inset + row.x)}\" y=\"${num(
        cy - iconSize / 2,
      )}\" width=\"${num(iconSize)}\" height=\"${num(
        iconSize,
      )}\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$drawn</svg>"
    }
    if (row.label != null) {
      inner += leftLabelSvg(row.label, inset + row.labelX, top + row.top + paddingY, " class=\"pele-label\"${if (directory) " font-weight=\"var(--_hw)\"" else ""}$family")
    } else if (row.text != "") {
      inner += "<text class=\"pele-label\" x=\"${num(inset + row.labelX)}\" y=\"${num(cy + baseline)}\"${
        if (directory) " font-weight=\"var(--_hw)\"" else ""
      }$family xml:space=\"preserve\">${esc(row.text)}</text>"
    }
    if (row.description != null) {
      inner += leftLabelSvg(row.description, inset + row.labelX, top + row.top + paddingY + (row.label?.height ?: 0.0), " class=\"pele-tree-description\" font-style=\"italic\" fill=\"var(--_m)\"")
    } else if (!node.description.isNullOrEmpty()) {
      inner += "<text class=\"pele-tree-description\" x=\"${num(inset + descX)}\" y=\"${num(
        cy + baseline,
      )}\" font-style=\"italic\" fill=\"var(--_m)\" xml:space=\"preserve\">${esc(decodeEntities(node.description))}</text>"
    }
    body.append("<g class=\"pele-node pele-tree-${if (directory) "dir" else "file"}${classNames(node.cssClass ?: "")}\" data-id=\"${escText(
      node.name,
    )}\">$inner</g>")
  }

  val content =
    labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      (if (guides.isNotEmpty()) "<g class=\"pele-tree-guides\" fill=\"none\" stroke=\"var(--_b)\" stroke-width=\"${num(lineThickness)}\">$guides</g>" else "") +
      body

  return Rendered(
    svg = svgDocument("treeView", totalWidth, totalHeight, size, options, model, content),
    width = totalWidth,
    height = totalHeight,
    links = ArrayList(),
  )
}
