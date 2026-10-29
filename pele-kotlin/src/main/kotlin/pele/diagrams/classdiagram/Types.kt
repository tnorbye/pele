// Ported from Pele src/diagrams/class/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.svg.Accessible

// PORT: `number | 'none'` is a relation type number, or RELATION_NONE for 'none'.
typealias RelationEnd = Int

const val RELATION_NONE = -1

class ClassNode(
  val id: String,
  val type: String,
  var label: String,
  var cssClasses: String,
  val methods: MutableList<ClassMember>,
  val members: MutableList<ClassMember>,
  val annotations: MutableList<String>,
  val styles: MutableList<String>,
  var parent: String? = null,
  var link: String? = null,
  var linkTarget: String? = null,
  var haveCallback: Boolean? = null,
  var tooltip: String? = null,
)

class RelationKind(
  var type1: RelationEnd,
  var type2: RelationEnd,
  var lineType: Int,
)

class ClassRelation(
  var id1: String,
  var id2: String,
  var relationTitle1: String,
  var relationTitle2: String,
  var title: String? = null,
  val relation: RelationKind,
)

class ClassNote(
  val id: String,
  // PORT: `class` is a Kotlin keyword.
  val className: String?,
  val text: String,
  val index: Int,
  var parent: String? = null,
)

class ClassInterface(
  val id: String,
  val label: String,
  val classId: String,
)

class NamespaceNode(
  val id: String,
  var label: String,
  val classes: LinkedHashMap<String, ClassNode>,
  val notes: LinkedHashMap<String, ClassNote>,
  val children: LinkedHashMap<String, NamespaceNode>,
  var parent: String? = null,
  // False for the ancestors created on the way to a dotted name such as `A.B.C`.
  var explicit: Boolean,
)

class StyleClass(
  val id: String,
  val styles: MutableList<String>,
  val textStyles: MutableList<String>,
)

interface ClassModel : Accessible {
  val type: String
  val direction: String
  val classes: Map<String, ClassNode>
  val relations: List<ClassRelation>
  val notes: Map<String, ClassNote>
  val namespaces: Map<String, NamespaceNode>
  val interfaces: List<ClassInterface>
  val styleClasses: Map<String, StyleClass>
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
}
