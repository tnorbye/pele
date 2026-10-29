// Ported from Pele src/diagrams/er/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.js.jsSlice
import pele.js.jsTrim
import pele.syntaxError

private val CARDINALITY: Map<Int, Cardinality> =
  mapOf(
    T.ZERO_OR_ONE to "ZERO_OR_ONE",
    T.ZERO_OR_MORE to "ZERO_OR_MORE",
    T.ONE_OR_MORE to "ONE_OR_MORE",
    T.ONLY_ONE to "ONLY_ONE",
    T.MD_PARENT to "MD_PARENT",
  )

private val DIRECTIONS = listOf("TB", "BT", "RL", "LR")

private const val NAME = "'UNICODE_TEXT', 'NUM', 'ENTITY_NAME', 'DECIMAL_NUM', 'ENTITY_ONE'"
private const val CARDS = "'ZERO_OR_ONE', 'ZERO_OR_MORE', 'ONE_OR_MORE', 'ONLY_ONE', 'MD_PARENT'"
private const val LINE =
  "'EOF', 'NEWLINE', 'title', 'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'SUBGRAPH', 'direction_tb', " +
    "'direction_bt', 'direction_rl', 'direction_lr', 'CLASSDEF', 'CLASS', 'STYLE', " +
    NAME

private class Frame(
  val id: String,
  val title: String,
  // What Mermaid's grammar hands to addSubGraph: the value of every statement inside. Besides
  // entity
  // names that includes accessibility text and the keywords `style`, `class` and `classDef`.
  val list: MutableList<DocItem>,
)

