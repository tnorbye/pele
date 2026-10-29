// Ported from Pele src/diagrams/treemap/format.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treemap

import pele.js.JsRegExp
import pele.js.jsNumber
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsStr
import pele.js.toFixed
import pele.js.toPrecision
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

// The parts of d3-format's specifier that treemap values use: an optional `$`, `,` for thousands
// separators, a precision, and a type of `f`, `%`, `d`, `e` or none. Fill, alignment and width are
// read and ignored. Anything else falls back to the default, `,`.
private val RE_SPEC = JsRegExp("""^(?:.?[<>=^])?([-+( ])?(\$)?0?\d*(,)?(?:\.(\d+))?(~)?([a-zA-Z%])?$""")
private val RE_ZEROS = JsRegExp("""\.?0+$""")

private fun group(digits: String): String {
  var out = ""
  var i = digits.length
  while (i > 0) {
    out = digits.jsSlice(maxOf(0, i - 3), i) + (if (out.isNotEmpty()) ",$out" else "")
    i -= 3
  }
  return out
}

// PORT: Number.prototype.toExponential, which the shared js helpers lack. `value` is not negative.
private fun toExponential(value: Double, digits: Int): String {
  if (value.isNaN()) return "NaN"
  if (value.isInfinite()) return "Infinity"
  if (value == 0.0) return (if (digits > 0) "0." + "0".repeat(digits) else "0") + "e+0"
  val rounded = BigDecimal(value).round(MathContext(digits + 1, RoundingMode.HALF_UP))
  val e = rounded.precision() - rounded.scale() - 1
  val all = rounded.unscaledValue().toString().padEnd(digits + 1, '0').substring(0, digits + 1)
  val mantissa = if (digits > 0) all[0] + "." + all.substring(1) else all
  return mantissa + "e" + (if (e >= 0) "+" else "-") + abs(e)
}

fun formatter(spec: String): (Double) -> String {
  // Mermaid documents `$0,0` for a dollar amount with thousands separators.
  val m = RE_SPEC.exec(if (spec == "\$0,0") "\$," else spec) ?: RE_SPEC.exec(",")!!
  val plus = m[1] == "+"
  val comma = m[3] != null
  val precision: Double? = if (m[4] == null) null else minOf(jsNumber(m[4]!!), 20.0)
  val type = m[6] ?: ""

  return { value ->
    val abs = kotlin.math.abs(value)
    val body: String
    var suffix = ""
    if (type == "f") {
      body = abs.toFixed((precision ?: 6.0).toInt())
    } else if (type == "%") {
      body = (abs * 100).toFixed((precision ?: 6.0).toInt())
      suffix = "%"
    } else if (type == "d") {
      body = jsRound(abs).toFixed(0)
    } else if (type == "e") {
      body = toExponential(abs, (precision ?: 6.0).toInt())
    } else {
      body = jsStr(jsNumber(abs.toPrecision(maxOf(1.0, precision ?: 12.0).toInt())))
    }

    var text = body
    if (!m[5].isNullOrEmpty() && text.contains('.') && !text.contains('e')) text = text.jsReplace(RE_ZEROS, "")
    if (comma && !text.contains('e')) {
      val dot = text.indexOf('.')
      text = if (dot == -1) group(text) else group(text.jsSlice(0, dot)) + text.jsSlice(dot)
    }
    (if (value < 0) "-" else if (plus) "+" else "") + (m[2] ?: "") + text + suffix
  }
}
