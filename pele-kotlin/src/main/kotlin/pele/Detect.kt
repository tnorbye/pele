// Ported from Pele src/detect.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele

import pele.js.JsRegExp

typealias DiagramType = String

typealias MermaidType = String

// Mermaid's detectors, in the order Mermaid registers them. The first match wins.
private val DETECTORS: List<Triple<DiagramType, MermaidType, JsRegExp>> =
  listOf(
    Triple("flowchart", "flowchart-elk", JsRegExp("^\\s*flowchart-elk")),
    Triple("mindmap", "mindmap", JsRegExp("^\\s*mindmap")),
    Triple("architecture", "architecture", JsRegExp("^\\s*architecture")),
    Triple("agentflow", "agentflow", JsRegExp("^\\s*agentflow-beta\\b")),
    Triple(
      "c4",
      "c4",
      JsRegExp("^\\s*(?:C4Context|C4Container|C4Component|C4Dynamic|C4Deployment)"),
    ),
    Triple("kanban", "kanban", JsRegExp("^\\s*kanban")),
    Triple("class", "classDiagram", JsRegExp("^\\s*classDiagram")),
    Triple("er", "er", JsRegExp("^\\s*erDiagram")),
    Triple("gantt", "gantt", JsRegExp("^\\s*gantt")),
    Triple("info", "info", JsRegExp("^\\s*info")),
    Triple("pie", "pie", JsRegExp("^\\s*pie")),
    Triple("requirement", "requirement", JsRegExp("^\\s*requirement(Diagram)?")),
    Triple("sequence", "sequence", JsRegExp("^\\s*sequenceDiagram")),
    Triple("swimlane", "swimlane", JsRegExp("^\\s*swimlane-beta\\b")),
    Triple("flowchart", "flowchart-v2", JsRegExp("^\\s*(graph|flowchart)")),
    Triple("timeline", "timeline", JsRegExp("^\\s*timeline")),
    Triple("gitGraph", "gitGraph", JsRegExp("^\\s*gitGraph")),
    Triple("state", "stateDiagram", JsRegExp("^\\s*stateDiagram")),
    Triple("journey", "journey", JsRegExp("^\\s*journey")),
    Triple("quadrantChart", "quadrantChart", JsRegExp("^\\s*quadrantChart")),
    Triple("sankey", "sankey", JsRegExp("^\\s*sankey(-beta)?")),
    Triple("packet", "packet", JsRegExp("^\\s*packet(-beta)?")),
    Triple("xychart", "xychart", JsRegExp("^\\s*xychart(-beta)?")),
    Triple("block", "block", JsRegExp("^\\s*block(-beta)?")),
    Triple("eventmodeling", "eventmodeling", JsRegExp("^\\s*eventmodeling")),
    Triple("treeView", "treeView", JsRegExp("^\\s*treeView-beta")),
    Triple("radar", "radar", JsRegExp("^\\s*radar-beta")),
    Triple("ishikawa", "ishikawa", JsRegExp("^\\s*ishikawa(-beta)?\\b", "i")),
    Triple("treemap", "treemap", JsRegExp("^\\s*treemap")),
    Triple("railroad", "railroad", JsRegExp("^\\s*railroad-beta", "i")),
    Triple("railroad", "railroadEbnf", JsRegExp("^\\s*railroad-ebnf-beta", "i")),
    Triple("railroad", "railroadAbnf", JsRegExp("^\\s*railroad-abnf-beta", "i")),
    Triple("railroad", "railroadPeg", JsRegExp("^\\s*railroad-peg-beta", "i")),
    Triple("venn", "venn", JsRegExp("^\\s*venn-beta")),
    Triple("wardley", "wardley", JsRegExp("^\\s*wardley-beta", "i")),
    Triple("cynefin", "cynefin", JsRegExp("^\\s*cynefin-beta(?:[\\s:]|$)")),
    Triple("usecase", "usecase", JsRegExp("^\\s*usecase-beta(?:\\s|$)")),
  )

class Detected(val type: DiagramType, val mermaidType: MermaidType)

fun detectDiagram(cleaned: String): Detected? {
  for ((type, mermaidType, re) in DETECTORS) if (re.test(cleaned))
    return Detected(type, mermaidType)
  return null
}

fun detect(cleaned: String): DiagramType? = detectDiagram(cleaned)?.type