fun parseEr(src: String, db: ErDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  fun fail(expected: String): Nothing {
    val t = types[i]
    if (t == T.ERROR) throw syntaxError("er", src, starts[i], "", true)
    throw syntaxError(
      "er",
      src,
      starts[i],
      "Expecting $expected, got '${if (t == T.CHAR) texts[i] else TOKEN_NAMES[t]}'",
    )
  }

  fun expect(type: Int): String {
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'")
    return texts[i++]
  }

  fun separator() {
    val t = types[i]
    if (t != T.NEWLINE && t != T.SEMI && t != T.EOF) fail("'EOF', 'NEWLINE', 'SEMI'")
    i++
  }

  fun isName(t: Int): Boolean =
    t == T.UNICODE_TEXT ||
      t == T.ENTITY_NAME ||
      t == T.NUM ||
      t == T.DECIMAL_NUM ||
      t == T.ENTITY_ONE

  fun entityName(): String {
    val t = types[i]
    if (!isName(t)) fail(NAME)
    val text = texts[i++]
    return if (t == T.ENTITY_NAME) text.jsSlice(1, -1) else text
  }

  fun idList(): List<String> {
    val ids = ArrayList<String>()
    while (true) {
      val t = types[i]
      if (t != T.UNICODE_TEXT && t != T.STYLE_TEXT) fail("'UNICODE_TEXT', 'STYLE_TEXT'")
      ids.add(texts[i++])
      if (types[i] != T.COMMA) return ids
      i++
    }
  }

  fun isStyleComponent(t: Int): Boolean =
    t == T.STYLE_TEXT || t == T.NUM || t == T.COLON || t == T.BRKT

  fun stylesOpt(): List<String> {
    val styles = ArrayList<String>()
    while (true) {
      if (!isStyleComponent(types[i])) fail("'STYLE_TEXT', 'COLON', 'NUM', 'BRKT'")
      var style = texts[i++]
      while (isStyleComponent(types[i])) style += texts[i++]
      styles.add(style)
      if (types[i] != T.COMMA) return styles
      i++
    }
  }

  fun relSpec(): RelSpec {
    val cardB = CARDINALITY[types[i++]]!!
    val r = types[i]
    if (r != T.IDENTIFYING && r != T.NON_IDENTIFYING) fail("'NON_IDENTIFYING', 'IDENTIFYING'")
    val relType: Identification = if (r == T.IDENTIFYING) "IDENTIFYING" else "NON_IDENTIFYING"
    i++
    val cardA = CARDINALITY[types[i]] ?: fail(CARDS)
    i++
    return RelSpec(cardA, relType, cardB)
  }

  fun role(): String {
    val t = types[i]
    if (t != T.WORD && t != T.ENTITY_NAME && t != T.UNICODE_TEXT)
      fail("'UNICODE_TEXT', 'ENTITY_NAME', 'WORD'")
    val text = texts[i++]
    return if (t == T.UNICODE_TEXT) text else text.jsSlice(1, -1)
  }

  fun isChar(ch: String): Boolean = types[i] == T.CHAR && texts[i] == ch

  // Returns the attributes last to first, as Mermaid's right-recursive rule builds them.
  fun attributes(): List<ParsedAttribute> {
    val list = ArrayList<ParsedAttribute>()
    do {
      var type = expect(T.ATTRIBUTE_WORD)
      if (isChar("?")) type += texts[i++]
      if (types[i] != T.ATTRIBUTE_WORD)
        fail(if (type.endsWith("?")) "'ATTRIBUTE_WORD'" else "'ATTRIBUTE_WORD', '?'")
      val attribute = ParsedAttribute(type, texts[i++])
      if (types[i] == T.ATTRIBUTE_KEY) {
        val keys = arrayListOf(texts[i++])
        while (isChar(",")) {
          i++
          keys.add(expect(T.ATTRIBUTE_KEY))
        }
        attribute.keys = keys
      }
      if (types[i] == T.COMMENT) attribute.comment = texts[i++].jsSlice(1, -1)
      list.add(attribute)
    } while (types[i] != T.BLOCK_STOP)
    return list.reversed()
  }

  // Parses an optional `{ ... }` block; null when there is none or it is empty.
  fun block(): List<ParsedAttribute>? {
    if (types[i] != T.BLOCK_START) return null
    i++
    if (types[i] == T.BLOCK_STOP) {
      i++
      return null
    }
    val list = attributes()
    i++
    return list
  }

  fun entityStatement(): List<String> {
    val a = entityName()
    var t = types[i]
    if (t == T.SQS) {
      i++
      val alias = entityName()
      expect(T.SQE)
      var classes: List<String>? = null
      if (types[i] == T.STYLE_SEPARATOR) {
        i++
        classes = idList()
      }
      val attrs = block()
      db.addEntity(a, alias)
      if (attrs != null) db.addAttributes(a, attrs)
      if (classes != null) db.setClass(listOf(a), classes)
      return listOf(a)
    }
    var classes: List<String>? = null
    if (t == T.STYLE_SEPARATOR) {
      i++
      classes = idList()
      t = types[i]
    }
    if (CARDINALITY.containsKey(t)) {
      val spec = relSpec()
      val b = entityName()
      var classesB: List<String>? = null
      if (types[i] == T.STYLE_SEPARATOR) {
        i++
        classesB = idList()
      }
      expect(T.COLON)
      val label = role()
      db.addEntity(a)
      db.addEntity(b)
      db.addRelationship(a, label, b, spec)
      if (classes != null) db.setClass(listOf(a), classes)
      if (classesB != null) db.setClass(listOf(b), classesB)
      return listOf(a, b)
    }
    val attrs = block()
    db.addEntity(a)
    if (attrs != null) db.addAttributes(a, attrs)
    if (classes != null) db.setClass(listOf(a), classes)
    return listOf(a)
  }

  expect(T.ER_DIAGRAM)
  // Subgraphs nest on this stack rather than on the call stack; the first frame is the diagram
  // itself.
  val frames = arrayListOf(Frame("", "", ArrayList()))
  var frame = frames[0]
  var nested = false

  while (true) {
    val t = types[i]
    when (t) {
      T.NEWLINE -> i++
      T.EOF -> {
        i++
        if (nested) fail("'END', $LINE")
        return
      }
      T.END_KW -> {
        if (!nested) fail(LINE)
        i++
        val id = db.addSubGraph(frame.id, frame.list, frame.title)
        frames.removeAt(frames.size - 1)
        frame = frames[frames.size - 1]
        nested = frames.size > 1
        if (nested) frame.list.add(id)
      }
      T.SUBGRAPH -> {
        i++
        val id = entityName()
        var title = id
        if (types[i] == T.SQS) {
          i++
          title = entityName()
          while (isName(types[i])) title += " " + entityName()
          expect(T.SQE)
        }
        separator()
        frame = Frame(id, title, ArrayList())
        frames.add(frame)
        nested = true
      }
      T.acc_title -> {
        i++
        val value = expect(T.acc_title_value).jsTrim()
        db.setAccTitle(value)
        if (nested) frame.list.add(value)
      }
      T.acc_descr -> {
        i++
        val value = expect(T.acc_descr_value).jsTrim()
        db.setAccDescription(value)
        if (nested) frame.list.add(value)
      }
      T.acc_descr_multiline_value -> {
        val value = texts[i++].jsTrim()
        db.setAccDescription(value)
        if (nested) frame.list.add(value)
      }
      T.direction_tb,
      T.direction_bt,
      T.direction_rl,
      T.direction_lr -> {
        i++
        val value = DIRECTIONS[t - T.direction_tb]
        if (nested) frame.list.add(DirStmt(value)) else db.setDirection(value)
      }
      T.CLASSDEF -> {
        val keyword = texts[i++]
        val ids = idList()
        val styles = stylesOpt()
        separator()
        db.addClass(ids, styles)
        if (nested) frame.list.add(keyword)
      }
      T.CLASS -> {
        val keyword = texts[i++]
        val ids = idList()
        db.setClass(ids, idList())
        if (nested) frame.list.add(keyword)
      }
      T.STYLE -> {
        val keyword = texts[i++]
        val ids = idList()
        val styles = stylesOpt()
        separator()
        db.addCssStyles(ids, styles)
        if (nested) frame.list.add(keyword)
      }
      else -> {
        if (!isName(t)) fail(if (nested) "'END', $LINE" else LINE)
        val names = entityStatement()
        if (nested) for (name in names) frame.list.add(name)
      }
    }
  }
}
