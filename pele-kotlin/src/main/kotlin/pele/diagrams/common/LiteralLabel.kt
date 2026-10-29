// Ported from Pele src/diagrams/common/literal-label.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import kotlin.math.ceil
import pele.js.JsRegExp
import pele.js.jsRound
import pele.js.jsSplit
import pele.js.jsTrimEnd
import pele.js.toCodePointStrings
import pele.svg.esc
import pele.svg.labelSvg
import pele.svg.num
import pele.svg.sharedStyle
import pele.svg.spanStyle
import pele.svg.textStyle
import pele.text.Label
import pele.text.Span
import pele.text.TextMeasurer
import pele.text.withHeights

private val SPACE = JsRegExp("""\s""")

// Literal labels, including filenames and protocol fields, must retain every character but the
// spaces a line breaks at. Long words can break between code points; measurement stays bounded
// by one line of text.
fun literalLabel(
  text: String,
  measurer: TextMeasurer,
  size: Double,
  room: Double,
  style: Int = 0,
): Label {
  val lines = ArrayList<String>()
  for (raw in text.jsSplit("\n")) {
    val chars = raw.toCodePointStrings()
    if (chars.isEmpty()) lines.add("")
    var from = 0
    while (from < chars.size) {
      val fits = { length: Int ->
        measurer.width(
          chars.subList(from, minOf(chars.size, from + length)).joinToString(""),
          size,
          style,
        ) <= room
      }
      var hi = 1
      while (hi < chars.size - from && fits(hi)) hi = minOf(chars.size - from, hi * 2)
      var lo = 1
      while (lo < hi) {
        val mid = ceil((lo + hi) / 2.0).toInt()
        if (fits(mid)) lo = mid else hi = mid - 1
      }
      var take = lo
      val wraps = from + take < chars.size
      if (wraps && !SPACE.test(chars[from + take])) {
        for (i in take - 1 downTo 1) {
          if (SPACE.test(chars[from + i])) {
            take = i + 1
            break
          }
        }
      }
      val line = chars.subList(from, minOf(chars.size, from + take)).joinToString("")
      lines.add(if (wraps) line.jsTrimEnd().ifEmpty { line } else line)
      from += take
      while (wraps && from < chars.size && SPACE.test(chars[from])) from++
    }
  }
  if (text == "") lines.clear()
  val widths = lines.map { line -> measurer.width(line, size, style) }
  var width = 0.0
  for (w in widths) width = maxOf(width, w)
  val lineHeight = jsRound(size * 1.5)
  return Label(
    lines =
      lines.mapIndexed { i, line ->
        mutableListOf(Span(text = line, style = style, width = widths[i]))
      },
    widths = widths,
    width = width,
    height = lines.size * lineHeight,
    size = size,
    lineHeight = lineHeight,
  )
}

fun breakLabel(label: Label, measurer: TextMeasurer, room: Double): Label {
  if (label.width <= room) return label
  val lines = ArrayList<MutableList<Span>>()
  val widths = ArrayList<Double>()
  for (i in label.lines.indices) {
    if (label.widths[i] <= room) {
      lines.add(label.lines[i])
      widths.add(label.widths[i])
      continue
    }
    var current = ArrayList<Span>()
    var width = 0.0
    val commit = {
      lines.add(current)
      widths.add(width)
      current = ArrayList()
      width = 0.0
    }
    for (span in label.lines[i]) {
      if (span.icon != null || span.math != null) {
        if (current.isNotEmpty() && width + span.width > room) commit()
        current.add(span)
        width += span.width
        continue
      }
      for (char in span.text.toCodePointStrings()) {
        var last: Span? = current.lastOrNull()
        val joined =
          last != null && last.icon == null && last.math == null && last.style == span.style
        val nextWidth =
          measurer.width(if (joined) last!!.text + char else char, label.size, span.style)
        var added = nextWidth - (if (joined) last!!.width else 0.0)
        if (current.isNotEmpty() && width + added > room) {
          commit()
          last = null
          added = measurer.width(char, label.size, span.style)
        }
        if (last != null && last.icon == null && last.math == null && last.style == span.style) {
          last.text += char
          last.width += added
        } else {
          current.add(Span(text = char, style = span.style, width = added))
        }
        width += added
      }
    }
    if (current.isNotEmpty() || label.lines[i].isEmpty()) commit()
  }
  var width = 0.0
  for (w in widths) width = maxOf(width, w)
  val broken =
    label.copy(
      lines = lines,
      widths = widths,
      width = width,
      height = lines.size * label.lineHeight,
    )
  return if (label.heights != null) withHeights(broken) else broken
}

// Writes a label with every line starting at x. A line is anchored at its start and keeps its
// spaces, so that where it begins does not depend on how its text was measured.
fun leftLabelSvg(label: Label, x: Double, top: Double, attrs: String): String {
  val shared = sharedStyle(label)
  val open = "<text" + textStyle(attrs, shared)
  val body = StringBuilder()
  for (i in label.lines.indices) {
    val spans = label.lines[i]
    val cy = top + (i + 0.5) * label.lineHeight
    if (spans.any { span -> span.icon != null }) {
      val width = label.widths[i]
      body.append(
        labelSvg(
          label.copy(
            lines = listOf(spans),
            widths = listOf(width),
            width = width,
            height = label.lineHeight,
          ),
          x + width / 2,
          cy,
          attrs,
        )
      )
      continue
    }
    val text = StringBuilder()
    for (span in spans) {
      val own = spanStyle(span.style and shared.inv())
      text.append(if (own.isNotEmpty()) "<tspan$own>${esc(span.text)}</tspan>" else esc(span.text))
    }
    if (text.isNotEmpty()) {
      body.append(
        "$open x=\"${num(x)}\" y=\"${num(cy + label.size * 0.35)}\" text-anchor=\"start\" xml:space=\"preserve\">$text</text>"
      )
    }
  }
  return body.toString()
}
