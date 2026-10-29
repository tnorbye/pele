// Ported from Pele src/text/label.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.text

import kotlin.math.ceil
import pele.MathMarkup
import pele.js.JsRegExp
import pele.js.fromCharCode
import pele.js.jsMatchGlobal
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsSplitStrings
import pele.js.jsTrim
import pele.js.jsTrimEnd
import pele.js.jsTrimStart

data class Span(
  var text: String,
  var style: Int,
  val icon: String? = null,
  val math: MathMarkup? = null,
  var width: Double,
)

data class Label(
  val lines: List<MutableList<Span>>,
  val widths: List<Double>,
  val width: Double,
  val height: Double,
  val size: Double,
  val lineHeight: Double,
  // Present only when math needs extra line height.
  var heights: List<Double>? = null,
)

const val LINE_HEIGHT = 1.5

// Lines of one wrapped label sit closer, so the label reads as one block.
private const val WRAPPED_LINE_HEIGHT = 1.3

private val RE_BREAK = JsRegExp("""<br\s*\/?>|\\n|\n""", "gi")
private val RE_TAG = JsRegExp("""<\/?([a-zA-Z][\w-]*)(?:\s[^<>]*)?\/?>""", "y")
private val RE_ICON = JsRegExp("""(fa[bklrs]?):fa-([\w-]+)""", "g")
private val RE_MARKUP = JsRegExp("""[<\\\n*_]|fa[bklrs]?:fa-""")
private val TAG_STYLES =
  mapOf(
    "b" to Style.Bold,
    "strong" to Style.Bold,
    "i" to Style.Italic,
    "em" to Style.Italic,
    "cite" to Style.Italic,
    "dfn" to Style.Italic,
    "var" to Style.Italic,
    "code" to Style.Mono,
    "kbd" to Style.Mono,
    "samp" to Style.Mono,
    "tt" to Style.Mono,
    "sub" to Style.Sub,
    "sup" to Style.Sup,
    "u" to Style.Underline,
    "ins" to Style.Underline,
  )
private const val SHIFT = Style.Sub or Style.Sup
private val HTML_TAGS: Set<String> =
  ("a abbr address article aside audio b bdi bdo big blockquote body br button caption center cite code col " +
      "colgroup data dd del details dfn dialog div dl dt em embed fieldset figcaption figure font footer form h1 h2 " +
      "h3 h4 h5 h6 head header hr html i iframe img input ins kbd label legend li link main map mark meta meter nav " +
      "noscript object ol optgroup option output p picture pre progress q rp rt ruby s samp script section select " +
      "small source span strike strong style sub summary sup table tbody td template textarea tfoot th thead time " +
      "title tr track tt u ul var video wbr")
    .split(' ')
    .toSet()

// Break between any two Chinese or Japanese characters. Hangul is left out: Korean puts spaces
// between words.
private const val CJK = "\\u2e80-\\u9fff\\uf900-\\ufaff\\uff00-\\uff60"
private const val CLOSE = "、。，．：；！？）］｝」』】〕〉》〙〗ー…‥・ゝゞぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ,.:;!?)\\]}"
private const val OPEN = "（［｛「『【〔〈《〘〖([{"
private val RE_CJK = JsRegExp("[$CJK]")

// Break hints: a zero-width space, and a soft hyphen, which shows as '-' where a line breaks.
private const val HINT = "\\u200b\\u00ad"
private val RE_HINT = JsRegExp("[$HINT]")
private val RE_PIECE =
  JsRegExp(
    "\\s*(?:[$OPEN]*[$CJK][$CLOSE]*|[^\\s$CJK$HINT]+[$HINT]*[$CLOSE]*|[$HINT]+)\\s*|\\s+",
    "g",
  )

// Unique placeholders protect TeX from markup parsing and keep expressions aligned when tags are
// removed.
private const val MARK = 0xe000
private const val MAX_MARKS = 0x1900
private val RE_MATH = JsRegExp("""\$\$(.+?)\$\$""", "g")
private val RE_MARK = JsRegExp("[\\ue000-\\uf8ff]")
private val RE_MARKS = JsRegExp("([\\ue000-\\uf8ff])")

private class Marks(
  val tex: MutableList<String> = ArrayList(),
  val math: MutableList<MathMarkup?> = ArrayList(),
)

// PORT: per-thread state, as renders may run concurrently.
private val marks = ThreadLocal<Marks?>()

private val EMPTY = Label(emptyList(), emptyList(), 0.0, 0.0, 0.0, 0.0)

