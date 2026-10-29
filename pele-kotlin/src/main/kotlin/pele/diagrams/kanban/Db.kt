// Ported from Pele src/diagrams/kanban/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.kanban

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.diagrams.common.Decoration
import pele.diagrams.common.OutlineDb
import pele.js.isTruthy
import pele.js.jsString
import pele.util.YamlValue
import pele.util.parseYaml

open class KanbanNode(
  var id: String,
  var level: Int,
  var label: String,
  var icon: String? = null,
  var assigned: String? = null,
  var ticket: String? = null,
  var priority: String? = null,
  var cssClasses: String? = null,
)

class KanbanSection(node: KanbanNode) :
  KanbanNode(
    node.id,
    node.level,
    node.label,
    node.icon,
    node.assigned,
    node.ticket,
    node.priority,
    node.cssClasses,
  ) {
  val items: MutableList<KanbanNode> = ArrayList()
}

interface KanbanModel {
  val type: String
  val title: String?
  val sections: List<KanbanSection>
}

class KanbanDb : KanbanModel, OutlineDb {
  override val type = "kanban"
  override var title: String? = null
  override val sections: MutableList<KanbanSection> = ArrayList()

  override fun getType(start: String, end: String): Int = pele.diagrams.common.getType(start, end)

  private var last: KanbanNode? = null
  private var sectionLevel = 0
  private var misplaced: KanbanNode? = null

  override fun addNode(level: Int, id: String, descr: String, type: Int, shapeData: String?) {
    val node = KanbanNode(id, level, descr)

    if (shapeData != null) {
      val yaml = if (shapeData.contains('\n')) shapeData + '\n' else "{\n" + shapeData + "\n}"
      val doc: YamlValue
      try {
        doc = parseYaml(yaml)
      } catch (error: Exception) {
        throw PeleError(
          "Metadata of \"${node.id}\" is not valid YAML. ${error.message}",
          PeleErrorCode.SYNTAX,
          PeleErrorInfo(type = "kanban"),
        )
      }
      // Mermaid throws a TypeError on metadata that is empty or not a mapping. Here it is ignored.
      if (doc is Map<*, *>) {
        val shape = if (isTruthy(doc["shape"])) jsString(doc["shape"]) else ""
        if (shape != shape.lowercase() || shape.contains('_')) {
          throw PeleError(
            "No such shape: $shape. Shape names should be lowercase.",
            PeleErrorCode.SEMANTIC,
            PeleErrorInfo(type = "kanban"),
          )
        }
        if (isTruthy(doc["label"])) node.label = jsString(doc["label"])
        if (isTruthy(doc["icon"])) node.icon = jsString(doc["icon"])
        if (isTruthy(doc["assigned"])) node.assigned = jsString(doc["assigned"])
        if (isTruthy(doc["ticket"])) node.ticket = jsString(doc["ticket"])
        if (isTruthy(doc["priority"])) node.priority = jsString(doc["priority"])
      }
    }

    // A node indented less than the first column becomes a card, and Mermaid rejects whatever
    // follows it.
    val m = misplaced
    if (m != null) {
      throw PeleError(
        "Items without section detected, found section (\"${m.label}\")",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "kanban"),
      )
    }
    val sections = this.sections
    if (sections.size == 0) this.sectionLevel = level
    if (level == this.sectionLevel) {
      // PORT: upstream turns the node itself into the section; here it is copied into one.
      val section = KanbanSection(node)
      this.last = section
      sections.add(section)
    } else {
      this.last = node
      sections[sections.size - 1].items.add(node)
      if (level < this.sectionLevel) this.misplaced = node
    }
  }

  // Mermaid throws a TypeError when a decoration comes before any node. Here it is ignored.
  override fun decorateNode(decoration: Decoration) {
    val node = this.last ?: return
    if (!decoration.icon.isNullOrEmpty()) node.icon = decoration.icon
    if (!decoration.`class`.isNullOrEmpty()) node.cssClasses = decoration.`class`
  }
}
