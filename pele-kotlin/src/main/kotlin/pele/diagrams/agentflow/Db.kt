// Ported from Pele src/diagrams/agentflow/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.isTruthy
import pele.js.jsKeys
import pele.js.jsNumber
import pele.js.jsOrdered
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsString
import pele.js.jsSubstring
import pele.js.jsTrim
import pele.js.jsTrimStart
import pele.js.jsonStringify
import pele.locate
import pele.util.YamlValue
import pele.util.parseYaml
import pele.util.safeUrl

class AgentflowDbOptions(
  val maxEdges: Int? = null,
  val inheritDir: Boolean? = null,
  val direction: String? = null,
  // Lines of front matter before the text the parser sees, added to every reported line.
  val lineOffset: Int? = null,
  // The text the parser sees, where errors are located.
  val source: String? = null,
  val log: ((severity: String, message: String) -> Unit)? = null,
)

// Presentation-only metadata keys, left out of the semantic model.
private val PRESENTATION_KEYS: Set<String> = hashSetOf(
  "shape",
  "view",
  "icon",
  "img",
  "form",
  "pos",
  "w",
  "h",
  "class",
  "style",
  "labelType",
)

private val TOOL_SHAPES: Set<String> = hashSetOf("subroutine", "subprocess", "subproc", "framed-rectangle", "tool")

fun isToolDefinition(vertex: AgentNode): Boolean {
  val type = vertex.type
  return type != null && TOOL_SHAPES.contains(type)
}

private fun labelType(type: Any?): LabelType =
  if (type == "markdown" || type == "string" || type == "text") type as String else "markdown"

private fun unquote(text: String): String =
  if (text.startsWith("\"") && text.endsWith("\"")) text.jsSubstring(1, text.length - 1) else text

private fun isObject(value: Any?): Boolean = value is Map<*, *>

@Suppress("UNCHECKED_CAST")
private fun asMetadata(value: Any?): Metadata = value as Metadata

private fun isContainer(value: Any?): Boolean = value is Map<*, *> || value is List<*>

// Drops `__proto__`, `constructor` and `prototype` keys at every level, so that a consumer
// merging metadata into its own objects cannot be made to pollute a prototype.
private fun stripPrototypeKeys(root: YamlValue): YamlValue {
  val copy = { value: YamlValue ->
    if (value is List<*>) ArrayList<YamlValue>() else if (isObject(value)) LinkedHashMap<String, YamlValue>() else value
  }
  val out = copy(root)
  val pending = ArrayList<Pair<YamlValue, YamlValue>>()
  pending.add(Pair(root, out))
  while (pending.size > 0) {
    val (from, to) = pending.removeAt(pending.size - 1)
    if (from is List<*>) {
      @Suppress("UNCHECKED_CAST")
      val target = to as MutableList<YamlValue>
      for (entry in from) {
        val next = copy(entry)
        target.add(next)
        if (isContainer(entry)) pending.add(Pair(entry, next))
      }
    } else if (isObject(from)) {
      val source = asMetadata(from)
      val target = asMetadata(to)
      for (key in jsKeys(source)) {
        if (key == "__proto__" || key == "constructor" || key == "prototype") continue
        val entry = source[key]
        val next = copy(entry)
        target[key] = next
        if (isContainer(entry)) pending.add(Pair(entry, next))
      }
    }
  }
  return out
}

// Adds what a metadata block holds to an element's metadata. Mermaid spreads the parsed YAML
// whatever it is, so a list adds its indexes as keys and a string its characters.
private fun merge(into: Metadata?, doc: YamlValue): Metadata {
  val target: Metadata = into ?: LinkedHashMap()
  if (doc is Map<*, *>) {
    for ((k, v) in asMetadata(doc)) target[k] = v
  } else if (doc is List<*>) {
    for (i in doc.indices) target[i.toString()] = doc[i]
  } else if (doc is String) {
    for (i in doc.indices) target[i.toString()] = doc[i].toString()
  }
  // PORT: a JS object keeps integer-like keys first, in ascending order.
  val ordered = jsOrdered(target)
  target.clear()
  target.putAll(ordered)
  return target
}

