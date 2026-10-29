// Ported from Pele src/diagrams/eventmodeling/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.eventmodeling

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.titleValue
import pele.js.JsRegExp
import pele.js.cmp
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsTrim
import pele.js.jsTrimEnd
import pele.js.jsTrimStart
import pele.locate
import pele.svg.Accessible

// EntityKind is "ui" | "processor" | "command" | "readmodel" | "event".
// LaneGroup is "trigger" | "state" | "stream".

// Compared by identity, as upstream does.
class EmLane(
  // Mermaid's ordering key: 0 for UI/Automation, 100 for Command/Read Model, 200 for Events,
  // and the next free number after those for each namespace.
  val index: Int,
  val label: String,
  val group: String,
  val namespace: String? = null,
)

class EmBox(
  // The frame number as written, such as "01".
  val id: String,
  val kind: String,
  val entity: String,
  val name: String,
  val namespace: String? = null,
  val reset: Boolean,
  val lane: EmLane,
  // Example data from the frame or from the data block it refers to.
  val data: String? = null,
  val dataType: String? = null,
)

class EmRelation(
  val source: EmBox,
  val target: EmBox,
)

class EmNote(
  val frame: String,
  val text: String,
  val dataType: String? = null,
)

class EmSpecification(
  val frame: String,
  val given: List<String>,
  val `when`: List<String>,
  val then: List<String>,
)

class EventModelingModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  val boxes: List<EmBox>,
  // Sorted from top to bottom.
  val lanes: List<EmLane>,
  val relations: List<EmRelation>,
  val notes: List<EmNote>,
  val specifications: List<EmSpecification>,
  val entities: List<String>,
) : Accessible

private val KINDS = mapOf(
  "ui" to "ui",
  "pcr" to "processor",
  "processor" to "processor",
  "rmo" to "readmodel",
  "readmodel" to "readmodel",
  "cmd" to "command",
  "command" to "command",
  "evt" to "event",
  "event" to "event",
)

private class GroupInfo(val first: Int, val label: String, val prefix: String)

// [first index, label, label prefix for a namespace]
private val GROUPS = mapOf(
  "trigger" to GroupInfo(0, "UI/Automation", "UI/A: "),
  "state" to GroupInfo(100, "Command/Read Model", "C/RM: "),
  "stream" to GroupInfo(200, "Events", "Stream: "),
)

private fun groupOf(kind: String): String =
  if (kind == "ui" || kind == "processor") "trigger" else if (kind == "event") "stream" else "state"

private val RE_CR = JsRegExp("""\r""", "g")

