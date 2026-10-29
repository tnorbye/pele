// Ported from Pele src/diagrams/journey/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.journey

import kotlin.math.ceil
import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.fitLabel
import pele.diagrams.common.plainLabel
import pele.diagrams.common.turnToFit
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsTrim
import pele.svg.ROUNDED
import pele.svg.escText
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.seriesColor
import pele.svg.svgDocument
import pele.text.Label
import pele.text.Style
import pele.text.defaultMeasurer

private const val PAD_X = 10.0
private const val PAD_Y = 8.0
private const val MIN_TEXT = 124.0
private const val MAX_TEXT = 200.0
private const val GAP = 12.0
private const val BAND_PAD = 6.0
private const val GUTTER = 24.0
private const val LEVEL = 26.0
private const val FACE = 11.0
private const val DOT = 5.0
private const val DOT_STEP = 13.0
private const val LEGEND_GAP = 20.0

private const val EYES =
  "<circle cx=\"-3.6\" cy=\"-2.8\" r=\"1.3\"/><circle cx=\"3.6\" cy=\"-2.8\" r=\"1.3\"/>"
private val MOUTHS =
  mapOf(
    "happy" to "M-5,2A6,6 0 0 0 5,2",
    "neutral" to "M-4.5,4.5H4.5",
    "sad" to "M-5,6.5A6,6 0 0 1 5,6.5",
  )

private fun face(x: Double, y: Double, mood: String): String =
  "<g class=\"pele-face pele-face-$mood\" transform=\"translate(${num(x)},${num(y)})\">" +
    "<circle r=\"${jsStr(FACE)}\" fill=\"var(--_bg)\" stroke=\"var(--_l)\"/>" +
    "<g fill=\"var(--_l)\">$EYES</g>" +
    "<path d=\"${MOUTHS[mood]}\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\"/>" +
    "</g>"

fun renderJourney(model: JourneyModel, _config: Config, options: RenderOptions): Rendered =
  turnToFit(options) { down -> draw(model, _config, options, down) }

private class LegendItem(
  val name: String,
  val label: Label,
  val x: Double,
  val y: Double,
  val h: Double,
)

private class Run(val start: Int, val count: Int, val label: Label?)

