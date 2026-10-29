// Ported from Pele src/diagrams/git/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.git

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.plainLabel
import pele.diagrams.common.turnToFit
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.ROUNDED
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.ceil

private const val DOT = 7.0
private const val STEP = 44.0
private const val LANE = 44.0

// Shorter than a step, so a curve between lanes never runs through a commit on a lane it crosses.
private const val CURVE = 36.0
private const val GAP = 8.0
private const val LABEL_GAP = 20.0
private const val TAIL = 20.0
private const val NOTCH = 7.0
private const val TAG_GAP = 6.0
private const val HALF = 0.7071067811865476
private val CLASSES = listOf("normal", "reverse", "highlight", "merge", "cherry-pick")

private class View(
  val commit: GitCommit,
  val index: Int,
  val lane: Int,
  var m: Double,
  val label: Label?,
  val tags: List<Label>,
)

private fun flag(config: Config, key: String, fallback: Boolean): Boolean {
  val value = (config["gitGraph"] as? Map<*, *>)?.get(key)
  return if (value is Boolean) value else fallback
}

private fun tagWidth(tag: Label): Double = NOTCH + 4 + tag.width + 6

private fun symbol(type: Int, x: Double, y: Double, color: String): String {
  val at = "cx=\"${num(x)}\" cy=\"${num(y)}\""
  if (type == commitType.HIGHLIGHT) {
    return "<rect x=\"${num(x - DOT - 1)}\" y=\"${num(y - DOT - 1)}\" width=\"${jsStr(2 * DOT + 2)}\" height=\"${jsStr(2 * DOT + 2)}\" rx=\"2\" fill=\"$color\"/>"
  }
  if (type == commitType.MERGE) {
    return "<circle $at r=\"${jsStr(DOT - 1)}\" fill=\"var(--_bg)\" stroke=\"$color\" stroke-width=\"2\"/><circle $at r=\"2.5\" fill=\"$color\"/>"
  }
  val dot = "<circle $at r=\"${jsStr(DOT)}\" fill=\"$color\"/>"
  if (type == commitType.REVERSE) {
    return dot +
      "<path d=\"M${num(x - 3)},${num(y - 3)}l6,6M${num(x - 3)},${num(y + 3)}l6,-6\" fill=\"none\" stroke=\"var(--_bg)\" stroke-width=\"1.5\" stroke-linecap=\"round\"/>"
  }
  if (type == commitType.CHERRY_PICK) {
    return dot +
      "<path d=\"M${num(x - 2.6)},${num(y + 1)}L${num(x + 0.8)},${num(y - 4.2)}L${num(x + 2.6)},${num(y + 1)}\" fill=\"none\" stroke=\"var(--_bg)\" stroke-linejoin=\"round\"/>" +
      "<circle cx=\"${num(x - 2.6)}\" cy=\"${num(y + 2.2)}\" r=\"1.8\" fill=\"var(--_bg)\"/>" +
      "<circle cx=\"${num(x + 2.6)}\" cy=\"${num(y + 2.2)}\" r=\"1.8\" fill=\"var(--_bg)\"/>"
  }
  return dot
}

fun renderGit(model: GitModel, config: Config, options: RenderOptions): Rendered {
  if (model.direction != "LR") return draw(model, config, options, model.direction)
  return turnToFit(options) { down -> draw(model, config, options, if (down) "TB" else "LR") }
}

