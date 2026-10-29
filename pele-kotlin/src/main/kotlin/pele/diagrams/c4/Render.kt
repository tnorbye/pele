// Ported from Pele src/diagrams/c4/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import pele.Config
import pele.LinkInfo
import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.leftLabelSvg
import pele.diagrams.common.tighten
import pele.diagrams.common.titleRoom
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.ARROW
import pele.svg.EdgePath
import pele.svg.Fill
import pele.svg.ResolvedStyle
import pele.svg.casingsSvg
import pele.svg.drawShape
import pele.svg.edgeCasing
import pele.svg.esc
import pele.svg.escText
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num
import pele.svg.overFills
import pele.svg.paints
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.svgDocument
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import pele.util.linked
import pele.util.sanitizeUrl

private const val PAIR_SHIFT = 7.0
private const val NARROW_GAP = 36.0
private const val WIDEN = 24.0
private const val MAX_OFFSET = 2000.0

// A label may cover this much of a shape's edge, where there is no text.
private const val EDGE = 8.0

// Places along a relation to try for its label, nearest the middle first.
private val SLIDE = listOf(0.5, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8)

// Above this many box comparisons, labels stay at the middle and lines are not steered around
// shapes.
private const val CARE_BUDGET = 2000000.0

private val RE_FLIGHT = JsRegExp("ﬂ°°?", "g")
private val RE_PILCROW = JsRegExp("¶ß", "g")

private fun userStyle(fill: String?, stroke: String?, color: String?): ResolvedStyle {
  val declarations = ArrayList<String>()
  if (!fill.isNullOrEmpty()) declarations.add("fill:$fill")
  if (!stroke.isNullOrEmpty()) declarations.add("stroke:$stroke")
  if (!color.isNullOrEmpty()) declarations.add("color:$color")
  return resolveStyle(declarations)
}

private fun link(
  body: String,
  url: String?,
  id: String,
  links: MutableList<LinkInfo>,
  options: RenderOptions,
): String {
  if (url.isNullOrEmpty()) return body
  // Entity encoding has hidden any `#name;` in the address. Put it back, so that the check sees
  // what a browser would.
  return linked(
    body,
    sanitizeUrl(
      if (url.contains("¶ß")) url.jsReplace(RE_FLIGHT, "#").jsReplace(RE_PILCROW, ";") else url
    ),
    id,
    links,
    options,
  )
}

fun renderC4(db: C4Db, _config: Config, options: RenderOptions): Rendered {
  return tighten(options) { tight ->
    if (!tight) return@tighten draw(db, _config, options, false).first
    val first = draw(db, _config, options, true, NARROW_GAP)
    var best = first.first
    var squeezed = first.second
    var gap = NARROW_GAP + WIDEN
    while (squeezed > 0 && gap <= NARROW_GAP + 3 * WIDEN) {
      val (wider, left) = draw(db, _config, options, true, gap)
      if (left < squeezed) {
        best = wider
        squeezed = left
      }
      gap += WIDEN
    }
    best
  }
}

