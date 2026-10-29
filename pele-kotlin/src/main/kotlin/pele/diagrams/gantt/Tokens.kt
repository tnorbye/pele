// Ported from Pele src/diagrams/gantt/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

object T {
  const val END = 0
  const val ERROR = 1
  const val open_directive = 2
  const val acc_title = 3
  const val acc_title_value = 4
  const val acc_descr = 5
  const val acc_descr_value = 6
  const val acc_descr_multiline_value = 7
  const val NL = 8
  const val href = 9
  const val callbackname = 10
  const val callbackargs = 11
  const val click = 12
  const val gantt = 13
  const val dateFormat = 14
  const val inclusiveEndDates = 15
  const val topAxis = 16
  const val axisFormat = 17
  const val tickInterval = 18
  const val includes = 19
  const val excludes = 20
  const val todayMarker = 21
  const val weekday_monday = 22
  const val weekday_tuesday = 23
  const val weekday_wednesday = 24
  const val weekday_thursday = 25
  const val weekday_friday = 26
  const val weekday_saturday = 27
  const val weekday_sunday = 28
  const val weekend_friday = 29
  const val weekend_saturday = 30
  const val date = 31
  const val title = 32
  const val accDescription = 33
  const val section = 34
  const val taskTxt = 35
  const val taskData = 36
  const val COLON = 37
  const val EOF = 38
}

val TOKEN_NAMES: List<String> = listOf(
  "\$end",
  "INVALID",
  "open_directive",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "NL",
  "href",
  "callbackname",
  "callbackargs",
  "click",
  "gantt",
  "dateFormat",
  "inclusiveEndDates",
  "topAxis",
  "axisFormat",
  "tickInterval",
  "includes",
  "excludes",
  "todayMarker",
  "weekday_monday",
  "weekday_tuesday",
  "weekday_wednesday",
  "weekday_thursday",
  "weekday_friday",
  "weekday_saturday",
  "weekday_sunday",
  "weekend_friday",
  "weekend_saturday",
  "date",
  "title",
  "accDescription",
  "section",
  "taskTxt",
  "taskData",
  ":",
  "EOF",
)
