// Ported from Pele src/diagrams/flowchart/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.isTruthy
import pele.js.jsNumber
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsString
import pele.js.jsSubstring
import pele.js.jsTrim
import pele.util.YamlValue
import pele.util.parseYaml

class FlowDbOptions(
  val maxEdges: Int? = null,
  val inheritDir: Boolean? = null,
  val direction: String? = null,
  val warn: ((String) -> Unit)? = null,
)

private fun labelType(type: String?): LabelType =
  if (type == "markdown" || type == "string" || type == "text") type else "markdown"

private fun unquote(text: String): String =
  if (text.startsWith("\"") && text.endsWith("\"")) text.jsSubstring(1, text.length - 1) else text

private fun countChar(ch: Char, str: String): Int {
  var count = 0
  for (c in str) if (c == ch) count++
  return count
}

private fun destructStartLink(raw: String): LinkInfo {
  var str = raw.jsTrim()
  var type = "arrow_open"
  when (str.getOrNull(0)) {
    '<' -> {
      type = "arrow_point"
      str = str.jsSlice(1)
    }
    'x' -> {
      type = "arrow_cross"
      str = str.jsSlice(1)
    }
    'o' -> {
      type = "arrow_circle"
      str = str.jsSlice(1)
    }
  }
  var stroke = "normal"
  if (str.contains('=')) stroke = "thick"
  if (str.contains('.')) stroke = "dotted"
  return LinkInfo(type, stroke)
}

private fun destructEndLink(raw: String): LinkInfo {
  val str = raw.jsTrim()
  var line = str.jsSlice(0, -1)
  var type = "arrow_open"
  when (str.jsSlice(-1)) {
    "x" -> {
      type = "arrow_cross"
      if (str.startsWith("x")) {
        type = "double_$type"
        line = line.jsSlice(1)
      }
    }
    ">" -> {
      type = "arrow_point"
      if (str.startsWith("<")) {
        type = "double_$type"
        line = line.jsSlice(1)
      }
    }
    "o" -> {
      type = "arrow_circle"
      if (str.startsWith("o")) {
        type = "double_$type"
        line = line.jsSlice(1)
      }
    }
  }
  var stroke = "normal"
  var length = line.length - 1
  if (line.startsWith("=")) stroke = "thick"
  if (line.startsWith("~")) stroke = "invisible"
  val dots = countChar('.', line)
  if (dots != 0) {
    stroke = "dotted"
    length = dots
  }
  return LinkInfo(type, stroke, length.toDouble())
}

private val RE_ESCAPED_COMMA = JsRegExp("""\\,""", "g")
private val RE_COMMA = JsRegExp(""",""", "g")
private val RE_MARK = JsRegExp("""§§§""", "g")
private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")
private val RE_SPACE = JsRegExp("""\s""")

class FlowDb(options: FlowDbOptions = FlowDbOptions()) : FlowchartModel {
  override val type = "flowchart"
  override var direction: String? = null
  override val nodes = LinkedHashMap<String, FlowNode>()
  override val edges = ArrayList<FlowEdge>()
  override val subgraphs = ArrayList<FlowSubgraph>()
  override val classes = LinkedHashMap<String, FlowClassDef>()
  override val tooltips = LinkedHashMap<String, String>()
  override var defaultEdgeStyle: MutableList<String>? = null
  override var defaultInterpolate: String? = null
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null

  private val subgraphLookup = HashMap<String, FlowSubgraph>()
  private val members = HashSet<String>()
  private val edgeById = HashMap<String, FlowEdge>()
  private val pairCount = HashMap<String, Int>()
  private var subCount = 0
  private val options: FlowDbOptions = options

