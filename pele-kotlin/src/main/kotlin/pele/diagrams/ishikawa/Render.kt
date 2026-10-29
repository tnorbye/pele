// Ported from Pele src/diagrams/ishikawa/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.ishikawa

import kotlin.math.ceil
import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.breakLabel
import pele.diagrams.common.titleRoom
import pele.diagrams.common.turnToFit
import pele.js.jsHypot
import pele.js.jsRound
import pele.js.jsStr
import pele.js.truthy
import pele.svg.ARROW
import pele.svg.Acc
import pele.svg.ROUNDED
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.num
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.defaultMeasurer
import pele.text.layoutLabel

// How far a bone moves toward the tail for each unit it rises from the spine.
private const val SLANT = 0.5
private val LENGTH = jsHypot(SLANT, 1.0)
private const val FIRST_ROW = 14.0
private const val ROW_GAP = 6.0
private const val BONE_END = 8.0
private const val TWIG_STEP = 22.0
private const val STUB = 18.0
private const val TEXT_GAP = 5.0
private const val PAIR_GAP = 26.0
private const val HEAD_GAP = 34.0
private const val TAIL = 28.0
private const val BOX_PAD_X = 10.0
private const val BOX_PAD_Y = 5.0

// A cause below a category. Bones at even depths run level, from the slanted bone above them
// toward the tail; bones at odd depths slant away from the spine, from the level bone above them.
private class Bone(
  val label: Label,
  val depth: Int,
  val parent: Int,
  // Its place among its siblings, counted from the spine outward.
  val slot: Int,
  val kids: Int,
  // How far its row is from the spine.
  var rise: Double,
  // Where it meets its parent, measured level from the category's bone: 0 on it, negative toward
  // the tail.
  var at: Double,
  // Where its text ends.
  var textEnd: Double,
)

private class Category(
  val node: IshikawaNode,
  val label: Label,
  val boxW: Double,
  val boxH: Double,
  val bones: List<Bone>,
  // The length of its bone, as distance from the spine.
  var rise: Double,
  // How far its causes reach toward the tail, measured level from its bone.
  val reach: Double,
)

private class Frame(
  val node: IshikawaNode,
  val depth: Int,
  val parent: Int,
  val slot: Int,
  // Set when the bone was made and only its row is still to come.
  var bone: Int,
)

// Gives every cause of a category a row of its own, so that no two texts can meet, and measures
// how far the whole of it reaches. A level bone takes the row nearest the spine and its twigs the
// rows beyond; a slanted twig takes the row beyond everything that hangs off it. The outline may
// be nested as deep as its indentation goes, so the walk keeps a stack of its own.
private fun arrange(
  node: IshikawaNode,
  up: Boolean,
  measurer: TextMeasurer,
  size: Double,
  small: Double,
): Category {
  val label = layoutLabel(node.text, false, measurer, size, 180.0)
  val lineHeight = jsRound(small * 1.5)
  val bones = ArrayList<Bone>()
  val rows = ArrayList<Int>()
  val stack = ArrayList<Frame>()
  // Above the spine the first cause goes farthest out, so that the causes read from the top down.
  val push = { children: List<IshikawaNode>, depth: Int, parent: Int ->
    val n = children.size
    for (slot in n - 1 downTo 0) {
      stack.add(Frame(children[if (up) n - 1 - slot else slot], depth, parent, slot, -1))
    }
  }
  push(node.children, 2, -1)
  while (stack.size > 0) {
    val frame = stack.removeAt(stack.size - 1)
    if (frame.bone != -1) {
      rows.add(frame.bone)
      continue
    }
    val index = bones.size
    bones.add(
      Bone(
        label = layoutLabel(frame.node.text, false, measurer, small, 200.0),
        depth = frame.depth,
        parent = frame.parent,
        slot = frame.slot,
        kids = frame.node.children.size,
        rise = 0.0,
        at = 0.0,
        textEnd = 0.0,
      )
    )
    if (frame.depth % 2 == 0) {
      rows.add(index)
    } else {
      frame.bone = index
      stack.add(frame)
    }
    push(frame.node.children, frame.depth + 1, index)
  }

  var rise = FIRST_ROW
  for (index in rows) {
    val height = maxOf(bones[index].label.height, lineHeight) + ROW_GAP
    bones[index].rise = rise + height / 2
    rise += height
  }
  val boxW = label.width + 2 * BOX_PAD_X
  var reach = boxW / 2
  for (bone in bones) {
    val parent = if (bone.parent == -1) null else bones[bone.parent]
    if (bone.depth % 2 == 0) {
      bone.at = if (parent != null) parent.at else 0.0
      bone.textEnd = bone.at - (if (bone.kids > 0) (bone.kids + 1) * TWIG_STEP else STUB) - TEXT_GAP
    } else {
      // Twigs stand along their level bone, the one nearest the spine nearest the tail.
      bone.at = parent!!.at - (parent.kids - bone.slot) * TWIG_STEP
      bone.textEnd = bone.at - TEXT_GAP
    }
    reach = maxOf(reach, bone.label.width - bone.textEnd)
  }
  return Category(node, label, boxW, label.height + 2 * BOX_PAD_Y, bones, rise + BONE_END, reach)
}