private fun dedent(text: String): String {
  val lines = text.jsSplit("\n")
  var min = Int.MAX_VALUE
  for (line in lines) {
    if (line.jsTrim() == "") continue
    val indent = line.length - line.jsTrimStart().length
    if (indent < min) min = indent
  }
  if (min == Int.MAX_VALUE || min == 0) return text
  return lines.joinToString("\n") { line ->
    line.jsSlice(minOf(min, line.length - line.jsTrimStart().length))
  }
}

private fun plain(span: Span?): Boolean = span != null && span.icon == null && span.math == null

private fun pushText(line: MutableList<Span>, text: String, style: Int) {
  if (text == "") return
  val found = marks.get()
  if (found != null && RE_MARK.test(text)) {
    val parts = text.jsSplit(RE_MARKS)
    for (i in parts.indices) {
      val part = parts[i] ?: ""
      if (i % 2 == 0) {
        if (part != "") pushPlain(line, part, style)
        continue
      }
      val at = part[0].code - MARK
      val math = found.math.getOrNull(at)
      if (math != null) {
        line.add(Span(text = "", style = style, math = math, width = math.width))
      } else {
        pushPlain(line, "$$${found.tex.getOrNull(at)}$$", style)
      }
    }
    return
  }
  pushPlain(line, text, style)
}

private fun pushPlain(line: MutableList<Span>, text: String, style: Int) {
  val decoded = decodeEntities(text)
  val last = line.lastOrNull()
  if (plain(last) && last!!.style == style) {
    last.text += decoded
  } else {
    line.add(Span(text = decoded, style = style, width = 0.0))
  }
}

private fun pushIcons(line: MutableList<Span>, text: String, style: Int) {
  if (!text.contains(":fa-")) {
    pushText(line, text, style)
    return
  }
  var last = 0
  RE_ICON.lastIndex = 0
  while (true) {
    val m = RE_ICON.exec(text) ?: break
    val icon = "${m[1]}:fa-${m[2]}"
    pushText(line, text.jsSlice(last, m.index), style)
    last = m.index + m.value.length
    if (iconMarkup(icon) != "") {
      line.add(Span(text = "", style = style, icon = icon, width = 0.0))
    } else if (last < text.length && text[last] == ' ') {
      // An icon the host does not have leaves no gap, nor the space that set it off from the text.
      last++
    } else if (last == text.length) {
      val before = line.lastOrNull()
      if (plain(before)) before!!.text = before.text.jsTrimEnd()
    }
  }
  pushText(line, text.jsSlice(last), style)
}

private class Open(
  var style: Int,
  // Sub/sup stack; only the innermost tag shifts text.
  val shifts: MutableList<Int>,
)

private val RE_WORD = JsRegExp("""\w""")

// Splits inline HTML and, for markdown labels, emphasis markers into styled spans.
private fun inline(
  line: MutableList<Span>,
  source: String,
  markdown: Boolean,
  base: Int,
  open: Open,
) {
  var style = open.style
  var marked = 0
  val text = StringBuilder()
  var i = 0
  val n = source.length
  val flush = {
    pushIcons(line, text.toString(), style or (open.shifts.lastOrNull() ?: 0))
    text.setLength(0)
  }
  while (i < n) {
    val ch = source[i]
    if (ch == '<') {
      RE_TAG.lastIndex = i
      val m = RE_TAG.exec(source)
      if (m != null && m.index == i) {
        val tag = m[1]!!.lowercase()
        val closing = i + 1 < n && source[i + 1] == '/'
        val bit = TAG_STYLES[tag] ?: 0
        if ((bit and SHIFT) != 0) {
          flush()
          val at = open.shifts.lastIndexOf(bit)
          if (!closing) {
            open.shifts.add(bit)
          } else if (at != -1) {
            open.shifts.removeAt(at)
          }
        } else if (bit != 0) {
          flush()
          open.style =
            if (closing) (open.style and bit.inv()) or (base and bit) else open.style or bit
          style = if (closing) (style and bit.inv()) or (base and bit) else style or bit
        }
        // Other HTML tags are dropped and their text kept. Anything else shaped like a tag, such
        // as the type in List<T>, is text.
        if (bit != 0 || tag in HTML_TAGS) {
          i += m.value.length
          continue
        }
      }
    } else if (markdown && (ch == '*' || ch == '_')) {
      val double = i + 1 < n && source[i + 1] == ch
      val bit = if (double) Style.Bold else Style.Italic
      val len = if (double) 2 else 1
      val opening = (marked and bit) == 0
      val prev = if (i > 0) source[i - 1].toString() else " "
      val next = if (i + len < n) source[i + len].toString() else " "
      val canOpen =
        opening &&
          next.jsTrim() != "" &&
          source.indexOf(if (double) "$ch$ch" else "$ch", i + len) != -1
      val canClose = !opening && prev.jsTrim() != ""
      val wordy = ch == '_' && RE_WORD.test(prev) && RE_WORD.test(next)
      if ((canOpen || canClose) && !wordy) {
        flush()
        marked = if (canOpen) marked or bit else marked and bit.inv()
        style = if (canOpen) style or bit else (style and bit.inv()) or (base and bit)
        i += len
        continue
      }
    }
    text.append(ch)
    i++
  }
  flush()
}

