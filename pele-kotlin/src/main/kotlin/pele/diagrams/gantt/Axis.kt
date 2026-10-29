// Ported from Pele src/diagrams/gantt/axis.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

import java.lang.StrictMath.log10
import java.lang.StrictMath.pow
import kotlin.math.floor
import kotlin.math.sqrt
import pele.js.JsRegExp
import pele.js.jsParseInt
import pele.js.jsSlice
import pele.js.jsStr
import pele.js.truthy

// Time axis support, following d3-time and d3-time-format (English locale, local time), which
// Mermaid uses:
// the `axisFormat` specifiers, tick intervals with d3's `every` rule, and its choice of automatic
// ticks.

private const val SECOND = 1000.0
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY
private const val MONTH = 30 * DAY
private const val YEAR = 365 * DAY

// 'millisecond' | 'second' | 'minute' | 'hour' | 'day' | 'week' | 'month' | 'year'
typealias TickUnit = String

// floor and offset change the date in place. `field` is the value d3 tests against a step: a tick
// falls where it is a multiple of the step. `cycle` is how many values `field` cycles through,
// which
// bounds how far apart filtered ticks can be.
private data class Calendar(
  val floor: (JsDate) -> Unit,
  val offset: (JsDate, Double) -> Unit,
  val field: ((JsDate) -> Double)?,
  val cycle: Double,
  val ms: Double,
)

private fun startOfDay(d: JsDate) {
  d.setHours(0.0, 0.0, 0.0, 0.0)
}

private fun weekCalendar(weekStart: Double): Calendar =
  Calendar(
    floor = { d ->
      d.setDate(d.getDate() - ((d.getDay() + 7 - weekStart) % 7))
      startOfDay(d)
    },
    offset = { d, step -> d.setDate(d.getDate() + step * 7) },
    field = null,
    cycle = Double.POSITIVE_INFINITY,
    ms = WEEK,
  )

private val CALENDARS: Map<String, Calendar> =
  mapOf(
    "millisecond" to
      Calendar(
        {},
        { d, step -> d.setTime(d.getTime() + step) },
        null,
        Double.POSITIVE_INFINITY,
        1.0,
      ),
    "second" to
      Calendar(
        { d -> d.setTime(d.getTime() - d.getMilliseconds()) },
        { d, step -> d.setTime(d.getTime() + step * SECOND) },
        { d -> d.getUTCSeconds() },
        60.0,
        SECOND,
      ),
    "minute" to
      Calendar(
        { d -> d.setTime(d.getTime() - d.getMilliseconds() - d.getSeconds() * SECOND) },
        { d, step -> d.setTime(d.getTime() + step * MINUTE) },
        { d -> d.getMinutes() },
        60.0,
        MINUTE,
      ),
    "hour" to
      Calendar(
        { d ->
          d.setTime(
            d.getTime() - d.getMilliseconds() - d.getSeconds() * SECOND - d.getMinutes() * MINUTE
          )
        },
        { d, step -> d.setTime(d.getTime() + step * HOUR) },
        { d -> d.getHours() },
        24.0,
        HOUR,
      ),
    "day" to
      Calendar(
        ::startOfDay,
        { d, step -> d.setDate(d.getDate() + step) },
        { d -> d.getDate() - 1 },
        31.0,
        DAY,
      ),
    "month" to
      Calendar(
        { d ->
          d.setDate(1.0)
          startOfDay(d)
        },
        { d, step -> d.setMonth(d.getMonth() + step) },
        { d -> d.getMonth() },
        12.0,
        MONTH,
      ),
    "year" to
      Calendar(
        { d ->
          d.setMonth(0.0, 1.0)
          startOfDay(d)
        },
        { d, step -> d.setFullYear(d.getFullYear() + step) },
        null,
        Double.POSITIVE_INFINITY,
        YEAR,
      ),
  )

// Whole units between two floored dates, discounting a change of UTC offset between them.
private fun span(start: JsDate, end: JsDate, unit: Double): Double =
  floor(
    (end.getTime() -
      start.getTime() -
      (end.getTimezoneOffset() - start.getTimezoneOffset()) * MINUTE) / unit
  )

private fun mod(a: Double, b: Double): Double = ((a % b) + b) % b

