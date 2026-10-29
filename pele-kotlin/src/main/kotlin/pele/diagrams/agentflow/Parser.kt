// Ported from Pele src/diagrams/agentflow/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import pele.inputOffset
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsTrim
import pele.js.jsTrimEnd
import pele.replacements
import pele.syntaxError

private fun table(vararg tokens: Int): BooleanArray = BooleanArray(T.EOF + 1).also { t -> for (k in tokens) t[k] = true }

private val ID = table(
  T.NUM,
  T.NODE_STRING,
  T.DOWN,
  T.MINUS,
  T.DEFAULT,
  T.COMMA,
  T.COLON,
  T.AMP,
  T.BRKT,
  T.MULT,
  T.UNICODE_TEXT,
)

private val ALPHANUM = table(
  T.NUM,
  T.UNICODE_TEXT,
  T.NODE_STRING,
  T.DIR,
  T.DOWN,
  T.MINUS,
  T.COMMA,
  T.COLON,
  T.AMP,
  T.BRKT,
  T.MULT,
)

private val NO_TAGS = table(
  T.NUM,
  T.NODE_STRING,
  T.SPACE,
  T.MINUS,
  T.AMP,
  T.UNICODE_TEXT,
  T.COLON,
  T.MULT,
  T.BRKT,
  T.STYLE,
  T.LINKSTYLE,
  T.CLASSDEF,
  T.CLASS,
  T.CLICK,
  T.GRAPH,
  T.DIR,
  T.flow,
  T.connector,
  T.global,
  T.end,
  T.DOWN,
  T.UP,
  T.START_LINK,
)

private val STYLE_PART = table(T.NUM, T.NODE_STRING, T.COLON, T.SPACE, T.BRKT, T.STYLE)

private val SEPARATOR = table(T.NEWLINE, T.SEMI, T.EOF, T.COMMENT)

private class Frame(
  val global: Boolean,
  // Token index of the `flow` keyword.
  val at: Int,
  val id: FlowText?,
  val title: FlowText?,
  val data: String?,
  val dataFrom: Int,
  val dataTo: Int,
  var list: MutableList<DocItem>,
  var collect: Boolean,
)

private const val TEXT_TOKENS = "'UNICODE_TEXT', 'TEXT', 'TAGSTART'"
private const val SEPARATORS = "'COMMENT', 'SEMI', 'NEWLINE', 'EOF'"
private val TRAILING = table(T.COMMENT, T.NEWLINE, T.SPACE, T.EOF)
private val NO_ITEMS: List<String> = emptyList()

// Mermaid trims spaces and tabs between a closing brace and the end of its line before parsing.
private val RE_BRACE_TRAIL = JsRegExp("""}[^\S\n]*\n""", "g")

