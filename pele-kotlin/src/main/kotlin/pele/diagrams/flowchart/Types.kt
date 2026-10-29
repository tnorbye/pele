// Ported from Pele src/diagrams/flowchart/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

import pele.svg.Accessible
import pele.util.YamlValue

// 'text' | 'string' | 'markdown'
typealias LabelType = String

class FlowText(val text: String, val type: LabelType)

class FlowNode(
  val id: String,
  var text: String? = null,
  var labelType: LabelType,
  var type: String? = null,
  val styles: MutableList<String>,
  val classes: MutableList<String>,
  var dir: String? = null,
  var props: MutableMap<String, String>? = null,
  var link: String? = null,
  var linkTarget: String? = null,
  var icon: String? = null,
  var form: String? = null,
  var pos: String? = null,
  var img: String? = null,
  var constraint: String? = null,
  var assetWidth: Double? = null,
  var assetHeight: Double? = null,
)

class FlowEdge(
  val start: String,
  val end: String,
  var type: String? = null,
  var text: String,
  var labelType: LabelType,
  var stroke: String? = null,
  var length: Double? = null,
  var style: MutableList<String>? = null,
  val classes: MutableList<String>,
  var id: String? = null,
  var isUserDefinedId: Boolean,
  var interpolate: String? = null,
  // PORT: YAML values, kept as they were given, as upstream casts without checking.
  var animate: Any? = null,
  var animation: Any? = null,
)

class FlowClassDef(
  val id: String,
  val styles: MutableList<String>,
  val textStyles: MutableList<String>,
)

class FlowSubgraph(
  val id: String,
  var nodes: MutableList<String>,
  val title: String,
  val classes: MutableList<String>,
  val dir: String? = null,
  val labelType: LabelType,
  var metadata: MutableMap<String, YamlValue>? = null,
)

class LinkInfo(
  var type: String,
  val stroke: String,
  var length: Double? = null,
  var text: FlowText? = null,
  var id: String? = null,
)

// string | string[] | { stmt: 'dir'; value: string }
typealias DocItem = Any

class DirStatement(val value: String) {
  val stmt: String
    get() = "dir"
}

interface FlowchartModel : Accessible {
  val type: String
  val direction: String?
  val nodes: Map<String, FlowNode>
  val edges: List<FlowEdge>
  val subgraphs: List<FlowSubgraph>
  val classes: Map<String, FlowClassDef>
  val tooltips: Map<String, String>
  val defaultEdgeStyle: List<String>?
  val defaultInterpolate: String?
  val title: String?
}