  fun addVertex(
    id: String,
    textObj: FlowText? = null,
    type: String? = null,
    style: List<String>? = null,
    classes: List<String>? = null,
    dir: String? = null,
    props: Map<String, String>? = null,
    metadata: String? = null,
  ) {
    if (id == "" || id.jsTrim().isEmpty()) return

    var doc: MutableMap<String, YamlValue>? = null
    if (metadata != null) {
      val yaml = if (metadata.contains('\n')) metadata + "\n" else "{\n" + metadata + "\n}"
      val parsed: YamlValue
      try {
        parsed = parseYaml(yaml)
      } catch (error: Exception) {
        throw PeleError(
          "Metadata of \"$id\" is not valid YAML. ${error.message}",
          PeleErrorCode.SYNTAX,
          PeleErrorInfo(type = "flowchart"),
        )
      }
      @Suppress("UNCHECKED_CAST")
      doc = if (parsed is MutableMap<*, *>) parsed as MutableMap<String, YamlValue> else null
    }

    val subgraph = subgraphLookup[id]
    if (subgraph != null && doc != null) {
      // PORT: a spread into a new object; the YAML maps already keep JS key order.
      val merged = LinkedHashMap<String, YamlValue>()
      subgraph.metadata?.let { merged.putAll(it) }
      merged.putAll(doc)
      subgraph.metadata = pele.js.jsOrdered(merged)
      return
    }

    val edge = edgeById[id]
    if (edge != null) {
      if (doc != null && doc.containsKey("animate")) edge.animate = doc["animate"]
      if (doc != null && doc.containsKey("animation")) edge.animation = doc["animation"]
      // PORT: upstream stores the value as it is; one that is not a string only matters by its
      // truthiness.
      if (doc != null && doc.containsKey("curve"))
        edge.interpolate = doc["curve"].let { if (isTruthy(it)) jsString(it) else null }
      return
    }

    var vertex = nodes[id]
    if (vertex == null) {
      if (textObj == null && type == null && style != null) {
        options.warn?.invoke(
          "Style applied to unknown node \"$id\". This may indicate a typo. The node will be created automatically."
        )
      }
      vertex = FlowNode(id = id, labelType = "text", styles = ArrayList(), classes = ArrayList())
      nodes[id] = vertex
    }

    if (textObj != null) {
      vertex.labelType = textObj.type
      vertex.text = unquote(textObj.text.jsTrim())
    } else if (vertex.text == null) {
      vertex.text = id
    }
    if (type != null) vertex.type = type
    if (style != null) for (s in style) vertex.styles.add(s)
    if (classes != null) for (c in classes) vertex.classes.add(c)
    if (dir != null) vertex.dir = dir
    val ownProps = vertex.props
    if (ownProps == null) vertex.props = LinkedHashMap(props ?: emptyMap())
    else if (props != null) ownProps.putAll(props)

    if (doc == null) return

    if (isTruthy(doc["shape"])) {
      val shape = jsString(doc["shape"])
      if (shape != shape.lowercase() || shape.contains('_')) {
        throw PeleError(
          "No such shape: $shape. Shape names should be lowercase.",
          PeleErrorCode.SEMANTIC,
          PeleErrorInfo(type = "flowchart"),
        )
      }
      if (!isValidShape(shape)) {
        throw PeleError(
          "No such shape: $shape.",
          PeleErrorCode.SEMANTIC,
          PeleErrorInfo(type = "flowchart"),
        )
      }
      vertex.type = shape
    }
    val label = doc["label"] as? String
    if (isTruthy(doc["label"])) {
      vertex.text = jsString(doc["label"])
      vertex.labelType = labelType(doc["labelType"] as? String)
    }
    if (isTruthy(doc["icon"])) {
      vertex.icon = jsString(doc["icon"])
      if ((label?.jsTrim() ?: "") == "" && vertex.text == id) vertex.text = ""
    }
    if (isTruthy(doc["form"])) vertex.form = jsString(doc["form"])
    if (isTruthy(doc["pos"])) vertex.pos = jsString(doc["pos"])
    if (isTruthy(doc["img"])) {
      vertex.img = jsString(doc["img"])
      if ((label?.jsTrim() ?: "") == "" && vertex.text == id) vertex.text = ""
    }
    if (isTruthy(doc["constraint"])) vertex.constraint = jsString(doc["constraint"])
    if (isTruthy(doc["w"])) vertex.assetWidth = jsNumber(doc["w"])
    if (isTruthy(doc["h"])) vertex.assetHeight = jsNumber(doc["h"])
  }

