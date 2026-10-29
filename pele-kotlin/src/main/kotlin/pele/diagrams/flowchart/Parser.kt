// Ported from Pele src/diagrams/flowchart/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

import pele.inputOffset
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsTrim
import pele.replacements
import pele.syntaxError

private fun table(vararg tokens: Int): BooleanArray =
  BooleanArray(T.EOF + 1).also { t -> for (k in tokens) t[k] = true }

private val ID =
  table(
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

private val ALPHANUM =
  table(
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

private val NO_TAGS =
  table(
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
    T.subgraph,
    T.end,
    T.DOWN,
    T.UP,
    T.START_LINK,
  )

private val STYLE_PART = table(T.NUM, T.NODE_STRING, T.COLON, T.SPACE, T.BRKT, T.STYLE)

private class Frame(
  val id: FlowText?,
  val title: FlowText?,
  var list: MutableList<DocItem>,
  var collect: Boolean,
)

private const val TEXT_TOKENS = "'UNICODE_TEXT', 'TEXT', 'TAGSTART'"

// PORT: compared by identity, as upstream does.
private val NO_ITEMS: List<String> = emptyList()

// Mermaid trims whitespace between a closing brace and the end of its line before parsing.
private val RE_BRACE_TRAIL = JsRegExp("""}\s*\n""", "g")

fun parseFlowchart(source: String, db: FlowDb) {
  val src = if (source.contains('}')) source.jsReplace(RE_BRACE_TRAIL, "}\n") else source
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0
  var pendingData: String? = null

  val fail =
    fun(expected: String): Nothing {
      val t = types[i]
      val at =
        if (src == source) starts[i]
        else inputOffset(listOf(replacements(source, RE_BRACE_TRAIL, 2)), starts[i])
      if (t == T.ERROR) throw syntaxError("flowchart", source, at, "", true)
      throw syntaxError("flowchart", source, at, "Expecting $expected, got '${TOKEN_NAMES[t]}'")
    }

  val expect =
    fun(type: Int, also: String): String {
      if (types[i] != type) fail("'${TOKEN_NAMES[type]}'${if (also != "") ", $also" else ""}")
      return texts[i++]
    }

  val separator = {
    val t = types[i]
    if (t != T.NEWLINE && t != T.SEMI && t != T.EOF) fail("'SEMI', 'NEWLINE', 'EOF'")
    i++
  }

  val idString =
    fun(): String {
      if (!ID[types[i]]) fail("'NODE_STRING'")
      var s = texts[i++]
      while (ID[types[i]]) s += texts[i++]
      return s
    }

  val alphaNum =
    fun(): String {
      if (!ALPHANUM[types[i]]) fail("'NODE_STRING'")
      var s = texts[i++]
      while (ALPHANUM[types[i]]) s += texts[i++]
      return s
    }

  val text =
    fun(): FlowText {
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

  val closed = { type: Int -> expect(type, TEXT_TOKENS) }

  val edgeText =
    fun(): FlowText {
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

  val textNoTags =
    fun(): FlowText {
      val t = types[i]
      val type: LabelType
      if (t == T.STR) type = "text"
      else if (t == T.MD_STR) type = "markdown"
      else if (NO_TAGS[t]) type = "text" else fail("'NODE_STRING', 'STR', 'MD_STR'")
      var s = texts[i++]
      while (NO_TAGS[types[i]]) s += texts[i++]
      return FlowText(s, type)
    }

  val shapeData =
    fun(): String {
      var s = texts[i++]
      while (types[i] == T.SHAPE_DATA) s += texts[i++]
      return s
    }

  val vertex =
    fun(): String {
      val id = idString()
      val label: FlowText
      when (types[i]) {
        T.SQS -> {
          i++
          label = text()
          closed(T.SQE)
          db.addVertex(id, label, "square")
        }
        T.DOUBLECIRCLESTART -> {
          i++
          label = text()
          closed(T.DOUBLECIRCLEEND)
          db.addVertex(id, label, "doublecircle")
        }
        T.PS -> {
          i++
          if (types[i] == T.PS) {
            i++
            label = text()
            closed(T.PE)
            expect(T.PE, "")
            db.addVertex(id, label, "circle")
          } else {
            label = text()
            closed(T.PE)
            db.addVertex(id, label, "round")
          }
        }
        T.ELLIPSE_START -> {
          i++
          label = text()
          closed(T.ELLIPSE_END)
          db.addVertex(id, label, "ellipse")
        }
        T.STADIUMSTART -> {
          i++
          label = text()
          closed(T.STADIUMEND)
          db.addVertex(id, label, "stadium")
        }
        T.SUBROUTINESTART -> {
          i++
          label = text()
          closed(T.SUBROUTINEEND)
          db.addVertex(id, label, "subroutine")
        }
        T.VERTEX_WITH_PROPS_START -> {
          i++
          val field = expect(T.NODE_STRING, "")
          expect(T.COLON, "")
          val value = expect(T.NODE_STRING, "")
          expect(T.PIPE, "")
          label = text()
          closed(T.SQE)
          db.addVertex(id, label, "rect", null, null, null, linkedMapOf(field to value))
        }
        T.CYLINDERSTART -> {
          i++
          label = text()
          closed(T.CYLINDEREND)
          db.addVertex(id, label, "cylinder")
        }
        T.DIAMOND_START -> {
          i++
          if (types[i] == T.DIAMOND_START) {
            i++
            label = text()
            closed(T.DIAMOND_STOP)
            expect(T.DIAMOND_STOP, "")
            db.addVertex(id, label, "hexagon")
          } else {
            label = text()
            closed(T.DIAMOND_STOP)
            db.addVertex(id, label, "diamond")
          }
        }
        T.TAGEND -> {
          i++
          label = text()
          closed(T.SQE)
          db.addVertex(id, label, "odd")
        }
        T.TRAPSTART -> {
          i++
          label = text()
          if (types[i] == T.INVTRAPEND) {
            i++
            db.addVertex(id, label, "lean_right")
          } else {
            closed(T.TRAPEND)
            db.addVertex(id, label, "trapezoid")
          }
        }
        T.INVTRAPSTART -> {
          i++
          label = text()
          if (types[i] == T.TRAPEND) {
            i++
            db.addVertex(id, label, "lean_left")
          } else {
            closed(T.INVTRAPEND)
            db.addVertex(id, label, "inv_trapezoid")
          }
        }
        else -> db.addVertex(id)
      }
      return id
    }

  val styledVertex =
    fun(): String {
      val id = vertex()
      if (types[i] == T.STYLE_SEPARATOR) {
        i++
        db.setClass(id, idString())
      }
      return id
    }

  val node =
    fun(): MutableList<String> {
      val ids = arrayListOf(styledVertex())
      pendingData = null
      while (true) {
        val data = if (types[i] == T.SHAPE_DATA) shapeData() else null
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
        if (data != null) {
          db.addVertex(ids[ids.size - 1], null, null, null, null, null, null, data)
        }
        ids.add(next)
      }
    }

  val link =
    fun(): LinkInfo {
      var id: String? = null
      if (types[i] == T.LINK_ID) id = texts[i++]
      if (types[i] == T.LINK) {
        val info = db.destructLink(texts[i++])
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
        val info = db.destructLink(end, start)
        info.text = label
        if (id != null) info.id = id
        return info
      }
      fail("'LINK', 'START_LINK'")
    }

  val vertexStatement =
    fun(collect: Boolean): List<String> {
      var stmt: List<String> = node()
      if (pendingData != null) {
        db.addVertex(stmt[stmt.size - 1], null, null, null, null, null, null, pendingData)
      }
      var nodes = stmt
      while (true) {
        val t = types[i]
        if (t != T.LINK && t != T.START_LINK && t != T.LINK_ID) return nodes
        val info = link()
        val next = node()
        if (pendingData != null) {
          db.addVertex(next[next.size - 1], null, null, null, null, null, null, pendingData)
        }
        db.addLink(stmt, next, info)
        if (collect) nodes = next + nodes
        stmt = next
      }
    }

  val style =
    fun(): String {
      if (!STYLE_PART[types[i]]) fail("'NODE_STRING', 'NUM', 'COLON', 'BRKT'")
      var s = texts[i++]
      while (STYLE_PART[types[i]]) s += texts[i++]
      return s
    }

  val stylesOpt =
    fun(): MutableList<String> {
      val out = arrayListOf(style())
      while (types[i] == T.COMMA) {
        i++
        out.add(style())
      }
      return out
    }

  val linkStyleStatement =
    fun() {
      i++
      expect(T.SPACE, "")
      val positions: MutableList<String>
      if (types[i] == T.DEFAULT) {
        positions = arrayListOf(texts[i++])
      } else {
        positions = arrayListOf(expect(T.NUM, "'DEFAULT'"))
        while (types[i] == T.COMMA) {
          i++
          positions.add(expect(T.NUM, ""))
        }
      }
      expect(T.SPACE, "")
      if (types[i] != T.INTERPOLATE) {
        db.updateLink(positions, stylesOpt())
        return
      }
      i++
      expect(T.SPACE, "")
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

  val linkTail =
    fun(id: String, url: String) {
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
        db.setLink(id, url, expect(T.LINK_TARGET, ""))
      } else {
        db.setLink(id, url)
      }
      db.setTooltip(id, tooltip)
    }

  val clickStatement =
    fun() {
      val id = texts[i++]
      when (types[i]) {
        T.CALLBACKNAME -> {
          val name = texts[i++]
          val hasArgs = types[i] == T.CALLBACKARGS
          val args = if (hasArgs) texts[i++] else null
          var tooltip: String? = null
          if (types[i] == T.SPACE) {
            i++
            tooltip = expect(T.STR, "")
          }
          if (hasArgs) db.setClickEvent(id, name, args) else db.setClickEvent(id, name)
          if (tooltip != null) db.setTooltip(id, tooltip)
          return
        }
        T.HREF -> {
          i++
          linkTail(id, expect(T.STR, ""))
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
            val tooltip = expect(T.STR, "")
            db.setClickEvent(id, name)
            db.setTooltip(id, tooltip)
          } else {
            db.setClickEvent(id, name)
          }
        }
      }
    }

  val subgraphHeader =
    fun(): Frame {
      i++
      var t = types[i]
      if (t == T.NEWLINE || t == T.SEMI || t == T.EOF) {
        i++
        return Frame(null, null, ArrayList(), false)
      }
      expect(T.SPACE, "'SEMI', 'NEWLINE', 'EOF'")
      val id = textNoTags()
      var title = id
      if (types[i] == T.SQS) {
        i++
        title = text()
        closed(T.SQE)
      }
      t = types[i]
      if (t != T.NEWLINE && t != T.SEMI && t != T.EOF) fail("'SEMI', 'NEWLINE', 'EOF', 'SQS'")
      i++
      return Frame(id, title, ArrayList(), false)
    }

  val statement =
    fun(collect: Boolean): DocItem {
      val t = types[i]
      when (t) {
        T.STYLE -> {
          i++
          expect(T.SPACE, "")
          val id = idString()
          expect(T.SPACE, "")
          db.addVertex(id, null, null, stylesOpt())
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
          expect(T.SPACE, "")
          val id = idString()
          expect(T.SPACE, "")
          db.addClass(id, stylesOpt())
          separator()
          return NO_ITEMS
        }
        T.CLASS -> {
          i++
          expect(T.SPACE, "")
          val ids = idString()
          expect(T.SPACE, "")
          db.setClass(ids, idString())
          separator()
          return NO_ITEMS
        }
        T.CLICK -> {
          clickStatement()
          separator()
          return NO_ITEMS
        }
        T.direction_tb,
        T.direction_bt,
        T.direction_rl,
        T.direction_lr,
        T.direction_td -> {
          i++
          return DirStatement(DIRECTIONS[t - T.direction_tb])
        }
        T.acc_title -> {
          i++
          val value = expect(T.acc_title_value, "").jsTrim()
          db.setAccTitle(value)
          return value
        }
        T.acc_descr -> {
          i++
          val value = expect(T.acc_descr_value, "").jsTrim()
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
          val nodes = vertexStatement(collect)
          separator()
          return nodes
        }
      }
    }

  // Subgraphs nest without recursion: each open one keeps the list and collect flag of its parent.
  val document = {
    val open = ArrayList<Frame>()
    var list: MutableList<DocItem> = ArrayList()
    var collect = false
    while (true) {
      val t = types[i]
      if (t == T.END) break
      if (t == T.end) {
        val frame = open.removeLastOrNull() ?: break
        i++
        val id = db.addSubGraph(frame.id, list, frame.title)
        list = frame.list
        collect = frame.collect
        if (collect) list.add(id)
      } else if (t == T.SEMI || t == T.NEWLINE || t == T.SPACE || t == T.EOF) {
        if (collect) list.add(texts[i])
        i++
      } else if (t == T.subgraph) {
        val frame = subgraphHeader()
        frame.list = list
        frame.collect = collect
        open.add(frame)
        list = ArrayList()
        collect = true
      } else {
        val item = statement(collect)
        if (collect && (item !is List<*> || item.size > 0)) list.add(item)
      }
    }
    if (open.size > 0) fail("'end'")
  }

  while (types[i] == T.SPACE || types[i] == T.NEWLINE) i++
  expect(T.GRAPH, "'SPACE', 'NEWLINE'")
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
    } else if (t != T.SEMI && t != T.NEWLINE) {
      fail("'SEMI', 'NEWLINE', 'SPACE'")
    }
    i++
    db.setDirection(dir)
  }
  document()
  if (types[i] != T.END) fail(STATEMENT_START)
}

private val DIRECTIONS = listOf("TB", "BT", "RL", "LR", "TD")
private const val STATEMENT_START =
  "'SEMI', 'NEWLINE', 'SPACE', 'EOF', 'subgraph', 'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'STYLE', 'LINKSTYLE', 'CLASSDEF', 'CLASS', 'CLICK', 'NODE_STRING'"
