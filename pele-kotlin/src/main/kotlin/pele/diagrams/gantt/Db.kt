// Ported from Pele src/diagrams/gantt/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsNumber
import pele.js.jsParseFloat
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsSplitStrings
import pele.js.jsTrim
import pele.svg.Accessible
import pele.util.safeUrl

class GanttDbOptions(val warn: ((String) -> Unit)? = null)

private val RE_NUMERIC_FORMAT = JsRegExp("""^[YMDHhmsSZXx\-/.:, T]*$""")
private val RE_TAG = JsRegExp("""^\s*(active|done|crit|milestone|vert)\s*$""")
private val RE_AFTER = JsRegExp("""^after\s+([\d\w- ]+)""")
private val RE_UNTIL = JsRegExp("""^until\s+([\d\w- ]+)""")
private val RE_DURATION = JsRegExp("""^(\d+(?:\.\d+)?)([Mdhmswy]|ms)$""")
private val RE_TOKEN_SPLIT = JsRegExp("""[\s,]+""")
private val RE_DIGITS = JsRegExp("""^\d+$""")
private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")
private val DAY_NAMES: Set<String> = WEEKDAYS.map { it.lowercase() }.toSet()

// Scheduling walks tasks day by day and parses dates with a format the diagram supplies. This
// bounds the
// total, in rough character operations, so a hostile diagram fails instead of hanging.
private const val WORK_LIMIT = 4e8
private const val DAY_STEP = 400
private const val DATED_STEP = 200
private const val FORMAT_STEP = 400

// The one place the current time is read.
fun clock(now: Double? = null): Double {
  val given = now ?: Double.NaN
  return if (given.isFinite()) given else System.currentTimeMillis().toDouble()
}

fun parseDuration(str: String): Pair<Double, DateUnit> {
  val statement = RE_DURATION.exec(str.jsTrim())
  return if (statement != null) jsParseFloat(statement[1]!!) to statement[2]!!
  else Double.NaN to "ms"
}

private fun mergeTokens(existing: List<String>, txt: String): List<String> {
  val merged = LinkedHashSet(existing)
  for (token in txt.lowercase().jsSplitStrings(RE_TOKEN_SPLIT)) if (token != "") merged.add(token)
  return merged.toList()
}

// A missing date compares as NaN, so no comparison with it holds.
private fun timeOf(date: JsDate?): Double = date?.getTime() ?: Double.NaN

