// Ported from Pele src/svg/builder.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import pele.IconResolver
import pele.MathMarkup
import pele.js.JsRegExp
import pele.js.jsRound
import pele.js.jsStr
import pele.js.jsTrim
import pele.text.Label
import pele.text.Style
import pele.text.decodeEntities

// PORT: loops in place of upstream's RE_NEEDS_ESC, RE_ESC and RE_INVALID_XML, which run on every
// text and attribute. Like those /u patterns they match only unpaired surrogates.
private fun isInvalidXmlUnit(text: String, i: Int): Boolean {
  val c = text[i]
  val code = c.code
  if (code <= 0x1f) return code != 0x09 && code != 0x0a && code != 0x0d
  if (code == 0x7f || code == 0xfffe || code == 0xffff) return true
  if (c.isHighSurrogate()) return i + 1 >= text.length || !text[i + 1].isLowSurrogate()
  if (c.isLowSurrogate()) return i == 0 || !text[i - 1].isHighSurrogate()
  return false
}

// Removing these characters in esc() can make validated values unsafe.
fun hasInvalidXml(text: String): Boolean {
  for (i in text.indices) if (isInvalidXmlUnit(text, i)) return true
  return false
}

fun stripInvalidXml(text: String): String {
  var sb: StringBuilder? = null
  var last = 0
  for (i in text.indices) {
    if (!isInvalidXmlUnit(text, i)) continue
    if (sb == null) sb = StringBuilder(text.length)
    sb.append(text, last, i)
    last = i + 1
  }
  if (sb == null) return text
  return sb.append(text, last, text.length).toString()
}

// Escapes text for XML and drops invalid XML characters and DEL.
fun esc(text: String): String {
  var sb: StringBuilder? = null
  var last = 0
  for (i in text.indices) {
    val replacement = when (text[i]) {
      '&' -> "&amp;"
      '<' -> "&lt;"
      '>' -> "&gt;"
      '"' -> "&quot;"
      else -> if (isInvalidXmlUnit(text, i)) "" else continue
    }
    if (sb == null) sb = StringBuilder(text.length + 16)
    sb.append(text, last, i).append(replacement)
    last = i + 1
  }
  if (sb == null) return text
  return sb.append(text, last, text.length).toString()
}

// Escapes text that is only ever read as text: a tooltip, an accessible name, a `data-id`. Entity
// codes are turned into their characters first. A value that was checked before it is written,
// such as a style, a colour or a URL, must go through esc() instead, so that what was checked is
// what is written.
fun escText(text: String): String = esc(decodeEntities(text))

// The two decimals of a coordinate, by hundredths. Building the string from integers is much
// faster than converting a double.
private val FRACTION = Array(100) { i ->
  when {
    i == 0 -> ""
    i % 10 == 0 -> "." + (i / 10)
    i < 10 -> ".0$i"
    else -> ".$i"
  }
}

fun num(value: Double): String {
  // PORT: the rounded value stays a Double, so NaN, infinities and huge values print as in JS.
  val n = jsRound(value * 100)
  if (n == 0.0) return "0"
  if (n > 0) {
    if (n < 1e11) {
      val l = n.toLong()
      val q = l / 100
      return q.toString() + FRACTION[(l - q * 100).toInt()]
    }
  } else if (n > -1e11) {
    val m = (-n).toLong()
    val q = m / 100
    return "-" + q + FRACTION[(m - q * 100).toInt()]
  }
  return jsStr(n / 100)
}

// PORT: every Int is a whole number well inside the range num() prints without rounding.
fun num(value: Int): String = value.toString()

// Writes a measured label as SVG text centered on (cx, cy).
fun labelSvg(label: Label, cx: Double, cy: Double, attrsIn: String, icons: IconResolver? = null): String {
  var attrs = attrsIn
  val lines = label.lines
  if (lines.isEmpty()) return ""
  val lh = label.lineHeight
  val heights = label.heights
  val shift = label.size * 0.35
  var top = cy - label.height / 2

  val only = if (lines.size == 1 && lines[0].size == 1) lines[0][0] else null
  if (only != null && only.icon == null && only.math == null && only.style == 0) {
    return "<text$attrs x=\"${num(cx)}\" y=\"${num(top + lh / 2 + shift)}\" text-anchor=\"middle\">${esc(only.text)}</text>"
  }

  val shared = sharedStyle(label)
  val given = attrs
  var math: MathLook? = null
  attrs = textStyle(attrs, shared)
  val body = StringBuilder()
  val extra = StringBuilder()
  for (i in lines.indices) {
    val line = lines[i]
    val h = heights?.get(i) ?: lh
    val mid = top + h / 2
    val y = mid + shift
    var placed = false
    for (span in line) if (span.icon != null || span.math != null) placed = true
    if (placed) {
      var x = cx - label.widths[i] / 2
      for (span in line) {
        val spanMath = span.math
        if (spanMath != null) {
          val box = "x=\"${num(x)}\" y=\"${num(mid - spanMath.height / 2)}\" width=\"${num(spanMath.width)}\" height=\"${num(spanMath.height)}\""
          if (math == null) math = mathLook(given)
          extra.append(
            if (math.halo == null) mathSvg(spanMath, box, label.size, math.attrs) else "<rect $box rx=\"2\" fill=\"${math.halo}\"/>",
          )
        } else if (span.icon != null) {
          val side = label.size * 1.1
          val inner = icons?.invoke(span.icon) ?: ""
          extra.append(
            "<svg class=\"pele-icon\" data-icon=\"${escText(span.icon)}\" x=\"${num(x)}\" y=\"${num(mid - side / 2)}\" width=\"${num(side)}\" height=\"${num(side)}\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$inner</svg>",
          )
        } else if (span.text != "") {
          body.append("<tspan x=\"${num(x)}\" y=\"${num(y)}\" text-anchor=\"start\"${spanStyle(span.style and shared.inv())}>${esc(span.text)}</tspan>")
        }
        x += span.width
      }
    } else {
      for (j in line.indices) {
        val span = line[j]
        val pos = if (j == 0) " x=\"${num(cx)}\" y=\"${num(y)}\"" else ""
        body.append("<tspan$pos${spanStyle(span.style and shared.inv())}>${esc(span.text)}</tspan>")
      }
      if (line.isEmpty()) body.append("<tspan x=\"${num(cx)}\" y=\"${num(y)}\"> </tspan>")
    }
    top += h
  }
  return "<text$attrs text-anchor=\"middle\" xml:space=\"preserve\">$body</text>$extra"
}

