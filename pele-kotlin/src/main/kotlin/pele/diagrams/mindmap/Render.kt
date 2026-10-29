// Ported from Pele src/diagrams/mindmap/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.mindmap

import pele.Config
import pele.IconResolver
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.iconSvg
import pele.diagrams.common.titleRoom
import pele.diagrams.common.turnToFit
import pele.js.jsOr
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.Acc
import pele.svg.classNames
import pele.svg.drawShape
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.seriesColor
import pele.svg.shapeInset
import pele.svg.shapeSize
import pele.svg.svgDocument
import pele.svg.titleLabel
import pele.text.Label
import pele.text.defaultMeasurer
import pele.text.iconMarkup
import pele.text.labelLine
import pele.text.layoutLabel
import pele.text.lineMiddle
import kotlin.math.abs
import kotlin.math.ceil

// Indexed by Mermaid's node type. A node without delimiters is text on a line, except the root.
private val SHAPES = listOf("", "rounded", "rect", "circle", "cloud", "bang", "hex")
private const val ROOT_SHAPE = "stadium"

private const val GAP_X = 36.0
private const val GAP_Y = 8.0

// Between two subtrees, of which at least one has children of its own.
private const val GROUP_GAP = 18.0
private const val BRANCH_GAP_X = 52.0
private const val BRANCH_GAP_Y = 28.0
private const val TEXT_PAD = 6.0
private const val PILL_PAD = 12.0
private const val TINT = 0.22
private const val ICON_GAP = 6.0

// Running down, each level is set in by this much, and links leave a node this far from its left edge.
private const val INDENT = 28.0
private const val STEM = 12.0
private const val BEND = 8.0
private const val ROW_GAP = 10.0

// A label with each line starting at `left`, for an outline, where centered lines would look ragged.
private fun leftLabel(label: Label, left: Double, cy: Double, icons: IconResolver?): String {
  if (label.lines.size < 2) return labelSvg(label, left + label.width / 2, cy, " class=\"pele-label\"", icons)
  var out = ""
  val top = cy - label.height / 2
  for (k in 0 until label.lines.size) {
    out += labelSvg(labelLine(label, k), left + label.widths[k] / 2, top + lineMiddle(label, k), " class=\"pele-label\"", icons)
  }
  return out
}

fun renderMindmap(model: MindmapModel, config: Config, options: RenderOptions): Rendered =
  turnToFit(options) { down -> draw(model, config, options, down) }

