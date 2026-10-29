// Ported from Pele src/diagrams/wardley/builder.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.wardley

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo

// SourceStrategy is "build" | "buy" | "outsource" | "market".
// Flow is "forward" | "backward" | "bidirectional".

// Coordinates are percentages: x is evolution, y is visibility.
class WardleyNode(
  val id: String,
  val label: String,
  var x: Double? = null,
  var y: Double? = null,
  // 'anchor', 'component', or 'pipeline-component'
  var className: String? = null,
  var labelOffsetX: Double? = null,
  var labelOffsetY: Double? = null,
  var inPipeline: Boolean? = null,
  var isPipelineParent: Boolean? = null,
  var inertia: Boolean? = null,
  var sourceStrategy: String? = null,
)

class WardleyLink(
  val source: String,
  val target: String,
  val dashed: Boolean? = null,
  val label: String? = null,
  val flow: String? = null,
)

class WardleyTrend(
  val nodeId: String,
  val targetX: Double,
  val targetY: Double,
)

class WardleyPipeline(
  val nodeId: String,
  val componentIds: MutableList<String>,
)

class WardleyPoint(val x: Double, val y: Double)

class WardleyAnnotation(
  val number: Double,
  val coordinates: List<WardleyPoint>,
  val text: String? = null,
)

class WardleyNote(
  val text: String,
  val x: Double,
  val y: Double,
)

class WardleyForce(
  val name: String,
  val x: Double,
  val y: Double,
)

class WardleyAxesConfig(
  val xLabel: String? = null,
  val yLabel: String? = null,
  val stages: List<String>? = null,
  // Right edge of each stage, from 0 to 1.
  val stageBoundaries: List<Double>? = null,
)

class WardleySize(val width: Double, val height: Double)

open class WardleyBuildResult(
  val nodes: List<WardleyNode>,
  val links: List<WardleyLink>,
  val trends: List<WardleyTrend>,
  val pipelines: List<WardleyPipeline>,
  val annotations: List<WardleyAnnotation>,
  val notes: List<WardleyNote>,
  val accelerators: List<WardleyForce>,
  val deaccelerators: List<WardleyForce>,
  val annotationsBox: WardleyPoint?,
  val axes: WardleyAxesConfig,
  val size: WardleySize?,
)

class WardleyBuilder {
  private val nodes = LinkedHashMap<String, WardleyNode>()
  private var labels: LinkedHashMap<String, String>? = null
  private val links: MutableList<WardleyLink> = ArrayList()
  private val trends = LinkedHashMap<String, WardleyTrend>()
  private val pipelines = LinkedHashMap<String, WardleyPipeline>()
  private val annotations: MutableList<WardleyAnnotation> = ArrayList()
  private val notes: MutableList<WardleyNote> = ArrayList()
  private val accelerators: MutableList<WardleyForce> = ArrayList()
  private val deaccelerators: MutableList<WardleyForce> = ArrayList()
  private var annotationsBox: WardleyPoint? = null
  private var axes = WardleyAxesConfig()
  private var size: WardleySize? = null

  // A node added again keeps its class and label offsets unless the new one sets them.
  // PORT: upstream spreads the new node over the old one, so `inertia` and `sourceStrategy` are
  // replaced only when the caller passes those keys; `sets` says whether it does.
  fun addNode(node: WardleyNode, sets: Boolean = true) {
    val existing = this.nodes[node.id] ?: WardleyNode(node.id, node.label)
    this.nodes[node.id] = WardleyNode(
      id = node.id,
      label = node.label,
      x = node.x,
      y = node.y,
      className = node.className ?: existing.className,
      labelOffsetX = node.labelOffsetX ?: existing.labelOffsetX,
      labelOffsetY = node.labelOffsetY ?: existing.labelOffsetY,
      inPipeline = existing.inPipeline,
      isPipelineParent = existing.isPipelineParent,
      inertia = if (sets) node.inertia else existing.inertia,
      sourceStrategy = if (sets) node.sourceStrategy else existing.sourceStrategy,
    )
    this.labels = null
  }

  fun addLink(link: WardleyLink) {
    this.links.add(link)
  }

  fun addTrend(trend: WardleyTrend) {
    this.trends[trend.nodeId] = trend
  }

  fun startPipeline(nodeId: String) {
    this.pipelines[nodeId] = WardleyPipeline(nodeId, ArrayList())
    val node = this.nodes[nodeId]
    if (node != null) node.isPipelineParent = true
  }

  fun addPipelineComponent(pipelineNodeId: String, componentId: String) {
    this.pipelines[pipelineNodeId]?.componentIds?.add(componentId)
    val node = this.nodes[componentId]
    if (node != null) node.inPipeline = true
  }

  fun addAnnotation(annotation: WardleyAnnotation) {
    this.annotations.add(annotation)
  }

  fun addNote(note: WardleyNote) {
    this.notes.add(note)
  }

  fun addAccelerator(accelerator: WardleyForce) {
    this.accelerators.add(accelerator)
  }

  fun addDeaccelerator(deaccelerator: WardleyForce) {
    this.deaccelerators.add(deaccelerator)
  }

  fun setAnnotationsBox(x: Double, y: Double) {
    this.annotationsBox = WardleyPoint(x, y)
  }

  fun setAxes(partial: WardleyAxesConfig) {
    val axes = this.axes
    this.axes = WardleyAxesConfig(
      xLabel = partial.xLabel ?: axes.xLabel,
      yLabel = partial.yLabel ?: axes.yLabel,
      stages = partial.stages ?: axes.stages,
      stageBoundaries = partial.stageBoundaries ?: axes.stageBoundaries,
    )
  }

  fun setSize(width: Double, height: Double) {
    this.size = WardleySize(width, height)
  }

  fun getNode(id: String): WardleyNode? = this.nodes[id]

  // A name is a node id, or else the label of the first node that has it. Pipeline components
  // have ids like "Parent_Child" and are linked by label.
  fun resolveNodeId(name: String): String {
    if (this.nodes.containsKey(name)) return name
    var labels = this.labels
    if (labels == null) {
      labels = LinkedHashMap()
      this.labels = labels
      for ((id, node) in this.nodes) if (!labels.containsKey(node.label)) labels[node.label] = id
    }
    return labels[name] ?: name
  }

  fun build(): WardleyBuildResult {
    val nodes = ArrayList<WardleyNode>()
    for (node in this.nodes.values) {
      if (node.x == null || node.y == null) {
        throw PeleError("Node \"${node.label}\" is missing coordinates", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "wardley"))
      }
      nodes.add(node)
    }
    return WardleyBuildResult(
      nodes = nodes,
      links = ArrayList(this.links),
      trends = ArrayList(this.trends.values),
      pipelines = ArrayList(this.pipelines.values),
      annotations = ArrayList(this.annotations),
      notes = ArrayList(this.notes),
      accelerators = ArrayList(this.accelerators),
      deaccelerators = ArrayList(this.deaccelerators),
      annotationsBox = this.annotationsBox,
      axes = WardleyAxesConfig(this.axes.xLabel, this.axes.yLabel, this.axes.stages, this.axes.stageBoundaries),
      size = this.size,
    )
  }
}