fun renderIshikawa(model: IshikawaModel, _config: Config, options: RenderOptions): Rendered =
  turnToFit(options) { down -> draw(model, _config, options, down) }

private fun draw(
  model: IshikawaModel,
  _config: Config,
  options: RenderOptions,
  down: Boolean,
): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  if (down) return verticalCauses(model, options)
  val root = model.root
  val title = layoutLabel(model.title, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0

  var body = ""
  var minX = 0.0
  var maxX = 0.0
  var minY = 0.0
  var maxY = 0.0
  if (root != null) {
    val head = layoutLabel(root.text, false, measurer, size, 200.0, Style.Bold)
    val headH = maxOf(head.height + 24, 48.0)
    val headW = head.width + 24 + headH / 2
    val categories =
      root.children.mapIndexed { i, node -> arrange(node, i % 2 == 0, measurer, size, small) }
    // Even a category without causes has a bone long enough to carry its name clear of the head.
    for (category in categories) category.rise = maxOf(category.rise, headH / 2 + 14)

    // Categories meet the spine in pairs, one above and one below, each pair as far toward the
    // tail from the one before as the wider of the two needs.
    val xs = ArrayList<Double>()
    var x = -HEAD_GAP
    var i = 0
    while (i < categories.size) {
      if (i > 0) {
        var room = 0.0
        for (side in 0 until 2) {
          val before = categories.getOrNull(i - 2 + side)
          val next = categories.getOrNull(i + side)
          if (before != null)
            room = maxOf(room, before.reach + (if (next != null) next.boxW / 2 else 0.0))
        }
        x -= room + PAIR_GAP
      }
      xs.add(x)
      xs.add(x)
      i += 2
    }
    val tail = if (categories.size > 0) x - TAIL else -HEAD_GAP

    maxX = headW
    minX = tail
    minY = -headH / 2
    maxY = headH / 2
    var groups = ""
    categories.forEachIndexed { index, category ->
      val sign = if (index % 2 == 0) -1 else 1
      val ax = xs[index]
      val px = { at: Double, rise: Double -> ax + at - rise * SLANT }
      val endX = px(0.0, category.rise)
      val endY = sign * category.rise
      // The bone stops short of the spine to leave its arrowhead a clean point.
      val trim = ARROW - 1
      var d =
        "M${num(ax - (SLANT / LENGTH) * trim)},${num((sign * trim) / LENGTH)}L${num(endX)},${num(endY)}"
      var texts = ""
      for (bone in category.bones) {
        val y = sign * bone.rise
        if (bone.depth % 2 == 0) {
          d +=
            "M${num(px(bone.at, bone.rise))},${num(y)}H${num(px(bone.textEnd + TEXT_GAP, bone.rise))}"
        } else {
          val from = category.bones[bone.parent].rise
          d +=
            "M${num(px(bone.at, from))},${num(sign * from)}L${num(px(bone.at, bone.rise))},${num(y)}"
        }
        val right = px(bone.textEnd, bone.rise)
        texts +=
          labelSvg(
            bone.label,
            right - bone.label.width / 2,
            y,
            if (bone.depth > 2) " fill=\"var(--_m)\"" else "",
          )
        minX = minOf(minX, right - bone.label.width)
      }
      val boxY = endY + sign * (category.boxH / 2)
      minX = minOf(minX, endX - category.boxW / 2)
      maxX = maxOf(maxX, endX + category.boxW / 2)
      minY = minOf(minY, boxY - category.boxH / 2)
      maxY = maxOf(maxY, boxY + category.boxH / 2)
      groups +=
        "<g class=\"pele-ishikawa-category\" data-id=\"${escText(category.node.text)}\">" +
          "<path class=\"pele-ishikawa-bones\" d=\"$d\" fill=\"none\" stroke=\"var(--_l)\"/>" +
          marker("arrow_point", ax, 0.0, SLANT / LENGTH, -sign / LENGTH, "var(--_l)") +
          "<rect x=\"${num(endX - category.boxW / 2)}\" y=\"${num(boxY - category.boxH / 2)}\" width=\"${num(category.boxW)}\" height=\"${num(
          category.boxH
        )}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"$ROUNDED/>" +
          labelSvg(category.label, endX, boxY, " class=\"pele-label\"") +
          (if (texts != "")
            "<g class=\"pele-ishikawa-causes\" font-size=\"${jsStr(small)}\">$texts</g>"
          else "") +
          "</g>"
    }

    val r = headH / 2
    body =
      "<path class=\"pele-ishikawa-spine\" d=\"M${num(tail)},0H${num(-(ARROW - 1))}\" fill=\"none\" stroke=\"var(--_l)\"/>" +
        marker("arrow_point", 0.0, 0.0, 1.0, 0.0, "var(--_l)") +
        groups +
        "<g class=\"pele-ishikawa-head\" data-id=\"${escText(root.text)}\">" +
        "<path d=\"M0,${num(-r)}H${num(headW - r)}A${num(r)},${num(r)} 0 0 1 ${num(headW - r)},${num(r)}H0Z\" fill=\"var(--_s)\" stroke=\"var(--_b)\" stroke-linejoin=\"round\"/>" +
        labelSvg(
          head,
          12 + head.width / 2,
          0.0,
          " class=\"pele-label\" font-weight=\"var(--_hw)\"",
        ) +
        "</g>"
  }

  val inner = maxOf(maxX - minX, title.width)
  val width = ceil(inner + 2 * pad)
  val height = ceil(maxY - minY + titleHeight + 2 * pad)
  val ox = pad + (inner - (maxX - minX)) / 2 - minX
  val oy = pad + titleHeight - minY
  val svg =
    svgDocument(
      "ishikawa",
      width,
      height,
      size,
      options,
      Acc(),
      labelSvg(
        title,
        width / 2,
        pad + title.height / 2,
        " class=\"pele-title\" font-weight=\"var(--_tw)\"",
      ) + (if (body != "") "<g transform=\"translate(${num(ox)},${num(oy)})\">$body</g>" else ""),
    )
  return Rendered(svg, width, height, ArrayList())
}

