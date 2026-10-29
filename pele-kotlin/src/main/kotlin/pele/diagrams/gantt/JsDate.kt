// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.diagrams.gantt

import pele.js.isJsWhitespace
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.floor
import kotlin.math.truncate

private const val MS_PER_DAY = 86400000.0
private const val MAX_TIME = 8.64e15

// Offset of the system zone from UTC at an instant, in milliseconds. The zone is read on every call
// because it can be changed at run time.
private fun offsetAt(utc: Double): Double =
  ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc.toLong())).totalSeconds * 1000.0

// Offset for a local time; a time that does not exist or occurs twice takes the offset before the
// transition, as in ECMAScript.
private fun offsetOfLocal(local: Double): Double {
  val whole = local.toLong()
  val seconds = Math.floorDiv(whole, 1000L)
  val millis = Math.floorMod(whole, 1000L)
  val ldt = LocalDateTime.ofEpochSecond(seconds, (millis * 1000000L).toInt(), ZoneOffset.UTC)
  val rules = ZoneId.systemDefault().rules
  val transition = rules.getTransition(ldt)
  val offset = transition?.offsetBefore ?: rules.getOffset(ldt)
  return offset.totalSeconds * 1000.0
}

// A local time further out than any offset could bring back into range is NaN after TimeClip anyway.
private fun toUtc(local: Double): Double =
  if (local.isFinite() && local >= -MAX_TIME - 1e9 && local <= MAX_TIME + 1e9) local - offsetOfLocal(local) else Double.NaN

private fun toLocal(utc: Double): Double = if (utc.isFinite()) utc + offsetAt(utc) else Double.NaN

private fun timeClip(time: Double): Double =
  if (time >= -MAX_TIME && time <= MAX_TIME) truncate(time) + 0.0 else Double.NaN

// Days since 1970-01-01 of a proleptic Gregorian date, with the month in 1..12.
private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
  val y = if (month <= 2) year - 1 else year
  val era = Math.floorDiv(y, 400L)
  val yoe = y - era * 400
  val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
  val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
  return era * 146097 + doe - 719468
}

private fun civilFromDays(days: Long): LongArray {
  val z = days + 719468
  val era = Math.floorDiv(z, 146097L)
  val doe = z - era * 146097
  val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
  val y = yoe + era * 400
  val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
  val mp = (5 * doy + 2) / 153
  val d = doy - (153 * mp + 2) / 5 + 1
  val m = if (mp < 10) mp + 3 else mp - 9
  return longArrayOf(if (m <= 2) y + 1 else y, m, d)
}

// V8's MakeDay: NaN outside its supported range.
fun makeDay(year: Double, month: Double, date: Double): Double {
  if (!(year >= -1000000 && year <= 1000000 && month >= -10000000 && month <= 10000000 && date.isFinite())) return Double.NaN
  var y = truncate(year).toLong()
  var m = truncate(month).toLong()
  y += m / 12
  m %= 12
  if (m < 0) {
    m += 12
    y -= 1
  }
  return daysFromCivil(y, (m + 1).toInt(), 1).toDouble() + truncate(date) - 1
}

fun makeTime(hour: Double, minute: Double, second: Double, ms: Double): Double {
  if (!(hour.isFinite() && minute.isFinite() && second.isFinite() && ms.isFinite())) return Double.NaN
  return truncate(hour) * 3600000.0 + truncate(minute) * 60000.0 + truncate(second) * 1000.0 + truncate(ms)
}

fun makeDate(day: Double, time: Double): Double {
  if (!(day.isFinite() && time.isFinite())) return Double.NaN
  val result = day * MS_PER_DAY + time
  return if (result.isFinite()) result else Double.NaN
}

// Years 0 to 99 mean 1900 to 1999 in the multi-argument forms.
private fun fullYear(year: Double): Double {
  if (year.isNaN()) return year
  val integer = truncate(year)
  return if (integer >= 0 && integer <= 99) 1900 + integer else year
}

// `Date.UTC`.
fun dateUtc(
  year: Double,
  month: Double = 0.0,
  date: Double = 1.0,
  hours: Double = 0.0,
  minutes: Double = 0.0,
  seconds: Double = 0.0,
  ms: Double = 0.0,
): Double = timeClip(makeDate(makeDay(fullYear(year), month, date), makeTime(hours, minutes, seconds, ms)))