// `down` draws the map as an outline: the root at the top, and each level set in under its parent.
// It is far narrower than branches spreading to both sides, and as tall as it needs to be.
private fun draw(model: MindmapModel, config: Config, options: RenderOptions, down: Boolean): Rendered {
  val size = options.fontSize ?: 16.0
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val icons = options.icons
  val maxNodeWidth = (config["mindmap"] as? Map<*, *>)?.get("maxNodeWidth")
  val wrap = if (maxNodeWidth is Double && maxNodeWidth > 0) minOf(maxNodeWidth, 2000.0) else 200.0
  val glyph = size * 1.25

  val nodes = model.nodes
  val n = nodes.size
  val parent = IntArray(n)
  val depth = IntArray(n)
  for (i in 0 until n) {
    for (child in nodes[i].children) {
      parent[child.id] = i
      depth[child.id] = depth[i] + 1
    }
  }
  // An outline has the width the host gives it, less what its level is set in.
  val room = if (down && options.maxWidth != null && options.maxWidth > 0) options.maxWidth - 2 * pad else Double.POSITIVE_INFINITY
  val labels = arrayOfNulls<Label>(n)
  val shapes = arrayOfNulls<String>(n)
  val w = DoubleArray(n)
  val h = DoubleArray(n)
  val dy = DoubleArray(n)
  // Where links meet the node, measured down from its top: its middle.
  val anchor = DoubleArray(n)
  // Whether the host has the node's icon. One it does not have takes no room.
  val drawn = IntArray(n)

  for (i in 0 until n) {
    val node = nodes[i]
    drawn[i] = if (node.icon != null && iconMarkup(node.icon!!) != "") 1 else 0
    val fit = minOf(wrap, maxOf(72.0, room - depth[i] * INDENT - 2 * TEXT_PAD - (if (drawn[i] != 0) glyph + ICON_GAP else 0.0)))
    val label = layoutLabel(node.descr, true, measurer, size, fit)
    labels[i] = label
    val tw = label.width + (if (drawn[i] != 0) glyph + (if (label.width > 0) ICON_GAP else 0.0) else 0.0)
    val th = maxOf(label.height, jsRound(size * 1.5))
    var shape = SHAPES.getOrNull(node.type).let { if (it.isNullOrEmpty()) (if (i == 0) ROOT_SHAPE else "") else it }
    // In an outline the root heads a column, and the trunk drops from under its left end. A circle
    // or a cloud would stand wide of that, so the root is a pill there unless it is a box already.
    if (down && i == 0 && shape != "rect" && shape != "rounded") shape = ROOT_SHAPE
    shapes[i] = shape
    if (shape == "") {
      // A node with no shape of its own is plain text when it ends a branch, and text in a
      // tinted pill when the branch goes on from it, which sets the levels apart.
      val pill = node.children.size > 0
      w[i] = tw + 2 * (if (pill) PILL_PAD else TEXT_PAD)
      h[i] = th + (if (pill) 6 else 0)
      anchor[i] = h[i] / 2
    } else {
      val s = shapeSize(shape, tw, th)
      w[i] = s.w
      h[i] = s.h
      dy[i] = s.dy
      anchor[i] = s.h / 2
    }
  }

  // Each subtree takes a band of height `extent`. `rel` is a subtree's offset among its siblings,
  // `own` the node's offset inside its band, and `kids` the offset of its children's bands.
  val extent = DoubleArray(n)
  val rel = DoubleArray(n)
  val own = DoubleArray(n)
  val kids = DoubleArray(n)
  var stacked = 0.0

  // Stacks subtrees top to bottom and returns the height at which their parent's anchor belongs.
  val stack = { list: List<MindmapNode>, from: Int, to: Int, gap: Double ->
    var y = 0.0
    var last = 0.0
    for (k in from until to) {
      val c = list[k].id
      rel[c] = y
      val grouped = list[k].children.size > 0 || (k + 1 < to && list[k + 1].children.size > 0)
      last = if (grouped) maxOf(gap, GROUP_GAP) else gap
      y += extent[c] + last
    }
    stacked = y - last
    val a = list[from].id
    val b = list[to - 1].id
    (rel[a] + own[a] + anchor[a] + rel[b] + own[b] + anchor[b]) / 2
  }

  // Nodes are in depth-first order, so going backwards visits children before their parent.
  for (i in n - 1 downTo 1) {
    val children = nodes[i].children
    if (children.size == 0) {
      extent[i] = h[i]
      continue
    }
    val top = stack(children, 0, children.size, GAP_Y) - anchor[i]
    val low = minOf(0.0, top)
    extent[i] = maxOf(stacked, top + h[i]) - low
    own[i] = top - low
    kids[i] = -low
  }

  val x = DoubleArray(n)
  val y = DoubleArray(n)
  val band = DoubleArray(n)
  val side = IntArray(n)
  // Running down: how far the trunk under each node has been drawn.
  val trunk = DoubleArray(n)

  if (down) {
    // Nodes are in depth-first order, which is the order of an outline's rows.
    var row = 0.0
    for (i in 0 until n) {
      if (i > 0 && parent[i] == 0) row += ROW_GAP
      x[i] = depth[i] * INDENT + w[i] / 2
      y[i] = row
      row += h[i] + ROW_GAP
    }
  } else if (n > 0) {
    // The first branches go right and the rest left, split where the two sides are closest in height.
    val branches = nodes[0].children
    val count = branches.size
    var total = 0.0
    for (branch in branches) total += extent[branch.id]
    var split = count
    var best = Double.POSITIVE_INFINITY
    var right = 0.0
    for (k in 1 until count) {
      right += extent[branches[k - 1].id]
      val diff = abs(2 * right - total + (2 * k - count) * BRANCH_GAP_Y)
      if (diff < best) {
        best = diff
        split = k
      }
    }
    var from = 0
    var to = split
    var dir = 1
    while (from < count) {
      val middle = stack(branches, from, to, BRANCH_GAP_Y)
      for (k in from until to) {
        val c = branches[k].id
        band[c] = rel[c] - middle
        side[c] = dir
      }
      from = to
      to = count
      dir = -1
    }
    y[0] = -h[0] / 2
    for (i in 0 until n) {
      if (i > 0) y[i] = band[i] + own[i]
      for (child in nodes[i].children) {
        val c = child.id
        if (i > 0) {
          side[c] = side[i]
          band[c] = band[i] + kids[i] + rel[c]
        }
        x[c] = x[i] + side[c] * (w[i] / 2 + (if (i > 0) GAP_X else BRANCH_GAP_X) + w[c] / 2)
      }
    }
  }

  var minX = 0.0
  var maxX = 0.0
  var minY = 0.0
  var maxY = 0.0
  var out = ""
  var edges = ""
  var body = ""
  var color = ""
  val flush = { section: Int? ->
    if (body != "") {
      out +=
        "<g class=\"pele-branch pele-section-${section ?: 0}\">" +
        "<g class=\"pele-edges\" fill=\"none\" stroke=\"$color\" stroke-width=\"1.5\">$edges</g>$body</g>"
      edges = ""
      body = ""
    }
  }

  // The root is drawn last, on top of the links that leave it.
  for (k in 1..n) {
    val i = k % n
    val node = nodes[i]
    val shape = shapes[i]!!
    val label = labels[i]!!
    val cx = x[i]
    val top = y[i]
    minX = minOf(minX, cx - w[i] / 2)
    maxX = maxOf(maxX, cx + w[i] / 2)
    minY = minOf(minY, top)
    maxY = maxOf(maxY, top + h[i])

    if (i == 0 || parent[i] == 0) flush(nodes[k - 1].section)
    if (i > 0 && down) {
      if (parent[i] == 0) color = seriesColor(node.section ?: 0)
      val p = parent[i]
      // The link drops from under its parent and turns in to the node's left. It starts where
      // the link to the sibling above turned in, so each stretch of a shared trunk has one color.
      // Under the root's rounded end, the trunk starts where that end has come down to the bottom.
      val stem = if (p == 0) minOf(maxOf(STEM, h[0] / 2), INDENT - BEND) else STEM
      val x1 = x[p] - w[p] / 2 + minOf(stem, w[p] / 2)
      val y1 = trunk[p].jsOr { y[p] + h[p] }
      val left = cx - w[i] / 2
      val x2 = left + (if (shape != "") shapeInset(shape, w[i], h[i], 3) else 0.0)
      val y2 = top + anchor[i]
      val bend = maxOf(0.0, minOf(BEND, minOf(x2 - x1, y2 - y1)))
      // The next link carries on from where this one leaves the trunk, so the trunk has no gap.
      trunk[p] = y2 - bend
      edges += "<path class=\"pele-edge\" d=\"M${num(x1)},${num(y1)}V${num(y2 - bend)}Q${num(x1)},${num(y2)} ${num(x1 + bend)},${num(y2)}H${num(x2)}\"/>"
    } else if (i > 0) {
      if (parent[i] == 0) color = seriesColor(node.section ?: 0)
      val p = parent[i]
      val dir = side[i]
      val from = if (dir > 0) 1 else 3
      val x1 = x[p] + dir * (w[p] / 2 - (if (shapes[p] != "") shapeInset(shapes[p]!!, w[p], h[p], from) else 0.0))
      val y1 = y[p] + anchor[p]
      val near = cx - (dir * w[i]) / 2
      val x2 = near + (if (shape != "") dir * shapeInset(shape, w[i], h[i], 4 - from) else 0.0)
      val y2 = top + anchor[i]
      val mid = num((x1 + x2) / 2)
      edges += "<path class=\"pele-edge\" d=\"M${num(x1)},${num(y1)}C$mid,${num(y1)} $mid,${num(y2)} ${num(x2)},${num(y2)}\"/>"
    }

    val pill = shape == "" && node.children.size > 0
    // Every node off the root is filled with a tint of its branch's color and outlined in it,
    // whether its shape was given in the source or is the pill of a node that branches.
    // The root is neutral.
    val tint = " fill=\"$color\" fill-opacity=\"${jsStr(TINT)}\" stroke=\"$color\""
    var inner = ""
    if (shape != "") {
      inner = if (i > 0) drawShape(shape, w[i], h[i], tint, " stroke=\"$color\"") else drawShape(shape, w[i], h[i], " fill=\"var(--_s)\" stroke=\"var(--_b)\"", " stroke=\"var(--_b)\"")
    } else if (pill) {
      inner = "<rect x=\"${num(-w[i] / 2)}\" y=\"${num(-h[i] / 2)}\" width=\"${num(w[i])}\" height=\"${num(h[i])}\" rx=\"${num(h[i] / 2)}\"$tint/>"
    }
    var shift = 0.0
    val icon = node.icon
    if (!icon.isNullOrEmpty() && drawn[i] != 0) {
      shift = (glyph + (if (label.width > 0) ICON_GAP else 0.0)) / 2
      inner += iconSvg(icon, -shift - label.width / 2, dy[i] - glyph / 2, glyph, icons)
    }
    val group =
      "<g class=\"pele-node pele-shape-${if (shape != "") shape else (if (pill) "branch" else "text")}${if (i > 0) "" else " pele-root"}${classNames(node.`class` ?: "")}\" data-id=\"${escText(
        node.nodeId,
      )}\" transform=\"translate(${num(cx)},${num(top + h[i] / 2)})\">" +
      inner +
      (if (down && shape == "") leftLabel(label, shift - label.width / 2, dy[i], icons) else labelSvg(label, shift, dy[i], " class=\"pele-label\"", icons)) +
      "</g>"
    if (i > 0) body += group else out += group
  }

  val title = titleLabel(model.title, measurer, size, titleRoom(options))
  val titleHeight = if (title.height > 0) title.height + 16 else 0.0
  val width = maxOf(maxX - minX, title.width)
  val totalWidth = ceil(width + 2 * pad)
  val totalHeight = ceil(maxY - minY + titleHeight + 2 * pad)
  val content =
    labelSvg(title, totalWidth / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g transform=\"translate(${num(pad - minX + (width - maxX + minX) / 2)},${num(pad + titleHeight - minY)})\">$out</g>"

  return Rendered(
    svgDocument("mindmap", totalWidth, totalHeight, size, options, Acc(), content),
    totalWidth,
    totalHeight,
    ArrayList(),
  )
}
