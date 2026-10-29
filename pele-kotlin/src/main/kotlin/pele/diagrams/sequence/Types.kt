// Ported from Pele src/diagrams/sequence/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.svg.Accessible

// The message kinds, numbered as in Mermaid.
object LINETYPE {
  const val SOLID = 0
  const val DOTTED = 1
  const val NOTE = 2
  const val SOLID_CROSS = 3
  const val DOTTED_CROSS = 4
  const val SOLID_OPEN = 5
  const val DOTTED_OPEN = 6
  const val LOOP_START = 10
  const val LOOP_END = 11
  const val ALT_START = 12
  const val ALT_ELSE = 13
  const val ALT_END = 14
  const val OPT_START = 15
  const val OPT_END = 16
  const val ACTIVE_START = 17
  const val ACTIVE_END = 18
  const val PAR_START = 19
  const val PAR_AND = 20
  const val PAR_END = 21
  const val RECT_START = 22
  const val RECT_END = 23
  const val SOLID_POINT = 24
  const val DOTTED_POINT = 25
  const val AUTONUMBER = 26
  const val CRITICAL_START = 27
  const val CRITICAL_OPTION = 28
  const val CRITICAL_END = 29
  const val BREAK_START = 30
  const val BREAK_END = 31
  const val PAR_OVER_START = 32
  const val BIDIRECTIONAL_SOLID = 33
  const val BIDIRECTIONAL_DOTTED = 34
  const val SOLID_TOP = 41
  const val SOLID_BOTTOM = 42
  const val STICK_TOP = 43
  const val STICK_BOTTOM = 44
  const val SOLID_ARROW_TOP_REVERSE = 45
  const val SOLID_ARROW_BOTTOM_REVERSE = 46
  const val STICK_ARROW_TOP_REVERSE = 47
  const val STICK_ARROW_BOTTOM_REVERSE = 48
  const val SOLID_TOP_DOTTED = 51
  const val SOLID_BOTTOM_DOTTED = 52
  const val STICK_TOP_DOTTED = 53
  const val STICK_BOTTOM_DOTTED = 54
  const val SOLID_ARROW_TOP_REVERSE_DOTTED = 55
  const val SOLID_ARROW_BOTTOM_REVERSE_DOTTED = 56
  const val STICK_ARROW_TOP_REVERSE_DOTTED = 57
  const val STICK_ARROW_BOTTOM_REVERSE_DOTTED = 58
  const val CENTRAL_CONNECTION = 59
  const val CENTRAL_CONNECTION_REVERSE = 60
  const val CENTRAL_CONNECTION_DUAL = 61
}

object PLACEMENT {
  const val LEFTOF = 0
  const val RIGHTOF = 1
  const val OVER = 2
}

class SeqText(val text: String, val wrap: Boolean?)

class SeqBoxData(val text: String?, val color: String, val wrap: Boolean?)

class SeqBox(
  val name: String?,
  val wrap: Boolean,
  val fill: String,
  val actorKeys: MutableList<String>,
)

class SeqActor(
  var box: SeqBox?,
  val name: String,
  val description: String,
  val wrap: Boolean,
  val prevActor: String?,
  var nextActor: String? = null,
  val links: MutableMap<String, Any?>,
  val properties: MutableMap<String, Any?>,
  // The id of the page element named by a `details` statement. Pele has no page to read it from.
  var details: String? = null,
  val type: String,
)

class SeqNumbering(val start: Double?, val step: Double?, val visible: Boolean)

class SeqMessage(
  val id: String,
  val from: String?,
  val to: String?,
  // String | SeqNumbering
  val message: Any,
  val wrap: Boolean,
  val type: Int? = null,
  val activate: Boolean? = null,
  val placement: Int? = null,
  val centralConnection: Int? = null,
  val answer: Any? = null,
)

class SeqNote(
  // String | List<String>
  val actor: Any,
  val placement: Int,
  val message: String,
  val wrap: Boolean,
)

// One entry of the statement list the parser hands to `apply`, as Mermaid's grammar builds it.
class SeqStatement(
  var type: String,
  // String | List<String>
  var actor: Any? = null,
  var draw: String? = null,
  var description: SeqText? = null,
  var config: String? = null,
  var signalType: Int? = null,
  var from: String? = null,
  var to: String? = null,
  var msg: SeqText? = null,
  var activate: Boolean? = null,
  var centralConnection: Int? = null,
  var placement: Int? = null,
  var text: SeqText? = null,
  var boxData: SeqBoxData? = null,
  var boxText: String? = null,
  // SeqText | String
  var loopText: Any? = null,
  var optText: SeqText? = null,
  var altText: SeqText? = null,
  var parText: SeqText? = null,
  var criticalText: SeqText? = null,
  var optionText: SeqText? = null,
  var breakText: SeqText? = null,
  var color: SeqText? = null,
  var sequenceIndex: Double? = null,
  var sequenceIndexStep: Double? = null,
  var sequenceVisible: Boolean? = null,
)

// SeqItem = SeqStatement | String | MutableList<SeqItem>; the list case is a MutableList<Any>.

interface SequenceModel : Accessible {
  val type: String
  val actors: MutableMap<String, SeqActor>
  val createdActors: MutableMap<String, Int>
  val destroyedActors: MutableMap<String, Int>
  val boxes: MutableList<SeqBox>
  val messages: MutableList<SeqMessage>
  val notes: MutableList<SeqNote>
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
}