private fun draw(
  model: JourneyModel,
  _config: Config,
  options: RenderOptions,
  down: Boolean,
): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val tiny = jsRound(size * 0.75)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  if (down) return verticalJourney(model, options)
  val tasks = model.tasks
  val n = tasks.size

  // `Task: 5:` names an actor with no name; Mermaid gives it a dot and an empty legend row.
  val names = journeyActors(tasks).filter { name -> name != "" }
  val actor = LinkedHashMap<String, Int>()
  names.forEachIndexed { i, name -> actor[name] = i }

  // Cards widen, up to a point, for a word that would not fit; past that the word is cut.
  var textW = MIN_TEXT
  var labels = tasks.map { task -> plainLabel(task.task, measurer, small, textW) }
  var widest = 0.0
  for (label in labels) widest = maxOf(widest, label.width)
  if (widest > textW) {
    textW = minOf(ceil(widest), MAX_TEXT)
    val fixed = textW
    labels = tasks.map { task -> fitLabel(task.task, measurer, small, fixed) }
  }

  val cardW = textW + 2 * PAD_X
  val pitch = cardW + GAP
  val plotW = if (n > 0) n * pitch - GAP else 0.0

  val lineH = jsRound(small * 1.5)
  val limit = maxOf(plotW, 240.0)
  val legend = ArrayList<LegendItem>()
  var legendW = 0.0
  var legendH = 0.0
  var lx = 0.0
  for (name in names) {
    val label = fitLabel(name, measurer, small, limit - 2 * DOT - 6)
    val w = 2 * DOT + 6 + label.width
    if (lx > 0 && lx + w > limit) {
      lx = 0.0
      legendH += 4
    }
    val y = if (lx > 0) legend[legend.size - 1].y else legendH
    val h = maxOf(label.height, lineH)
    legend.add(LegendItem(name, label, lx, y, h))
    lx += w
    legendW = maxOf(legendW, lx)
    legendH = maxOf(legendH, y + h)
    lx += LEGEND_GAP
  }

  val bodyW = if (n > 0) GUTTER + plotW else 0.0
  val title = fitLabel(model.title, measurer, size, maxOf(bodyW, 320.0), Style.Bold)
  val titleH = if (title.height > 0) title.height + 16 else 0.0
  val width = maxOf(maxOf(bodyW, title.width), if (n > 0) GUTTER + legendW else 0.0)
  val left = pad + (width - bodyW) / 2 + GUTTER
  val top = pad + titleH

  // A band covers a run of tasks that share a section, as in Mermaid; a section with no tasks draws
  // nothing.
  val runs = ArrayList<Run>()
  var bandH = 0.0
  var i = 0
  while (i < n) {
    var end = i + 1
    while (end < n && tasks[end].section == tasks[i].section) end++
    var label: Label? = null
    if (tasks[i].section != "") {
      label =
        fitLabel(tasks[i].section, measurer, small, (end - i) * pitch - GAP - 2 * PAD_X, Style.Bold)
      bandH = maxOf(bandH, maxOf(label.height, lineH) + 2 * BAND_PAD)
    }
    runs.add(Run(i, end - i, label))
    i = end
  }

  var textH = lineH
  for (label in labels) textH = maxOf(textH, label.height)
  val dotsH = if (names.isNotEmpty()) 2 * DOT + 6 else 0.0
  val cardH = textH + dotsH + 2 * PAD_Y
  val cardsY = top + (if (bandH > 0) bandH + BAND_PAD else 0.0)
  val chartTop = cardsY + cardH + FACE + 16
  val chartBottom = chartTop + 4 * LEVEL
  val right = left + plotW

  // PORT: JVM strings copy on concatenation; builders keep SVG assembly linear.
  val body = StringBuilder()
  var bottom = top
  if (n > 0) {
    val grid = StringBuilder()
    val scale = StringBuilder()
    for (score in 5 downTo 1) {
      val y = chartTop + (5 - score) * LEVEL
      grid.append("M${num(left - 6)},${num(y)}H${num(right)}")
      scale.append("<text x=\"${num(left - 12)}\" y=\"${num(y + tiny * 0.35)}\">$score</text>")
    }
    body.append(
      "<path class=\"pele-grid\" d=\"$grid\" fill=\"none\" stroke=\"var(--_a)\"/>" +
        "<g class=\"pele-scale\" font-size=\"${jsStr(tiny)}\" text-anchor=\"end\" fill=\"var(--_m)\">$scale</g>"
    )

    val line = StringBuilder()
    val stems = StringBuilder()
    val faces = StringBuilder()
    val cards = ArrayList<String>()
    tasks.forEachIndexed { index, task ->
      val x = left + index * pitch
      val cx = x + cardW / 2
      val score = task.score
      val level = if (score.isNaN()) 3.0 else minOf(5.0, maxOf(1.0, score))
      val y = chartTop + (5 - level) * LEVEL
      line.append("${if (index == 0) "M" else "L"}${num(cx)},${num(y)}")
      stems.append("M${num(cx)},${num(cardsY + cardH)}V${num(y - FACE)}")
      faces.append(face(cx, y, if (score > 3) "happy" else if (score < 3) "sad" else "neutral"))

      val dots = StringBuilder()
      val people = task.people.filter { name -> name != "" }
      val step = minOf(DOT_STEP, (cardW - 2 * PAD_X - 2 * DOT) / maxOf(people.size - 1, 1))
      people.forEachIndexed { k, name ->
        dots.append(
          "<circle class=\"pele-actor\" cx=\"${num(cx + (k - (people.size - 1) / 2.0) * step)}\" cy=\"${num(cardsY + cardH - PAD_Y - DOT)}\" r=\"${jsStr(DOT)}\" fill=\"${seriesColor(actor[name] ?: 0)}\" stroke=\"var(--_s)\">" +
            "<title>${escText(name)}</title></circle>"
        )
      }
      cards.add(
        "<g class=\"pele-node pele-task\" data-id=\"${escText(task.task.jsTrim())}\">" +
          "<rect x=\"${num(x)}\" y=\"${num(cardsY)}\" width=\"${num(cardW)}\" height=\"${num(cardH)}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"$ROUNDED/>" +
          labelSvg(
            labels[index],
            cx,
            cardsY + PAD_Y + textH / 2,
            " font-size=\"${jsStr(small)}\"",
          ) +
          dots +
          "</g>"
      )
    }
    body.append(
      "<path class=\"pele-stems\" d=\"$stems\" fill=\"none\" stroke=\"var(--_b)\" stroke-dasharray=\"2 3\"/>" +
        (if (n > 1)
          "<path class=\"pele-edge\" d=\"$line\" fill=\"none\" stroke=\"var(--_l)\" stroke-linejoin=\"round\"/>"
        else "") +
        faces
    )

    for (run in runs) {
      val members =
        cards.subList(run.start, minOf(run.start + run.count, cards.size)).joinToString("")
      if (run.label == null) {
        body.append(members)
        continue
      }
      val x = left + run.start * pitch
      val w = run.count * pitch - GAP
      body.append(
        "<g class=\"pele-cluster pele-section\" data-id=\"${escText(tasks[run.start].section.jsTrim())}\">" +
          "<rect x=\"${num(x)}\" y=\"${num(top)}\" width=\"${num(w)}\" height=\"${num(bandH)}\" fill=\"var(--_a)\"$ROUNDED/>" +
          labelSvg(
            run.label,
            x + w / 2,
            top + bandH / 2,
            " font-size=\"${jsStr(small)}\" font-weight=\"var(--_hw)\"",
          ) +
          members +
          "</g>"
      )
    }
    bottom = chartBottom + FACE

    if (legend.size > 0) {
      val x0 = left + (plotW - legendW) / 2
      val y0 = bottom + 16
      val items = StringBuilder()
      for (item in legend) {
        val cy = y0 + item.y + item.h / 2
        items.append(
          "<g class=\"pele-legend-item\" data-id=\"${escText(item.name)}\">" +
            "<circle cx=\"${num(x0 + item.x + DOT)}\" cy=\"${num(cy)}\" r=\"${jsStr(DOT)}\" fill=\"${seriesColor(actor[item.name] ?: 0)}\"/>" +
            labelSvg(item.label, x0 + item.x + 2 * DOT + 6 + item.label.width / 2, cy, "") +
            "</g>"
        )
      }
      body.append("<g class=\"pele-legend\" font-size=\"${jsStr(small)}\">$items</g>")
      bottom = y0 + legendH
    }
  }

  val totalWidth = ceil(width + 2 * pad)
  val totalHeight = ceil(maxOf(bottom, pad) + pad)
  val content =
    labelSvg(
      title,
      pad + width / 2,
      pad + title.height / 2,
      " class=\"pele-title\" font-weight=\"var(--_tw)\"",
    ) + body
  return Rendered(
    svgDocument("journey", totalWidth, totalHeight, size, options, model, content),
    totalWidth,
    totalHeight,
    ArrayList(),
  )
}

