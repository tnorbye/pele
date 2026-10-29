// Ported from Pele src/diagrams/state/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

import pele.svg.Accessible

class StateNote(
  var position: String? = null,
  val text: String,
)

// PORT: data class because upstream spreads a state (`{ ...item, doc }`).
// `description` is a String or a List<String>.
data class StateStmt(
  var id: String,
  var type: String? = null,
  var description: Any? = null,
  var descriptions: MutableList<String>? = null,
  var doc: MutableList<Any>? = null,
  var note: StateNote? = null,
  var start: Boolean? = null,
  var classes: MutableList<String>? = null,
  var styles: MutableList<String>? = null,
  var textStyles: MutableList<String>? = null,
)

class RelationStmt(
  val state1: StateStmt,
  val state2: StateStmt,
  val description: String? = null,
)

class ClassDefStmt(
  val id: String,
  val classes: String,
)

class StyleStmt(
  val id: String,
  val styleClass: String,
)

class ApplyClassStmt(
  val id: String,
  val styleClass: String,
)

class DirectionStmt(var value: String)

// `id` is a StateStmt or a String.
class ClickStmt(
  val id: Any,
  val url: String,
  val tooltip: String,
)

// Mermaid's grammar also leaves the text of statements that have no effect in a document
// (`hide empty description`, `scale`, a bare `state name`, a floating note); those are the strings.
// PORT: a Stmt is a String, StateStmt, RelationStmt, ClassDefStmt, StyleStmt, ApplyClassStmt,
// DirectionStmt or ClickStmt.
typealias Stmt = Any

class StateRelation(
  val id1: String,
  val id2: String,
  val relationTitle: String? = null,
)

class StateClassDef(
  val id: String,
  val styles: MutableList<String>,
  val textStyles: MutableList<String>,
)

class StateLink(
  val url: String,
  val tooltip: String,
)

interface StateModel : Accessible {
  val type: String
  val direction: String
  val rootDoc: List<Stmt>
  val states: Map<String, StateStmt>
  val relations: List<StateRelation>
  val classes: Map<String, StateClassDef>
  val links: Map<String, StateLink>
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
}
