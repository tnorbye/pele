// Ported from Pele src/diagrams/c4/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import pele.svg.Accessible

// A `$key="value"` argument.
class C4Named(
  val key: String,
  val value: String,
)

// A String or a C4Named.
typealias C4Attr = Any

open class C4Element(
  var alias: String = "",
  var label: String = "",
  // A named argument written where the alias or the label belongs. It is applied to the field it
  // names.
  var aliasAttr: C4Named? = null,
  var labelAttr: C4Named? = null,
  var parent: C4Boundary? = null,
) {
  var type: String? = null
  var descr: String? = null
  var techn: String? = null
  var sprite: String? = null
  var tags: String? = null
  var link: String? = null
  var bgColor: String? = null
  var fontColor: String? = null
  var borderColor: String? = null
  var shadowing: String? = null
  var shape: String? = null
  var legendText: String? = null
  var legendSprite: String? = null

  // Named arguments that set no known field.
  var extra: LinkedHashMap<String, String>? = null
}

class C4Shape : C4Element() {
  // Mermaid's typeC4Shape: person, external_system_db, container_queue and so on.
  var kind: String = ""
}

class C4Boundary : C4Element() {
  // Set on deployment nodes: node, nodeL or nodeR.
  var nodeType: String? = null
}

class C4Rel(
  var type: String,
  var from: String,
  var to: String,
  var label: String,
) {
  var fromAttr: C4Named? = null
  var toAttr: C4Named? = null
  var labelAttr: C4Named? = null
  var techn: String? = null
  var descr: String? = null
  var sprite: String? = null
  var tags: String? = null
  var link: String? = null
  var textColor: String? = null
  var lineColor: String? = null
  var offsetX: Double? = null
  var offsetY: Double? = null
  var extra: LinkedHashMap<String, String>? = null
}

interface C4Model : Accessible {
  val type: String

  // The header keyword: C4Context, C4Container, C4Component, C4Dynamic or C4Deployment.
  val c4Type: String?
  val title: String?
  val shapes: MutableList<C4Shape>

  // The first boundary is the unnamed one that holds everything at the top level.
  val boundaries: MutableList<C4Boundary>
  val rels: MutableList<C4Rel>
  val shapeInRow: Double
  val boundaryInRow: Double
}
