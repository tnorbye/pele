// Ported from Pele src/diagrams/gantt/dates.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

import pele.js.JsRegExp
import pele.js.jsMatch
import pele.js.jsMatchGlobal
import pele.js.jsNumber
import pele.js.jsReplace
import pele.js.jsRound
import pele.js.jsSlice
import pele.js.jsStr
import pele.js.truthy
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor

// The parts of dayjs 1.11 that Mermaid's gantt code relies on, with the plugins it loads (custom parse format,
// advanced format, ISO week) and the English locale: strict parsing against a format, formatting, and calendar
// arithmetic, all in local time. Quirks are kept, because a date is accepted only when formatting the parsed
// result gives back the input.

val MONTHS = listOf(
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
)
val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private const val DAY = 86400000.0
private const val WEEK = 7 * DAY

// PORT: named DateUnit because Unit is kotlin.Unit. 'ms' | 's' | 'm' | 'h' | 'd' | 'w' | 'M' | 'y'
typealias DateUnit = String

fun isValid(date: JsDate): Boolean = !date.getTime().isNaN()

private fun pad(value: Double, length: Int): String {
  val s = jsStr(value)
  return if (s.length >= length) s else "0".repeat(length - s.length) + s
}

private fun pad(value: Int, length: Int): String = pad(value.toDouble(), length)

private fun ordinal(n: Int): String {
  val s = listOf("th", "st", "nd", "rd")
  val v = n % 100
  // s[(v - 20) % 10] is undefined for a negative index, and so is s[v] past the end.
  return jsStr(n) + (s.getOrNull((v - 20) % 10) ?: s.getOrNull(v) ?: s[0])
}

private fun utcOffset(date: JsDate): Double {
  val offset = -jsRound(date.getTimezoneOffset() / 15) * 15
  return if (offset == 0.0) 0.0 else offset
}

private fun zone(date: JsDate): String {
  val offset = utcOffset(date)
  val minutes = Math.abs(offset)
  return (if (offset >= 0) "+" else "-") + pad(floor(minutes / 60), 2) + ":" + pad(minutes % 60, 2)
}

fun isoWeekday(date: JsDate): Double {
  val day = date.getDay()
  return if (truthy(day)) day else 7.0
}

fun addDays(date: JsDate, days: Double): JsDate {
  val next = JsDate(date)
  next.setDate(next.getDate() + days)
  return next
}

private fun daysInMonth(date: JsDate): Double {
  val last = JsDate.local(date.getFullYear(), date.getMonth() + 1, 0.0)
  last.setHours(23.0, 59.0, 59.0, 999.0)
  return last.getDate()
}

// Sets the month or the year, keeping the day of the month unless the new month is shorter.
private fun setCalendar(date: JsDate, apply: (JsDate) -> Unit): JsDate {
  val next = JsDate(date)
  next.setDate(1.0)
  apply(next)
  next.setDate(minOf(date.getDate(), daysInMonth(next)))
  return next
}

fun add(date: JsDate, amount: Double, unit: DateUnit): JsDate {
  when (unit) {
    "M" -> return setCalendar(date) { d -> d.setMonth(date.getMonth() + amount) }
    "y" -> return setCalendar(date) { d -> d.setFullYear(date.getFullYear() + amount) }
    "d" -> return addDays(date, jsRound(amount))
    "w" -> return addDays(date, jsRound(7 * amount))
    "h" -> return JsDate(date.getTime() + amount * 3600000)
    "m" -> return JsDate(date.getTime() + amount * 60000)
    "s" -> return JsDate(date.getTime() + amount * 1000)
    else -> return JsDate(date.getTime() + amount)
  }
}

private fun weekThursday(date: JsDate): JsDate = addDays(date, 4 - isoWeekday(date))

private fun isoWeek(date: JsDate): Double {
  val thursday = weekThursday(date)
  val jan1 = JsDate.local(thursday.getFullYear(), 0.0, 1.0)
  val weekday = isoWeekday(jan1)
  val first = addDays(jan1, 4 - weekday + (if (weekday > 4) 7 else 0))
  val zoneDelta = (utcOffset(first) - utcOffset(thursday)) * 60000
  val weeks = (thursday.getTime() - first.getTime() - zoneDelta) / WEEK
  // Math.ceil(weeks) || 0
  val whole = if (weeks < 0) ceil(weeks).let { if (truthy(it)) it else 0.0 } else floor(weeks)
  return whole + 1
}