/** The part of the JS `Date` that the gantt code uses. The time value is epoch milliseconds, NaN when invalid. */
class JsDate private constructor(private var time: Double, @Suppress("UNUSED_PARAMETER") tag: Unit) {
  // new Date(ms)
  constructor(ms: Double) : this(timeClip(ms), Unit)

  // new Date(date)
  constructor(date: JsDate) : this(date.time, Unit)

  // new Date(string)
  constructor(text: String) : this(parseDate(text), Unit)

  companion object {
    // new Date(year, month, day, hours, minutes, seconds, ms), in local time.
    fun local(
      year: Double,
      month: Double,
      date: Double = 1.0,
      hours: Double = 0.0,
      minutes: Double = 0.0,
      seconds: Double = 0.0,
      ms: Double = 0.0,
    ): JsDate {
      val local = makeDate(makeDay(fullYear(year), month, date), makeTime(hours, minutes, seconds, ms))
      return JsDate(timeClip(toUtc(local)))
    }

    // Date.parse
    fun parse(text: String): Double = parseDate(text)
  }

  fun getTime(): Double = time

  private fun localTime(): Double = toLocal(time)

  private fun withinDay(local: Double): Double = local - floor(local / MS_PER_DAY) * MS_PER_DAY

  private fun civil(): LongArray? {
    val local = localTime()
    if (local.isNaN()) return null
    return civilFromDays(floor(local / MS_PER_DAY).toLong())
  }

  fun getFullYear(): Double = civil()?.get(0)?.toDouble() ?: Double.NaN

  fun getMonth(): Double = civil()?.let { (it[1] - 1).toDouble() } ?: Double.NaN

  fun getDate(): Double = civil()?.get(2)?.toDouble() ?: Double.NaN

  fun getDay(): Double {
    val local = localTime()
    if (local.isNaN()) return local
    return Math.floorMod(floor(local / MS_PER_DAY).toLong() + 4, 7L).toDouble()
  }

  fun getHours(): Double {
    val local = localTime()
    return if (local.isNaN()) local else floor(withinDay(local) / 3600000.0)
  }

  fun getMinutes(): Double {
    val local = localTime()
    return if (local.isNaN()) local else floor(withinDay(local) / 60000.0) % 60
  }

  fun getSeconds(): Double {
    val local = localTime()
    return if (local.isNaN()) local else floor(withinDay(local) / 1000.0) % 60
  }

  fun getMilliseconds(): Double {
    val local = localTime()
    return if (local.isNaN()) local else withinDay(local) % 1000
  }

  fun getUTCSeconds(): Double =
    if (time.isNaN()) time else floor((time - floor(time / MS_PER_DAY) * MS_PER_DAY) / 1000.0) % 60

  // V8 answers in whole minutes, rounded toward zero.
  fun getTimezoneOffset(): Double {
    if (time.isNaN()) return time
    return truncate((time - localTime()) / 60000.0) + 0.0
  }

  fun setTime(ms: Double): Double {
    time = timeClip(ms)
    return time
  }

  fun setDate(date: Double): Double {
    val local = localTime()
    if (local.isNaN()) return Double.NaN
    val civil = civilFromDays(floor(local / MS_PER_DAY).toLong())
    return setTime(toUtc(makeDate(makeDay(civil[0].toDouble(), (civil[1] - 1).toDouble(), date), withinDay(local))))
  }

  fun setMonth(month: Double, date: Double? = null): Double {
    val local = localTime()
    if (local.isNaN()) return Double.NaN
    val civil = civilFromDays(floor(local / MS_PER_DAY).toLong())
    val day = date ?: civil[2].toDouble()
    return setTime(toUtc(makeDate(makeDay(civil[0].toDouble(), month, day), withinDay(local))))
  }

  fun setFullYear(year: Double, month: Double? = null, date: Double? = null): Double {
    val local = if (time.isNaN()) 0.0 else localTime()
    val civil = civilFromDays(floor(local / MS_PER_DAY).toLong())
    val m = month ?: (civil[1] - 1).toDouble()
    val d = date ?: civil[2].toDouble()
    return setTime(toUtc(makeDate(makeDay(year, m, d), withinDay(local))))
  }