private class Pending(val node: IshikawaNode, val depth: Int, val parent: Int)

private class Entry(
  val node: IshikawaNode,
  val depth: Int,
  val parent: Int,
  var x: Double,
  var y: Double,
  var w: Double,
  var h: Double,
  var label: Label? = null,
)

private fun verticalCauses(model: IshikawaModel, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val room = maxOf(140.0, (options.maxWidth ?: 320.0) - 2 * pad)
  val title = layoutLabel(model.title, false, measurer, size, titleRoom(options), Style.Bold)
  val order = ArrayList<Entry>()
  val pending =
    if (model.root != null) mutableListOf(Pending(model.root!!, 0, -1)) else ArrayList<Pending>()
  var depth = 0
  while (pending.size > 0) {
    val entry = pending.removeAt(pending.size - 1)
    val index = order.size
    order.add(Entry(entry.node, entry.depth, entry.parent, 0.0, 0.0, 0.0, 0.0))
    depth = maxOf(depth, entry.depth)
    for (i in entry.node.children.size - 1 downTo 0) pending.add(
      Pending(entry.node.children[i], entry.depth + 1, index)
    )
  }
  val indent = minOf(20.0, maxOf(2.0, (room - 120) / maxOf(1.0, depth.toDouble())))
  var y = pad + (if (truthy(title.height)) title.height + 12 else 0.0)
  var width = title.width
  var nodes = ""
  var edges = ""
  for (entry in order) {
    entry.x = pad + entry.depth * indent
    val labelRoom = maxOf(60.0, room - entry.depth * indent - 20)
    val label =
      breakLabel(
        layoutLabel(
          entry.node.text,
          false,
          measurer,
          if (entry.depth < 2) size else small,
          labelRoom,
          if (entry.depth < 2) Style.Bold else 0,
        ),
        measurer,
        labelRoom,
      )
    entry.label = label
    entry.w = label.width + 20
    entry.h = label.height + 12
    entry.y = y
    y += entry.h + 12
    width = maxOf(width, entry.x - pad + entry.w)
    val framed = entry.depth < 2
    nodes +=
      "<g class=\"pele-node ${if (entry.depth == 0) "pele-ishikawa-head" else if (entry.depth == 1) "pele-ishikawa-category" else "pele-ishikawa-cause"}\" data-id=\"${escText(entry.node.text)}\">" +
        (if (framed)
          "<rect x=\"${num(entry.x)}\" y=\"${num(entry.y)}\" width=\"${num(entry.w)}\" height=\"${num(entry.h)}\" rx=\"${if (entry.depth == 0) 12 else 4}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"/>"
        else "") +
        labelSvg(
          label,
          entry.x + entry.w / 2,
          entry.y + entry.h / 2,
          " class=\"pele-label\"${if (entry.depth < 2) " font-weight=\"var(--_hw)\"" else ""}${if (entry.depth > 1) " font-size=\"${jsStr(small)}\"" else ""}",
        ) +
        "</g>"
    if (entry.parent >= 0) {
      val parent = order[entry.parent]
      val gx = parent.x + indent / 2
      edges +=
        "<path class=\"pele-edge\" d=\"M${num(gx)},${num(parent.y + parent.h)}V${num(entry.y + entry.h / 2)}H${num(entry.x)}\" fill=\"none\" stroke=\"var(--_b)\"/>"
    }
  }
  val totalWidth = ceil(width + 2 * pad)
  val height = ceil(maxOf(pad + title.height, y - 12) + pad)
  val content =
    labelSvg(
      title,
      totalWidth / 2,
      pad + title.height / 2,
      " class=\"pele-title\" font-weight=\"var(--_tw)\"",
    ) + edges + nodes
  return Rendered(
    svgDocument("ishikawa", totalWidth, height, size, options, Acc(), content),
    totalWidth,
    height,
    ArrayList(),
  )
}
