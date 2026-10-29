// Ported from Pele src/diagrams/wardley/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.wardley

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsStr
import pele.js.jsTrim
import pele.svg.Accessible

class WardleyModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  build: WardleyBuildResult,
) : WardleyBuildResult(
  build.nodes,
  build.links,
  build.trends,
  build.pipelines,
  build.annotations,
  build.notes,
  build.accelerators,
  build.deaccelerators,
  build.annotationsBox,
  build.axes,
  build.size,
),
  Accessible

// Values up to 1 are fractions; larger ones are already percentages.
private fun toPercent(value: Double, context: String): Double {
  val normalized = if (value <= 1) value * 100 else value
  if (normalized < 0 || normalized > 100) {
    throw PeleError(
      "$context must be between 0-1 (decimal) or 0-100 (percentage). Received: ${jsStr(value)}",
      PeleErrorCode.SEMANTIC,
      PeleErrorInfo(type = "wardley"),
    )
  }
  return normalized
}

private fun toCoordinates(visibility: Double, evolution: Double, context: String): WardleyPoint =
  WardleyPoint(toPercent(evolution, "$context evolution"), toPercent(visibility, "$context visibility"))

private fun flowFromPort(port: String?): String? =
  if (port == "+<>") "bidirectional" else if (port == "+<") "backward" else if (port == "+>") "forward" else null

private class ArrowFlow(val flow: String? = null, val label: String? = null)

private fun flowFromArrow(arrow: String?): ArrowFlow {
  if (arrow == null || !arrow.startsWith("+")) return ArrowFlow()
  val end = arrow.indexOf("'", 2)
  val label = if (arrow.getOrNull(1) == '\'' && end != -1) arrow.jsSlice(2, end) else null
  if (arrow.contains("<>")) return ArrowFlow("bidirectional", label)
  if (arrow.contains("<")) return ArrowFlow("backward", label)
  if (arrow.contains(">")) return ArrowFlow("forward", label)
  return ArrowFlow(label = label)
}

private fun offsets(label: LabelAst?): Pair<Double?, Double?> =
  if (label != null) Pair((if (label.negX) -1.0 else 1.0) * label.offsetX, (if (label.negY) -1.0 else 1.0) * label.offsetY) else Pair(null, null)

// Mermaid's populateDb: fills the builder from the syntax tree, in Mermaid's order.
fun populate(ast: WardleyAst, db: WardleyBuilder) {
  val size = ast.size
  if (size != null) db.setSize(size.width, size.height)

  val evolution = ast.evolution
  if (evolution != null) {
    db.setAxes(
      WardleyAxesConfig(
        stages = evolution.stages.map { stage ->
          if (!stage.secondName.isNullOrEmpty()) "${stage.name.jsTrim()} / ${stage.secondName!!.jsTrim()}" else stage.name.jsTrim()
        },
        stageBoundaries = evolution.stages.filter { stage -> stage.boundary != null }.map { stage -> stage.boundary!! },
      ),
    )
  }

  for (anchor in ast.anchors) {
    val at = toCoordinates(anchor.visibility, anchor.evolution, "Anchor \"${anchor.name}\"")
    db.addNode(WardleyNode(id = anchor.name, label = anchor.name, x = at.x, y = at.y, className = "anchor"), false)
  }

  for (component in ast.components) {
    val at = toCoordinates(component.visibility, component.evolution, "Component \"${component.name}\"")
    val (labelOffsetX, labelOffsetY) = offsets(component.label)
    db.addNode(
      WardleyNode(
        id = component.name,
        label = component.name,
        x = at.x,
        y = at.y,
        className = "component",
        labelOffsetX = labelOffsetX,
        labelOffsetY = labelOffsetY,
        inertia = component.inertia,
        sourceStrategy = component.decorator?.strategy,
      ),
    )
  }

  for (note in ast.notes) {
    val at = toCoordinates(note.visibility, note.evolution, "Note \"${note.text}\"")
    db.addNote(WardleyNote(note.text, at.x, at.y))
  }

  for (pipeline in ast.pipelines) {
    val parent = db.getNode(pipeline.parent)
    if (parent == null || parent.y == null) {
      throw PeleError(
        "Pipeline \"${pipeline.parent}\" must reference an existing component with coordinates.",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "wardley"),
      )
    }
    val y = parent.y
    db.startPipeline(pipeline.parent)
    for (component in pipeline.components) {
      val id = "${pipeline.parent}_${component.name}"
      val (labelOffsetX, labelOffsetY) = offsets(component.label)
      val x = toPercent(component.evolution, "Pipeline component \"${component.name}\" evolution")
      db.addNode(
        WardleyNode(
          id = id,
          label = component.name,
          x = x,
          y = y,
          className = "pipeline-component",
          labelOffsetX = labelOffsetX,
          labelOffsetY = labelOffsetY,
          inertia = null,
          sourceStrategy = null,
        ),
      )
      db.addPipelineComponent(pipeline.parent, id)
    }
  }

  for (link in ast.links) {
    val dashed = !link.arrow.isNullOrEmpty() && (link.arrow!!.contains("-.->") || link.arrow!!.contains(".-."))
    val fromArrow = flowFromArrow(link.arrow)
    val flow = flowFromPort(link.fromPort) ?: flowFromPort(link.toPort) ?: fromArrow.flow
    db.addLink(
      WardleyLink(
        source = db.resolveNodeId(link.from),
        target = db.resolveNodeId(link.to),
        dashed = dashed,
        label = fromArrow.label ?: link.linkLabel,
        flow = flow,
      ),
    )
  }

  for (evolve in ast.evolves) {
    val node = db.getNode(evolve.component)
    if (node?.y != null) {
      db.addTrend(WardleyTrend(evolve.component, toPercent(evolve.target, "Evolve target for \"${evolve.component}\""), node.y!!))
    }
  }

  if (ast.annotations.size > 0) {
    val at = toCoordinates(ast.annotations[0].x, ast.annotations[0].y, "Annotations box")
    db.setAnnotationsBox(at.x, at.y)
  }

  for (annotation in ast.annotation) {
    val at = toCoordinates(annotation.x, annotation.y, "Annotation ${jsStr(annotation.number)}")
    db.addAnnotation(WardleyAnnotation(annotation.number, listOf(WardleyPoint(at.x, at.y)), annotation.text))
  }

  for (accelerator in ast.accelerators) {
    val at = toCoordinates(accelerator.x, accelerator.y, "Accelerator \"${accelerator.name}\"")
    db.addAccelerator(WardleyForce(accelerator.name, at.x, at.y))
  }

  for (deaccelerator in ast.deaccelerators) {
    val at = toCoordinates(deaccelerator.x, deaccelerator.y, "Deaccelerator \"${deaccelerator.name}\"")
    db.addDeaccelerator(WardleyForce(deaccelerator.name, at.x, at.y))
  }
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun buildWardley(ast: WardleyAst, title: String?): WardleyModel {
  val db = WardleyBuilder()
  populate(ast, db)
  return WardleyModel(
    type = "wardley",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle = if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr = if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    build = db.build(),
  )
}
