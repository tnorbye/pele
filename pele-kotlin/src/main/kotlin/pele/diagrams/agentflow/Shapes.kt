// Ported from Pele src/diagrams/agentflow/shapes.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import pele.diagrams.flowchart.isValidShape as isFlowchartShape

// Author-facing names for the shapes that carry meaning in an agentflow.
val SHAPE_ALIASES: Map<String, String> = mapOf(
  "task" to "roundedRect",
  "tool" to "subroutine",
  "input" to "lean-right",
  "decision" to "diamond",
  "refdoc" to "lin-doc",
  "action" to "hexagon",
  "round" to "rect",
)

val REMOVED_SHAPES: Set<String> = linkedSetOf(
  "doc",
  "stadium",
  "terminal",
  "circle",
  "trapezoid",
  "inv_trapezoid",
  "inv-trapezoid",
  "doublecircle",
  "double-circle",
  "typeDeclaration",
  "procs",
  "lean_left",
  "lean-left",
  "in-out",
  "cylinder",
  "ellipse",
  "odd",
  "tag-rect",
  "tagged-rectangle",
  "delay",
  "half-rounded-rectangle",
  "lin-rect",
  "lined-rectangle",
  "win-pane",
  "window-pane",
  "curv-trap",
  "curved-trapezoid",
)

val ALLOWED_SHAPES: Set<String> = linkedSetOf(
  "roundedRect",
  "subroutine",
  "subprocess",
  "subproc",
  "framed-rectangle",
  "lean-right",
  "diamond",
  "lin-doc",
  "lined-document",
  "hexagon",
  "hex",
  "connector",
  "collapsedGroup",
)

const val DEFAULT_SHAPE = "roundedRect"

fun resolveShapeAlias(shape: String?): String? {
  if (shape.isNullOrEmpty()) return shape
  return SHAPE_ALIASES[shape] ?: shape
}

// Mermaid looks a shape name up with `in` on a plain object, so `constructor` passes there too.
fun isValidShape(name: String): Boolean = isFlowchartShape(name) || name == "constructor"

class EmitContext(val nodeId: String? = null, val edgeId: String? = null)

interface ShapeSink {
  fun emitError(id: DiagnosticId, message: String, ctx: EmitContext? = null)

  fun emitWarning(id: DiagnosticId, message: String, ctx: EmitContext? = null)
}

// PORT: `shape` is never absent on a graph node.
interface ShapedNode {
  val id: String
  var shape: String
  val isGroup: Boolean?
}

// Reduces every node's shape to one agentflow draws, reporting the removed and unsupported ones.
fun normaliseNodeShapes(nodes: List<ShapedNode>, sink: ShapeSink) {
  for (node in nodes) {
    if (node.isGroup == true) continue
    if (node.shape.isEmpty() || node.shape == "squareRect" || node.shape == "rect") node.shape = DEFAULT_SHAPE
    if (REMOVED_SHAPES.contains(node.shape)) {
      sink.emitError("SHAPE_REMOVED", "shape \"${node.shape}\" was removed in v0.8.1, using \"$DEFAULT_SHAPE\"", EmitContext(nodeId = node.id))
      node.shape = DEFAULT_SHAPE
    } else if (!ALLOWED_SHAPES.contains(node.shape)) {
      sink.emitWarning("SHAPE_UNSUPPORTED", "shape \"${node.shape}\" is not supported, using \"$DEFAULT_SHAPE\"", EmitContext(nodeId = node.id))
      node.shape = DEFAULT_SHAPE
    }
  }
}
