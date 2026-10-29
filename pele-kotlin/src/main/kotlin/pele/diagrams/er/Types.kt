// Ported from Pele src/diagrams/er/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.svg.Accessible

// 'ZERO_OR_ONE' | 'ZERO_OR_MORE' | 'ONE_OR_MORE' | 'ONLY_ONE' | 'MD_PARENT'
typealias Cardinality = String

// 'NON_IDENTIFYING' | 'IDENTIFYING'
typealias Identification = String

class ErAttribute(
  val type: String,
  val name: String,
  val keys: List<String>,
  val comment: String,
)

// What the parser hands over for one attribute; keys and comment are present only when written.
class ParsedAttribute(
  val type: String,
  val name: String,
  var keys: List<String>? = null,
  var comment: String? = null,
)

class ErEntity(
  val id: String,
  val label: String,
  var alias: String,
  val attributes: MutableList<ErAttribute>,
  var cssClasses: String,
  val cssStyles: MutableList<String>,
)

// cardB sits at entityA's end of the line and cardA at entityB's, as in Mermaid.
class RelSpec(
  val cardA: Cardinality,
  val relType: Identification,
  val cardB: Cardinality,
)

// entityA and entityB hold an entity's id, or a subgraph's id when the name was a subgraph at the
// time.
class ErRelationship(
  val entityA: String,
  val roleA: String,
  val entityB: String,
  val relSpec: RelSpec,
)

class ErClassDef(
  val id: String,
  val styles: MutableList<String>,
  val textStyles: MutableList<String>,
)

// labelType is 'text' | 'string' | 'markdown'.
class ErSubgraph(
  val id: String,
  val nodes: List<String>,
  val title: String,
  val classes: MutableList<String>,
  val cssStyles: MutableList<String>,
  val dir: String?,
  val labelType: String,
)

// PORT: `{ stmt: 'dir'; value: string }`.
class DirStmt(val value: String)

// PORT: `string | { stmt: 'dir'; value: string }` is a String or a DirStmt.
typealias DocItem = Any

interface ErModel : Accessible {
  val type: String
  val direction: String
  val entities: Map<String, ErEntity>
  val relationships: List<ErRelationship>
  val classes: Map<String, ErClassDef>
  val subgraphs: List<ErSubgraph>
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
}
