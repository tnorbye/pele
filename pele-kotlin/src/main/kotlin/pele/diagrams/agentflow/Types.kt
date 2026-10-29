// Ported from Pele src/diagrams/agentflow/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import pele.svg.Accessible
import pele.util.YamlValue

// 'text' | 'string' | 'markdown'
typealias LabelType = String

class FlowText(val text: String, val type: LabelType)

// A source span as Jison reports it: lines start at 1, columns at 0.
class Loc(
  val first_line: Int,
  val first_column: Int,
  val last_line: Int,
  val last_column: Int,
)

typealias Metadata = MutableMap<String, YamlValue>

// 'tool' | 'action' | 'input' | 'refdoc' | 'decision' | 'connector' | 'task'
typealias VertexKind = String

// 'sequence' | 'reference' | 'failure'
typealias EdgeSemantic = String

class AgentNode(
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
  var metadata: Metadata? = null,
  var isConnector: Boolean? = null,
)

class AgentEdge(
  val start: String,
  val end: String,
  var type: String? = null,
  var text: String,
  var labelType: LabelType,
  var stroke: String? = null,
  var edgeSemantic: EdgeSemantic? = null,
  var length: Double? = null,
  var style: MutableList<String>? = null,
  val classes: MutableList<String>,
  var id: String? = null,
  var isUserDefinedId: Boolean,
  var interpolate: String? = null,
  var animate: YamlValue = null,
  var animation: YamlValue = null,
  var metadata: Metadata? = null,
)

class AgentClassDef(
  val id: String,
  val styles: MutableList<String>,
  val textStyles: MutableList<String>,
)

class AgentSubgraph(
  val id: String,
  var nodes: MutableList<String>,
  var title: String,
  val classes: MutableList<String>,
  var dir: String? = null,
  val labelType: LabelType,
  val type: String = "flow",
  var metadata: Metadata? = null,
)

class LinkInfo(
  var type: String,
  var stroke: String,
  var length: Double? = null,
  var edgeSemantic: EdgeSemantic? = null,
  var text: FlowText? = null,
  var id: String? = null,
)

// string | string[] | { stmt: 'dir'; value: string }
typealias DocItem = Any

class DirStatement(val value: String) {
  val stmt: String
    get() = "dir"
}

// PORT: classes without equals, because upstream tells mappings apart by identity.
class ElementPosition(
  var startLine: Int,
  var startColumn: Int,
  var endLine: Int,
  var endColumn: Int,
  var startIndex: Int,
  var endIndex: Int,
)

// 'vertex' | 'edge' | 'subgraph' | 'connector' | 'attachment'
typealias StatementType = String

class ElementMapping(val id: String, var type: StatementType, val position: ElementPosition)

// 'SHAPE_UNSUPPORTED' | 'SHAPE_REMOVED' | ...
typealias DiagnosticId = String

class Diagnostic(
  val id: DiagnosticId,
  // 'warning' | 'error'
  val severity: String,
  val message: String,
  var nodeId: String? = null,
  var edgeId: String? = null,
  var position: ElementPosition? = null,
)

class SemanticVertex(
  val id: String,
  var label: String? = null,
  var shape: String? = null,
  var vertexKind: VertexKind? = null,
  var metadata: Metadata? = null,
)

class SemanticEdge(
  val start: String,
  val end: String,
  var id: String? = null,
  var label: String? = null,
  var type: String? = null,
  var stroke: String? = null,
  var edgeSemantic: EdgeSemantic? = null,
  var length: Double? = null,
  var metadata: Metadata? = null,
)

class SemanticSubgraph(
  val id: String,
  var type: String? = null,
  var title: String? = null,
  val nodes: MutableList<String>,
  var metadata: Metadata? = null,
  var direction: String? = null,
)

class SemanticConnector(
  val id: String,
  var title: String? = null,
  var metadata: Metadata? = null,
)

// The diagram with presentation-only controls left out, for tools that read what it means.
class SemanticModel(
  var direction: String? = null,
  val vertices: List<SemanticVertex>,
  val edges: List<SemanticEdge>,
  val subGraphs: List<SemanticSubgraph>,
  val connectors: List<SemanticConnector>,
  val diagnostics: List<Diagnostic>,
)

interface AgentflowModel : Accessible {
  val type: String
  val direction: String?
  val nodes: MutableMap<String, AgentNode>
  val edges: MutableList<AgentEdge>
  val subgraphs: MutableList<AgentSubgraph>
  val connectors: MutableMap<String, AgentNode>
  val classes: MutableMap<String, AgentClassDef>
  val tooltips: MutableMap<String, String>
  val defaultEdgeStyle: MutableList<String>?
  val defaultInterpolate: String?

  // Where each statement sits in the source, in the order the statements were read.
  val mappings: MutableList<ElementMapping>
  val diagnostics: MutableList<Diagnostic>
  val title: String?

  fun semanticModel(): SemanticModel
}