private val RE_ADVANCED = JsRegExp("""\[([^\]]+)]|Q|wo|ww|w|WW|W|zzz|z|gggg|GGGG|Do|X|x|k{1,2}|S""", "g")
private val RE_FORMAT = JsRegExp("""\[([^\]]+)]|YYYY|YY|M{1,4}|D{1,2}|d{1,4}|H{1,2}|h{1,2}|a|A|m{1,2}|s{1,2}|Z{1,2}|SSS""", "g")
private const val DEFAULT_FORMAT = "YYYY-MM-DDTHH:mm:ssZ"

// Returns null for the week-of-year and time zone name tokens, which need dayjs plugins Mermaid
// does not load and so fail there.
fun format(date: JsDate, template: String): String? {
  if (!isValid(date)) return "Invalid Date"
  val str = if (template.isNotEmpty()) template else DEFAULT_FORMAT
  if (str == "YYYY-MM-DD") return isoDate(date)
  var unsupported = false
  val advanced = str.jsReplace(RE_ADVANCED) { m ->
    val match = m.value
    when (match) {
      "Q" -> jsStr(ceil((date.getMonth() + 1) / 3))
      "Do" -> "[" + ordinal(date.getDate().toInt()) + "]"
      "GGGG" -> jsStr(weekThursday(date).getFullYear())
      "W" -> jsStr(isoWeek(date))
      "WW" -> pad(isoWeek(date), 2)
      "k" -> jsStr(if (truthy(date.getHours())) date.getHours() else 24.0)
      "kk" -> pad(if (truthy(date.getHours())) date.getHours() else 24.0, 2)
      "X" -> jsStr(floor(date.getTime() / 1000))
      "x" -> jsStr(date.getTime())
      "gggg", "wo", "w", "ww", "z", "zzz" -> {
        unsupported = true
        match
      }
      else -> match
    }
  }
  if (unsupported) return null
  val hours = date.getHours()
  return advanced.jsReplace(RE_FORMAT) { m ->
    val literal = m[1]
    if (!literal.isNullOrEmpty()) {
      literal
    } else {
      when (m.value) {
        "YY" -> jsStr(date.getFullYear()).jsSlice(-2)
        "YYYY" -> pad(date.getFullYear(), 4)
        "M" -> jsStr(date.getMonth() + 1)
        "MM" -> pad(date.getMonth() + 1, 2)
        "MMM" -> MONTHS[date.getMonth().toInt()].jsSlice(0, 3)
        "MMMM" -> MONTHS[date.getMonth().toInt()]
        "D" -> jsStr(date.getDate())
        "DD" -> pad(date.getDate(), 2)
        "d" -> jsStr(date.getDay())
        "dd" -> WEEKDAYS[date.getDay().toInt()].jsSlice(0, 2)
        "ddd" -> WEEKDAYS[date.getDay().toInt()].jsSlice(0, 3)
        "dddd" -> WEEKDAYS[date.getDay().toInt()]
        "H" -> jsStr(hours)
        "HH" -> pad(hours, 2)
        "h" -> jsStr(if (truthy(hours % 12)) hours % 12 else 12.0)
        "hh" -> pad(if (truthy(hours % 12)) hours % 12 else 12.0, 2)
        "a" -> if (hours < 12) "am" else "pm"
        "A" -> if (hours < 12) "AM" else "PM"
        "m" -> jsStr(date.getMinutes())
        "mm" -> pad(date.getMinutes(), 2)
        "s" -> jsStr(date.getSeconds())
        "ss" -> pad(date.getSeconds(), 2)
        "SSS" -> pad(date.getMilliseconds(), 3)
        "Z" -> zone(date)
        else -> zone(date).jsReplace(":", "")
      }
    }
  }
}

fun isoDate(date: JsDate): String {
  val year = date.getFullYear()
  val month = date.getMonth() + 1
  val day = date.getDate()
  return (if (year >= 1000 && year <= 9999) jsStr(year) else pad(year, 4)) + (if (month < 10) "-0" else "-") + jsStr(month) +
    (if (day < 10) "-0" else "-") + jsStr(day)
}

private class Fields {
  var year: Double? = null
  var month: Double? = null
  var day: Double? = null
  var dayGiven: Boolean? = null
  var hours: Double? = null
  var minutes: Double? = null
  var seconds: Double? = null
  var milliseconds: Double? = null
  var week: Double? = null
  var offset: Double? = null
  var afternoon: Boolean? = null
}

private fun interface Reader {
  fun read(fields: Fields, input: String): Boolean
}