  fun setHours(hours: Double, minutes: Double? = null, seconds: Double? = null, ms: Double? = null): Double {
    val local = localTime()
    if (local.isNaN()) return Double.NaN
    val day = floor(local / MS_PER_DAY)
    val inDay = withinDay(local)
    val m = minutes ?: (floor(inDay / 60000.0) % 60)
    val s = seconds ?: (floor(inDay / 1000.0) % 60)
    val milli = ms ?: (inDay % 1000)
    return setTime(toUtc(makeDate(day, makeTime(hours, m, s, milli))))
  }
}

// The lenient fallback: what V8's Date.parse accepts, ES5 ISO strings and its legacy formats.

private const val NONE = Int.MAX_VALUE

private object Kw {
  const val INVALID = 0
  const val MONTH_NAME = 1
  const val TIME_ZONE_NAME = 2
  const val TIME_SEPARATOR = 3
  const val AM_PM = 4
}

private class Keyword(val a: Char, val b: Char, val c: Char, val type: Int, val value: Int)

private val KEYWORDS = listOf(
  Keyword('j', 'a', 'n', Kw.MONTH_NAME, 1),
  Keyword('f', 'e', 'b', Kw.MONTH_NAME, 2),
  Keyword('m', 'a', 'r', Kw.MONTH_NAME, 3),
  Keyword('a', 'p', 'r', Kw.MONTH_NAME, 4),
  Keyword('m', 'a', 'y', Kw.MONTH_NAME, 5),
  Keyword('j', 'u', 'n', Kw.MONTH_NAME, 6),
  Keyword('j', 'u', 'l', Kw.MONTH_NAME, 7),
  Keyword('a', 'u', 'g', Kw.MONTH_NAME, 8),
  Keyword('s', 'e', 'p', Kw.MONTH_NAME, 9),
  Keyword('o', 'c', 't', Kw.MONTH_NAME, 10),
  Keyword('n', 'o', 'v', Kw.MONTH_NAME, 11),
  Keyword('d', 'e', 'c', Kw.MONTH_NAME, 12),
  Keyword('a', 'm', '\u0000', Kw.AM_PM, 0),
  Keyword('p', 'm', '\u0000', Kw.AM_PM, 12),
  Keyword('u', 't', '\u0000', Kw.TIME_ZONE_NAME, 0),
  Keyword('u', 't', 'c', Kw.TIME_ZONE_NAME, 0),
  Keyword('z', '\u0000', '\u0000', Kw.TIME_ZONE_NAME, 0),
  Keyword('g', 'm', 't', Kw.TIME_ZONE_NAME, 0),
  Keyword('c', 'd', 't', Kw.TIME_ZONE_NAME, -5),
  Keyword('c', 's', 't', Kw.TIME_ZONE_NAME, -6),
  Keyword('e', 'd', 't', Kw.TIME_ZONE_NAME, -4),
  Keyword('e', 's', 't', Kw.TIME_ZONE_NAME, -5),
  Keyword('m', 'd', 't', Kw.TIME_ZONE_NAME, -6),
  Keyword('m', 's', 't', Kw.TIME_ZONE_NAME, -7),
  Keyword('p', 'd', 't', Kw.TIME_ZONE_NAME, -7),
  Keyword('p', 's', 't', Kw.TIME_ZONE_NAME, -8),
  Keyword('t', '\u0000', '\u0000', Kw.TIME_SEPARATOR, 0),
)

private object Tag {
  const val INVALID = -6
  const val UNKNOWN = -5
  const val NUMBER = -4
  const val SYMBOL = -3
  const val WHITESPACE = -2
  const val END = -1
}

// Keyword tokens carry their keyword type (0 and up) as the tag.
private class DateToken(val tag: Int, val length: Int, val value: Int) {
  val isInvalid get() = tag == Tag.INVALID
  val isNumber get() = tag == Tag.NUMBER
  val isEndOfInput get() = tag == Tag.END
  val isWhiteSpace get() = tag == Tag.WHITESPACE
  val isKeyword get() = tag >= 0

  fun isFixedLengthNumber(n: Int) = isNumber && length == n

  fun isAsciiSign() = tag == Tag.SYMBOL && (value == '-'.code || value == '+'.code)

  fun isSymbol(c: Char) = tag == Tag.SYMBOL && value == c.code

