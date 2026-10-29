// Ported from Pele src/diagrams/usecase/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.svg.Accessible

// 'text' | 'markdown'
typealias LabelType = String

// 'normal' | 'hollow' | 'awesome' | 'icon'
typealias ActorType = String

// 'ellipse' | 'rect'
typealias UseCaseShape = String

// 'rect' | 'package'
typealias BoundaryType = String

// 'association' | 'include' | 'extend' | 'generalization'
typealias RelationshipType = String

// 'fast' | 'slow'
typealias Animation = String

// 'TB' | 'TD' | 'BT' | 'RL' | 'LR'
typealias Direction = String

// 'actor' | 'usecase' | 'boundary' | 'json' | 'edge'
typealias SymbolKind = String

// Start and end offsets in the source, end exclusive.
typealias Span = IntArray

fun span(start: Int, end: Int): Span = intArrayOf(start, end)

class Actor(
  val id: String,
  val label: String,
  val labelType: LabelType,
  val type: ActorType,
  var icon: String? = null,
  val business: Boolean,
  var stereotype: String? = null,
  var parentId: String? = null,
  val classes: MutableList<String>,
  val styles: MutableList<String>,
)

class UseCase(
  val id: String,
  val label: String,
  val labelType: LabelType,
  val shape: UseCaseShape,
  val business: Boolean,
  var stereotype: String? = null,
  var parentId: String? = null,
  val classes: MutableList<String>,
  val styles: MutableList<String>,
)

class SystemBoundary(
  val id: String,
  val label: String,
  val labelType: LabelType,
  var type: BoundaryType,
  var members: MutableList<String>,
  var classes: MutableList<String>,
  val styles: MutableList<String>,
)

object Arrow {
  const val SOLID_ARROW = 0
  const val BACK_ARROW = 1
  const val LINE_SOLID = 2
  const val CIRCLE_ARROW = 3
  const val CROSS_ARROW = 4
  const val CIRCLE_ARROW_REVERSED = 5
  const val CROSS_ARROW_REVERSED = 6
}

class Relationship(
  val id: String,
  val explicitId: Boolean,
  val source: String,
  val target: String,
  val type: RelationshipType,
  val arrowType: Int,
  var label: String? = null,
  var labelType: LabelType? = null,
  val minlen: Int,
  val classes: MutableList<String>,
  val styles: MutableList<String>,
  var animate: Boolean,
  var animation: Animation? = null,
)

class UsecaseNote(
  val id: String,
  val target: String,
  val label: String,
  val labelType: LabelType,
)

class UsecaseJsonNode(
  val id: String,
  val value: Map<String, Any?>,
  // The keys of each object in the order they were written, by JSON Pointer.
  val propertyOrder: Map<String, List<String>>,
  val classes: MutableList<String>,
  val styles: MutableList<String>,
)

class ClassDef(
  val id: String,
  val styles: MutableList<String>,
)

open class MetadataOccurrence(
  val key: String,
  val span: Span,
  val keySpan: Span,
  val valueSpan: Span,
)

class NodeOccurrence(
  var id: String,
  val span: Span,
  val idSpan: Span,
  var labelSpan: Span? = null,
  var defines: Boolean? = null,
  var stereotypeSpan: Span? = null,
  var metadata: List<MetadataOccurrence>? = null,
  var classSpans: List<Span>? = null,
)

class EdgeOccurrence(
  val id: String,
  var span: Span,
  var labelSpan: Span? = null,
  var idSpan: Span? = null,
  var metadata: List<MetadataOccurrence>? = null,
)

// One statement of the source with the positions of its parts, as in Mermaid's use case AST.
class GraphStatement(
  // 'node' | 'edge' | 'group' | 'note' | 'json' | 'metadata' | 'edgeMetadata' | 'classDef' | 'classAssign' | 'style'
  // | 'direction' | 'accTitle' | 'accDescr' | 'comment' | 'blank'
  var kind: String,
  var span: Span,
  var nodes: List<NodeOccurrence>? = null,
  var edges: List<EdgeOccurrence>? = null,
  var group: String? = null,
  var idSpan: Span? = null,
  var titleSpan: Span? = null,
  var endSpan: Span? = null,
  var ref: String? = null,
  var refSpan: Span? = null,
  var children: List<GraphStatement>? = null,
  var metadata: List<MetadataOccurrence>? = null,
  var classSpans: List<Span>? = null,
)

class UsecaseModel(
  val type: String = "usecase",
  val actors: MutableMap<String, Actor> = LinkedHashMap(),
  val useCases: MutableMap<String, UseCase> = LinkedHashMap(),
  val systemBoundaries: MutableMap<String, SystemBoundary> = LinkedHashMap(),
  val relationships: MutableList<Relationship> = ArrayList(),
  val notes: MutableMap<String, UsecaseNote> = LinkedHashMap(),
  val jsonNodes: MutableMap<String, UsecaseJsonNode> = LinkedHashMap(),
  val classDefs: MutableMap<String, ClassDef> = LinkedHashMap(),
  var direction: Direction = "LR",
  var title: String? = null,
  override var accTitle: String = "",
  override var accDescr: String = "",
  // The accessible description as written, before the indentation of its lines is dropped.
  var accDescription: String = "",
  var source: String = "",
  var headerSpan: Span = span(0, 0),
  var statements: List<GraphStatement> = ArrayList(),
) : Accessible

fun emptyModel(): UsecaseModel = UsecaseModel()