private fun measureSpans(line: List<Span>, measurer: TextMeasurer, size: Double): Double {
  var total = 0.0
  for (span in line) {
    span.width =
      if (span.icon != null) size * 1.25
      else span.math?.width ?: measurer.width(span.text, size, span.style)
    total += span.width
  }
  return total
}

private fun wrap(
  line: List<Span>,
  measurer: TextMeasurer,
  size: Double,
  max: Double,
  out: MutableList<MutableList<Span>>,
  widths: MutableList<Double>,
) {
  var current = ArrayList<Span>()
  var width = 0.0
  val commit = { hyphen: Boolean ->
    val last = current.lastOrNull()
    if (plain(last)) {
      var trimmed = last!!.text.jsTrimEnd()
      if (hyphen && last.text.endsWith("­")) trimmed = trimmed.jsSlice(0, -1) + "-"
      if (trimmed != last.text) {
        last.text = trimmed
        val w = measurer.width(trimmed, size, last.style)
        width += w - last.width
        last.width = w
      }
    }
    out.add(current)
    widths.add(width)
    current = ArrayList()
    width = 0.0
  }
  for (span in line) {
    if (span.icon != null || span.math != null) {
      val w = span.math?.width ?: (size * 1.25)
      if (width > 0 && width + w > max) commit(false)
      current.add(span.copy(width = w))
      width += w
      continue
    }
    val pieces = span.text.jsMatchGlobal(RE_PIECE) ?: emptyList()
    // A piece ending in a soft hyphen fits if its '-' does, or if the rest of the word does, which
    // hides the hyphen. The line only grows along a word, so no later hyphen in it fits either,
    // and the remaining width of each word makes this one check.
    val soft = span.text.contains('­')
    val n = if (soft) pieces.size else 0
    val own = DoubleArray(n)
    val rest = DoubleArray(n + 1)
    for (q in n - 1 downTo 0) {
      // A soft hyphen before a space is no break of its own: the space is.
      val shy = pieces[q].endsWith("­")
      val end = pieces[q].jsTrimEnd()
      own[q] = measurer.width(if (shy) end.jsSlice(0, -1) else end, size, span.style)
      // Word width beyond this span is unknown.
      rest[q] =
        if (!shy) {
          own[q]
        } else if (q == n - 1) {
          if (span === line[line.size - 1]) own[q] else Double.POSITIVE_INFINITY
        } else {
          own[q] + rest[q + 1]
        }
    }
    for (p in pieces.indices) {
      var part = pieces[p]
      val partWidth = if (soft) own[p] else measurer.width(part.jsTrimEnd(), size, span.style)
      val fits =
        if (!soft || !part.endsWith("­")) {
          width + partWidth <= max
        } else {
          width + measurer.width(part.jsSlice(0, -1) + "-", size, span.style) <= max ||
            width + rest[p] <= max
        }
      if (width > 0 && !fits) {
        // Breaking at whitespace leaves a preceding soft hyphen hidden.
        commit(part.jsTrimStart() == part)
        part = part.jsTrimStart()
      }
      val last = current.lastOrNull()
      if (plain(last) && last!!.style == span.style) {
        last.text += part
        // Remeasuring for each zero-width piece would take quadratic time.
        if (partWidth > 0 || part.jsTrimEnd() != part) {
          val before = last.width
          last.width = measurer.width(last.text, size, last.style)
          width += last.width - before
        }
      } else {
        val w = measurer.width(part, size, span.style)
        current.add(Span(text = part, style = span.style, width = w))
        width += w
      }
    }
  }
  if (current.isNotEmpty() || out.isEmpty()) commit(false)
}