  fun isKeywordType(type: Int) = tag == type

  fun isKeywordZ() = isKeywordType(Kw.TIME_ZONE_NAME) && length == 1

  fun asciiSign() = 44 - value

  companion object {
    val INVALID = DateToken(Tag.INVALID, 0, 0)
    val UNKNOWN = DateToken(Tag.UNKNOWN, 0, 0)
    val END = DateToken(Tag.END, 0, 0)
  }
}

private fun number(value: Int, length: Int) = DateToken(Tag.NUMBER, length, value)

private fun symbol(c: Char) = DateToken(Tag.SYMBOL, 1, c.code)

private const val MAX_SIGNIFICANT_DIGITS = 9

// A NUL character ends the input, as in V8.
private class InputReader(private val buffer: String) {
  private var index = 0
  private var ch = 0

  init {
    next()
  }

  fun position() = index

  fun next() {
    ch = if (index < buffer.length) buffer[index].code else 0
    index++
  }

  fun isAsciiDigit() = ch >= 48 && ch <= 57

  fun isAsciiAlphaOrAbove() = ch >= 65

  // V8 does not count the line and paragraph separators as white space here.
  fun isWhiteSpaceChar() = isJsWhitespace(ch.toChar()) && ch != 0x2028 && ch != 0x2029

  fun isEnd() = ch == 0

  fun readUnsignedNumeral(): Int {
    var n = 0
    var i = 0
    while (ch == 48) next()
    while (isAsciiDigit()) {
      if (i < MAX_SIGNIFICANT_DIGITS) n = n * 10 + ch - 48
      i++
      next()
    }
    return n
  }

  // Returns the word's length; the first letters, lowercased, go to prefix.
  fun readWord(prefix: IntArray): Int {
    var len = 0
    while (isAsciiAlphaOrAbove() && !isWhiteSpaceChar()) {
      if (len < prefix.size) prefix[len] = ch or 0x20
      next()
      len++
    }
    for (i in len until prefix.size) prefix[i] = 0
    return len
  }

  fun skip(c: Char): Boolean {
    if (ch == c.code) {
      next()
      return true
    }
    return false
  }

  fun skipWhiteSpace(): Boolean {
    if (isWhiteSpaceChar()) {
      next()
      return true
    }
    return false
  }

  fun skipParentheses(): Boolean {
    if (ch != '('.code) return false
    var balance = 0
    do {
      if (ch == ')'.code) {
        --balance
      } else if (ch == '('.code) {
        ++balance
      }
      next()
    } while (balance > 0 && ch != 0)
    return true
  }
}

private class DateStringTokenizer(private val input: InputReader) {
  private var nextToken: DateToken = scan()

  fun next(): DateToken {
    val result = nextToken
    nextToken = scan()
    return result
  }

  fun peek(): DateToken = nextToken

  fun skipSymbol(c: Char): Boolean {
    if (nextToken.isSymbol(c)) {
      nextToken = scan()
      return true
    }
    return false
  }

  private fun lookup(pre: IntArray, len: Int): Keyword? {
    for (k in KEYWORDS) {
      if (pre[0] == k.a.code && pre[1] == k.b.code && pre[2] == k.c.code && (len <= 3 || k.type == Kw.MONTH_NAME)) return k
    }
    return null
  }

  private fun scan(): DateToken {
    val pre = input.position()
    if (input.isEnd()) return DateToken.END
    if (input.isAsciiDigit()) {
      val n = input.readUnsignedNumeral()
      return number(n, input.position() - pre)
    }
    if (input.skip(':')) return symbol(':')
    if (input.skip('-')) return symbol('-')
    if (input.skip('+')) return symbol('+')
    if (input.skip('.')) return symbol('.')
    if (input.skip(')')) return symbol(')')
    if (input.isAsciiAlphaOrAbove() && !input.isWhiteSpaceChar()) {
      val buffer = IntArray(3)
      val length = input.readWord(buffer)
      val k = lookup(buffer, length)
      return DateToken(k?.type ?: Kw.INVALID, length, k?.value ?: 0)
    }
    if (input.skipWhiteSpace()) return DateToken(Tag.WHITESPACE, input.position() - pre, 0)
    if (input.skipParentheses()) return DateToken.UNKNOWN
    input.next()
    return DateToken.UNKNOWN
  }
}

