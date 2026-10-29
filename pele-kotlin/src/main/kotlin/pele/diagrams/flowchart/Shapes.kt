// Ported from Pele src/diagrams/flowchart/shapes.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

// Mermaid's documented shape names: the short name first, then its aliases.
private val SHAPES: List<List<String>> = listOf(
  listOf("rect", "proc", "process", "rectangle", "squareRect"),
  listOf("rounded", "event", "roundedRect"),
  listOf("stadium", "terminal", "pill"),
  listOf("fr-rect", "subprocess", "subproc", "framed-rectangle", "subroutine"),
  listOf("cyl", "db", "database", "cylinder"),
  listOf("datastore", "data-store"),
  listOf("folder", "directory"),
  listOf("bucket"),
  listOf("console"),
  listOf("browser"),
  listOf("person"),
  listOf("circle", "circ"),
  listOf("bang"),
  listOf("cloud"),
  listOf("diam", "decision", "diamond", "question"),
  listOf("hex", "hexagon", "prepare"),
  listOf("lean-r", "lean-right", "in-out", "lean_right"),
  listOf("lean-l", "lean-left", "out-in", "lean_left"),
  listOf("trap-b", "priority", "trapezoid-bottom", "trapezoid"),
  listOf("trap-t", "manual", "trapezoid-top", "inv-trapezoid", "inv_trapezoid"),
  listOf("dbl-circ", "double-circle", "doublecircle"),
  listOf("text"),
  listOf("notch-rect", "card", "notched-rectangle"),
  listOf("lin-rect", "lined-rectangle", "lined-process", "lin-proc", "shaded-process"),
  listOf("sm-circ", "start", "small-circle", "stateStart"),
  listOf("fr-circ", "stop", "framed-circle", "stateEnd"),
  listOf("fork", "join", "forkJoin"),
  listOf("hourglass", "collate"),
  listOf("brace", "comment", "brace-l"),
  listOf("brace-r"),
  listOf("braces"),
  listOf("bolt", "com-link", "lightning-bolt"),
  listOf("doc", "document"),
  listOf("delay", "half-rounded-rectangle"),
  listOf("h-cyl", "das", "horizontal-cylinder"),
  listOf("lin-cyl", "disk", "lined-cylinder"),
  listOf("curv-trap", "curved-trapezoid", "display"),
  listOf("div-rect", "div-proc", "divided-rectangle", "divided-process"),
  listOf("tri", "extract", "triangle"),
  listOf("win-pane", "internal-storage", "window-pane"),
  listOf("f-circ", "junction", "filled-circle"),
  listOf("notch-pent", "loop-limit", "notched-pentagon"),
  listOf("flip-tri", "manual-file", "flipped-triangle"),
  listOf("sl-rect", "manual-input", "sloped-rectangle"),
  listOf("docs", "documents", "st-doc", "stacked-document"),
  listOf("st-rect", "procs", "processes", "stacked-rectangle"),
  listOf("bow-rect", "stored-data", "bow-tie-rectangle"),
  listOf("cross-circ", "summary", "crossed-circle"),
  listOf("tag-doc", "tagged-document"),
  listOf("tag-rect", "tagged-rectangle", "tag-proc", "tagged-process"),
  listOf("flag", "paper-tape"),
  listOf("odd", "rect_left_inv_arrow"),
  listOf("lin-doc", "lined-document"),
)

// Shapes Mermaid accepts by name without documenting them.
private val INTERNAL = listOf(
  "state",
  "choice",
  "note",
  "composite",
  "rectWithTitle",
  "labelRect",
  "block_arrow",
  "collapsedGroup",
  "iconSquare",
  "iconCircle",
  "icon",
  "iconRounded",
  "imageSquare",
  "anchor",
  "kanbanItem",
  "mindmapCircle",
  "defaultMindmapNode",
  "classBox",
  "erBox",
  "requirementBox",
  "usecaseActor",
  "usecaseActorHollow",
  "usecaseActorAwesome",
  "usecaseActorIcon",
  "usecaseBusiness",
  "usecaseEllipse",
  "usecaseJsonTable",
)

// Shapes produced by the bracket syntax, mapped to their documented names.
private val SYNTAX = mapOf(
  "square" to "rect",
  "round" to "rounded",
  "ellipse" to "ellipse",
  "hexagon" to "hex",
  "diamond" to "diam",
)

private val canonical: Map<String, String> = buildMap {
  for (names in SHAPES) for (name in names) put(name, names[0])
  for (name in INTERNAL) put(name, name)
}

fun isValidShape(name: String): Boolean = canonical.containsKey(name)

fun canonicalShape(name: String?): String {
  if (name == null) return "rect"
  return SYNTAX[name] ?: canonical[name] ?: "rect"
}