private val RE_FILL = JsRegExp(""" fill="([^"]*)"""")
private val RE_STROKE = JsRegExp(""" stroke="([^"]*)"""")
private val RE_STYLE = JsRegExp(""" style="([^"]*)"""")
private val RE_STYLE_FILL = JsRegExp("""(?:^|;)\s*fill:([^;]*)""")
private val RE_STYLE_OPACITY = JsRegExp("""(?:^|;)\s*opacity:([^;]*)""")

// Copy only color and opacity to preserve measured math dimensions.
// Use a solid patch for the cluster title's outline to hide edges behind math.
private class MathLook(val attrs: String, val halo: String? = null)

private fun mathLook(attrs: String): MathLook {
  val style = RE_STYLE.exec(attrs)?.get(1) ?: ""
  val fill = (RE_STYLE_FILL.exec(style)?.get(1) ?: RE_FILL.exec(attrs)?.get(1) ?: "").jsTrim()
  if (fill == "none") return MathLook("", RE_STROKE.exec(attrs)?.get(1) ?: "none")
  val opacity = RE_STYLE_OPACITY.exec(style)?.get(1)?.jsTrim()
  return MathLook((if (fill == "") "" else " color=\"$fill\"") + (if (!opacity.isNullOrEmpty()) " opacity=\"$opacity\"" else ""))
}

// Resolver markup is trusted.
private fun mathSvg(math: MathMarkup, box: String, size: Double, attrs: String): String {
  if (math is MathMarkup.Svg) return "<svg class=\"pele-math\" $box overflow=\"visible\"$attrs>${math.svg}</svg>"
  math as MathMarkup.Html
  return "<foreignObject class=\"pele-math\" $box overflow=\"visible\"$attrs>" +
    "<div xmlns=\"http://www.w3.org/1999/xhtml\" style=\"display:flex;align-items:center;justify-content:center;width:100%;height:100%;font-size:${num(size)}px;line-height:normal;white-space:nowrap\">${math.html}</div>" +
    "</foreignObject>"
}

private const val BOLD = " font-weight=\"var(--_w)\""
private const val ITALIC = " font-style=\"italic\""
private const val UNDERLINE = " text-decoration=\"underline\""
private const val MONO = " font-family=\"var(--_fm)\""
private const val SUB = " baseline-shift=\"sub\" font-size=\"smaller\""
private const val SUP = " baseline-shift=\"super\" font-size=\"smaller\""

fun spanStyle(style: Int): String =
  (if ((style and Style.Bold) != 0) BOLD else "") +
    (if ((style and Style.Italic) != 0) ITALIC else "") +
    (if ((style and Style.Mono) != 0) MONO else "") +
    (if ((style and Style.Underline) != 0) UNDERLINE else "") +
    (if ((style and Style.Sub) != 0) SUB else if ((style and Style.Sup) != 0) SUP else "")

// The emphasis every span of a label has. It is written once, on the text element, where a
// CSS rule for the label reaches it.
fun sharedStyle(label: Label): Int {
  var shared = Style.Bold or Style.Italic or Style.Mono
  var any = false
  for (line in label.lines) {
    for (span in line) {
      if (span.icon != null || span.text == "") continue
      shared = shared and span.style
      any = true
    }
  }
  return if (any) shared else 0
}

// Adds that emphasis to a text element's attributes, unless they give a weight of their own.
fun textStyle(attrs: String, shared: Int): String =
  attrs +
    (if ((shared and Style.Bold) != 0 && !attrs.contains(" font-weight=\"")) BOLD else "") +
    (if ((shared and Style.Italic) != 0 && !attrs.contains(" font-style=\"")) ITALIC else "") +
    (if ((shared and Style.Mono) != 0 && !attrs.contains(" font-family=\"")) MONO else "")