private fun between(x: Int, lo: Int, hi: Int) = x >= lo && x <= hi

private fun isMinute(x: Int) = between(x, 0, 59)

private fun isHour(x: Int) = between(x, 0, 23)

private fun isSecond(x: Int) = between(x, 0, 59)

private fun isHour12(x: Int) = between(x, 0, 12)

private fun isMillisecond(x: Int) = between(x, 0, 999)

private fun isMonth(x: Int) = between(x, 1, 12)

private fun isDay(x: Int) = between(x, 1, 31)

private class TimeZoneComposer {
  var sign = NONE
    private set
  var hour = NONE
  var minute = NONE

  fun set(offsetInHours: Int) {
    sign = if (offsetInHours < 0) -1 else 1
    hour = offsetInHours * sign
    minute = 0
  }

  fun setSign(s: Int) {
    sign = if (s < 0) -1 else 1
  }

  fun isExpecting(n: Int) = hour != NONE && minute == NONE && isMinute(n)

  fun isUTC() = hour == 0 && minute == 0

  fun isEmpty() = hour == NONE

  // The offset in seconds, NaN when none was given; null when the composition fails.
  fun write(): Double? {
    if (sign == NONE) return Double.NaN
    if (hour == NONE) hour = 0
    if (minute == NONE) minute = 0
    // Unsigned 32-bit arithmetic, as in V8.
    val total = ((hour.toLong() and 0xFFFFFFFFL) * 3600L + (minute.toLong() and 0xFFFFFFFFL) * 60L) and 0xFFFFFFFFL
    if (total > Int.MAX_VALUE) return null
    return (if (sign < 0) -total else total).toDouble()
  }
}

private class TimeComposer {
  val comp = IntArray(4)
  var index = 0
  var hourOffset = NONE

  fun isEmpty() = index == 0

  fun isExpecting(n: Int) =
    (index == 1 && isMinute(n)) || (index == 2 && isSecond(n)) || (index == 3 && isMillisecond(n))

  fun add(n: Int): Boolean {
    if (index < 4) {
      comp[index++] = n
      return true
    }
    return false
  }

  fun addFinal(n: Int): Boolean {
    if (!add(n)) return false
    while (index < 4) comp[index++] = 0
    return true
  }

  fun write(out: DoubleArray): Boolean {
    while (index < 4) comp[index++] = 0
    var hour = comp[0]
    val minute = comp[1]
    val second = comp[2]
    val millisecond = comp[3]
    if (hourOffset != NONE) {
      if (!isHour12(hour)) return false
      hour %= 12
      hour += hourOffset
    }
    if (!isHour(hour) || !isMinute(minute) || !isSecond(second) || !isMillisecond(millisecond)) {
      // A 24th hour is allowed if minutes, seconds and milliseconds are 0.
      if (hour != 24 || minute != 0 || second != 0 || millisecond != 0) return false
    }
    out[3] = hour.toDouble()
    out[4] = minute.toDouble()
    out[5] = second.toDouble()
    out[6] = millisecond.toDouble()
    return true
  }
}

private class DayComposer {
  val comp = IntArray(3)
  var index = 0
  var namedMonth = NONE
  var isIsoDate = false

  fun isEmpty() = index == 0

  fun add(n: Int): Boolean {
    if (index < 3) {
      comp[index] = n
      index++
      return true
    }
    return false
  }

  fun write(out: DoubleArray): Boolean {
    if (index < 1) return false
    // Day and month default to 1. This also makes the index 3 from here on, as in V8.
    while (index < 3) comp[index++] = 1
    var year = 0
    val month: Int
    val day: Int
    if (namedMonth == NONE) {
      if (isIsoDate || (index == 3 && !isDay(comp[0]))) {
        year = comp[0]
        month = comp[1]
        day = comp[2]
      } else {
        month = comp[0]
        day = comp[1]
        if (index == 3) year = comp[2]
      }
    } else {
      month = namedMonth
      if (index == 1) {
        day = comp[0]
      } else if (!isDay(comp[0])) {
        year = comp[0]
        day = comp[1]
      } else {
        day = comp[0]
        year = comp[1]
      }
    }
    if (!isIsoDate) {
      if (between(year, 0, 49)) {
        year += 2000
      } else if (between(year, 50, 99)) {
        year += 1900
      }
    }
    if (!isMonth(month) || !isDay(day)) return false
    out[0] = year.toDouble()
    out[1] = (month - 1).toDouble()
    out[2] = day.toDouble()
    return true
  }
}

