// Ported from Pele src/svg/ink.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import pele.js.JsRegExp
import pele.js.jsMatchAll
import pele.js.jsParseFloat
import pele.js.jsParseInt
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsSplitStrings
import pele.js.jsTrim
import kotlin.math.PI

import kotlin.math.sqrt

// Named colors that #fff contrasts with more than #1f1f1f does, and the rest.
private val DARK: Set<String> =
  "black blue blueviolet brown crimson darkblue darkcyan darkgreen darkmagenta darkolivegreen darkorchid darkred darkslateblue darkslategray darkviolet dimgray firebrick forestgreen green indigo maroon mediumblue mediumslateblue mediumvioletred midnightblue navy olive purple rebeccapurple royalblue saddlebrown seagreen sienna slateblue steelblue teal"
    .split(' ').toSet()
private val LIGHT: Set<String> =
  "aliceblue antiquewhite aqua aquamarine azure beige bisque blanchedalmond burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk cyan darkgoldenrod darkgray darkkhaki darkorange darksalmon darkseagreen darkturquoise deeppink deepskyblue dodgerblue floralwhite fuchsia gainsboro ghostwhite gold goldenrod gray greenyellow honeydew hotpink indianred ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow lightgray lightgreen lightpink lightsalmon lightseagreen lightskyblue lightslategray lightsteelblue lightyellow lime limegreen linen magenta mediumaquamarine mediumorchid mediumpurple mediumseagreen mediumspringgreen mediumturquoise mintcream mistyrose moccasin navajowhite oldlace olivedrab orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue red rosybrown salmon sandybrown seashell silver skyblue slategray snow springgreen tan thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen"
    .split(' ').toSet()

const val ON_LIGHT = "var(--pele-fg-on-light,#1f1f1f)"
const val ON_DARK = "var(--pele-fg-on-dark,#fff)"

private val RE_FILL = JsRegExp("""[";]fill:([^;"]*)""")
private val RE_OPACITIES = JsRegExp("""[";](?:fill-)?opacity:([^;"]*)""", "g")
private val RE_HEX = JsRegExp("""^#(?:[\da-f]{3,4}|[\da-f]{6}|[\da-f]{8})$""", "i")
private val RE_FUNCTION = JsRegExp("""^(rgba?|hsla?)\(([^()]*)\)$""", "i")
private val RE_NUMBER = JsRegExp("""^[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:e[+-]?\d+)?(%|deg|grad|rad|turn)?$""", "i")

private fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else StrictMath.pow((c + 0.055) / 1.055, 2.4)

private fun luminance(r: Double, g: Double, b: Double): Double = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)

private fun clamp(n: Double): Double = minOf(maxOf(n, 0.0), 1.0)

// Luminance where #1f1f1f and #fff have equal contrast.
private val EVEN = sqrt(1.05 * (luminance(31.0 / 255, 31.0 / 255, 31.0 / 255) + 0.05)) - 0.05

private fun amount(raw: String, full: Double): Double {
  val m = RE_NUMBER.exec(raw)
  if (m == null || (!m[1].isNullOrEmpty() && m[1] != "%")) return Double.NaN
  return clamp(jsParseFloat(raw) / (if (!m[1].isNullOrEmpty()) 100.0 else full))
}

private fun hue(raw: String): Double {
  val m = RE_NUMBER.exec(raw)
  if (m == null || m[1] == "%") return Double.NaN
  val unit = m[1]?.lowercase()
  val n = jsParseFloat(raw)
  return when (unit) {
    "turn" -> n * 360
    "rad" -> (n * 180) / PI
    "grad" -> n * 0.9
    else -> n
  }
}

private val RE_COMMA_LIST = JsRegExp("""\s*,\s*""")
private val RE_SPACE_LIST = JsRegExp("""\s+""")

// The comma syntax takes no `none`, rgb() channels all or none in %, and hsl() saturation and lightness in %.
private fun args(body: String, hsl: Boolean): List<String>? {
  val legacy = body.contains(',')
  val parts = if (legacy) listOf(body) else body.jsSplit("/")
  val main = parts[0]
  val alpha = parts.getOrNull(1)
  val extra = parts.getOrNull(2)
  if (extra != null) return null
  val list = main.jsTrim().jsSplitStrings(if (legacy) RE_COMMA_LIST else RE_SPACE_LIST).toMutableList()
  if (if (legacy) list.size < 3 || list.size > 4 else list.size != 3) return null
  if (alpha != null) list.add(alpha.jsTrim())
  if (!legacy) return list.map { c -> if (c == "none") "0" else c }
  val percent = list.jsSliceList(if (hsl) 1 else 0, 3).count { c -> c.endsWith("%") }
  return if (percent == (if (hsl) 2 else 3) || (!hsl && percent == 0)) list else null
}