  fun addSingleLink(start: String, end: String, type: LinkInfo?, id: String? = null) {
    val edge =
      FlowEdge(
        start = start,
        end = end,
        type = null,
        text = "",
        labelType = "text",
        classes = ArrayList(),
        isUserDefinedId = false,
        interpolate = defaultInterpolate,
      )
    val linkText = type?.text
    if (linkText != null) {
      edge.text = unquote(linkText.text.jsTrim())
      edge.labelType = labelType(linkText.type)
    }
    if (type != null) {
      edge.type = type.type
      edge.stroke = type.stroke
      val length = type.length
      edge.length = if (length != null && length > 10) 10.0 else length
    }
    val pair = start + "\u0000" + end
    val count = pairCount[pair] ?: 0
    if (!id.isNullOrEmpty() && !edgeById.containsKey(id)) {
      edge.id = id
      edge.isUserDefinedId = true
    } else {
      edge.id = "L_${start}_${end}_${if (count == 0) 0 else count + 1}"
    }

    val max = options.maxEdges
    if (max != null && edges.size >= max) {
      throw PeleError(
        "Edge limit exceeded. The limit is $max.",
        PeleErrorCode.LIMIT,
        PeleErrorInfo(type = "flowchart"),
      )
    }
    edges.add(edge)
    pairCount[pair] = count + 1
    if (!edgeById.containsKey(edge.id!!)) edgeById[edge.id!!] = edge
  }

  fun addLink(starts: List<String>, ends: List<String>, linkData: LinkInfo?) {
    val id = linkData?.id?.jsReplace("@", "")
    val lastStart = starts[starts.size - 1]
    val firstEnd = ends[0]
    for (start in starts) {
      for (end in ends) {
        addSingleLink(start, end, linkData, if (start == lastStart && end == firstEnd) id else null)
      }
    }
  }

  fun updateLinkInterpolate(positions: List<String>, interpolate: String) {
    for (pos in positions) {
      if (pos == "default") defaultInterpolate = interpolate
      else edgeAt(pos).interpolate = interpolate
    }
  }

  fun updateLink(positions: List<String>, style: MutableList<String>) {
    for (pos in positions) {
      if (pos == "default") {
        defaultEdgeStyle = style
        continue
      }
      val edge = edgeAt(pos)
      edge.style = style
      if (style.size > 0 && !style.any { s -> s.startsWith("fill") }) style.add("fill:none")
    }
  }