class GanttDb(private val options: GanttDbOptions = GanttDbOptions()) :
  GanttModel, GanttBuilder, Accessible {
  override val type = "gantt"
  override var title: String? = null
  override var accTitle: String? = null
    private set

  override var accDescr: String? = null
  override var dateFormat = ""
    private set

  override var axisFormat = ""
    private set

  override var tickInterval: String? = null
    private set

  override var todayMarker = ""
    private set

  override var includes: List<String> = ArrayList()
  override var excludes: List<String> = ArrayList()
  override var inclusiveEndDates = false
  override var topAxis = false
  override var displayMode = ""
  override var weekday: String? = null
    private set

  override var weekend = "saturday"
    private set

  override val sections: MutableList<String> = ArrayList()
  override val links: MutableMap<String, String> = LinkedHashMap()
  override val tasks: MutableList<GanttTask> = ArrayList()

  private val byId = LinkedHashMap<String, GanttTask>()
  private var currentSection = ""
  private var taskCount = 0
  private var lastOrder = 0
  private var lastTaskId: String? = null
  private var now = 0.0
  private var work = 0.0
  private var includeSet = LinkedHashSet<String>()
  private var excludeSet = LinkedHashSet<String>()
  private var excludedDays = BooleanArray(0)
  private var datedTokens = false

  override fun setDateFormat(txt: String) {
    dateFormat = txt
  }

  override fun enableInclusiveEndDates() {
    inclusiveEndDates = true
  }

  // Mermaid's grammar calls a method its database does not define, so `topAxis` crashes there.
  override fun TopAxis() {
    topAxis = true
  }

  override fun setAxisFormat(txt: String) {
    axisFormat = txt
  }

  override fun setTickInterval(txt: String) {
    tickInterval = txt
  }

  override fun setTodayMarker(txt: String) {
    todayMarker = txt
  }

  override fun setIncludes(txt: String) {
    includes = mergeTokens(includes, txt)
  }

  override fun setExcludes(txt: String) {
    excludes = mergeTokens(excludes, txt)
  }

  override fun setWeekday(txt: String) {
    weekday = txt
  }

  override fun setWeekend(txt: String) {
    weekend = txt
  }

  override fun setDiagramTitle(txt: String) {
    title = txt
  }

  override fun setAccTitle(txt: String) {
    accTitle = txt.jsReplace(RE_LEADING, "")
  }

  override fun setAccDescription(txt: String) {
    accDescr = txt.jsReplace(RE_INDENT, "\n")
  }

  override fun addSection(txt: String) {
    currentSection = txt
    sections.add(txt)
  }

  override fun addTask(descr: String, data: String) {
    val fields = (if (data.startsWith(":")) data.jsSlice(1) else data).jsSplit(",").toMutableList()
    val task =
      GanttTask(
        id = "",
        task = descr,
        section = currentSection,
        startTime = null,
        endTime = null,
        renderEndTime = null,
        manualEndTime = false,
        processed = false,
        active = false,
        done = false,
        crit = false,
        milestone = false,
        vert = false,
        order = -1,
        classes = ArrayList(),
        prevTaskId = lastTaskId,
        raw = GanttTaskRaw(data = data, start = null, end = ""),
      )

    while (fields.size > 0) {
      val tag = RE_TAG.exec(fields[0]) ?: break
      when (tag[1]) {
        "active" -> task.active = true
        "done" -> task.done = true
        "crit" -> task.crit = true
        "milestone" -> task.milestone = true
        "vert" -> task.vert = true
      }
      fields.removeAt(0)
    }
    // Mermaid fails with a TypeError on both of these.
    if (fields.size == 0) {
      throw PeleError(
        "Task \"${descr.jsTrim()}\" needs a duration or an end date.",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "gantt"),
      )
    }
    if (fields.size > 3) {
      throw PeleError(
        "Task \"${descr.jsTrim()}\" has too many fields. A task takes an id, a start, and an end or duration.",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "gantt"),
      )
    }
    for (i in fields.indices) fields[i] = fields[i].jsTrim()

    if (fields.size == 3) {
      task.id = fields[0]
    } else {
      taskCount++
      task.id = "task$taskCount"
    }
    if (fields.size > 1) task.raw.start = fields[fields.size - 2]
    task.raw.end = fields[fields.size - 1]

    if (!task.vert) task.order = lastOrder++
    tasks.add(task)
    lastTaskId = task.id
    byId[task.id] = task
  }

  override fun setLink(ids: String, link: String) {
    val href = safeUrl(link)
    for (id in ids.jsSplit(",")) {
      if (byId.containsKey(id)) links[id] = href
    }
    setClass(ids, "clickable")
  }

  // Callbacks are recognized and never run; the task is only marked clickable, as in Mermaid.
  override fun setClickEvent(ids: String, functionName: String, functionArgs: String?) {
    setClass(ids, "clickable")
  }

  fun setClass(ids: String, className: String) {
    for (id in ids.jsSplit(",")) byId[id]?.classes?.add(className)
  }

  // Resolves every task's start and end, from scratch on each call. A task may refer to tasks
  // defined
  // after it, so, like Mermaid, this makes up to eleven passes, each of which schedules every task
  // again.
  override fun getTasks(now: Double?): List<GanttTask> {
    this.now = clock(now)
    work = 0.0
    includeSet = LinkedHashSet(includes)
    excludeSet = LinkedHashSet(excludes)
    val weekendStart =
      if (weekend == "friday") 5.0 else if (weekend == "saturday") 6.0 else Double.NaN
    val weekends = excludeSet.contains("weekends")
    excludedDays =
      BooleanArray(7) { day ->
        val iso = if (day != 0) day else 7
        (weekends && (iso.toDouble() == weekendStart || iso.toDouble() == weekendStart + 1)) ||
          excludeSet.contains(WEEKDAYS[day].lowercase())
      }
    // A date written with only these tokens and separators can never read as a weekday name, so
    // when
    // nothing else is excluded or included there is no need to format each day.
    datedTokens = includes.size > 0 || !RE_NUMERIC_FORMAT.test(dateFormat.jsTrim())
    for (token in excludes) if (token != "weekends" && !DAY_NAMES.contains(token))
      datedTokens = true

    for (task in tasks) {
      task.startTime = null
      task.endTime = null
      task.renderEndTime = null
      task.processed = false
      task.manualEndTime = false
    }

    var allProcessed = compileTasks()
    var i = 0
    while (!allProcessed && i < 10) {
      allProcessed = compileTasks()
      i++
    }
    return tasks
  }

  // Whether a day is excluded. A day matches a token by its weekday name, by its date as
  // YYYY-MM-DD,
  // or by its date written in the chart's own format.
  fun isInvalidDate(date: JsDate): Boolean {
    val day = date.getDay()
    val weekday = if (day.isNaN()) false else excludedDays[day.toInt()]
    if (!datedTokens) return weekday
    val template = dateFormat.jsTrim()
    val dateOnly = isoDate(date)
    var formatted: String? = dateOnly
    spend(DATED_STEP.toDouble())
    if (template != "YYYY-MM-DD") {
      spend(FORMAT_STEP + template.length * 8.0)
      formatted = format(date, template)
    }
    val text = formatted
    val listed = { set: Set<String> ->
      set.contains(dateOnly) || (text != null && set.contains(text))
    }
    if (listed(includeSet)) return false
    return weekday || listed(excludeSet)
  }

  private fun spend(units: Double) {
    work += units
    if (work > WORK_LIMIT) {
      throw PeleError(
        "The gantt chart takes too much work to schedule.",
        PeleErrorCode.LIMIT,
        PeleErrorInfo(type = "gantt"),
      )
    }
  }

  private fun parse(str: String, template: String): JsDate? {
    spend((template.length + 1.0) * (str.length + 1.0))
    return parseStrict(str, template, JsDate(now))
  }

  private fun today(): JsDate {
    val today = JsDate(now)
    today.setHours(0.0, 0.0, 0.0, 0.0)
    return today
  }

  private fun warn(message: () -> String) {
    val warn = options.warn
    if (warn != null) warn(message())
  }

  private fun compileTasks(): Boolean {
    var allProcessed = true
    for (task in tasks) {
      compileTask(task)
      allProcessed = allProcessed && task.processed
    }
    return allProcessed
  }

  private fun compileTask(task: GanttTask) {
    val start = task.raw.start
    if (start == null) {
      // Mermaid fails with a TypeError when the first task has no start. It starts today here.
      val previous = if (task.prevTaskId == null) null else byId[task.prevTaskId!!]
      task.startTime = if (previous != null) previous.endTime else today()
    } else {
      val startTime = getStartDate(start)
      if (startTime != null) task.startTime = startTime
    }
    if (task.startTime == null) return
    task.endTime = getEndDate(task.startTime!!, task.raw.end)
    if (task.endTime == null) return
    task.processed = true
    task.manualEndTime = parse(task.raw.end, "YYYY-MM-DD") != null
    checkTaskDates(task)
  }

  private fun references(keyword: String, list: String): List<GanttTask> {
    val found = ArrayList<GanttTask>()
    var unknown: MutableList<String>? = null
    for (id in list.jsSplit(" ")) {
      if (id == "") continue
      val task = byId[id]
      if (task != null) {
        found.add(task)
      } else {
        if (unknown == null) unknown = ArrayList()
        unknown.add(id)
      }
    }
    if (unknown != null) {
      val ids: List<String> = unknown
      warn {
        "Gantt: \"$keyword\" refers to task ids that do not exist: ${ids.joinToString(", ")}."
      }
    }
    return found
  }

  private fun getStartDate(raw: String): JsDate? {
    val str = raw.jsTrim()
    val template = dateFormat.jsTrim()

    if ((template == "x" || template == "X") && RE_DIGITS.test(str))
      return checked(JsDate(jsNumber(str)), str)

    val after = RE_AFTER.exec(str)
    if (after != null) {
      val tasks = references("after", after[1]!!)
      var latest: GanttTask? = null
      for (task in tasks) {
        if (latest == null || timeOf(task.endTime) > timeOf(latest.endTime)) latest = task
      }
      return if (latest != null) latest.endTime else today()
    }

    val parsed = parse(str, template)
    if (parsed != null) return parsed

    // What does not fit the format gets a second chance with the JavaScript date parser, as in
    // Mermaid.
    val date = JsDate(str)
    if (!isValid(date) || date.getFullYear() < -10000 || date.getFullYear() > 10000) {
      throw PeleError("Invalid date:$str", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "gantt"))
    }
    return date
  }

  private fun checked(date: JsDate, str: String): JsDate {
    if (!isValid(date))
      throw PeleError("Invalid date:$str", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "gantt"))
    return date
  }

  private fun getEndDate(startTime: JsDate, raw: String): JsDate? {
    val str = raw.jsTrim()

    val until = RE_UNTIL.exec(str)
    if (until != null) {
      val tasks = references("until", until[1]!!)
      var earliest: GanttTask? = null
      for (task in tasks) {
        if (earliest == null || timeOf(task.startTime) < timeOf(earliest.startTime)) earliest = task
      }
      return if (earliest != null) earliest.startTime else today()
    }

    val template = dateFormat.jsTrim()
    val parsed = parse(str, template)
    if (parsed != null) return if (inclusiveEndDates) addDays(parsed, 1.0) else parsed

    val (value, unit) = parseDuration(str)
    if (value.isNaN()) {
      warn {
        "Gantt: \"$str\" is neither a date in the \"$template\" format nor a duration such as \"3d\", so the task gets no duration."
      }
      return JsDate(startTime)
    }
    val end = add(startTime, value, unit)
    return if (isValid(end)) end else JsDate(startTime)
  }

  // Pushes the end of a task out by one day for every excluded day it covers.
  private fun checkTaskDates(task: GanttTask) {
    if (excludes.size == 0 || task.manualEndTime) return
    val cursor = addDays(task.startTime!!, 1.0)
    val endTime = JsDate(task.endTime!!)
    val maxEndTime = addDays(endTime, 10000.0).getTime()
    var invalid = false
    var renderEndTime: Double? = null
    while (cursor.getTime() <= endTime.getTime()) {
      spend(DAY_STEP.toDouble())
      if (!invalid) renderEndTime = endTime.getTime()
      invalid = isInvalidDate(cursor)
      if (invalid) {
        endTime.setDate(endTime.getDate() + 1)
        if (endTime.getTime() > maxEndTime) {
          throw PeleError(
            "Failed to find a valid date that was not excluded by `excludes` after 10,000 iterations.",
            PeleErrorCode.SEMANTIC,
            PeleErrorInfo(type = "gantt"),
          )
        }
      }
      cursor.setDate(cursor.getDate() + 1)
    }
    task.endTime = endTime
    task.renderEndTime = if (renderEndTime == null) null else JsDate(renderEndTime)
  }
}
