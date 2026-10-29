// Ported from Pele src/diagrams/gantt/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

class GanttTaskRaw(var data: String, var start: String?, var end: String)

class GanttTask(
  var id: String,
  // The task's label, under the name Mermaid gives it.
  var task: String,
  var section: String,
  // Unset until `getTasks` has scheduled the task, and still unset when what it refers to never resolves.
  var startTime: JsDate?,
  var endTime: JsDate?,
  // Where the bar ends when excluded days pushed the task's end out; null when the end was given as a date.
  var renderEndTime: JsDate?,
  var manualEndTime: Boolean,
  var processed: Boolean,
  var active: Boolean,
  var done: Boolean,
  var crit: Boolean,
  var milestone: Boolean,
  var vert: Boolean,
  // Row index in source order; -1 for vertical markers, which take no row.
  var order: Int,
  var classes: MutableList<String>,
  var prevTaskId: String?,
  var raw: GanttTaskRaw,
)

interface GanttModel {
  val type: String
  val title: String?
  val accTitle: String?
  val accDescr: String?
  val dateFormat: String
  val axisFormat: String
  val tickInterval: String?
  val todayMarker: String
  val includes: List<String>
  val excludes: List<String>
  val inclusiveEndDates: Boolean
  val topAxis: Boolean
  val displayMode: String

  // The day week-based ticks start on, when the chart names one. Mermaid's default is Sunday.
  val weekday: String?
  val weekend: String
  val sections: List<String>
  val links: Map<String, String>
  val tasks: List<GanttTask>

  // Schedules the tasks against the given time, or the current time, and returns them.
  fun getTasks(now: Double? = null): List<GanttTask>
}