// The text between the outer braces, without the indentation its lines share.
private fun blockText(raw: String): String {
  val open = raw.indexOf('{')
  val close = raw.lastIndexOf('}')
  val inner =
    if (open != -1 && close > open) raw.jsSlice(open + 1, close) else if (open == -1 && raw.length >= 2) raw.jsSlice(1, -1) else ""
  val lines = inner.jsReplace(RE_CR, "").jsSplit("\n").toMutableList()
  while (lines.size > 0 && lines[0].jsTrim() == "") lines.removeAt(0)
  while (lines.size > 0 && lines[lines.size - 1].jsTrim() == "") lines.removeAt(lines.size - 1)
  var indent = Double.POSITIVE_INFINITY
  for (line in lines) {
    if (line.jsTrim() == "") continue
    indent = minOf(indent, (line.length - line.jsTrimStart().length).toDouble())
  }
  return lines.joinToString("\n") { line -> line.jsSlice(minOf(indent, line.length.toDouble()).toInt()).jsTrimEnd() }
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun buildEventModel(ast: EventModelAst, title: String?): EventModelingModel {
  val ids = LinkedHashSet<String>()
  for (frame in ast.frames) {
    if (ids.contains(frame.name)) {
      val line = locate(ast.source, frame.offset).line
      throw PeleError("Duplicate event modeling frame ID \"${frame.name}\" on line $line", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "eventmodeling", line = line))
    }
    ids.add(frame.name)
  }

  val data = LinkedHashMap<String, String>()
  for (entity in ast.dataEntities) if (!data.containsKey(entity.name)) data[entity.name] = entity.dataBlockValue

  // One lane per group, and one more per namespace within a group.
  val laneOf = LinkedHashMap<String, EmLane>()
  val next = LinkedHashMap<String, Int>()
  val lane = { kind: String, namespace: String? ->
    val group = groupOf(kind)
    val key = if (namespace == null) group else "$group $namespace"
    var found = laneOf[key]
    if (found == null) {
      val info = GROUPS[group]!!
      var index = info.first
      if (namespace != null) {
        index = next[group] ?: (info.first + 1)
        next[group] = index + 1
      }
      found = EmLane(index, if (namespace == null) info.label else info.prefix + namespace, group, namespace)
      laneOf[key] = found
    }
    found
  }

  val boxes = ArrayList<EmBox>()
  val byId = LinkedHashMap<String, EmBox>()
  val relations = ArrayList<EmRelation>()
  ast.frames.forEachIndexed { index, frame ->
    // Only a two-part name has a namespace.
    val parts = frame.entityIdentifier.jsSplit(".")
    val namespace = if (parts.size == 2) parts[0] else null
    val kind = KINDS[frame.modelEntityType] ?: "event"
    var text = if (frame.dataInlineValue == null) null else blockText(frame.dataInlineValue!!)
    if (frame.dataReference != null) {
      val block = data[frame.dataReference!!.refText]
      if (block != null) text = blockText(block)
    }
    val box = EmBox(
      id = frame.name,
      kind = kind,
      entity = frame.entityIdentifier,
      name = if (parts.size == 2) parts[1] else frame.entityIdentifier,
      namespace = namespace,
      reset = frame.type == "EmResetFrame",
      lane = lane(kind, namespace),
      data = text,
      dataType = frame.dataType,
    )
    boxes.add(box)
    byId[box.id] = box

    if (frame.sourceFrames.size > 0) {
      // Sources connect in frame order, and only frames that already have a box.
      val wanted = LinkedHashSet(frame.sourceFrames.map { ref -> ref.refText })
      for (source in boxes) if (wanted.contains(source.id)) relations.add(EmRelation(source, box))
    } else if (index > 0 && !box.reset) {
      // Otherwise the nearest earlier frame in another lane feeds this one.
      for (i in index - 1 downTo 0) {
        if (boxes[i].lane !== box.lane) {
          relations.add(EmRelation(boxes[i], box))
          break
        }
      }
    }
  }

  val names = { statements: List<EmGwtStatement> -> statements.map { s -> s.entityIdentifier.refText } }
  return EventModelingModel(
    type = "eventmodeling",
    title = run {
      val head = if (!ast.title.isNullOrEmpty()) titleValue(ast.title!!) else ast.title
      if (!head.isNullOrEmpty()) head else title
    },
    accTitle = if (!ast.accTitle.isNullOrEmpty()) accTitleValue(ast.accTitle!!).jsReplace(RE_LEADING, "") else null,
    accDescr = if (!ast.accDescr.isNullOrEmpty()) accDescrValue(ast.accDescr!!).jsReplace(RE_INDENT, "\n") else null,
    boxes = boxes,
    lanes = laneOf.values.sortedWith { a, b -> cmp((a.index - b.index).toDouble()) },
    relations = relations,
    notes = ast.noteEntities.map { note -> EmNote(note.sourceFrame.refText, blockText(note.dataBlockValue), note.dataType) },
    specifications = ast.gwtEntities.map { gwt ->
      EmSpecification(
        frame = gwt.sourceFrame.refText,
        given = names(gwt.givenStatements),
        `when` = names(gwt.whenStatements),
        then = names(gwt.thenStatements),
      )
    },
    entities = ast.modelEntities.map { entity -> entity.name },
  )
}
