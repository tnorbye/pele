// Ported from Pele src/diagrams/gantt/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

import pele.syntaxError
import pele.js.jsSlice
import pele.js.jsTrim

// The calls the grammar's actions make, under Mermaid's names.
interface GanttBuilder {
  fun setDateFormat(txt: String)

  fun enableInclusiveEndDates()

  fun TopAxis()

  fun setAxisFormat(txt: String)

  fun setTickInterval(txt: String)

  fun setExcludes(txt: String)

  fun setIncludes(txt: String)

  fun setTodayMarker(txt: String)

  fun setWeekday(txt: String)

  fun setWeekend(txt: String)

  fun setDiagramTitle(txt: String)

  fun setAccTitle(txt: String)

  fun setAccDescription(txt: String)

  fun addSection(txt: String)

  fun addTask(descr: String, data: String)

  fun setClickEvent(ids: String, functionName: String, functionArgs: String?)

  fun setLink(ids: String, link: String)
}

// PORT: renamed because dates.ts's WEEKDAYS is a package-level name here.
private val WEEKDAY_NAMES = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

private const val LINE =
  "'EOF', 'SPACE', 'NL', 'weekday_monday', 'weekday_tuesday', 'weekday_wednesday', 'weekday_thursday', " +
    "'weekday_friday', 'weekday_saturday', 'weekday_sunday', 'weekend_friday', 'weekend_saturday', 'dateFormat', " +
    "'inclusiveEndDates', 'topAxis', 'axisFormat', 'tickInterval', 'excludes', 'includes', 'todayMarker', 'title', " +
    "'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'section', 'taskTxt', 'click'"

// Tokens that can start a line, which are also the only ones that can follow a complete statement.
private val STARTS_LINE: BooleanArray = BooleanArray(T.EOF + 1).also { starts ->
  for (t in T.weekday_monday..T.weekend_saturday) starts[t] = true
  val others = listOf(
    T.EOF,
    T.NL,
    T.dateFormat,
    T.inclusiveEndDates,
    T.topAxis,
    T.axisFormat,
    T.tickInterval,
    T.excludes,
    T.includes,
    T.todayMarker,
    T.title,
    T.acc_title,
    T.acc_descr,
    T.acc_descr_multiline_value,
    T.section,
    T.taskTxt,
    T.click,
  )
  for (t in others) starts[t] = true
}

fun parseGantt(src: String, db: GanttBuilder) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  fun fail(expected: String): Nothing {
    val t = types[i]
    throw syntaxError("gantt", src, starts[i], "Expecting $expected, got '${if (t == T.END) "1" else TOKEN_NAMES[t]}'")
  }

  // Mermaid's parser runs a statement's action only after seeing that the next token can follow it.
  fun end(also: String = "") {
    if (!STARTS_LINE[types[i]]) fail(LINE + also)
  }

  if (types[i] != T.gantt) fail("'gantt'")
  i++

  while (true) {
    val t = types[i]
    val text = texts[i]
    if (!STARTS_LINE[t]) fail(LINE)
    i++
    when (t) {
      T.EOF -> {
        if (types[i] == T.END) return
        end()
      }
      T.NL -> {}
      T.dateFormat -> {
        end()
        db.setDateFormat(text.jsSlice(11))
      }
      T.inclusiveEndDates -> {
        end()
        db.enableInclusiveEndDates()
      }
      T.topAxis -> {
        end()
        db.TopAxis()
      }
      T.axisFormat -> {
        end()
        db.setAxisFormat(text.jsSlice(11))
      }
      T.tickInterval -> {
        end()
        db.setTickInterval(text.jsSlice(13))
      }
      T.excludes -> {
        end()
        db.setExcludes(text.jsSlice(9))
      }
      T.includes -> {
        end()
        db.setIncludes(text.jsSlice(9))
      }
      T.todayMarker -> {
        end()
        db.setTodayMarker(text.jsSlice(12))
      }
      T.weekend_friday -> {
        end()
        db.setWeekend("friday")
      }
      T.weekend_saturday -> {
        end()
        db.setWeekend("saturday")
      }
      T.title -> {
        end()
        db.setDiagramTitle(text.jsSlice(6))
      }
      T.acc_title -> {
        if (types[i] != T.acc_title_value) fail("'acc_title_value'")
        val value = texts[i++]
        end()
        db.setAccTitle(value.jsTrim())
      }
      T.acc_descr -> {
        if (types[i] != T.acc_descr_value) fail("'acc_descr_value'")
        val value = texts[i++]
        end()
        db.setAccDescription(value.jsTrim())
      }
      T.acc_descr_multiline_value -> {
        end()
        db.setAccDescription(text.jsTrim())
      }
      T.section -> {
        end()
        db.addSection(text.jsSlice(8))
      }
      T.taskTxt -> {
        if (types[i] != T.taskData) fail("'taskData'")
        val data = texts[i++]
        end()
        db.addTask(text, data)
      }
      T.click -> {
        var name: String? = null
        var args: String? = null
        var href: String? = null
        if (types[i] == T.callbackname) {
          name = texts[i++]
          if (types[i] == T.callbackargs) args = texts[i++]
          if (types[i] == T.href) {
            href = texts[i++]
            end()
          } else {
            end(if (args == null) ", 'callbackargs', 'href'" else ", 'href'")
          }
        } else if (types[i] == T.href) {
          href = texts[i++]
          if (types[i] == T.callbackname) {
            name = texts[i++]
            if (types[i] == T.callbackargs) {
              args = texts[i++]
              end()
            } else {
              end(", 'callbackargs'")
            }
          } else {
            end(", 'callbackname'")
          }
        } else {
          fail("'callbackname', 'href'")
        }
        if (name != null) db.setClickEvent(text, name, args)
        if (href != null) db.setLink(text, href)
      }
      else -> {
        end()
        db.setWeekday(WEEKDAY_NAMES[t - T.weekday_monday])
      }
    }
  }
}