private fun List<String>.jsSliceList(from: Int, to: Int): List<String> = subList(minOf(from, size), minOf(to, size))

private val RE_ANY = JsRegExp(".", "g")

// Luminance and alpha; named colors use 0 or 1 to preserve the contrast choice.
private fun color(raw: String): DoubleArray? {
  val value = raw.jsTrim().lowercase()
  val name = value.replaceFirst("grey", "gray")
  if (name in DARK) return doubleArrayOf(0.0, 1.0)
  if (name in LIGHT) return doubleArrayOf(1.0, 1.0)
  if (RE_HEX.test(value)) {
    val digits = if (value.length < 6) value.jsSlice(1).jsReplace(RE_ANY, "$&$&") else value.jsSlice(1)
    val n = { i: Int -> jsParseInt(digits.jsSlice(2 * i, 2 * i + 2), 16) / 255 }
    return doubleArrayOf(luminance(n(0), n(1), n(2)), if (digits.length == 8) n(3) else 1.0)
  }
  val fn = RE_FUNCTION.exec(value)
  val hsl = fn?.get(1)?.startsWith("hsl") ?: false
  val list = if (fn != null) args(fn[2]!!, hsl) else null
  if (list == null) return null
  val alpha = if (list.size == 4) amount(list[3], 1.0) else 1.0
  val rgb: List<Double>
  if (!hsl) {
    rgb = list.subList(0, 3).map { c -> amount(c, 255.0) }
  } else {
    val h = (((hue(list[0]) % 360) + 360) % 360) / 30
    val s = amount(list[1], 100.0)
    val l = amount(list[2], 100.0)
    val a = s * minOf(l, 1 - l)
    rgb = listOf(0, 8, 4).map { k ->
      val t = (k + h) % 12
      l - a * maxOf(-1.0, minOf(minOf(t - 3, 9 - t), 1.0))
    }
  }
  val lum = luminance(rgb[0], rgb[1], rgb[2])
  return if ((lum + alpha).isNaN()) null else doubleArrayOf(lum, alpha)
}

// How opaque a style's fill-opacity and opacity leave its fill, or NaN when either isn't a plain number.
private fun opacity(shape: String): Double {
  var product = 1.0
  for (m in shape.jsMatchAll(RE_OPACITIES)) product *= amount(m[1]!!.jsTrim(), 1.0)
  return product
}

// PORT: a synchronized cache, as renders may run concurrently.
private val known = HashMap<String, String?>() // PORT-OK: synchronized

// Styles come from resolveStyle(). Fills below half opacity depend on an unknown background.
fun ink(shape: String, text: String = ""): String? {
  val key = "$shape\u0000$text"
  synchronized(known) {
    if (known.containsKey(key)) return known[key]
  }
  val result = pick(shape, text)
  synchronized(known) {
    if (known.size > 1000) known.clear()
    known[key] = result
  }
  return result
}

private fun pick(shape: String, text: String): String? {
  val fill = RE_FILL.exec(shape)
  val found = if (fill != null && !RE_FILL.test(text)) color(fill[1]!!) else null
  if (found == null) return null
  // An opacity that isn't a plain number, such as calc() or var(), may still be low.
  if (!(found[1] * opacity(shape) >= 0.5)) return null
  return if (found[0] > EVEN) ON_LIGHT else ON_DARK
}

// Unknown fill colors and opacities are treated as opaque.
fun paints(shape: String): Boolean {
  val fill = RE_FILL.exec(shape)?.get(1)?.jsTrim()?.lowercase()
  if (fill.isNullOrEmpty() || fill == "none" || fill == "transparent") return false
  val scale = opacity(shape)
  return (color(fill)?.get(1) ?: 1.0) * (if (scale.isNaN()) 1.0 else scale) > 0
}

class InkStyle(val shape: String, val text: String)

fun inkFill(style: InkStyle, fallback: String? = null): String {
  val color = ink(style.shape, style.text) ?: fallback
  return if (!color.isNullOrEmpty()) " fill=\"$color\"" else ""
}

fun inkFill(style: ResolvedStyle, fallback: String? = null): String = inkFill(InkStyle(style.shape, style.text), fallback)