// `raw` parses the text as it is, the way Mermaid's generated parser does without its wrapper.
fun parseAgentflow(source: String, db: AgentflowDb, raw: Boolean = false) {
  val src = if (!raw && source.contains('}')) source.jsReplace(RE_BRACE_TRAIL, "}\n") else source
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  val locs = tokens.locs
  var i = 0
  var pendingData: String? = null
  var dataFrom = 0
  var dataTo = 0

  fun fail(expected: String): Nothing {
    val t = types[i]
    var from = starts[i]
    if (t == T.EOF || t == T.END) {
      // Comments are tokens here, so skip trailing ones.
      var k = i
      while (k > 0 && TRAILING[types[k - 1]]) k--
      from = src.jsSlice(0, starts[k]).jsTrimEnd().length
    }
    val at = if (src == source) from else inputOffset(listOf(replacements(source, RE_BRACE_TRAIL, 2)), from)
    if (t == T.ERROR) throw syntaxError("agentflow", source, at, "", true)
    throw syntaxError("agentflow", source, at, "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }

  // The span from the start of token `a` to the end of token `b`.
  fun span(a: Int, b: Int): Loc = Loc(locs[4 * a], locs[4 * a + 1], locs[4 * b + 2], locs[4 * b + 3])

  fun expect(type: Int, also: String = ""): String {
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'${if (also != "") ", $also" else ""}")
    return texts[i++]
  }

  fun separator() {
    if (!SEPARATOR[types[i]]) fail(SEPARATORS)
    i++
  }

  fun idString(): String {
    if (!ID[types[i]]) fail("'NODE_STRING'")
    var s = texts[i++]
    while (ID[types[i]]) s += texts[i++]
    return s
  }

  fun alphaNum(): String {
    if (!ALPHANUM[types[i]]) fail("'NODE_STRING'")
    var s = texts[i++]
    while (ALPHANUM[types[i]]) s += texts[i++]
    return s
  }

  fun text(): FlowText {
    val t = types[i]
    val type: LabelType
    if (t == T.STR) type = "string"
    else if (t == T.MD_STR) type = "markdown"
    else if (t == T.TEXT || t == T.TAGSTART || t == T.TAGEND || t == T.UNICODE_TEXT) type = "text"
    else fail("$TEXT_TOKENS, 'TAGEND', 'STR', 'MD_STR'")
    var s = texts[i++]
    while (true) {
      val n = types[i]
      if (n != T.TEXT && n != T.TAGSTART && n != T.TAGEND && n != T.UNICODE_TEXT) break
      s += texts[i++]
    }
    return FlowText(s, type)
  }

  fun closed(type: Int) {
    expect(type, TEXT_TOKENS)
  }

  fun edgeText(): FlowText {
    val t = types[i]
    val type: LabelType
    if (t == T.STR) type = "string"
    else if (t == T.MD_STR) type = "markdown"
    else if (t == T.EDGE_TEXT || t == T.UNICODE_TEXT) type = "text"
    else fail("'EDGE_TEXT', 'STR', 'MD_STR'")
    var s = texts[i++]
    while (types[i] == T.EDGE_TEXT || types[i] == T.UNICODE_TEXT) s += texts[i++]
    return FlowText(s, type)
  }

  fun textNoTags(): FlowText {
    val t = types[i]
    val type: LabelType
    if (t == T.STR) type = "text"
    else if (t == T.MD_STR) type = "markdown"
    else if (NO_TAGS[t]) type = "text"
    else fail("'NODE_STRING', 'STR', 'MD_STR'")
    var s = texts[i++]
    while (NO_TAGS[types[i]]) s += texts[i++]
    return FlowText(s, type)
  }

  fun shapeData(): String {
    dataFrom = i
    var s = texts[i++]
    while (types[i] == T.SHAPE_DATA) s += texts[i++]
    dataTo = i - 1
    return s
  }

  fun attach(id: String, data: String, from: Int, to: Int) {
    val loc = span(from, to)
    db.addVertex(id, metadata = data, metadataLoc = loc)
    db.extendVertexMapping(id, loc)
  }

  fun attachData(id: String, frame: Frame) {
    if (frame.data == null) return
    db.addVertex(id, metadata = frame.data, metadataLoc = span(frame.dataFrom, frame.dataTo))
  }

  fun vertex(): String {
    val from = i
    val id = idString()
    var label: FlowText? = null
    var type: String? = null
    var props: Map<String, String>? = null
    when (types[i]) {
      T.SQS -> {
        i++
        label = text()
        closed(T.SQE)
        type = "square"
      }
      T.DOUBLECIRCLESTART -> {
        i++
        label = text()
        closed(T.DOUBLECIRCLEEND)
        type = "doublecircle"
      }
      T.PS -> {
        i++
        if (types[i] == T.PS) {
          i++
          label = text()
          closed(T.PE)
          expect(T.PE)
          type = "circle"
        } else {
          label = text()
          closed(T.PE)
          type = "round"
        }
      }
      T.ELLIPSE_START -> {
        i++
        label = text()
        closed(T.ELLIPSE_END)
        type = "ellipse"
      }
      T.STADIUMSTART -> {
        i++
        label = text()
        closed(T.STADIUMEND)
        type = "stadium"
      }
      T.SUBROUTINESTART -> {
        i++
        label = text()
        closed(T.SUBROUTINEEND)
        type = "subroutine"
      }
      T.VERTEX_WITH_PROPS_START -> {
        i++
        val field = expect(T.NODE_STRING)
        expect(T.COLON)
        val value = expect(T.NODE_STRING)
        expect(T.PIPE)
        label = text()
        closed(T.SQE)
        type = "rect"
        props = mapOf(field to value)
      }
      T.CYLINDERSTART -> {
        i++
        label = text()
        closed(T.CYLINDEREND)
        type = "cylinder"
      }
      T.DIAMOND_START -> {
        i++
        if (types[i] == T.DIAMOND_START) {
          i++
          label = text()
          closed(T.DIAMOND_STOP)
          expect(T.DIAMOND_STOP)
          type = "hexagon"
        } else {
          label = text()
          closed(T.DIAMOND_STOP)
          type = "diamond"
        }
      }
      T.TAGEND -> {
        i++
        label = text()
        closed(T.SQE)
        type = "odd"
      }
      T.TRAPSTART -> {
        i++
        label = text()
        if (types[i] == T.INVTRAPEND) {
          i++
          type = "lean_right"
        } else {
          closed(T.TRAPEND)
          type = "trapezoid"
        }
      }
      T.INVTRAPSTART -> {
        i++
        label = text()
        if (types[i] == T.TRAPEND) {
          i++
          type = "lean_left"
        } else {
          closed(T.INVTRAPEND)
          type = "inv_trapezoid"
        }
      }
    }
    if (props != null) db.addVertex(id, label, type, props = props)
    else if (type != null) db.addVertex(id, label, type)
    else db.addVertex(id)
    db.addVertexMapping(id, label, type, span(from, i - 1))
    return id
  }

  fun styledVertex(): String {
    val id = vertex()
    if (types[i] == T.STYLE_SEPARATOR) {
      i++
      db.setClass(id, idString())
    }
    return id
  }

  fun node(): List<String> {
    val ids = arrayListOf(styledVertex())
    pendingData = null
    while (true) {
      val hasData = types[i] == T.SHAPE_DATA
      val data = if (hasData) shapeData() else null
      val from = dataFrom
      val to = dataTo
      if (types[i] != T.SPACE) {
        pendingData = data
        return ids
      }
      do i++ while (types[i] == T.SPACE)
      if (types[i] != T.AMP) {
        if (data != null) fail("'AMP'")
        return ids
      }
      i++
      if (types[i] != T.SPACE) fail("'SPACE'")
      do i++ while (types[i] == T.SPACE)
      val next = styledVertex()
      if (data != null) attach(ids[ids.size - 1], data, from, to)
      ids.add(next)
    }
  }

  fun link(): LinkInfo {
    var id: String? = null
    if (types[i] == T.LINK_ID) id = texts[i++]
    if (types[i] == T.LINK) {
      val info: LinkInfo = db.destructLink(texts[i++])
      if (id != null) info.id = id
      if (types[i] == T.PIPE) {
        i++
        info.text = text()
        closed(T.PIPE)
        if (types[i] == T.SPACE) i++
      }
      return info
    }
    if (types[i] == T.START_LINK) {
      val start = texts[i++]
      val label = edgeText()
      val end = expect(T.LINK, "'EDGE_TEXT'")
      val info: LinkInfo = db.destructLink(end, start)
      info.text = label
      if (id != null) info.id = id
      return info
    }
    fail("'LINK', 'START_LINK'")
  }

  fun vertexStatement(): List<String> {
    val from = i
    var stmt = node()
    val first = pendingData
    if (first != null) attach(stmt[stmt.size - 1], first, dataFrom, dataTo)
    var nodes = stmt
    while (true) {
      val t = types[i]
      if (t != T.LINK && t != T.START_LINK && t != T.LINK_ID) return nodes
      val info = link()
      val next = node()
      val pending = pendingData
      if (pending != null) attach(next[next.size - 1], pending, dataFrom, dataTo)
      db.addLink(stmt, next, info)
      db.addEdgeMapping(stmt, next, info, span(from, i - 1))
      nodes = next + nodes
      stmt = next
    }
  }

  fun style(): String {
    if (!STYLE_PART[types[i]]) fail("'NODE_STRING', 'NUM', 'COLON', 'BRKT'")
    var s = texts[i++]
    while (STYLE_PART[types[i]]) s += texts[i++]
    return s
  }

  fun stylesOpt(): MutableList<String> {
    val out = arrayListOf(style())
    while (types[i] == T.COMMA) {
      i++
      out.add(style())
    }
    return out
  }

  fun linkStyleStatement() {
    i++
    expect(T.SPACE)
    val positions: MutableList<String>
    if (types[i] == T.DEFAULT) {
      positions = arrayListOf(texts[i++])
    } else {
      positions = arrayListOf(expect(T.NUM, "'DEFAULT'"))
      while (types[i] == T.COMMA) {
        i++
        positions.add(expect(T.NUM))
      }
    }
    expect(T.SPACE)
    if (types[i] != T.INTERPOLATE) {
      db.updateLink(positions, stylesOpt())
      return
    }
    i++
    expect(T.SPACE)
    val interpolate = alphaNum()
    if (types[i] == T.SPACE) {
      i++
      val styles = stylesOpt()
      db.updateLinkInterpolate(positions, interpolate)
      db.updateLink(positions, styles)
    } else {
      db.updateLinkInterpolate(positions, interpolate)
    }
  }

  fun linkTail(id: String, url: String) {
    if (types[i] != T.SPACE) {
      db.setLink(id, url)
      return
    }
    i++
    if (types[i] == T.LINK_TARGET) {
      db.setLink(id, url, texts[i++])
      return
    }
    val tooltip = expect(T.STR, "'LINK_TARGET'")
    if (types[i] == T.SPACE) {
      i++
      db.setLink(id, url, expect(T.LINK_TARGET))
    } else {
      db.setLink(id, url)
    }
    db.setTooltip(id, tooltip)
  }

  fun clickStatement() {
    val id = texts[i++]
    when (types[i]) {
      T.CALLBACKNAME -> {
        val name = texts[i++]
        val hasArgs = types[i] == T.CALLBACKARGS
        val args = if (hasArgs) texts[i++] else null
        var tooltip: String? = null
        if (types[i] == T.SPACE) {
          i++
          tooltip = expect(T.STR)
        }
        if (hasArgs) db.setClickEvent(id, name, args)
        else db.setClickEvent(id, name)
        if (tooltip != null) db.setTooltip(id, tooltip)
        return
      }
      T.HREF -> {
        i++
        linkTail(id, expect(T.STR))
        return
      }
      T.STR -> {
        linkTail(id, texts[i++])
        return
      }
      else -> {
        val name = alphaNum()
        if (types[i] == T.SPACE) {
          i++
          val tooltip = expect(T.STR)
          db.setClickEvent(id, name)
          db.setTooltip(id, tooltip)
        } else {
          db.setClickEvent(id, name)
        }
      }
    }
  }

  // Reads `flow` or `connector` up to the separator: an id, an optional bracketed title, optional metadata.
  fun header(at: Int): Frame {
    expect(T.SPACE, SEPARATORS)
    val id = textNoTags()
    var title = FlowText("", "text")
    if (types[i] == T.SQS) {
      i++
      title = text()
      closed(T.SQE)
    }
    val data = if (types[i] == T.SHAPE_DATA) shapeData() else null
    if (!SEPARATOR[types[i]]) fail("$SEPARATORS, 'SHAPE_DATA'")
    return Frame(false, at, id, title, data, dataFrom, dataTo, ArrayList(), false)
  }

  fun statement(): DocItem {
    val t = types[i]
    when (t) {
      T.STYLE -> {
        i++
        expect(T.SPACE)
        val id = idString()
        expect(T.SPACE)
        db.addVertex(id, style = stylesOpt())
        separator()
        return NO_ITEMS
      }
      T.LINKSTYLE -> {
        linkStyleStatement()
        separator()
        return NO_ITEMS
      }
      T.CLASSDEF -> {
        i++
        expect(T.SPACE)
        val id = idString()
        expect(T.SPACE)
        db.addClass(id, stylesOpt())
        separator()
        return NO_ITEMS
      }
      T.CLASS -> {
        i++
        expect(T.SPACE)
        val ids = idString()
        expect(T.SPACE)
        db.setClass(ids, idString())
        separator()
        return NO_ITEMS
      }
      T.CLICK -> {
        clickStatement()
        separator()
        return NO_ITEMS
      }
      T.connector -> {
        val frame = header(i++)
        val sep = i++
        val id = db.addConnector(frame.id!!, frame.title)
        attachData(id, frame)
        db.addConnectorMapping(frame.id, frame.title, span(frame.at, frame.at), span(sep, sep))
        return id
      }
      T.direction_tb, T.direction_bt, T.direction_rl, T.direction_lr, T.direction_td -> {
        i++
        return DirStatement(DIRECTIONS[t - T.direction_tb])
      }
      T.acc_title -> {
        i++
        val value = expect(T.acc_title_value).jsTrim()
        db.setAccTitle(value)
        return value
      }
      T.acc_descr -> {
        i++
        val value = expect(T.acc_descr_value).jsTrim()
        db.setAccDescription(value)
        return value
      }
      T.acc_descr_multiline_value -> {
        val value = texts[i++].jsTrim()
        db.setAccDescription(value)
        return value
      }
      else -> {
        if (!ID[t]) fail(STATEMENT_START)
        val nodes = vertexStatement()
        separator()
        return nodes
      }
    }
  }

  // Blocks nest without recursion: each open one keeps the list and collect flag of its parent.
  fun document() {
    val open = ArrayList<Frame>()
    var list: MutableList<DocItem> = ArrayList()
    var collect = false
    while (true) {
      val t = types[i]
      if (t == T.END) break
      if (t == T.end) {
        if (open.isEmpty()) break
        val frame = open.removeAt(open.size - 1)
        val at = i++
        if (frame.global) {
          db.addGlobal(list)
          list = frame.list
          collect = frame.collect
          continue
        }
        val id = db.addSubGraph(frame.id, list, frame.title, "flow")
        attachData(id, frame)
        db.addSubgraphMapping(frame.id, frame.title, span(frame.at, frame.at), span(at, at))
        list = frame.list
        collect = frame.collect
        if (collect) list.add(id)
      } else if (t == T.SEMI || t == T.NEWLINE || t == T.SPACE || t == T.EOF) {
        if (collect) list.add(texts[i])
        i++
      } else if (t == T.COMMENT) {
        i++
      } else if (t == T.flow || t == T.global) {
        val at = i++
        val frame: Frame
        if (t == T.global || SEPARATOR[types[i]]) {
          frame = Frame(t == T.global, at, null, null, null, 0, 0, list, collect)
          separator()
        } else {
          frame = header(at)
          frame.list = list
          frame.collect = collect
          i++
        }
        open.add(frame)
        list = ArrayList()
        collect = true
      } else {
        val item = statement()
        if (collect && (item !is List<*> || item.isNotEmpty())) list.add(item)
      }
    }
    if (open.size > 0) fail("'end'")
  }

  while (types[i] == T.SPACE || types[i] == T.NEWLINE || types[i] == T.COMMENT) i++
  expect(T.GRAPH, "'SPACE', 'NEWLINE', 'COMMENT'")
  if (types[i] == T.NODIR) {
    i++
    db.setDirection("TB")
  } else {
    val dir = expect(T.DIR, "'NODIR'")
    var t = types[i]
    if (t == T.SPACE) {
      do i++ while (types[i] == T.SPACE)
      t = types[i]
      if (t != T.NEWLINE) fail("'NEWLINE', 'SPACE'")
    } else if (t != T.SEMI && t != T.NEWLINE && t != T.COMMENT) {
      fail("'COMMENT', 'SEMI', 'NEWLINE', 'SPACE'")
    }
    i++
    db.setDirection(dir)
  }
  document()
  if (types[i] != T.END) fail(STATEMENT_START)
}

private val DIRECTIONS = listOf("TB", "BT", "RL", "LR", "TD")
private const val STATEMENT_START =
  "'COMMENT', 'SEMI', 'NEWLINE', 'SPACE', 'EOF', 'flow', 'global', 'connector', 'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'STYLE', 'LINKSTYLE', 'CLASSDEF', 'CLASS', 'CLICK', 'NODE_STRING'"