private sealed class Step {
  class Literal(val text: String) : Step()

  class Read(val re: JsRegExp, val read: Reader) : Step()
}

private val RE_TOKENS = JsRegExp("""(\[[^[]*\])|([-_:/.,()\s]+)|(A|a|Q|YYYY|YY?|ww?|MM?M?M?|Do|DD?|hh?|HH?|mm?|ss?|S{1,3}|z|ZZ?)""", "g")
private val RE_LITERAL = JsRegExp("""\[[^\]]+]""", "g")
private val RE_BRACKETS = JsRegExp("""^\[|\]$""", "g")
private val MATCH_1 = JsRegExp("""\d""")
private val MATCH_2 = JsRegExp("""\d\d""")
private val MATCH_3 = JsRegExp("""\d{3}""")
private val MATCH_4 = JsRegExp("""\d{4}""")
private val MATCH_1_TO_2 = JsRegExp("""\d\d?""")
private val MATCH_SIGNED = JsRegExp("""[+-]?\d+""")
private val MATCH_OFFSET = JsRegExp("""[+-]\d\d:?(\d\d)?|Z""")
private val MATCH_WORD = JsRegExp("""\d*[^-_:/,()\s\d]+""")
private val RE_OFFSET_PARTS = JsRegExp("""([+-]|\d\d)""", "g")
private val RE_DIGITS = JsRegExp("""\d+""")
private val RE_LOCALIZED = JsRegExp("""[Ll]""")

private fun field(set: (Fields, Double) -> Unit): Reader = Reader { fields, input ->
  set(fields, jsNumber(input))
  true
}

private val readOffset = Reader { fields, input ->
  if (input == "Z") {
    fields.offset = 0.0
  } else {
    val parts = input.jsMatchGlobal(RE_OFFSET_PARTS)!!
    // +parts[2] || 0
    val third = parts.getOrNull(2)?.let { jsNumber(it) }
    val minutes = jsNumber(parts[1]) * 60 + (if (third != null && truthy(third)) third else 0.0)
    fields.offset = if (minutes == 0.0) 0.0 else if (parts[0] == "+") -minutes else minutes
  }
  true
}

private fun monthName(names: List<String>): Reader = Reader { fields, input ->
  val index = names.indexOf(input) + 1
  if (index < 1) {
    false
  } else {
    fields.month = (if (index % 12 != 0) index % 12 else index).toDouble()
    true
  }
}

private fun step(re: JsRegExp, read: Reader) = Step.Read(re, read)

private val READERS: Map<String, Step.Read> = mapOf(
  "A" to step(MATCH_WORD) { f, input -> f.afternoon = input == "PM"; true },
  "a" to step(MATCH_WORD) { f, input -> f.afternoon = input == "pm"; true },
  "Q" to step(MATCH_1) { f, input -> f.month = (jsNumber(input) - 1) * 3 + 1; true },
  "S" to step(MATCH_1) { f, input -> f.milliseconds = jsNumber(input) * 100; true },
  "SS" to step(MATCH_2) { f, input -> f.milliseconds = jsNumber(input) * 10; true },
  "SSS" to step(MATCH_3) { f, input -> f.milliseconds = jsNumber(input); true },
  "s" to step(MATCH_1_TO_2, field { f, v -> f.seconds = v }),
  "ss" to step(MATCH_1_TO_2, field { f, v -> f.seconds = v }),
  "m" to step(MATCH_1_TO_2, field { f, v -> f.minutes = v }),
  "mm" to step(MATCH_1_TO_2, field { f, v -> f.minutes = v }),
  "H" to step(MATCH_1_TO_2, field { f, v -> f.hours = v }),
  "h" to step(MATCH_1_TO_2, field { f, v -> f.hours = v }),
  "HH" to step(MATCH_1_TO_2, field { f, v -> f.hours = v }),
  "hh" to step(MATCH_1_TO_2, field { f, v -> f.hours = v }),
  "D" to step(MATCH_1_TO_2, field { f, v -> f.day = v }),
  "DD" to step(MATCH_2, field { f, v -> f.day = v }),
  "Do" to step(MATCH_WORD) { f, input ->
    val digits = input.jsMatch(RE_DIGITS)
    if (digits == null) {
      false
    } else {
      // dayjs keeps the digits as a string here, so even "0" counts as a day having been given.
      f.day = jsNumber(digits[0]!!)
      f.dayGiven = true
      for (i in 1..31) if (ordinal(i) == input) f.day = i.toDouble()
      true
    }
  },
  "w" to step(MATCH_1_TO_2, field { f, v -> f.week = v }),
  "ww" to step(MATCH_2, field { f, v -> f.week = v }),
  "M" to step(MATCH_1_TO_2, field { f, v -> f.month = v }),
  "MM" to step(MATCH_2, field { f, v -> f.month = v }),
  "MMM" to step(MATCH_WORD, monthName(MONTHS.map { it.jsSlice(0, 3) })),
  "MMMM" to step(MATCH_WORD, monthName(MONTHS)),
  "Y" to step(MATCH_SIGNED, field { f, v -> f.year = v }),
  "YY" to step(MATCH_2) { f, input -> f.year = jsNumber(input) + (if (jsNumber(input) > 68) 1900 else 2000); true },
  "YYYY" to step(MATCH_4, field { f, v -> f.year = v }),
  "Z" to step(MATCH_OFFSET, readOffset),
  "ZZ" to step(MATCH_OFFSET, readOffset),
)

