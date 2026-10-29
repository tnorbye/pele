// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.truncate

/** ECMAScript `Math.round`: half up, keeping NaN, infinities and zero as they are. */
fun jsRound(x: Double): Double {
  if (x.isNaN() || x.isInfinite() || x == 0.0 || abs(x) >= 4503599627370496.0) return x
  if (x > 0.0 && x < 0.5) return 0.0
  // JS returns -0 here; num() and jsStr() print it as "0".
  if (x >= -0.5 && x < 0.0) return -0.0
  return floor(x + 0.5)
}

/** ECMAScript `Number.prototype.toFixed`. */
fun Double.toFixed(digits: Int): String {
  if (isNaN()) return "NaN"
  if (abs(this) >= 1e21) return jsStr(this)
  if (this == 0.0) return if (digits <= 0) "0" else "0." + "0".repeat(digits)
  val rounded = BigDecimal(abs(this)).setScale(digits, RoundingMode.HALF_UP)
  val sign = if (this < 0.0) "-" else ""
  return sign + rounded.toPlainString()
}

/** ECMAScript `Number.prototype.toPrecision`. */
fun Double.toPrecision(precision: Int): String {
  if (isNaN()) return "NaN"
  if (isInfinite()) return if (this > 0) "Infinity" else "-Infinity"
  if (this == 0.0) {
    return if (precision == 1) "0" else "0." + "0".repeat(precision - 1)
  }
  val sign = if (this < 0) "-" else ""
  val bd = BigDecimal(abs(this)).round(MathContext(precision, RoundingMode.HALF_UP))
  // e = floor(log10(value)) after rounding.
  val e = bd.precision() - bd.scale() - 1
  val digits = bd.unscaledValue().toString().padEnd(precision, '0').substring(0, precision)
  if (e < -6 || e >= precision) {
    val mantissa = if (precision == 1) digits else digits[0] + "." + digits.substring(1)
    return sign + mantissa + "e" + (if (e >= 0) "+" else "-") + abs(e)
  }
  if (e == precision - 1) return sign + digits
  if (e >= 0) return sign + digits.substring(0, e + 1) + "." + digits.substring(e + 1)
  return sign + "0." + "0".repeat(-(e + 1)) + digits
}

/** ECMAScript `Number::toString`, with the shortest digits that round-trip on any JDK. */
fun jsStr(x: Double): String {
  if (x.isNaN()) return "NaN"
  if (x == 0.0) return "0"
  if (x == Double.POSITIVE_INFINITY) return "Infinity"
  if (x == Double.NEGATIVE_INFINITY) return "-Infinity"
  val ax = abs(x)
  if (x == truncate(x) && ax < 9.007199254740992E15) return x.toLong().toString()
  val sign = if (x < 0) "-" else ""
  val exact = BigDecimal(ax)
  var digits = ""
  var n = 0
  for (p in 1..17) {
    val bd = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
    if (bd.toDouble() == ax) {
      val stripped = bd.stripTrailingZeros()
      digits = stripped.unscaledValue().toString()
      n = digits.length - stripped.scale()
      break
    }
  }
  val k = digits.length
  return sign + when {
    n in k..21 -> digits + "0".repeat(n - k)
    n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
    n in -5..0 -> "0." + "0".repeat(-n) + digits
    else -> {
      val e = n - 1
      val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
      mantissa + "e" + (if (e >= 0) "+" else "-") + abs(e)
    }
  }
}

fun jsStr(x: Int): String = x.toString()