private val RE_BLOCK_SCALAR = JsRegExp(""":\s*[>|][\d+-]*\s*$""")
private val RE_TRAILING_COMMA = JsRegExp(""",([\t ]*)$""")
private val RE_ESCAPED_COMMA = JsRegExp("""\\,""", "g")
private val RE_COMMA = JsRegExp(""",""", "g")
private val RE_MARK = JsRegExp("""§§§""", "g")
private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")
private val RE_SPACE = JsRegExp("""\s""")

// Removes the comma that ends a line of a multi-line metadata body, which block YAML does not allow.
// Commas inside quoted scalars, flow collections and block scalars are content and stay.
private fun stripLineTrailingCommas(body: String): String {
  var inSingle = false
  var inDouble = false
  var flowDepth = 0
  var blockScalarIndent: Int? = null
  val out = ArrayList<String>()
  for (line in body.jsSplit("\n")) {
    if (blockScalarIndent != null) {
      val indent = line.length - line.jsTrimStart().length
      if (line.jsTrim() == "" || indent > blockScalarIndent) {
        out.add(line)
        continue
      }
      blockScalarIndent = null
    }
    var commentStart = -1
    var i = 0
    while (i < line.length) {
      val ch = line[i]
      if (inDouble) {
        if (ch == '\\') i++
        else if (ch == '"') inDouble = false
      } else if (inSingle) {
        if (ch == '\'') inSingle = false
      } else if (ch == '"') {
        inDouble = true
      } else if (ch == '\'') {
        inSingle = true
      } else if (ch == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t')) {
        commentStart = i
        break
      } else if (ch == '[' || ch == '{') {
        flowDepth++
      } else if (ch == ']' || ch == '}') {
        flowDepth = maxOf(0, flowDepth - 1)
      }
      i++
    }
    if (inSingle || inDouble || flowDepth > 0) {
      out.add(line)
      continue
    }
    val code = if (commentStart >= 0) line.jsSlice(0, commentStart) else line
    val comment = if (commentStart >= 0) line.jsSlice(commentStart) else ""
    if (RE_BLOCK_SCALAR.test(code)) {
      blockScalarIndent = line.length - line.jsTrimStart().length
      out.add(line)
      continue
    }
    out.add(code.jsReplace(RE_TRAILING_COMMA, "$1") + comment)
  }
  return out.joinToString("\n")
}

private fun edgeSemantic(type: String, stroke: String): EdgeSemantic? {
  if (type == "arrow_point" && stroke == "normal") return "sequence"
  if (type == "arrow_cross" && stroke == "normal") return "failure"
  if (type == "arrow_open" && stroke == "dotted") return "reference"
  return null
}

private class EndLink(val type: String, val stroke: String, val length: Double)

private fun destructEndLink(raw: String): EndLink {
  val str = raw.jsTrim()
  var line = str.jsSlice(0, -1)
  var type = "arrow_open"
  when (str.jsSlice(-1)) {
    "x" -> type = "arrow_cross"
    ">" -> type = "arrow_point"
    "-", "." -> line = str
  }
  var stroke = "normal"
  var length = (line.length - 1).toDouble()
  var dots = 0
  for (i in line.indices) if (line[i] == '.') dots++
  if (dots > 0) {
    stroke = "dotted"
    length = dots.toDouble()
  }
  return EndLink(type, stroke, length)
}

private fun semanticMetadata(metadata: Metadata?): Metadata? {
  if (metadata == null) return null
  val out: Metadata = LinkedHashMap()
  var any = false
  for (key in jsKeys(metadata)) {
    if (PRESENTATION_KEYS.contains(key)) continue
    out[key] = metadata[key]
    any = true
  }
  return if (any) out else null
}