fun layoutLabel(
  raw: String?,
  markdown: Boolean,
  measurer: TextMeasurer,
  size: Double,
  maxWidth: Double,
  base: Int = 0,
): Label {
  if (raw == null || raw == "") return EMPTY
  val lineHeight = jsRound(size * LINE_HEIGHT)

  if (mathActive() && raw.contains("$$") && !RE_MARK.test(raw)) {
    val found = Marks()
    val marked =
      raw.jsReplace(RE_MATH) { m ->
        if (found.tex.size == MAX_MARKS) {
          m.value
        } else {
          found.tex.add(m[1]!!)
          fromCharCode(MARK + found.tex.size - 1)
        }
      }
    if (found.tex.isNotEmpty()) {
      for (tex in found.tex) found.math.add(typeset(decodeEntities(tex), size))
      return layoutMarkup(marked, markdown, measurer, size, maxWidth, base, lineHeight, found)
    }
  }

  if (!RE_MARKUP.test(raw) && !raw.contains('&') && !raw.contains('ﬂ')) {
    val text = raw.jsTrim()
    val width = measurer.width(text, size, base)
    if (width <= maxWidth || !(text.contains(' ') || RE_CJK.test(text) || RE_HINT.test(text))) {
      return Label(
        lines = listOf(mutableListOf(Span(text = text, style = base, width = width))),
        widths = listOf(width),
        width = width,
        height = lineHeight,
        size = size,
        lineHeight = lineHeight,
      )
    }
    val lines = ArrayList<MutableList<Span>>()
    val widths = ArrayList<Double>()
    wrap(
      listOf(Span(text = text, style = base, width = width)),
      measurer,
      size,
      maxWidth,
      lines,
      widths,
    )
    var widest = 0.0
    for (w in widths) if (w > widest) widest = w
    val snug = if (lines.size > 1) jsRound(size * WRAPPED_LINE_HEIGHT) else lineHeight
    return Label(lines, widths, widest, lines.size * snug, size, snug)
  }
  return layoutMarkup(raw, markdown, measurer, size, maxWidth, base, lineHeight)
}

private fun layoutMarkup(
  raw: String,
  markdown: Boolean,
  measurer: TextMeasurer,
  size: Double,
  maxWidth: Double,
  base: Int,
  lineHeight: Double,
  found: Marks? = null,
): Label {
  val outer = marks.get()
  marks.set(found)
  try {
    val label = layoutLines(raw, markdown, measurer, size, maxWidth, base, lineHeight)
    return if (found != null) withHeights(label) else label
  } finally {
    marks.set(outer)
  }
}

private val RE_CR = JsRegExp("""\r\n?""", "g")

private fun layoutLines(
  raw: String,
  markdown: Boolean,
  measurer: TextMeasurer,
  size: Double,
  maxWidth: Double,
  base: Int,
  lineHeight: Double,
): Label {
  val source = if (markdown) dedent(raw.jsReplace(RE_CR, "\n")).jsTrim() else raw.jsTrim()
  val lines = ArrayList<MutableList<Span>>()
  val widths = ArrayList<Double>()
  var width = 0.0
  // HTML tags can span explicit breaks; Markdown emphasis stays local to each line.
  val open = Open(base, ArrayList())
  for (part in source.jsSplitStrings(RE_BREAK)) {
    val line = ArrayList<Span>()
    inline(line, part.jsTrim(), markdown, base, open)
    val w = measureSpans(line, measurer, size)
    if (w > maxWidth) {
      wrap(line, measurer, size, maxWidth, lines, widths)
    } else {
      lines.add(line)
      widths.add(w)
    }
  }
  for (w in widths) if (w > width) width = w
  val snug = if (lines.size > 1) jsRound(size * WRAPPED_LINE_HEIGHT) else lineHeight
  return Label(lines, widths, width, lines.size * snug, size, snug)
}

fun withHeights(label: Label): Label {
  var heights: MutableList<Double>? = null
  for (i in label.lines.indices) {
    var h = label.lineHeight
    for (span in label.lines[i]) {
      val math = span.math
      if (math != null) h = maxOf(h, ceil(math.height + label.size / 4))
    }
    if (h != label.lineHeight && heights == null)
      heights = label.lines.map { label.lineHeight }.toMutableList()
    if (heights != null) heights[i] = h
  }
  if (heights == null) {
    return if (label.heights == null) label
    else label.copy(heights = null, height = label.lines.size * label.lineHeight)
  }
  var height = 0.0
  for (h in heights) height += h
  return label.copy(heights = heights, height = height)
}

fun hasMath(line: List<Span>): Boolean {
  for (span in line) if (span.math != null) return true
  return false
}

fun lineMiddle(label: Label, i: Int): Double {
  val heights = label.heights ?: return (i + 0.5) * label.lineHeight
  var top = 0.0
  for (k in 0 until i) top += heights[k]
  return top + heights[i] / 2
}

fun labelLine(label: Label, i: Int): Label {
  val width = label.widths[i]
  val height = label.heights?.get(i) ?: label.lineHeight
  val line =
    Label(listOf(label.lines[i]), listOf(width), width, height, label.size, label.lineHeight)
  if (height != label.lineHeight) line.heights = listOf(height)
  return line
}