// PORT: the format and its steps live in one immutable pair that is swapped atomically.
private class Compiled(val format: String, val steps: List<Step>?)

private val compiled = AtomicReference<Compiled?>(null)

private fun compile(template: String): List<Step>? {
  val cached = compiled.get()
  if (cached != null && template == cached.format) return cached.steps
  val steps = compileSteps(template)
  compiled.set(Compiled(template, steps))
  return steps
}

private fun compileSteps(template: String): List<Step>? {
  // dayjs expands localized tokens through a locale table the English locale does not have, and fails.
  if (RE_LOCALIZED.test(template.jsReplace(RE_LITERAL, ""))) return null
  val tokens = template.jsMatchGlobal(RE_TOKENS) ?: return null
  return tokens.map { token -> READERS[token] ?: Step.Literal(token.jsReplace(RE_BRACKETS, "")) }
}

private fun loose(input: String, template: String, now: JsDate): JsDate? {
  if (template == "x" || template == "X") return JsDate((if (template == "X") 1000 else 1) * jsNumber(input))
  val steps = compile(template) ?: return null
  val f = Fields()
  var rest = input
  var start = 0
  for (step in steps) {
    if (step is Step.Literal) {
      start += step.text.length
      continue
    }
    step as Step.Read
    val match = step.re.exec(rest.jsSlice(start)) ?: return null
    if (!step.read.read(f, match[0]!!)) return null
    // dayjs drops the first occurrence of the matched text, wherever it is, and does not move `start`.
    rest = rest.jsReplace(match[0]!!, "")
  }
  val afternoon = f.afternoon
  val fHours = f.hours
  if (afternoon != null && fHours != null) {
    if (afternoon) {
      if (fHours < 12) f.hours = fHours + 12
    } else if (fHours == 12.0) {
      f.hours = 0.0
    }
  }
  val fDay = f.day
  val fYear = f.year
  val fMonth = f.month
  val yearTruthy = fYear != null && truthy(fYear)
  val monthTruthy = fMonth != null && truthy(fMonth)
  val day = if (f.dayGiven == true || (fDay != null && truthy(fDay))) fDay!! else if (!yearTruthy && !monthTruthy) now.getDate() else 1.0
  val year = if (yearTruthy) fYear!! else now.getFullYear()
  var month = 0.0
  if (!(yearTruthy && !monthTruthy)) month = if ((fMonth ?: Double.NaN) > 0) fMonth!! - 1 else now.getMonth()
  val hours = f.hours?.takeIf { truthy(it) } ?: 0.0
  val minutes = f.minutes?.takeIf { truthy(it) } ?: 0.0
  val seconds = f.seconds?.takeIf { truthy(it) } ?: 0.0
  val ms = f.milliseconds?.takeIf { truthy(it) } ?: 0.0
  val offset = f.offset
  if (offset != null) return JsDate(dateUtc(year, month, day, hours, minutes, seconds, ms + offset * 60000))
  // Applying a week number needs a dayjs plugin Mermaid does not load.
  val week = f.week
  if (week != null && truthy(week)) return null
  return JsDate.local(year, month, day, hours, minutes, seconds, ms)
}

// Parses the way `dayjs(input, format, true)` does. `now` supplies the fields the format leaves out.
fun parseStrict(input: String, template: String, now: JsDate): JsDate? {
  val date = loose(input, template, now)
  return if (date != null && isValid(date) && format(date, template) == input) date else null
}