class AgentflowDb(options: AgentflowDbOptions = AgentflowDbOptions()) : AgentflowModel, ShapeSink {
  override val type = "agentflow"
  override var direction: String? = null
  override val nodes = LinkedHashMap<String, AgentNode>()
  override val edges = ArrayList<AgentEdge>()
  override val subgraphs = ArrayList<AgentSubgraph>()
  override val connectors = LinkedHashMap<String, AgentNode>()
  override val classes = LinkedHashMap<String, AgentClassDef>()
  override val tooltips = LinkedHashMap<String, String>()
  override var defaultEdgeStyle: MutableList<String>? = null
  override var defaultInterpolate: String? = null
  override val mappings = ArrayList<ElementMapping>()
  override val diagnostics = ArrayList<Diagnostic>()
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  var lineOffset: Int

  private val subgraphLookup = HashMap<String, AgentSubgraph>()
  private val globalNodes = HashSet<String>()
  private val members = HashSet<String>()
  private val edgeById = HashMap<String, AgentEdge>()
  private val pairCount = HashMap<String, Int>()
  private var subCount = 0
  private val bareMappings: MutableSet<ElementMapping> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
  private val firstMapping = HashMap<String, ElementMapping>()
  private val reported = HashMap<String, MutableList<Diagnostic>>()
  private val options: AgentflowDbOptions = options

  init {
    lineOffset = options.lineOffset ?: 0
  }

  private fun metadata(id: String, source: String, loc: Loc?): YamlValue {
    val multiline = source.contains('\n')
    var parsed: YamlValue
    try {
      parsed = parseYaml(if (multiline) source + "\n" else "{\n" + source + "\n}")
    } catch (error: Exception) {
      // Block YAML rejects the comma-separated style of the single-line form, so try once more without the commas.
      val stripped = if (multiline) stripLineTrailingCommas(source) else source
      var retried: YamlValue = null
      var retriedOk = false
      if (stripped != source) {
        try {
          retried = parseYaml(stripped + "\n")
          retriedOk = true
        } catch (e: Exception) {
          retriedOk = false
        }
      }
      // PORT: upstream tells "no result" from a result by undefined; a YAML null is a result there.
      if (!retriedOk) {
        val at = errorAt(loc)
        val where = if (loc != null) " (${at.first}:${at.second})" else ""
        throw PeleError(
          "Metadata of \"$id\" is not valid YAML. ${error.message}$where",
          PeleErrorCode.SYNTAX,
          PeleErrorInfo(type = "agentflow", line = at.first, column = at.second),
        )
      }
      parsed = retried
    }
    return stripPrototypeKeys(parsed ?: LinkedHashMap<String, YamlValue>())
  }

  private fun errorAt(loc: Loc?): Pair<Int, Int> {
    if (loc == null) return Pair(0, 0)
    val source = options.source
    if (source == null) return Pair(loc.first_line + lineOffset, loc.first_column + 1)
    var start = 0
    for (line in 1 until loc.first_line) start = source.indexOf('\n', start) + 1
    val found = locate(source, start + loc.first_column)
    return Pair(found.line, found.column)
  }

