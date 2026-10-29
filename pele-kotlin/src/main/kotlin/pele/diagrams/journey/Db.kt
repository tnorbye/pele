// Ported from Pele src/diagrams/journey/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.journey

import pele.js.JsRegExp
import pele.js.jsNumber
import pele.js.jsReplace
import pele.js.jsSplit
import pele.js.jsSubstring
import pele.js.jsTrim
import pele.svg.Accessible

class JourneyTask(
  val task: String,
  // As written: any number, or NaN when the score is not one.
  val score: Double,
  val people: List<String>,
  // Name of the section in force when the task was declared, '' before the first one.
  val section: String,
)

interface JourneyModel : Accessible {
  val type: String
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
  val sections: List<String>
  val tasks: List<JourneyTask>
}

// Everyone named on a task, once each, in code unit order.
fun journeyActors(tasks: List<JourneyTask>): List<String> {
  val names = LinkedHashSet<String>()
  for (task in tasks) for (name in task.people) names.add(name)
  return names.sorted()
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

class JourneyDb : JourneyModel {
  override val type = "journey"
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  override val sections: MutableList<String> = ArrayList()
  override val tasks: MutableList<JourneyTask> = ArrayList()

  private var currentSection = ""

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

  fun addSection(text: String) {
    this.currentSection = text
    this.sections.add(text)
  }

  // `data` is the rest of the line from its first colon: `:score` or `:score:actor, actor`.
  fun addTask(task: String, data: String) {
    val pieces = data.jsSubstring(1).jsSplit(":")
    val people =
      if (pieces.size == 1) emptyList() else pieces[1].jsSplit(",").map { name -> name.jsTrim() }
    this.tasks.add(JourneyTask(task, jsNumber(pieces[0]), people, currentSection))
  }

  fun getActors(): List<String> = journeyActors(this.tasks)
}
