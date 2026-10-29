// Ported from Pele src/diagrams/block/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import pele.svg.Accessible

// One shape for blocks, edges, and the statements that set columns, classes, and styles, as in
// Mermaid.
class Block(
  // PORT: absent on a column-setting statement and on a composite whose header was a link
  // statement.
  var id: String? = null,
  var type: String? = null,
  var label: String? = null,
  var children: MutableList<Block>? = null,
  var columns: Double? = null,
  var widthInColumns: Double? = null,
  // Number of cells a `space` statement asked for.
  var width: Double? = null,
  var directions: List<String>? = null,
  var classes: MutableList<String>? = null,
  var styles: List<String>? = null,
  var colorIndex: Double? = null,
  var start: String? = null,
  var end: String? = null,
  var arrowTypeStart: String? = null,
  var arrowTypeEnd: String? = null,
  var thickness: String? = null,
  var pattern: String? = null,
  var css: String? = null,
  var styleClass: String? = null,
  var stylesStr: String? = null,
) {
  // PORT: object spread.
  fun copy(): Block =
    Block(
      id,
      type,
      label,
      children,
      columns,
      widthInColumns,
      width,
      directions,
      classes,
      styles,
      colorIndex,
      start,
      end,
      arrowTypeStart,
      arrowTypeEnd,
      thickness,
      pattern,
      css,
      styleClass,
      stylesStr,
    )
}

// A statement with links is a list: block, edge, block, and so on.
// PORT: a Block or a List<Block>.
typealias Statement = Any

class BlockClassDef(
  val id: String,
  val styles: MutableList<String>,
  val textStyles: MutableList<String>,
)

interface BlockModel : Accessible {
  val type: String
  val root: Block
  val blocks: List<Block>
  val edges: List<Block>
  val classes: Map<String, BlockClassDef>
  val byId: Map<String, Block>
  val title: String?
}