private fun verticalJourney(model: JourneyModel, options: RenderOptions): Rendered {
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val pad = options.padding ?: 8.0
  val room = maxOf(140.0, (options.maxWidth ?: 320.0) - 2 * pad)
  val title = fitLabel(model.title, measurer, size, room, Style.Bold)
  val names = journeyActors(model.tasks).filter { name -> name != "" }
  val actors = LinkedHashMap<String, Int>()
  names.forEachIndexed { i, name -> actors[name] = i }
  var y = pad + (if (title.height != 0.0) title.height + 16 else 0.0)
  var section: String? = null
  val body = StringBuilder()
  var width = title.width
  model.tasks.forEachIndexed { index, task ->
    if (task.section != section) {
      section = task.section
      val head = fitLabel(task.section, measurer, size, room - 16, Style.Bold)
      if (head.height != 0.0) {
        body.append(
          labelSvg(
            head,
            pad + 8 + head.width / 2,
            y + head.height / 2,
            " class=\"pele-cluster-label\" font-weight=\"var(--_hw)\"",
          )
        )
        width = maxOf(width, head.width + 16)
        y += head.height + 8
      }
    }
    val label = fitLabel(task.task, measurer, small, room - 72)
    val people =
      task.people
        .filter { name -> name != "" }
        .map { name -> Pair(name, fitLabel(name, measurer, small, room - 44)) }
    val textH = maxOf(label.height, 42.0)
    var h = 2 * PAD_Y + textH
    for (person in people) h += maxOf(16.0, person.second.height) + 4
    val w = maxOf(room, label.width + 72)
    body.append(
      "<g class=\"pele-node pele-journey-step\" data-id=\"$index\"><rect x=\"${num(pad)}\" y=\"${num(y)}\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"var(--_s)\" stroke=\"var(--_b)\"$ROUNDED/>"
    )
    body.append(
      labelSvg(
        label,
        pad + PAD_X + label.width / 2,
        y + PAD_Y + label.height / 2,
        " class=\"pele-label\"",
      )
    )
    val cx = pad + w - 28
    body.append(
      face(
        cx,
        y + PAD_Y + FACE,
        if (task.score > 3) "happy" else if (task.score < 3) "sad" else "neutral",
      )
    )
    if (!task.score.isNaN())
      body.append(
        "<text class=\"pele-journey-score\" x=\"${num(cx)}\" y=\"${num(y + PAD_Y + 2 * FACE + small)}\" font-size=\"${jsStr(small)}\" text-anchor=\"middle\">${num(minOf(5.0, maxOf(1.0, task.score)))}</text>"
      )
    var py = y + PAD_Y + textH + 4
    for (person in people) {
      val ph = maxOf(16.0, person.second.height)
      body.append(
        "<circle class=\"pele-actor\" cx=\"${num(pad + 16)}\" cy=\"${num(py + ph / 2)}\" r=\"${jsStr(DOT)}\" fill=\"${seriesColor(actors[person.first]!!)}\"/>" +
          labelSvg(
            person.second,
            pad + 28 + person.second.width / 2,
            py + ph / 2,
            " class=\"pele-journey-actor\" font-size=\"${jsStr(small)}\"",
          )
      )
      py += ph + 4
    }
    body.append("</g>")
    y += h
    if (index + 1 < model.tasks.size)
      body.append(
        "<path class=\"pele-edge\" d=\"M${num(pad + w / 2)},${num(y)}v16\" fill=\"none\" stroke=\"var(--_l)\"/>"
      )
    y += 16
    width = maxOf(width, w)
  }
  val totalWidth = ceil(width + 2 * pad)
  val height = ceil(maxOf(pad + title.height, y - (if (model.tasks.isNotEmpty()) 16 else 0)) + pad)
  val content =
    labelSvg(
      title,
      totalWidth / 2,
      pad + title.height / 2,
      " class=\"pele-title\" font-weight=\"var(--_tw)\"",
    ) + body
  return Rendered(
    svgDocument("journey", totalWidth, height, size, options, model, content),
    totalWidth,
    height,
    ArrayList(),
  )
}