private fun readMilliseconds(token: DateToken): Int {
  var number = token.value
  var length = token.length
  if (length < 3) {
    if (length == 1) {
      number *= 100
    } else if (length == 2) {
      number *= 10
    }
  } else if (length > 3) {
    if (length > MAX_SIGNIFICANT_DIGITS) length = MAX_SIGNIFICANT_DIGITS
    var factor = 1
    do {
      factor *= 10
      length--
    } while (length > 3)
    number /= factor
  }
  return number
}

private fun parseEs5DateTime(scanner: DateStringTokenizer, day: DayComposer, time: TimeComposer, tz: TimeZoneComposer): DateToken {
  // Mandatory date: [('-'|'+')yy]yyyy['-'MM['-'DD]]
  if (scanner.peek().isAsciiSign()) {
    val signToken = scanner.next()
    if (!scanner.peek().isFixedLengthNumber(6)) return signToken
    val sign = signToken.asciiSign()
    val year = scanner.next().value
    if (sign < 0 && year == 0) return signToken
    day.add(sign * year)
  } else if (scanner.peek().isFixedLengthNumber(4)) {
    day.add(scanner.next().value)
  } else {
    return scanner.next()
  }
  if (scanner.skipSymbol('-')) {
    if (!scanner.peek().isFixedLengthNumber(2) || !isMonth(scanner.peek().value)) return scanner.next()
    day.add(scanner.next().value)
    if (scanner.skipSymbol('-')) {
      if (!scanner.peek().isFixedLengthNumber(2) || !isDay(scanner.peek().value)) return scanner.next()
      day.add(scanner.next().value)
    }
  }
  // Optional time.
  if (!scanner.peek().isKeywordType(Kw.TIME_SEPARATOR)) {
    if (!scanner.peek().isEndOfInput) return scanner.next()
  } else {
    scanner.next()
    if (!scanner.peek().isFixedLengthNumber(2) || !between(scanner.peek().value, 0, 24)) return DateToken.INVALID
    // 24:00[:00[.000]] is allowed, but no other time starting with 24.
    val hourIs24 = scanner.peek().value == 24
    time.add(scanner.next().value)
    if (!scanner.skipSymbol(':')) return DateToken.INVALID
    if (!scanner.peek().isFixedLengthNumber(2) || !isMinute(scanner.peek().value) || (hourIs24 && scanner.peek().value > 0)) {
      return DateToken.INVALID
    }
    time.add(scanner.next().value)
    if (scanner.skipSymbol(':')) {
      if (!scanner.peek().isFixedLengthNumber(2) || !isSecond(scanner.peek().value) || (hourIs24 && scanner.peek().value > 0)) {
        return DateToken.INVALID
      }
      time.add(scanner.next().value)
      if (scanner.skipSymbol('.')) {
        if (!scanner.peek().isNumber || (hourIs24 && scanner.peek().value > 0)) return DateToken.INVALID
        time.add(readMilliseconds(scanner.next()))
      }
    }
    // Optional time zone: 'Z' | ('+'|'-')hh':'mm
    if (scanner.peek().isKeywordZ()) {
      scanner.next()
      tz.set(0)
    } else if (scanner.peek().isSymbol('+') || scanner.peek().isSymbol('-')) {
      tz.setSign(if (scanner.next().value == '+'.code) 1 else -1)
      if (scanner.peek().isFixedLengthNumber(4)) {
        val hourmin = scanner.next().value
        val hour = hourmin / 100
        val min = hourmin % 100
        if (!isHour(hour) || !isMinute(min)) return DateToken.INVALID
        tz.hour = hour
        tz.minute = min
      } else {
        if (!scanner.peek().isFixedLengthNumber(2) || !isHour(scanner.peek().value)) return DateToken.INVALID
        tz.hour = scanner.next().value
        if (!scanner.skipSymbol(':')) return DateToken.INVALID
        if (!scanner.peek().isFixedLengthNumber(2) || !isMinute(scanner.peek().value)) return DateToken.INVALID
        tz.minute = scanner.next().value
      }
    }
    if (!scanner.peek().isEndOfInput) return DateToken.INVALID
  }
  // Date-only forms are UTC and date-time forms are local time.
  if (tz.isEmpty() && time.isEmpty()) tz.set(0)
  day.isIsoDate = true
  return DateToken.END
}

