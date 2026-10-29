// Ported from Pele src/diagrams/sequence/colors.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.js.JsRegExp
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsSplitStrings
import pele.js.jsTrim

// Mermaid asks the browser whether the first word of a box title is a color. Pele has no browser,
// so it knows the color keywords and the rgb() and hsl() notations. Other functions, such as calc()
// and var(), are not recognized.
private const val KEYWORDS =
  "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood " +
    "cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray " +
    "darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen " +
    "darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue " +
    "firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew " +
    "hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan " +
    "lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray " +
    "lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine mediumblue " +
    "mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred " +
    "midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid " +
    "palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue purple " +
    "rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue " +
    "slateblue slategray slategrey snow springgreen steelblue tan teal thistle tomato turquoise violet wheat white " +
    "whitesmoke yellow yellowgreen transparent currentcolor inherit initial unset revert " +
    "accentcolor accentcolortext activetext buttonborder buttonface buttontext canvas canvastext field fieldtext " +
    "graytext highlight highlighttext linktext mark marktext selecteditem selecteditemtext visitedtext " +
    "activeborder activecaption appworkspace background buttonhighlight buttonshadow captiontext inactiveborder " +
    "inactivecaption inactivecaptiontext infobackground infotext menu menutext scrollbar threeddarkshadow threedface " +
    "threedhighlight threedlightshadow threedshadow window windowframe windowtext"

private val keywords: Set<String> by lazy { KEYWORDS.jsSplit(" ").toSet() }

private val RE_TERM =
  JsRegExp("""^[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:e[+-]?\d+)?(%|deg|grad|rad|turn)?$""", "i")
private val RE_SPACES = JsRegExp("""\s+""")

private object CK {
  const val Bad = 0
  const val Number = 1
  const val Percent = 2
  const val Angle = 3
  const val None = 4
}

private fun kind(term: String): Int {
  if (term.lowercase() == "none") return CK.None
  val m = RE_TERM.exec(term) ?: return CK.Bad
  return if (m[1] == null) CK.Number else if (m[1] == "%") CK.Percent else CK.Angle
}

private fun isAlpha(term: String, modern: Boolean): Boolean {
  val k = kind(term)
  return k == CK.Number || k == CK.Percent || (modern && k == CK.None)
}

private fun isFunction(color: String): Boolean {
  val hsl = color.startsWith("hsl")
  val open = color.indexOf('(')
  val name = color.jsSlice(0, open)
  if (name != "rgb" && name != "rgba" && name != "hsl" && name != "hsla") return false
  if (color.length > 120 || color.indexOf(')') != color.length - 1) return false
  val inner = color.jsSlice(open + 1, -1).jsTrim()
  val parts: MutableList<String>
  var alpha: String? = null
  val legacy = inner.contains(',')
  if (legacy) {
    parts = inner.jsSplit(",").map { s -> s.jsTrim() }.toMutableList()
    if (parts.size == 4) alpha = parts.removeAt(parts.size - 1)
  } else {
    val slash = inner.jsSplit("/")
    if (slash.size > 2) return false
    parts = slash[0].jsTrim().jsSplitStrings(RE_SPACES).toMutableList()
    if (slash.size == 2) alpha = slash[1].jsTrim()
  }
  if (parts.size != 3 || (alpha != null && !isAlpha(alpha, !legacy))) return false
  val kinds = parts.map { kind(it) }
  if (legacy) {
    if (hsl)
      return (kinds[0] == CK.Number || kinds[0] == CK.Angle) &&
        kinds[1] == CK.Percent &&
        kinds[2] == CK.Percent
    return (kinds[0] == CK.Number || kinds[0] == CK.Percent) &&
      kinds[1] == kinds[0] &&
      kinds[2] == kinds[0]
  }
  for (i in 0 until 3) {
    val k = kinds[i]
    val ok =
      k == CK.Number || k == CK.None || (if (hsl && i == 0) k == CK.Angle else k == CK.Percent)
    if (!ok) return false
  }
  return true
}

fun isCssColor(color: String): Boolean {
  if (color.contains('(')) return isFunction(color)
  return keywords.contains(color.lowercase())
}