// The interval d3's `unit.every(step)` gives.
private fun every(unit: TickUnit, step: Double, weekStart: Double): Calendar {
  val base = if (unit == "week") weekCalendar(weekStart) else CALENDARS[unit]!!
  if (step <= 1) return base
  if (unit == "millisecond") {
    return base.copy(
      floor = { d -> d.setTime(floor(d.getTime() / step) * step) },
      offset = { d, n -> d.setTime(d.getTime() + n * step) },
    )
  }
  if (unit == "year") {
    return base.copy(
      floor = { d ->
        d.setFullYear(floor(d.getFullYear() / step) * step)
        base.floor(d)
      },
      offset = { d, n -> d.setFullYear(d.getFullYear() + n * step) },
    )
  }
  if (unit == "week") {
    val epoch = JsDate(0.0)
    base.floor(epoch)
    return base.copy(
      floor = { d ->
        base.floor(d)
        base.offset(d, -mod(span(epoch, d, WEEK), step))
      },
      offset = { d, n -> base.offset(d, n * step) },
    )
  }
  val field = base.field!!
  val test = { d: JsDate -> field(d) % step == 0.0 }
  return base.copy(
    floor = { d ->
      // Date overflow makes field(d) NaN, which can never match the interval.
      base.floor(d)
      while (d.getTime().isFinite() && !test(d)) {
        d.setTime(d.getTime() - 1)
        base.floor(d)
      }
    },
    offset = { d, n ->
      var i = 0
      while (i < n && d.getTime().isFinite()) {
        do {
          base.offset(d, 1.0)
        } while (d.getTime().isFinite() && !test(d))
        i++
      }
    },
  )
}

// Ticks from start to stop inclusive, or null when there would be more than `limit`.
private fun range(calendar: Calendar, start: Double, stop: Double, limit: Double): List<Double>? {
  val d = JsDate(start - 1)
  calendar.floor(d)
  calendar.offset(d, 1.0)
  calendar.floor(d)
  val out = ArrayList<Double>()
  var previous = Double.NEGATIVE_INFINITY
  while (d.getTime() <= stop && d.getTime() > previous) {
    if (out.size >= limit) return null
    previous = d.getTime()
    out.add(previous)
    calendar.offset(d, 1.0)
    calendar.floor(d)
  }
  return out
}

private fun tickStep(start: Double, stop: Double, count: Double): Double {
  val step = (stop - start) / maxOf(0.0, count)
  val power = floor(log10(step))
  val error = step / pow(10.0, power)
  val factor =
    if (error >= sqrt(50.0)) 10
    else if (error >= sqrt(10.0)) 5 else if (error >= 1.4142135623730951) 2 else 1
  return pow(10.0, power) * factor
}

private class Auto(val unit: TickUnit, val step: Double, val ms: Double)

private val AUTO =
  listOf(
    Auto("second", 1.0, SECOND),
    Auto("second", 5.0, 5 * SECOND),
    Auto("second", 15.0, 15 * SECOND),
    Auto("second", 30.0, 30 * SECOND),
    Auto("minute", 1.0, MINUTE),
    Auto("minute", 5.0, 5 * MINUTE),
    Auto("minute", 15.0, 15 * MINUTE),
    Auto("minute", 30.0, 30 * MINUTE),
    Auto("hour", 1.0, HOUR),
    Auto("hour", 3.0, 3 * HOUR),
    Auto("hour", 6.0, 6 * HOUR),
    Auto("hour", 12.0, 12 * HOUR),
    Auto("day", 1.0, DAY),
    Auto("day", 2.0, 2 * DAY),
    Auto("week", 1.0, WEEK),
    Auto("month", 1.0, MONTH),
    Auto("month", 3.0, 3 * MONTH),
    Auto("year", 1.0, YEAR),
  )

// About `count` ticks at round times, chosen the way d3's time scale chooses them.
fun autoTicks(start: Double, stop: Double, count: Double): List<Double> {
  val target = (stop - start) / count
  var i = 0
  while (i < AUTO.size && AUTO[i].ms <= target) i++
  val calendar: Calendar
  if (i == AUTO.size) {
    calendar = every("year", maxOf(1.0, tickStep(start / YEAR, stop / YEAR, count)), 0.0)
  } else if (i == 0) {
    calendar = every("millisecond", maxOf(tickStep(start, stop, count), 1.0), 0.0)
  } else {
    val pick = AUTO[if (target / AUTO[i - 1].ms < AUTO[i].ms / target) i - 1 else i]
    calendar = every(pick.unit, pick.step, 0.0)
  }
  return range(calendar, start, stop, 4 * count + 8) ?: emptyList()
}

private val RE_INTERVAL =
  JsRegExp("""^([1-9]\d*)(millisecond|second|minute|hour|day|week|month)$""")
private val WEEK_STARTS =
  listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

// Ticks for a `tickInterval` such as `1week`, or null when it is not one or asks for more than
// `limit`.
fun intervalTicks(
  interval: String,
  weekday: String,
  start: Double,
  stop: Double,
  limit: Double,
): List<Double>? {
  val m = RE_INTERVAL.exec(interval) ?: return null
  val step = jsParseInt(m[1]!!, 10)
  val unit: TickUnit = m[2]!!
  val calendar = every(unit, step, maxOf(0, WEEK_STARTS.indexOf(weekday)).toDouble())
  if ((stop - start) / (minOf(step, calendar.cycle) * calendar.ms) > limit) return null
  return range(calendar, start, stop, limit)
}