private fun draw(model: GitModel, config: Config, options: RenderOptions, direction: GitDirection): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.75)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0

  val showBranches = flag(config, "showBranches", true)
  val showCommitLabel = flag(config, "showCommitLabel", true)
  val rotate = flag(config, "rotateCommitLabel", true)
  val parallel = flag(config, "parallelCommits", false)
  val vertical = direction != "LR"
  val flip = direction == "BT"

  val laneOf = LinkedHashMap<String, Int>()
  model.lanes.forEachIndexed { index, name -> laneOf[name] = index }
  val laneCount = model.lanes.size
  val medium = jsRound(size * 0.875)
  // Mermaid writes branch names, tags, and commit ids as plain text, so tags in them show as written.
  val names = model.lanes.map { name -> plainLabel(if (showBranches) name else "", measurer, medium, 4000.0) }

  val views = ArrayList<View>()
  val byId = LinkedHashMap<String, View>()
  for (commit in model.commits.values.sortedBy { it.seq }) {
    val labelled =
      showCommitLabel &&
        commit.type != commitType.CHERRY_PICK &&
        (commit.type != commitType.MERGE || commit.customId == true)
    val tags = ArrayList<Label>()
    for (tag in commit.tags) {
      val label = plainLabel(tag, measurer, small, 4000.0)
      if (label.lines.size > 0) tags.add(label)
    }
    val view = View(
      commit = commit,
      index = views.size,
      lane = laneOf[commit.branch] ?: 0,
      m = 0.0,
      label = if (labelled) plainLabel(commit.id, measurer, small, 4000.0) else null,
      tags = tags,
    )
    views.add(view)
    byId[commit.id] = view
  }

  // Room each commit needs: along the main axis on either side, and across it towards the
  // previous lane (`lo`) and the next one (`hi`). Tags sit on one side of a lane and ids on the other.
  val half = DoubleArray(views.size)
  val tagHalf = DoubleArray(views.size)
  val lo = DoubleArray(laneCount).also { it.fill(DOT) }
  val hi = DoubleArray(laneCount).also { it.fill(DOT) }
  views.forEachIndexed { k, view ->
    val label = view.label
    val tags = view.tags
    var tagsMain = 0.0
    var tagsCross = 0.0
    for (tag in tags) {
      val w = tagWidth(tag)
      if (vertical) {
        tagsMain = maxOf(tagsMain, tag.height)
        tagsCross += w + 4
      } else {
        tagsMain = maxOf(tagsMain, w)
        tagsCross += tag.height + 3
      }
    }
    var labelMain = 0.0
    var labelCross = 0.0
    if (label != null && label.lines.size > 0) {
      if (rotate) {
        labelCross = 3 + HALF * (label.width + label.height)
      } else {
        labelMain = if (vertical) label.height else label.width
        labelCross = 6 + (if (vertical) label.width else label.height)
      }
    }
    half[k] = maxOf(DOT, tagsMain / 2, labelMain / 2)
    tagHalf[k] = tagsMain / 2
    val tagSide = DOT + (if (tags.size > 0) TAG_GAP + tagsCross else 0.0)
    val labelSide = DOT + labelCross
    val lane = view.lane
    lo[lane] = maxOf(lo[lane], if (vertical) labelSide else tagSide)
    hi[lane] = maxOf(hi[lane], if (vertical) tagSide else labelSide)
  }

  // In a left-to-right graph the tags stand over their commit. A connector that comes down from
  // a lane above joins the commit's lane this far before the commit, clear of the tags, and the
  // commit is placed far enough along to leave room for that.
  val lead = DoubleArray(views.size)
  if (!vertical) {
    views.forEachIndexed { k, view ->
      if (tagHalf[k] == 0.0) return@forEachIndexed
      for (id in view.commit.parents) {
        val parent = byId[id]
        if (parent != null && parent.lane < view.lane) lead[k] = tagHalf[k] + 4
      }
    }
  }
  val space = { p: Int, k: Int ->
    maxOf(STEP, half[p] + half[k] + GAP, if (lead[k] > 0) half[p] + CURVE + lead[k] else 0.0)
  }

  views.forEachIndexed { k, view ->
    if (!parallel) {
      view.m = if (k == 0) 0.0 else views[k - 1].m + space(k - 1, k)
      return@forEachIndexed
    }
    // Mermaid's `parallelCommits`: a commit sits one step after its furthest parent, so branches
    // that grow side by side share positions instead of taking turns.
    var m = 0.0
    for (id in view.commit.parents) {
      val parent = byId[id]
      if (parent == null || parent.index >= k) continue
      m = maxOf(m, parent.m + space(parent.index, k))
    }
    view.m = m
  }
  var end = 0.0
  for (view in views) end = maxOf(end, view.m)

  val cross = DoubleArray(laneCount)
  for (i in 0 until laneCount) {
    val name = names[i]
    val reach = if (name.lines.size == 0) 0.0 else if (vertical) name.width / 2 + 8 else name.height / 2 + 2
    lo[i] = maxOf(lo[i], reach)
    hi[i] = maxOf(hi[i], reach)
    if (i > 0) cross[i] = cross[i - 1] + maxOf(LANE, hi[i - 1] + lo[i] + GAP)
  }

  val px = { m: Double, c: Double -> if (vertical) c else m }
  val py = { m: Double, c: Double -> if (vertical) (if (flip) -m else m) else c }
  val point = { m: Double, c: Double -> "${num(px(m, c))},${num(py(m, c))}" }

  var minX = Double.POSITIVE_INFINITY
  var minY = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  var maxY = Double.NEGATIVE_INFINITY
  val grow = { x0: Double, y0: Double, x1: Double, y1: Double ->
    if (x0 < minX) minX = x0
    if (y0 < minY) minY = y0
    if (x1 > maxX) maxX = x1
    if (y1 > maxY) maxY = y1
  }

  var branches = ""
  if (showBranches) {
    for (i in 0 until laneCount) {
      val name = names[i]
      val color = seriesColor(i)
      val c = cross[i]
      val w = name.width + 16
      val h = name.height + 4
      val from = if (name.lines.size == 0) -LABEL_GAP else -LABEL_GAP - (if (vertical) h else w)
      val cx = px(from + (if (vertical) h else w) / 2, c)
      val cy = py(from + (if (vertical) h else w) / 2, c)
      grow(px(-LABEL_GAP, c), py(-LABEL_GAP, c), px(-LABEL_GAP, c), py(-LABEL_GAP, c))
      grow(px(end + TAIL, c), py(end + TAIL, c), px(end + TAIL, c), py(end + TAIL, c))
      branches +=
        "<g class=\"pele-branch\" data-id=\"${escText(model.lanes[i])}\">" +
        "<path class=\"pele-lane\" d=\"M${point(-LABEL_GAP, c)}L${point(end + TAIL, c)}\" stroke=\"$color\" stroke-dasharray=\"2 4\"/>"
      if (name.lines.size > 0) {
        grow(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        branches +=
          "<rect x=\"${num(cx - w / 2)}\" y=\"${num(cy - h / 2)}\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"$color\"$ROUNDED/>" +
          labelSvg(name, cx, cy, " class=\"pele-label\" fill=\"var(--_bg)\"")
      }
      branches += "</g>"
    }
  }

  // Positions of the commits on each lane, to tell whether a connector may run along it.
  val taken = Array(laneCount) { ArrayList<Double>() }
  for (view in views) taken[view.lane].add(view.m)
  if (parallel) {
    for (list in taken) {
      list.sortWith { a, b ->
        val diff = a - b
        if (diff.isNaN()) 0 else if (diff < 0) -1 else if (diff > 0) 1 else 0
      }
    }
  }
  val busy = { lane: Int, from: Double, to: Double ->
    val list = taken[lane]
    var low = 0
    var high = list.size
    while (low < high) {
      val mid = (low + high) shr 1
      if (list[mid] <= from) low = mid + 1 else high = mid
    }
    low < list.size && list[low] < to
  }

  var curves = ""
  var lines = ""
  for (view in views) {
    val commit = view.commit
    val c2 = cross[view.lane]
    val m2 = view.m
    for (j in commit.parents.indices) {
      val parent = byId[commit.parents[j]]
      if (parent == null || parent === view) continue
      val c1 = cross[parent.lane]
      val m1 = parent.m
      if (parent.lane == view.lane) {
        lines += "<path class=\"pele-edge\" d=\"M${point(m1, c1)}L${point(m2, c2)}\" stroke=\"${seriesColor(view.lane)}\"/>"
        continue
      }
      val merged = j > 0 && commit.type == commitType.MERGE
      val picked = j > 0 && !merged
      var d = "M${point(m1, c1)}"
      if (m2 <= m1) {
        val mid = (m1 + m2) / 2
        d += "C${point(mid, c1)} ${point(mid, c2)} ${point(m2, c2)}"
      } else if (!merged && !busy(view.lane, m1, m2)) {
        // Leaves the parent at once and runs along the child's lane.
        val s = minOf(CURVE, m2 - m1)
        d += "C${point(m1 + s / 2, c1)} ${point(m1 + s / 2, c2)} ${point(m1 + s, c2)}L${point(m2, c2)}"
      } else {
        // Turns in just before the child, or before its tags when it comes from their side.
        val to = if (parent.lane < view.lane && m2 - lead[view.index] > m1) m2 - lead[view.index] else m2
        var s = minOf(CURVE, to - m1)
        var along = c1
        if (!merged || busy(parent.lane, m1, m2)) {
          // Neither lane is free to run along. Between two lanes, past the ids of one and before
          // the tags of the next, a strip stays empty, and the connector takes that.
          along = if (c1 > c2) c2 + hi[view.lane] + GAP / 2 else c2 - lo[view.lane] - GAP / 2
          s = minOf(CURVE, (to - m1) / 2)
          d += "C${point(m1 + s / 2, c1)} ${point(m1 + s / 2, along)} ${point(m1 + s, along)}"
        }
        d += "L${point(to - s, along)}C${point(to - s / 2, along)} ${point(to - s / 2, c2)} ${point(to, c2)}"
        if (to < m2) d += "L${point(m2, c2)}"
      }
      curves +=
        "<path class=\"pele-edge${if (merged) " pele-edge-merge" else if (picked) " pele-edge-cherry-pick" else ""}\" d=\"$d\" " +
        "stroke=\"${seriesColor(if (merged) parent.lane else view.lane)}\"${if (picked) " stroke-dasharray=\"4 3\"" else ""}/>"
    }
  }

  var commits = ""
  for (view in views) {
    val commit = view.commit
    val label = view.label
    val tags = view.tags
    val x = px(view.m, cross[view.lane])
    val y = py(view.m, cross[view.lane])
    val type = commit.customType ?: commit.type
    grow(x - DOT - 1, y - DOT - 1, x + DOT + 1, y + DOT + 1)
    // Merges and cherry-picks carry a message Mermaid makes up; only a written one is worth showing.
    val written = commit.type < commitType.MERGE && commit.message != ""
    var body = if (written) "<title>${escText(commit.message)}</title>" else ""
    body += symbol(type, x, y, seriesColor(view.lane))

    if (label != null && label.lines.size > 0) {
      val w = label.width
      val h = label.height
      val back = { cx: Double, cy: Double ->
        "<rect x=\"${num(cx - w / 2 - 2)}\" y=\"${num(cy - h / 2 + 2)}\" width=\"${num(w + 4)}\" height=\"${num(h - 4)}\" rx=\"3\" fill=\"var(--_bg)\"/>"
      }
      if (rotate) {
        // The text ends beside the commit and trails back the way the history came.
        val k = (HALF * h) / 2
        val ax = if (vertical) x - DOT - 3 - k else x + 3
        val ay = if (vertical) y else y + DOT + 3 + k
        val reach = HALF * w + k
        grow(ax - reach, if (flip) ay - reach else ay - k, ax + k, if (flip) ay + k else ay + reach)
        body +=
          "<g class=\"pele-commit-label\" transform=\"rotate(${if (flip) 45 else -45} ${num(ax)} ${num(ay)})\">" +
          back(ax - w / 2, ay) +
          labelSvg(label, ax - w / 2, ay, "") +
          "</g>"
      } else {
        val cx = if (vertical) x - DOT - 6 - w / 2 else x
        val cy = if (vertical) y else y + DOT + 6 + h / 2
        grow(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        body += "<g class=\"pele-commit-label\">${back(cx, cy)}${labelSvg(label, cx, cy, "")}</g>"
      }
    }

    // Beside the lane the tags form a row; above it they stack, the first one furthest out.
    var offset = DOT + TAG_GAP
    var t = if (vertical) 0 else tags.size - 1
    while (t >= 0 && t < tags.size) {
      val tag = tags[t]
      val w = tagWidth(tag)
      val h = tag.height
      val left = if (vertical) x + offset else x - w / 2
      val cy = if (vertical) y else y - offset - h / 2
      offset += if (vertical) w + 4 else h + 3
      grow(left, cy - h / 2, left + w, cy + h / 2)
      body +=
        "<g class=\"pele-tag\">" +
        "<path d=\"M${num(left)},${num(cy)}L${num(left + NOTCH)},${num(cy - h / 2)}H${num(left + w)}V${num(cy + h / 2)}H${num(left + NOTCH)}Z\" fill=\"var(--_s)\" stroke=\"var(--_b)\" stroke-linejoin=\"round\"/>" +
        "<circle cx=\"${num(left + NOTCH)}\" cy=\"${num(cy)}\" r=\"1.5\" fill=\"var(--_b)\"/>" +
        labelSvg(tag, left + NOTCH + 4 + tag.width / 2, cy, " fill=\"var(--_fg)\"") +
        "</g>"
      t += if (vertical) 1 else -1
    }

    val merge = if (commit.type == commitType.MERGE && type != commitType.MERGE) " pele-commit-merge" else ""
    commits += "<g class=\"pele-commit pele-commit-${CLASSES.getOrNull(type) ?: CLASSES[0]}$merge\" data-id=\"${escText(commit.id)}\">$body</g>"
  }

  if (minX > maxX) grow(0.0, 0.0, 0.0, 0.0)
  val title = layoutLabel(model.title, false, measurer, size, 4000.0, Style.Bold)
  val titleHeight = if (title.height > 0) title.height + 16 else 0.0
  val inner = maxOf(maxX - minX, title.width)
  val width = ceil(inner + 2 * pad)
  val height = ceil(maxY - minY + titleHeight + 2 * pad)
  val dx = pad - minX + (inner - (maxX - minX)) / 2
  val dy = pad + titleHeight - minY

  val content =
    labelSvg(title, width / 2, pad + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"") +
      "<g transform=\"translate(${num(dx)},${num(dy)})\">" +
      (if (branches != "") "<g class=\"pele-branches\" fill=\"none\" font-size=\"${jsStr(medium)}\">$branches</g>" else "") +
      (if (curves != "" || lines != "") "<g class=\"pele-edges\" fill=\"none\" stroke-width=\"2\" stroke-linecap=\"round\">$curves$lines</g>" else "") +
      (if (commits != "") "<g class=\"pele-commits\" font-size=\"${jsStr(small)}\" fill=\"var(--_m)\">$commits</g>" else "") +
      "</g>"

  return Rendered(
    svg = svgDocument("gitGraph", width, height, size, options, model, content),
    width = width,
    height = height,
    links = ArrayList(),
  )
}