private fun draw(
  db: C4Db,
  _config: Config,
  options: RenderOptions,
  tight: Boolean,
  gap: Double = NARROW_GAP,
): Pair<Rendered, Int> {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.8125)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val links = ArrayList<LinkInfo>()
  val layout =
    layoutC4(
      db,
      measurer,
      size,
      small,
      if (tight) maxOf(80.0, (options.maxWidth ?: 320.0) - 2 * pad) else null,
      gap,
    )
  val order = layout.order
  val boxes = layout.boxes
  val root = order[0]

  var minX = 0.0
  var minY = 0.0
  var maxX = root.w
  var maxY = root.h
  fun grow(x0: Double, y0: Double, x1: Double, y1: Double) {
    minX = minOf(minX, x0)
    minY = minOf(minY, y0)
    maxX = maxOf(maxX, x1)
    maxY = maxOf(maxY, y1)
  }

  val ends = ArrayList<Box>()
  val pairs = IdentityHashMap<Box, MutableSet<Box>>()
  for (rel in db.rels) {
    val from = if (rel.fromAttr != null) null else db.getC4Shape(rel.from)
    val to = if (rel.toAttr != null) null else db.getC4Shape(rel.to)
    val a = if (from != null) boxes[from] else null
    val b = if (to != null) boxes[to] else null
    if (a == null || b == null) {
      throw PeleError(
        "C4 rel \"${rel.from}\" -> \"${rel.to}\" references an unknown shape or boundary",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "c4"),
      )
    }
    ends.add(a)
    ends.add(b)
    var targets = pairs[a]
    if (targets == null) {
      targets = LinkedHashSet()
      pairs[a] = targets
    }
    targets.add(b)
  }

  // What a relation's label should not cover: shapes, arrowheads, boundary headings, and the labels
  // placed before it.
  val nodes = ArrayList<Box>()
  val taken = ArrayList<Double>()
  for (group in order) {
    for (node in group.nodes) {
      nodes.add(node)
      taken.addAll(
        listOf(node.x + EDGE, node.y + EDGE, node.x + node.w - EDGE, node.y + node.h - EDGE)
      )
    }
  }
  val careful =
    db.rels.size.toDouble() * (nodes.size + order.size + 3.0 * db.rels.size) <= CARE_BUDGET
  fun covered(x0: Double, y0: Double, x1: Double, y1: Double): Double {
    var area = 0.0
    var i = 0
    while (i < taken.size) {
      val w = minOf(x1, taken[i + 2]) - maxOf(x0, taken[i])
      if (w <= 0) {
        i += 4
        continue
      }
      val h = minOf(y1, taken[i + 3]) - maxOf(y0, taken[i + 1])
      if (h > 0) area += w * h
      i += 4
    }
    return area
  }
  fun offset(value: Double?): Double =
    if (value != null && value != 0.0 && !value.isNaN())
      maxOf(-MAX_OFFSET, minOf(MAX_OFFSET, value))
    else 0.0

  // Every line is drawn before any label is placed, so that labels can keep off the arrowheads.
  var casings = ""
  var edgesOut = ""
  val trim = markerTrim("arrow_point")
  val fills = ArrayList<Fill>()
  for (group in order) {
    if (group === root) continue
    val shape = userStyle(group.boundary.bgColor, null, null).shape
    if (paints(shape))
      fills.add(Fill(doubleArrayOf(group.x, group.y, group.x + group.w, group.y + group.h), shape))
  }
  val lanes = Lanes()
  for (group in order) if (group !== root) lanes.frame(group)
  val routes =
    db.rels.mapIndexed { index, rel ->
      val a = ends[index * 2]
      val b = ends[index * 2 + 1]
      val style = userStyle(null, rel.lineColor, null)
      val startArrow = rel.type == "birel" || rel.type == "rel_b"
      val endArrow = rel.type != "rel_b"
      val reverse = a !== b && pairs[b]?.contains(a) == true
      val r =
        route(
          a,
          b,
          if (reverse) PAIR_SHIFT else 0.0,
          if (careful) nodes else null,
          lanes,
          if (startArrow) trim else 0.0,
          if (endArrow) trim else 0.0,
        )
      grow(minOf(r.x0, r.x1), minOf(r.y0, r.y1), maxOf(r.x0, r.x1), maxOf(r.y0, r.y1))
      if (startArrow && careful)
        taken.addAll(listOf(r.sx - ARROW, r.sy - ARROW, r.sx + ARROW, r.sy + ARROW))
      if (endArrow && careful)
        taken.addAll(listOf(r.ex - ARROW, r.ey - ARROW, r.ex + ARROW, r.ey + ARROW))
      val over = overFills(r.d, doubleArrayOf(r.sx, r.sy, r.ex, r.ey), fills, style.line)
      val color =
        if (!over.isNullOrEmpty()) over
        else if (!style.stroke.isNullOrEmpty()) esc(style.stroke) else "var(--_l)"
      if (over == "") {
        casings +=
          edgeCasing(
            EdgePath(r.d, null, r.sx, r.sy, r.sdx, r.sdy, r.ex, r.ey, r.edx, r.edy),
            if (startArrow) "arrow_point" else "none",
            if (endArrow) "arrow_point" else "none",
            style.line,
          )
      }
      edgesOut +=
        "<g class=\"pele-edge\" data-id=\"${escText(rel.from + "-" + rel.to)}\"${if (!over.isNullOrEmpty()) " stroke=\"$over\"" else ""}${style.line}><path d=\"${r.d}\"/>" +
          (if (startArrow) marker("arrow_point", r.sx, r.sy, r.sdx, r.sdy, color) else "") +
          (if (endArrow) marker("arrow_point", r.ex, r.ey, r.edx, r.edy, color) else "") +
          "</g>"
      r
    }

  // A boundary's heading goes at the left, the middle or the right of its top edge: the first of
  // them no line crosses.
  for (group in order) {
    if (!careful || group.headW == 0.0) continue
    val top = group.y + HEAD_TOP
    val bottom = group.y + group.head - LANE
    var fewest = Double.POSITIVE_INFINITY
    for (x in listOf(HEAD_LEFT, (group.w - group.headW) / 2, group.w - HEAD_LEFT - group.headW)) {
      var count = 0
      for (r in routes) if (
        crosses(r.points, group.x + x - 4, top, group.x + x + group.headW + 4, bottom)
      )
        count++
      if (count < fewest) {
        fewest = count.toDouble()
        group.headX = x
      }
      if (count == 0) break
    }
    taken.addAll(
      listOf(group.x + group.headX - 4, top, group.x + group.headX + group.headW + 4, bottom)
    )
  }

  var labelsOut = ""
  db.rels.forEachIndexed each@{ index, rel ->
    val r = routes[index]
    val id = rel.from + "-" + rel.to
    val style = userStyle(null, null, rel.textColor)
    val text = (if (db.c4Type == "C4Dynamic") "${index + 1}: " else "") + rel.label
    val label = layoutLabel(text, false, measurer, small, r.room)
    val relTechn = rel.techn
    val techn =
      layoutLabel(
        if (!relTechn.isNullOrEmpty()) "[$relTechn]" else null,
        false,
        measurer,
        small,
        r.room,
        Style.Italic,
      )
    val lw = maxOf(label.width, techn.width) + LABEL_PAD
    val lh = label.height + techn.height
    if (lh == 0.0) return@each

    // The label sits on the line, or just beside it when another relation runs the opposite way.
    val beside = abs(r.nx) * (lw / 2) + abs(r.ny) * (lh / 2) + 2
    val aside =
      if (r.lean != 0.0 && !r.lean.isNaN()) maxOf(0.0, minOf(r.lean, beside - 14)) else beside
    // Manual offsets are tuned to the original spacing. Reposition compact labels beside
    // their routed arrows so an offset cannot leave them floating outside a boundary.
    var dx = r.nx * aside + (if (tight) 0.0 else offset(rel.offsetX))
    var dy = r.ny * aside + (if (tight) 0.0 else offset(rel.offsetY))
    var at = 0.5
    if (careful) {
      // Slide along the line to the spot that covers least. Offsets from the diagram are kept
      // unless a spot
      // on the line itself covers less than any they lead to: they are often tuned for Mermaid's
      // spacing.
      var least = Double.POSITIVE_INFINITY
      val moved = dx != r.nx * aside || dy != r.ny * aside
      var pass = 0
      while (pass < (if (moved) 2 else 1) && least > 0) {
        val px = if (pass != 0) r.nx * aside else dx
        val py = if (pass != 0) r.ny * aside else dy
        for (t in SLIDE) {
          val cx = r.x0 + (r.x1 - r.x0) * t + px
          val cy = r.y0 + (r.y1 - r.y0) * t + py
          val area = covered(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2)
          if (area < least) {
            least = area
            at = t
            dx = px
            dy = py
          }
          if (area == 0.0) break
        }
        pass++
      }
    }
    val cx = r.x0 + (r.x1 - r.x0) * at + dx
    val top = r.y0 + (r.y1 - r.y0) * at + dy - lh / 2
    if (careful) taken.addAll(listOf(cx - lw / 2, top, cx + lw / 2, top + lh))
    grow(cx - lw / 2, top, cx + lw / 2, top + lh)
    labelsOut +=
      "<g class=\"pele-edge-label\" data-id=\"${escText(id)}\">" +
        (if (!rel.descr.isNullOrEmpty()) "<title>${escText(rel.descr!!)}</title>" else "") +
        "<rect x=\"${num(cx - lw / 2)}\" y=\"${num(top)}\" width=\"${num(lw)}\" height=\"${num(lh)}\" rx=\"3\" fill=\"var(--_bg)\"/>" +
        link(
          labelSvg(label, cx, top + label.height / 2, style.text) +
            labelSvg(
              techn,
              cx,
              top + label.height + techn.height / 2,
              " class=\"pele-c4-techn\" fill=\"var(--_m)\"${style.text}",
            ),
          rel.link,
          id,
          links,
          options,
        ) +
        "</g>"
  }

  var clusters = ""
  var nodesOut = ""
  for (group in order) {
    if (group !== root) {
      val boundary = group.boundary
      val style = userStyle(boundary.bgColor, boundary.borderColor, boundary.fontColor)
      val x = group.x + group.headX
      var y = group.y + HEAD_TOP
      val color = inkFill(style) + style.text
      val muted = inkFill(style, "var(--_m)") + style.text
      var text =
        leftLabelSvg(
          group.labels[0],
          x,
          y,
          " class=\"pele-cluster-label\" font-weight=\"var(--_hw)\"$color",
        )
      y += group.labels[0].height
      text +=
        leftLabelSvg(
          group.labels[1],
          x,
          y,
          " class=\"pele-c4-type\" font-size=\"${jsStr(small)}\"$muted",
        )
      y += group.labels[1].height
      text +=
        leftLabelSvg(
          group.labels[2],
          x,
          y,
          " class=\"pele-c4-descr\" font-size=\"${jsStr(small)}\"$muted",
        )
      clusters +=
        "<g class=\"pele-cluster pele-c4-${if (!boundary.nodeType.isNullOrEmpty()) "node" else "boundary"}\" data-id=\"${escText(boundary.alias)}\">" +
          "<rect x=\"${num(group.x)}\" y=\"${num(group.y)}\" width=\"${num(group.w)}\" height=\"${num(group.h)}\" fill=\"none\" stroke=\"var(--_b)\"${
          if (!boundary.nodeType.isNullOrEmpty()) "" else " stroke-dasharray=\"6 4\""
        }${rounded(style.shape)}/>" +
          link(text, boundary.link, boundary.alias, links, options) +
          "</g>"
    }
    for (node in group.nodes) {
      val shape = node.shape
      val external = shape.kind.startsWith("external")
      val style = userStyle(shape.bgColor, shape.borderColor, shape.fontColor)
      val stroke = " stroke=\"var(--_b)\"" + (if (external) " stroke-dasharray=\"4 3\"" else "")
      val w = node.w
      val h = node.h
      val form = node.form
      val labels = node.labels
      val dx = if (form == "h-cyl") -queueRx(h) / 2 else 0.0
      val dy = if (form == "person") HEAD / 2 else if (form == "cyl") cylRy(w) / 2 else 0.0
      val color = inkFill(style) + style.text
      val muted = inkFill(style, "var(--_m)") + style.text
      var y = dy - node.textH / 2
      var text =
        labelSvg(
          labels[0],
          dx,
          y + labels[0].height / 2,
          " class=\"pele-c4-type\" font-size=\"${jsStr(small - 1)}\"$muted",
        )
      y += labels[0].height
      text +=
        labelSvg(
          labels[1],
          dx,
          y + labels[1].height / 2,
          " class=\"pele-label\" font-weight=\"var(--_hw)\"$color",
        )
      y += labels[1].height
      text +=
        labelSvg(
          labels[2],
          dx,
          y + labels[2].height / 2,
          " class=\"pele-c4-techn\" font-size=\"${jsStr(small)}\"$muted",
        )
      y += labels[2].height + 4
      text +=
        labelSvg(
          labels[3],
          dx,
          y + labels[3].height / 2,
          " class=\"pele-c4-descr\" font-size=\"${jsStr(small)}\"$color",
        )
      nodesOut +=
        "<g class=\"pele-node pele-shape-$form pele-c4-${shape.kind}${if (external) " pele-c4-external" else ""}\" data-id=\"${escText(
          shape.alias
        )}\" transform=\"translate(${num(node.x + w / 2)},${num(node.y + h / 2)})\">" +
          link(
            drawShape(
              form,
              w,
              h,
              " fill=\"var(${if (external) "--_bg" else "--_s"})\"$stroke${style.shape}",
              stroke + style.line,
            ) + text,
            shape.link,
            shape.alias,
            links,
            options,
          ) +
          "</g>"
    }
  }

  val title =
    layoutLabel(
      db.title,
      false,
      measurer,
      size,
      if (tight) titleRoom(options) else 4000.0,
      Style.Bold,
    )
  val titleHeight = if (title.height > 0) title.height + 12 else 0.0
  val inner = maxOf(maxX - minX, title.width)
  val width = ceil(inner + 2 * pad)
  val height = ceil(maxY - minY + titleHeight + 2 * pad)
  val ox = pad - minX + (inner - (maxX - minX)) / 2
  val oy = pad + titleHeight - minY

  val svg =
    svgDocument(
      "c4",
      width,
      height,
      size,
      options,
      db,
      labelSvg(
        title,
        width / 2,
        pad + title.height / 2,
        " class=\"pele-title\" font-weight=\"var(--_tw)\"",
      ) +
        "<g transform=\"translate(${num(ox)},${num(oy)})\">" +
        (if (clusters != "") "<g class=\"pele-clusters\">$clusters</g>" else "") +
        casingsSvg(casings) +
        (if (edgesOut != "")
          "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>"
        else "") +
        (if (nodesOut != "") "<g class=\"pele-nodes\">$nodesOut</g>" else "") +
        (if (labelsOut != "")
          "<g class=\"pele-edge-labels\" font-size=\"${jsStr(small)}\">$labelsOut</g>"
        else "") +
        "</g>",
    )
  return Pair(Rendered(svg, width, height, links), lanes.squeezed)
}