private fun pad(value: Double, fill: String, width: Int): String {
  val sign = if (value < 0) "-" else ""
  val digits = jsStr(if (sign != "") -value else value)
  return sign + (if (digits.length < width) fill.repeat(width - digits.length) + digits else digits)
}

private fun floored(date: JsDate, calendar: Calendar): JsDate {
  val d = JsDate(date.getTime())
  calendar.floor(d)
  return d
}

// Weeks since the last `weekStart` day before the year began, which is d3's %U and %W.
private fun weekOfYear(date: JsDate, weekStart: Double): Double {
  val week = weekCalendar(weekStart)
  val yearStart = floored(date, CALENDARS["year"]!!)
  return span(floored(JsDate(yearStart.getTime() - 1), week), floored(date, week), WEEK)
}

// The Thursday that decides which ISO week and ISO year a date belongs to.
private fun isoThursday(date: JsDate): JsDate {
  val thursday = weekCalendar(4.0)
  val day = date.getDay()
  val d = floored(if (day >= 4 || day == 0.0) date else JsDate(date.getTime() - 1), thursday)
  if (!(day >= 4 || day == 0.0)) {
    thursday.offset(d, 1.0)
    thursday.floor(d)
  }
  return d
}

private val PADS = mapOf("-" to "", "_" to " ", "0" to "0")

// Formats a date with a d3 time format specifier such as `%Y-%m-%d`.
fun timeFormat(specifier: String, date: JsDate): String {
  val out = StringBuilder()
  var from = 0
  val n = specifier.length
  var i = 0
  while (i < n) {
    if (specifier[i].code != 37) {
      i++
      continue
    }
    out.append(specifier.jsSlice(from, i))
    var c = specifier.getOrNull(++i)?.toString() ?: ""
    var fill = PADS[c]
    if (fill != null) {
      c = specifier.getOrNull(++i)?.toString() ?: ""
    } else {
      fill = if (c == "e") " " else "0"
    }
    out.append(directive(c, date, fill))
    from = i + 1
    i++
  }
  return out.toString() + specifier.jsSlice(from)
}

private fun directive(c: String, d: JsDate, p: String): String {
  when (c) {
    "a" -> return WEEKDAYS[d.getDay().toInt()].jsSlice(0, 3)
    "A" -> return WEEKDAYS[d.getDay().toInt()]
    "b" -> return MONTHS[d.getMonth().toInt()].jsSlice(0, 3)
    "B" -> return MONTHS[d.getMonth().toInt()]
    "c" -> return timeFormat("%x, %X", d)
    "d",
    "e" -> return pad(d.getDate(), p, 2)
    "f" -> return pad(d.getMilliseconds(), p, 3) + "000"
    "g" -> return pad(isoThursday(d).getFullYear() % 100, p, 2)
    "G" -> return pad(isoThursday(d).getFullYear() % 10000, p, 4)
    "H" -> return pad(d.getHours(), p, 2)
    "I" -> {
      val h = d.getHours() % 12
      return pad(if (truthy(h)) h else 12.0, p, 2)
    }
    "j" ->
      return pad(
        1 + span(floored(d, CALENDARS["year"]!!), floored(d, CALENDARS["day"]!!), DAY),
        p,
        3,
      )
    "L" -> return pad(d.getMilliseconds(), p, 3)
    "m" -> return pad(d.getMonth() + 1, p, 2)
    "M" -> return pad(d.getMinutes(), p, 2)
    "p" -> return if (d.getHours() >= 12) "PM" else "AM"
    "q" -> return jsStr(1 + floor(d.getMonth() / 3))
    "Q" -> return jsStr(d.getTime())
    "s" -> return jsStr(floor(d.getTime() / 1000))
    "S" -> return pad(d.getSeconds(), p, 2)
    "u" -> return jsStr(if (d.getDay() != 0.0 && !d.getDay().isNaN()) d.getDay() else 7.0)
    "U" -> return pad(weekOfYear(d, 0.0), p, 2)
    "V" -> {
      val thursday = isoThursday(d)
      val yearStart = floored(thursday, CALENDARS["year"]!!)
      val first = floored(yearStart, weekCalendar(4.0))
      return pad(span(first, thursday, WEEK) + (if (yearStart.getDay() == 4.0) 1 else 0), p, 2)
    }
    "w" -> return jsStr(d.getDay())
    "W" -> return pad(weekOfYear(d, 1.0), p, 2)
    "x" -> return timeFormat("%-m/%-d/%Y", d)
    "X" -> return timeFormat("%-I:%M:%S %p", d)
    "y" -> return pad(d.getFullYear() % 100, p, 2)
    "Y" -> return pad(d.getFullYear() % 10000, p, 4)
    "Z" -> {
      val z = d.getTimezoneOffset()
      val abs = Math.abs(z)
      return (if (z > 0) "-" else "+") + pad(floor(abs / 60), "0", 2) + pad(abs % 60, "0", 2)
    }
    "%" -> return "%"
    else -> return c
  }
}