private val RE_DECIMAL = Regex("^[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
private val RE_RADIX = Regex("^0[xX][0-9a-fA-F]+$|^0[oO][0-7]+$|^0[bB][01]+$")

/** ECMAScript `Number(string)`. */
fun jsNumber(raw: String): Double {
  val s = raw.jsTrim()
  if (s.isEmpty()) return 0.0
  if (s == "Infinity" || s == "+Infinity") return Double.POSITIVE_INFINITY
  if (s == "-Infinity") return Double.NEGATIVE_INFINITY
  if (RE_RADIX.matches(s)) {
    val radix = when (s[1].lowercaseChar()) {
      'x' -> 16
      'o' -> 8
      else -> 2
    }
    return s.substring(2).toBigInteger(radix).toDouble()
  }
  if (!RE_DECIMAL.matches(s)) return Double.NaN
  return s.toDouble()
}

/** ECMAScript `Number(value)` for the values a port passes it. */
fun jsNumber(value: Any?): Double = when (value) {
  null -> 0.0
  is Double -> value
  is Number -> value.toDouble()
  is Boolean -> if (value) 1.0 else 0.0
  is String -> jsNumber(value)
  else -> Double.NaN
}

private val RE_PARSE_FLOAT = Regex(
  "^[$JS_WHITESPACE_CLASS_BODY]*([-+]?(?:Infinity|[0-9]+(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?|\\.[0-9]+(?:[eE][-+]?[0-9]+)?))"
)

/** ECMAScript `parseFloat`: the longest numeric prefix after leading whitespace. */
fun jsParseFloat(raw: String): Double {
  val num = RE_PARSE_FLOAT.find(raw)?.groupValues?.get(1) ?: return Double.NaN
  return when (num) {
    "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
    "-Infinity" -> Double.NEGATIVE_INFINITY
    else -> num.toDouble()
  }
}

/** ECMAScript `parseInt`. Returns a Double, since the result can be NaN or exceed Int range. */
fun jsParseInt(raw: String, radix: Int = 0): Double {
  var s = raw.jsTrimStart()
  var sign = 1.0
  if (s.startsWith("-")) {
    sign = -1.0
    s = s.substring(1)
  } else if (s.startsWith("+")) {
    s = s.substring(1)
  }
  var r = radix
  var stripPrefix = true
  if (r != 0) {
    if (r < 2 || r > 36) return Double.NaN
    if (r != 16) stripPrefix = false
  } else {
    r = 10
  }
  if (stripPrefix && (s.startsWith("0x") || s.startsWith("0X"))) {
    s = s.substring(2)
    r = 16
  }
  var end = 0
  while (end < s.length && Character.digit(s[end], r) != -1 && s[end].code < 128) end++
  if (end == 0) return Double.NaN
  val digits = s.substring(0, end)
  val value = if (r == 10) BigDecimal(digits).toDouble() else java.math.BigInteger(digits, r).toDouble()
  return sign * value
}

/** ECMAScript `ToInt32`, as in `x | 0`. */
fun toInt32(x: Double): Int {
  if (x.isNaN() || x.isInfinite()) return 0
  val t = truncate(x)
  if (abs(t) < 2147483648.0) return t.toInt()
  val m = t.rem(4294967296.0)
  return (if (m < 0) m + 4294967296.0 else m).toLong().toInt()
}

/** ECMAScript `ToUint32`, as in `x >>> 0`, kept as a Double so it stays unsigned. */
fun toUint32(x: Double): Double = (toInt32(x).toLong() and 0xFFFFFFFFL).toDouble()

fun toUint32(x: Int): Double = (x.toLong() and 0xFFFFFFFFL).toDouble()

/** ECMAScript `Math.imul`. */
fun jsImul(a: Int, b: Int): Int = a * b

/** ECMAScript `Number.isInteger`. */
fun isInteger(x: Double): Boolean = !x.isNaN() && !x.isInfinite() && floor(x) == x

/** ECMAScript `Math.max(...)`, which is NaN when any argument is NaN, and treats -0 < +0. */
fun jsMax(vararg values: Double): Double {
  var result = Double.NEGATIVE_INFINITY
  for (v in values) result = jsMax(result, v)
  return result
}

fun jsMax(a: Double, b: Double): Double = maxOf(a, b)

fun jsMin(vararg values: Double): Double {
  var result = Double.POSITIVE_INFINITY
  for (v in values) result = jsMin(result, v)
  return result
}

fun jsMin(a: Double, b: Double): Double = minOf(a, b)

/** JS truthiness of a number. */
fun truthy(n: Double): Boolean = n != 0.0 && !n.isNaN()

/**
 * ECMAScript `Math.hypot` as V8 computes it (src/builtins/math.tq): the values scaled by the
 * largest, squared and summed with Kahan compensation. Neither `Math.hypot` nor
 * `StrictMath.hypot` rounds the same way.
 */
fun jsHypot(vararg values: Double): Double {
  var max = 0.0
  var nan = false
  val abs = DoubleArray(values.size)
  for (i in values.indices) {
    val v = abs(values[i])
    if (v.isNaN()) nan = true
    else if (v > max) max = v
    abs[i] = v
  }
  if (max == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
  if (nan) return Double.NaN
  if (max == 0.0) return 0.0
  var sum = 0.0
  var compensation = 0.0
  for (v in abs) {
    val n = v / max
    val summand = n * n - compensation
    val preliminary = sum + summand
    compensation = (preliminary - sum) - summand
    sum = preliminary
  }
  return kotlin.math.sqrt(sum) * max
}

/** A JS sort comparator's result as a Kotlin one: NaN counts as 0, as `Array.prototype.sort` treats it. */
fun cmp(d: Double): Int = if (d > 0.0) 1 else if (d < 0.0) -1 else 0

/** JS `a || b` for numbers: `b` when `a` is 0 or NaN. */
inline fun Double.jsOr(b: () -> Double): Double = if (this != 0.0 && !isNaN()) this else b()

/** JS truthiness of a dynamic value, as read from YAML or JSON. */
fun isTruthy(v: Any?): Boolean = when (v) {
  null -> false
  is Boolean -> v
  is Double -> truthy(v)
  is Number -> truthy(v.toDouble())
  is String -> v.isNotEmpty()
  else -> true
}

/** ECMAScript `String(value)` for the values a port passes it. */
fun jsString(value: Any?): String = when (value) {
  null -> "null"
  is String -> value
  is Double -> jsStr(value)
  is Float -> jsStr(value.toDouble())
  is Number -> jsStr(value.toDouble())
  is Boolean -> value.toString()
  is List<*> -> value.joinToString(",") { if (it == null) "" else jsString(it) }
  is Map<*, *> -> "[object Object]"
  else -> value.toString()
}
