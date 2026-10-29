// Ported from Pele src/diagrams/swimlane/index.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.swimlane

import pele.Config
import pele.Diagram
import pele.IconResolver
import pele.ParseLimits
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.flowchart.FlowDb
import pele.diagrams.flowchart.FlowGraph
import pele.diagrams.flowchart.FlowVariant
import pele.diagrams.flowchart.FlowchartModel
import pele.diagrams.flowchart.GraphNode
import pele.diagrams.flowchart.flowchart
import pele.diagrams.flowchart.renderFlowchart
import pele.layout.CEdge
import pele.layout.CNode
import pele.layout.CompoundResult
import pele.layout.Dir
import pele.layout.LayeredOptions
import pele.layout.laneLayout
import pele.svg.ResolvedStyle
import pele.svg.inkFill
import pele.svg.labelSvg
import pele.svg.num
import pele.text.Label

typealias SwimlaneModel = FlowchartModel

private const val DEFAULT_LANE = "__swimlane_default__"

// Nodes outside every lane share one unnamed lane, as in Mermaid. Lanes all run the same way,
// so a `direction` inside a subgraph has no effect.
private fun prepare(graph: FlowGraph) {
  var loose = false
  for (node in graph.nodes) {
    if (node.isGroup) node.dir = null else if (node.parentId == null) loose = true
  }
  if (!loose) return
  for (node in graph.nodes) if (!node.isGroup && node.parentId == null) node.parentId = DEFAULT_LANE
  graph.nodes.add(
    GraphNode(
      id = DEFAULT_LANE,
      label = null,
      labelType = "text",
      shape = "rect",
      isGroup = true,
      parentId = null,
      cssStyles = emptyList(),
      cssCompiledStyles = emptyList(),
      cssClasses = "",
      dir = null,
    )
  )
}

// A lane: an outline the length of the diagram, with its title in a band at the start.
private fun drawLane(
  c: CNode,
  label: Label,
  style: ResolvedStyle,
  classes: String,
  id: String,
  dir: Dir,
  icons: IconResolver?,
): String {
  val left = c.x - c.w / 2
  val top = c.y - c.h / 2
  val sideways = dir == "LR" || dir == "RL"
  val cx = if (sideways) left + c.padTop / 2 else c.x
  val cy = if (sideways) c.y else top + c.padTop / 2
  val text =
    labelSvg(
      label,
      cx,
      cy,
      """ class="pele-cluster-label"${inkFill(style, "var(--_m)")}${style.text}""",
      icons,
    )
  return ("""<g class="pele-cluster pele-lane$classes" data-id="$id">""" +
    """<rect x="${num(left)}" y="${num(top)}" width="${num(c.w)}" height="${num(c.h)}" fill="none" stroke="var(--_b)"/>""" +
    """<rect x="${num(left)}" y="${num(top)}" width="${num(if (sideways) c.padTop else c.w)}" height="${num(
        if (sideways) c.h else c.padTop
      )}" fill="var(--_a)" stroke="var(--_b)"${style.shape}/>""" +
    (if (sideways && text.isNotEmpty())
      """<g transform="rotate(-90 ${num(cx)} ${num(cy)})">$text</g>"""
    else text) +
    "</g>")
}

private fun laneBand(c: CNode, dir: Dir): DoubleArray {
  val left = c.x - c.w / 2
  val top = c.y - c.h / 2
  return if (dir == "LR" || dir == "RL") doubleArrayOf(left, top, left + c.padTop, top + c.h)
  else doubleArrayOf(left, top, left + c.w, top + c.padTop)
}

private val variant: FlowVariant =
  object : FlowVariant {
    override val type = "swimlane"

    override fun prepare(graph: FlowGraph) = pele.diagrams.swimlane.prepare(graph)

    override fun layout(
      nodes: List<CNode>,
      edges: List<CEdge>,
      dir: Dir,
      opt: LayeredOptions,
      tight: Boolean,
    ): CompoundResult =
      if (tight) laneLayout(nodes, edges, dir, opt, 12.0) else laneLayout(nodes, edges, dir, opt)

    override val drawGroup:
      ((CNode, Label, ResolvedStyle, String, String, Dir, IconResolver?) -> String)? =
      ::drawLane
    override val filledBox: ((CNode, Dir) -> DoubleArray)? = ::laneBand
  }

// A swimlane diagram is a flowchart whose top-level subgraphs are lanes: same syntax, same model.
val swimlane: Diagram<FlowDb> =
  object : Diagram<FlowDb> {
    override val type = "swimlane"
    override val math = true

    override fun parse(
      source: String,
      config: Config,
      title: String?,
      lineOffset: Int,
      limits: ParseLimits,
    ): FlowDb = flowchart.parse(source, config, title, lineOffset, limits)

    override fun render(model: FlowDb, config: Config, options: RenderOptions): Rendered =
      renderFlowchart(model, config, options, variant)
  }