  private fun shapeError(message: String, loc: Loc?): PeleError {
    val at = errorAt(loc)
    return PeleError(message, PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "agentflow", line = at.first, column = at.second))
  }

  fun addVertex(
    id: String,
    textObj: FlowText? = null,
    type: String? = null,
    style: List<String>? = null,
    classes: List<String>? = null,
    dir: String? = null,
    props: Map<String, String>? = null,
    metadata: String? = null,
    metadataLoc: Loc? = null,
  ) {
    if (id == "" || id.jsTrim().isEmpty()) return

    var doc: YamlValue = null
    var hasDoc = false
    var fields: Metadata? = null
    var authoredShape: String? = null
    if (metadata != null) {
      doc = metadata(id, metadata, metadataLoc)
      hasDoc = true
      if (isObject(doc)) {
        fields = asMetadata(doc)
        val shape = fields["shape"]
        if (shape is String) {
          authoredShape = shape
          fields["shape"] = resolveShapeAlias(shape)
        }
      }
    }
    // `doc` is truthy only when it holds something JS counts as true.
    val docTruthy = hasDoc && isTruthy(doc)

    val subgraph = subgraphLookup[id]
    if (subgraph != null && docTruthy) {
      subgraph.metadata = merge(subgraph.metadata, doc)
      return
    }

    val connector = connectors[id]
    if (connector != null && docTruthy) {
      connector.metadata = merge(connector.metadata, doc)
      return
    }

    // Mermaid reserves this id and creates no node for it.
    if (id == "connectors") return

    val edge = edgeById[id]
    if (edge != null) {
      if (fields != null && fields.containsKey("animate")) edge.animate = fields["animate"]
      if (fields != null && fields.containsKey("animation")) edge.animation = fields["animation"]
      // PORT: upstream stores the value as it is; one that is not a string only matters by its truthiness.
      if (fields != null && fields.containsKey("curve")) edge.interpolate = fields["curve"].let { if (isTruthy(it)) jsString(it) else null }
      if (docTruthy) edge.metadata = merge(edge.metadata, doc)
      return
    }

    var vertex = nodes[id]
    if (vertex == null) {
      vertex = AgentNode(id = id, labelType = "text", styles = ArrayList(), classes = ArrayList())
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

    if (!docTruthy) return
    vertex.metadata = merge(vertex.metadata, doc)
    if (fields == null) return

    if (isTruthy(fields["shape"])) {
      val authored: Any? = authoredShape ?: fields["shape"]
      if (authored !is String) {
        throw shapeError("No such shape: ${jsonStringify(authored)}.", metadataLoc)
      }
      if (authored != authored.lowercase() || authored.contains('_')) {
        throw shapeError("No such shape: $authored. Shape names should be lowercase.", metadataLoc)
      }
      val shape = fields["shape"] as String
      if (!isValidShape(shape)) throw shapeError("No such shape: $shape.", metadataLoc)
      vertex.type = shape
    }
    if (isTruthy(fields["label"])) {
      vertex.text = jsString(fields["label"])
      vertex.labelType = labelType(fields["labelType"])
    }
  }

  fun addSingleLink(start: String, end: String, type: LinkInfo?, id: String? = null) {
    val edge = AgentEdge(
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
      if (!type.edgeSemantic.isNullOrEmpty()) edge.edgeSemantic = type.edgeSemantic
    }
    val pair = start + "\u0000" + end
    val existing = pairCount[pair] ?: 0
    if (!id.isNullOrEmpty() && !edgeById.containsKey(id)) {
      edge.id = id
      edge.isUserDefinedId = true
    } else {
      edge.id = "L_${start}_${end}_${if (existing == 0) 0 else existing + 1}"
    }

    val max = options.maxEdges
    if (max != null && edges.size >= max) {
      throw PeleError("Edge limit exceeded. The limit is $max.", PeleErrorCode.LIMIT, PeleErrorInfo(type = "agentflow"))
    }
    edges.add(edge)
    pairCount[pair] = existing + 1
    if (!edgeById.containsKey(edge.id!!)) edgeById[edge.id!!] = edge
  }

  fun addLink(starts: List<String>, ends: List<String>, linkData: LinkInfo?) {
    val linkId = linkData?.id
    val id = if (linkId != null) linkId.jsReplace("@", "") else null
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

  // Mermaid checks the bounds only for a numeric index and crashes on the strings its own parser passes.
  private fun edgeAt(pos: String): AgentEdge {
    val index = jsNumber(pos)
    val edge = if (index >= 0 && index < edges.size && index == Math.floor(index)) edges[index.toInt()] else null
    if (edge == null) {
      throw PeleError(
        "The index $pos for linkStyle is out of bounds. Valid indices for linkStyle are between 0 and ${
          edges.size - 1
        }. (Help: Ensure that the index is within the range of existing edges.)",
        PeleErrorCode.SEMANTIC,
        PeleErrorInfo(type = "agentflow"),
      )
    }
    return edge
  }

  fun addClass(ids: String, styles: List<String>) {
    val style = styles.joinToString(",").jsReplace(RE_ESCAPED_COMMA, "§§§").jsReplace(RE_COMMA, ";").jsReplace(RE_MARK, ",").jsSplit(";")
    for (id in ids.jsSplit(",")) {
      var classNode = classes[id]
      if (classNode == null) {
        classNode = AgentClassDef(id, ArrayList(), ArrayList())
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
        vertex.link = if (url != "") safeUrl(url) else null
        vertex.linkTarget = target
      }
    }
    setClass(ids, "clickable")
  }

  // Callbacks are never executed; the node is only marked clickable, as in Mermaid's strict mode.
  fun setClickEvent(ids: String, functionName: String? = null, functionArgs: String? = null) {
    setClass(ids, "clickable")
  }

  @JvmName("applyAccTitle")
  fun setAccTitle(text: String) {
    accTitle = text.jsReplace(RE_LEADING, "")
  }

  @JvmName("applyAccDescription")
  fun setAccDescription(text: String) {
    accDescr = text.jsReplace(RE_INDENT, "\n")
  }

  fun addSubGraph(
    idText: FlowText?,
    list: List<DocItem>,
    titleText: FlowText?,
    type: String? = null,
  ): String {
    var id: String? = idText?.text?.jsTrim()
    val title = titleText?.text ?: ""
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
    if (dir == null) dir = if (options.inheritDir == true) (direction ?: options.direction) else null

    if (id == null) id = "subGraph$subCount"
    subCount++

    // A flow never contains itself, a globally scoped node, or a node another flow already holds.
    val subId: String = id
    val nodes = nodeList.filter { nodeId -> nodeId != subId && !globalNodes.contains(nodeId) && !members.contains(nodeId) }.toMutableList()
    for (nodeId in nodes) members.add(nodeId)

    val existing = subgraphLookup[id]
    if (existing != null) {
      // A repeated id adds its members to the first flow; its title and direction win.
      for (nodeId in nodes) existing.nodes.add(nodeId)
      if (title.jsTrim() != "") existing.title = title.jsTrim()
      if (!dir.isNullOrEmpty()) existing.dir = dir
      return id
    }
    val subgraph = AgentSubgraph(
      id = id,
      nodes = nodes,
      title = title.jsTrim(),
      classes = ArrayList(),
      dir = dir,
      labelType = labelType(titleText?.type),
      type = "flow",
    )
    subgraphs.add(subgraph)
    subgraphLookup[id] = subgraph
    return id
  }

  // Every node named inside a `global` block stays out of the flows that mention it.
  fun addGlobal(list: List<DocItem>) {
    var release = false
    val add = { item: String ->
      val id = item.jsTrim()
      if (id != "") {
        globalNodes.add(id)
        if (members.remove(id)) release = true
      }
    }
    for (entry in list) {
      if (entry is String) add(entry)
      else if (entry is List<*>) for (item in entry) add(item as String)
    }
    if (!release) return
    for (subgraph in subgraphs) {
      subgraph.nodes = subgraph.nodes.filter { nodeId -> !globalNodes.contains(nodeId) }.toMutableList()
    }
  }

  fun addConnector(textObj: FlowText?, titleObj: FlowText? = null): String {
    val id = textObj?.text?.jsTrim()
    if (id.isNullOrEmpty()) return ""
    val title = titleObj?.text?.jsTrim() ?: ""
    val connector = connectors[id]
    if (connector == null) {
      val created = AgentNode(
        id = id,
        labelType = "text",
        styles = ArrayList(),
        classes = ArrayList(),
        isConnector = true,
        text = if (title.isNotEmpty()) title else id,
      )
      connectors[id] = created
      nodes[id] = created
    } else if (title.isNotEmpty()) {
      connector.text = title
    }
    return id
  }

  fun destructLink(end: String, start: String? = null): LinkInfo {
    val info = destructEndLink(end)
    if (!start.isNullOrEmpty()) {
      // The first half of a split arrow only says whether the line is dotted.
      val stroke = if (start.contains('.')) "dotted" else "normal"
      if (stroke != info.stroke) return LinkInfo(type = "INVALID", stroke = "INVALID")
    }
    return LinkInfo(type = info.type, stroke = info.stroke, length = info.length, edgeSemantic = edgeSemantic(info.type, info.stroke))
  }

  fun subgraph(id: String): AgentSubgraph? = subgraphLookup[id]

  fun vertexKind(vertex: AgentNode): VertexKind {
    if (isToolDefinition(vertex)) return "tool"
    val shape = resolveShapeAlias(vertex.type)
    if (shape == "hexagon" || shape == "hex") return "action"
    if (shape == "lean-right" || shape == "lean_right") return "input"
    if (shape == "lin-doc" || shape == "lined-document") return "refdoc"
    if (shape == "diamond") return "decision"
    return "task"
  }

  override fun semanticModel(): SemanticModel {
    val vertices = ArrayList<SemanticVertex>()
    val connectors = ArrayList<SemanticConnector>()
    for ((id, v) in nodes) {
      if (subgraphLookup.containsKey(id)) continue
      val metadata = semanticMetadata(v.metadata)
      if (v.isConnector == true) {
        val connector = SemanticConnector(id)
        if (v.text != null && v.text != id) connector.title = v.text
        if (metadata != null) connector.metadata = metadata
        connectors.add(connector)
        continue
      }
      val vertex = SemanticVertex(id)
      if (v.text != null) vertex.label = v.text
      val shape = resolveShapeAlias(v.type)
      if (shape != null) vertex.shape = shape
      vertex.vertexKind = vertexKind(v)
      if (metadata != null) vertex.metadata = metadata
      vertices.add(vertex)
    }

    val edges = this.edges.map { e ->
      val edge = SemanticEdge(e.start, e.end)
      if (e.id != null) edge.id = e.id
      if (e.text.isNotEmpty()) edge.label = e.text
      if (e.type != null) edge.type = e.type
      if (e.stroke != null) edge.stroke = e.stroke
      if (e.edgeSemantic != null) edge.edgeSemantic = e.edgeSemantic
      if (e.length != null) edge.length = e.length
      val md = e.metadata
      if (md != null && md.size > 0) edge.metadata = LinkedHashMap(md)
      edge
    }

    val subGraphs = subgraphs.map { sg ->
      val out = SemanticSubgraph(id = sg.id, nodes = ArrayList(sg.nodes), type = sg.type, title = sg.title)
      if (sg.dir != null) out.direction = sg.dir
      val metadata = semanticMetadata(sg.metadata)
      if (metadata != null) out.metadata = metadata
      out
    }

    val model = SemanticModel(vertices = vertices, edges = edges, subGraphs = subGraphs, connectors = connectors, diagnostics = diagnostics)
    if (direction != null) model.direction = direction
    return model
  }

  private fun position(loc: Loc?): ElementPosition {
    val startLine = loc?.first_line ?: 0
    val startColumn = loc?.first_column ?: 0
    return ElementPosition(
      startLine = startLine + lineOffset,
      startColumn = startColumn,
      endLine = (loc?.last_line ?: startLine) + lineOffset,
      endColumn = loc?.last_column ?: startColumn,
      // Mermaid builds its parser without ranges, so these are always zero.
      startIndex = 0,
      endIndex = 0,
    )
  }

  private fun pushMapping(id: String, type: StatementType, position: ElementPosition): ElementMapping {
    val mapping = ElementMapping(id, type, position)
    mappings.add(mapping)
    if (!firstMapping.containsKey(id)) firstMapping[id] = mapping
    return mapping
  }

  fun addVertexMapping(id: String, text: Any?, shape: Any?, loc: Loc?) {
    if (id == "") return
    val mapping = pushMapping(id, "vertex", position(loc))
    // A bare reference becomes an attachment if a metadata block follows it.
    if (shape == null) bareMappings.add(mapping)
  }

  // Widens a node's span over the metadata block that follows it.
  fun extendVertexMapping(id: String, loc: Loc?) {
    if (id == "" || loc == null) return
    val end = position(loc)
    for (i in mappings.size - 1 downTo 0) {
      val m = mappings[i]
      if (m.type != "vertex" || m.id != id) continue
      if (bareMappings.remove(m)) m.type = "attachment"
      val p = m.position
      if (end.endLine > p.endLine || (end.endLine == p.endLine && end.endColumn > p.endColumn)) {
        p.endLine = end.endLine
        p.endColumn = end.endColumn
      }
      return
    }
    pushMapping(id, "attachment", end)
  }

  fun addEdgeMapping(from: Any?, toNodes: List<String>, link: Any?, loc: Loc?) {
    val ids = toNodes.filter { n -> n != "" }
    pushMapping(if (ids.isNotEmpty()) ids.joinToString(">") else "edge", "edge", position(loc))
  }

  private fun blockMapping(id: String, type: StatementType, startLoc: Loc?, endLoc: Loc?) {
    if (id == "") return
    val start = position(startLoc)
    val end = if (endLoc != null) position(endLoc) else start
    pushMapping(
      id,
      type,
      ElementPosition(
        startLine = start.startLine,
        startColumn = start.startColumn,
        endLine = end.endLine,
        endColumn = end.endColumn,
        startIndex = 0,
        endIndex = 0,
      ),
    )
  }

  fun addSubgraphMapping(id: FlowText?, title: Any?, startLoc: Loc?, endLoc: Loc?) {
    blockMapping(id?.text ?: "", "subgraph", startLoc, endLoc)
  }

  fun addConnectorMapping(textObj: FlowText?, title: Any?, startLoc: Loc?, endLoc: Loc?) {
    blockMapping(textObj?.text?.jsTrim() ?: "", "connector", startLoc, endLoc)
  }

  private fun emit(id: DiagnosticId, severity: String, message: String, ctx: EmitContext?) {
    val anchor = ctx?.nodeId ?: ctx?.edgeId
    val diagnostic = Diagnostic(id, severity, message)
    if (!ctx?.nodeId.isNullOrEmpty()) diagnostic.nodeId = ctx?.nodeId
    else if (!ctx?.edgeId.isNullOrEmpty()) diagnostic.edgeId = ctx?.edgeId
    val mapping = if (!anchor.isNullOrEmpty()) firstMapping[anchor] else null
    if (mapping != null) diagnostic.position = mapping.position

    // The graph may be built more than once; each finding is recorded once.
    val key = anchor ?: ""
    val same = reported[key]
    if (same == null) {
      reported[key] = arrayListOf(diagnostic)
    } else {
      for (d in same) {
        if (
          d.id == id &&
          d.severity == severity &&
          d.message == message &&
          d.nodeId == diagnostic.nodeId &&
          d.edgeId == diagnostic.edgeId
        ) {
          return
        }
      }
      same.add(diagnostic)
    }
    diagnostics.add(diagnostic)
    options.log?.invoke(severity, "agentflow[$id]: $message")
  }

  override fun emitWarning(id: DiagnosticId, message: String, ctx: EmitContext?) {
    emit(id, "warning", message, ctx)
  }

  override fun emitError(id: DiagnosticId, message: String, ctx: EmitContext?) {
    emit(id, "error", message, ctx)
  }
}
