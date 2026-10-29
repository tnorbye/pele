// Ported from Pele src/diagrams/timeline/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.timeline

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.svg.Accessible

class TimelinePeriod(
  val text: String,
  val events: MutableList<String>,
  // Name of the section in force when the period was declared, '' before the first one.
  val section: String,
  // Index into `sections` of that section, -1 before the first one.
  val sectionIndex: Int,
)

interface TimelineModel : Accessible {
  val type: String
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
  // "LR" | "TD"
  val direction: String
  val sections: List<String>
  val periods: List<TimelinePeriod>
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

class TimelineDb : TimelineModel {
  override val type = "timeline"
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  override var direction: String = "LR"
    private set

  override val sections: MutableList<String> = ArrayList()
  override val periods: MutableList<TimelinePeriod> = ArrayList()

  private var currentSection = ""

  // The grammar reaches the title and accessibility setters through this.
  fun getCommonDb(): TimelineDb = this

  fun setDiagramTitle(text: String) {
    this.title = text
  }

  @JvmName("setAccTitleText")
  fun setAccTitle(text: String) {
    this.accTitle = text.jsReplace(RE_LEADING, "")
  }

  @JvmName("setAccDescriptionText")
  fun setAccDescription(text: String) {
    this.accDescr = text.jsReplace(RE_INDENT, "\n")
  }

  fun setDirection(direction: String) {
    this.direction = direction
  }

  fun addSection(text: String) {
    this.currentSection = text
    this.sections.add(text)
  }

  fun addTask(period: String, _length: Double? = null, event: String? = null) {
    this.periods.add(
      TimelinePeriod(
        period,
        if (!event.isNullOrEmpty()) mutableListOf(event) else ArrayList(),
        currentSection,
        this.sections.size - 1,
      )
    )
  }

  fun addEvent(event: String) {
    val period = this.periods.getOrNull(this.periods.size - 1)
    // Mermaid fails here too, with a TypeError.
    if (period == null)
      throw PeleError(
        "An event needs a time period before it.",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "timeline"),
      )
    period.events.add(event)
  }
}