// Fills out with year, month, day, hour, minute, second, millisecond and UTC offset in seconds.
private fun parseFields(str: String, out: DoubleArray): Boolean {
  val input = InputReader(str)
  val scanner = DateStringTokenizer(input)
  val tz = TimeZoneComposer()
  val time = TimeComposer()
  val day = DayComposer()

  var token = parseEs5DateTime(scanner, day, time, tz)
  if (token.isInvalid) return false
  var hasReadNumber = !day.isEmpty()
  while (!token.isEndOfInput) {
    if (token.isNumber) {
      hasReadNumber = true
      val n = token.value
      if (scanner.skipSymbol(':')) {
        if (scanner.skipSymbol(':')) {
          // n + "::"
          if (!time.isEmpty()) return false
          time.add(n)
          time.add(0)
        } else {
          // n + ":"
          if (!time.add(n)) return false
          if (scanner.peek().isSymbol('.')) scanner.next()
        }
      } else if (scanner.skipSymbol('.') && time.isExpecting(n)) {
        time.add(n)
        if (!scanner.peek().isNumber) return false
        val ms = readMilliseconds(scanner.next())
        if (ms < 0) return false
        time.addFinal(ms)
      } else if (tz.isExpecting(n)) {
        tz.minute = n
      } else if (time.isExpecting(n)) {
        time.addFinal(n)
        // Require the end, white space, "Z", "+" or "-" right after the time is final.
        val peek = scanner.peek()
        if (!peek.isEndOfInput && !peek.isWhiteSpace && !peek.isKeywordZ() && !peek.isAsciiSign()) return false
      } else {
        if (!day.add(n)) return false
        scanner.skipSymbol('-')
      }
    } else if (token.isKeyword) {
      val type = token.tag
      val value = token.value
      if (type == Kw.AM_PM && !time.isEmpty()) {
        time.hourOffset = value
      } else if (type == Kw.MONTH_NAME) {
        day.namedMonth = value
        scanner.skipSymbol('-')
      } else if (type == Kw.TIME_ZONE_NAME && hasReadNumber) {
        tz.set(value)
      } else {
        // Garbage words are illegal once a number has been read.
        if (hasReadNumber) return false
        // The first number has to be separated from garbage words.
        if (scanner.peek().isNumber) return false
      }
    } else if (token.isAsciiSign() && (tz.isUTC() || !time.isEmpty())) {
      // A UTC offset, only after UTC or a time.
      tz.setSign(token.asciiSign())
      var n = 0
      var length = 0
      if (scanner.peek().isNumber) {
        val nextToken = scanner.next()
        length = nextToken.length
        n = nextToken.value
      }
      hasReadNumber = true
      if (scanner.peek().isSymbol(':')) {
        tz.hour = n
        tz.minute = NONE
      } else if (length == 2 || length == 1) {
        tz.hour = n
        tz.minute = 0
      } else if (length == 4 || length == 3) {
        tz.hour = n / 100
        tz.minute = n % 100
      } else {
        return false
      }
    } else if ((token.isAsciiSign() || token.isSymbol(')')) && hasReadNumber) {
      // An extra sign or ')' is illegal once a number has been read.
      return false
    }
    token = scanner.next()
  }

  if (!day.write(out) || !time.write(out)) return false
  val offset = tz.write() ?: return false
  out[7] = offset
  return true
}

private const val MAX_TIME_BEFORE_UTC = MAX_TIME + 864000000.0

private fun parseDate(text: String): Double {
  val out = DoubleArray(8)
  if (!parseFields(text, out)) return Double.NaN
  val day = makeDay(out[0], out[1], out[2])
  val time = makeTime(out[3], out[4], out[5], out[6])
  var date = makeDate(day, time)
  if (out[7].isNaN()) {
    if (date >= -MAX_TIME_BEFORE_UTC && date <= MAX_TIME_BEFORE_UTC) {
      date = toUtc(date)
    } else {
      return Double.NaN
    }
  } else {
    date -= out[7] * 1000.0
  }
  return timeClip(date)
}