  private fun edgeAt(pos: String): FlowEdge {
    val index = jsNumber(pos)
    // PORT: an array index must be an integer in range; anything else is undefined.
    val edge =
      if (index >= 0 && index < edges.size && index == kotlin.math.floor(index))
        edges[index.toInt()]
      else null
    if (edge == null) {
      throw PeleError(
        "The index $pos for linkStyle is out of bounds. Valid indices for linkStyle are between 0 and ${
          edges.size - 1
        }. (Help: Ensure that the index is within the range of existing edges.)",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "flowchart"),
      )
    }
    return edge
  }

  fun addClass(ids: String, styles: List<String>) {
    val style =
      styles
        .joinToString(",")
        .jsReplace(RE_ESCAPED_COMMA, "§§§")
        .jsReplace(RE_COMMA, ";")
        .jsReplace(RE_MARK, ",")
        .jsSplit(";")
    for (id in ids.jsSplit(",")) {
      var classNode = classes[id]
      if (classNode == null) {
        classNode = FlowClassDef(id, ArrayList(), ArrayList())
        classes[id] = classNode
      }
      for (s in style) {
        if (s.contains("color")) classNode.textStyles.add(s.jsReplace("fill", "bgFill"))
        classNode.styles.add(s)
      }
    }
  }

  @JvmName("applyDirection")
  fun setDirection(dir: String) {
    var d = dir.jsTrim()
    if (d.contains('<')) d = "RL"
    if (d.contains('^')) d = "BT"
    if (d.contains('>')) d = "LR"
    if (d.contains('v')) d = "TB"
    if (d == "TD") d = "TB"
    direction = d
  }

  fun setClass(ids: String, className: String) {
    for (id in ids.jsSplit(",")) {
      nodes[id]?.classes?.add(className)
      edgeById[id]?.classes?.add(className)
      subgraphLookup[id]?.classes?.add(className)
    }
  }

  fun setTooltip(ids: String, tooltip: String?) {
    if (tooltip == null) return
    for (id in ids.jsSplit(",")) tooltips[id] = tooltip
  }

  fun setLink(ids: String, link: String, target: String? = null) {
    val url = link.jsTrim()
    for (id in ids.jsSplit(",")) {
      val vertex = nodes[id]
      if (vertex != null) {
        vertex.link = if (url != "") pele.util.safeUrl(url) else null
        vertex.linkTarget = target
      }
    }
    setClass(ids, "clickable")
  }

  // Callbacks are never executed; the node is only marked clickable, as in Mermaid's strict mode.
  @Suppress("UNUSED_PARAMETER")
  fun setClickEvent(ids: String, functionName: String? = null, functionArgs: String? = null) {
    setClass(ids, "clickable")
  }

  @JvmName("applyAccTitle")
  fun setAccTitle(text: String) {
    accTitle = text.jsReplace(RE_LEADING, "")
  }

  fun setAccDescription(text: String) {
    accDescr = text.jsReplace(RE_INDENT, "\n")
  }

  fun addSubGraph(idText: FlowText?, list: List<DocItem>, titleText: FlowText?): String {
    var id: String? = idText?.text?.jsTrim()
    var title = titleText?.text ?: ""
    if (idText != null && idText === titleText && RE_SPACE.test(title)) id = null

    val seen = HashSet<String>()
    val nodeList = ArrayList<String>()
    var dir: String? = null
    for (entry in list) {
      if (entry is String) {
        if (entry.jsTrim() != "" && !seen.contains(entry)) {
          seen.add(entry)
          nodeList.add(entry)
        }
      } else if (entry is List<*>) {
        for (item in entry) {
          item as String
          if (item.jsTrim() != "" && !seen.contains(item)) {
            seen.add(item)
            nodeList.add(item)
          }
        }
      } else {
        dir = (entry as DirStatement).value
      }
    }
    if (dir == null)
      dir = if (options.inheritDir == true) (direction ?: options.direction) else null

    val sgId = id ?: ("subGraph" + subCount)
    title = if (title != "") title else ""
    subCount++

    val subgraph =
      FlowSubgraph(
        id = sgId,
        nodes = nodeList,
        title = title.jsTrim(),
        classes = ArrayList(),
        dir = dir,
        labelType = labelType(titleText?.type),
      )
    // A node belongs to the first subgraph that lists it, and a subgraph never contains itself.
    val members = this.members
    subgraph.nodes =
      nodeList.filter { nodeId -> !members.contains(nodeId) && nodeId != sgId }.toMutableList()
    for (nodeId in subgraph.nodes) members.add(nodeId)

    val existing = subgraphLookup[sgId]
    if (existing != null) for (nodeId in subgraph.nodes) existing.nodes.add(nodeId)
    else subgraphs.add(subgraph)
    subgraphLookup[sgId] = existing ?: subgraph
    return sgId
  }

  fun exists(all: List<FlowSubgraph>, id: String): Boolean {
    for (sg in all) if (sg.nodes.contains(id)) return true
    return false
  }

  fun makeUniq(sg: FlowSubgraph, all: List<FlowSubgraph>): List<String> {
    val taken = HashSet<String>()
    for (other in all) for (id in other.nodes) taken.add(id)
    return sg.nodes.filter { id -> !taken.contains(id) }
  }

  fun destructLink(end: String, start: String? = null): LinkInfo {
    val info = destructEndLink(end)
    if (start.isNullOrEmpty()) return info
    val startInfo = destructStartLink(start)
    if (startInfo.stroke != info.stroke) return LinkInfo("INVALID", "INVALID")
    if (startInfo.type == "arrow_open") {
      startInfo.type = info.type
    } else {
      if (startInfo.type != info.type) return LinkInfo("INVALID", "INVALID")
      startInfo.type = "double_" + startInfo.type
    }
    if (startInfo.type == "double_arrow") startInfo.type = "double_arrow_point"
    startInfo.length = info.length
    return startInfo
  }

  fun subgraph(id: String): FlowSubgraph? = subgraphLookup[id]
}
