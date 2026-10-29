// Ported from Pele src/diagrams/block/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import pele.js.jsParseInt
import pele.js.jsTrim
import pele.syntaxError

private class NodeInfo(
  val id: String,
  val label: String? = null,
  val typeStr: String? = null,
  val directions: List<String>? = null,
)

// PORT: named OpenFrame because Db.kt has its own private Frame in this package.
private class OpenFrame(
  val list: MutableList<Statement>,
  val header: Statement?,
)

private const val STATEMENT_START =
  "'NODE_ID', 'COLUMNS', 'SPACE_BLOCK', 'BLOCK_DIAGRAM_KEY', 'id-block', 'classDef', 'class', 'style'"

// The grammar inlines a document's last statement when it is a list.
@Suppress("UNCHECKED_CAST")
private fun closeDocument(list: MutableList<Statement>): MutableList<Statement> {
  val last = list[list.size - 1]
  if (last !is List<*>) return list
  list.removeAt(list.size - 1)
  val out = ArrayList(list)
  out.addAll(last as List<Statement>)
  return out
}

// PORT: flattens one level where upstream leaves the lists to setHierarchy, which flattens them the
// same way.
@Suppress("UNCHECKED_CAST")
private fun blocksOf(list: List<Statement>): MutableList<Block> {
  val out = ArrayList<Block>()
  for (s in list) {
    if (s is List<*>) out.addAll(s as List<Block>) else out.add(s as Block)
  }
  return out
}

fun parseBlock(src: String, db: BlockBuilder) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  fun fail(expected: String): Nothing {
    val t = types[i]
    if (t == T.ERROR) throw syntaxError("block", src, starts[i], "", true)
    throw syntaxError("block", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }

  fun expect(type: Int): String {
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'")
    return texts[i++]
  }

  fun node(): NodeInfo {
    val id = expect(T.NODE_ID)
    if (types[i] == T.NODE_DSTART) {
      val open = texts[i++]
      val label = expect(T.STR)
      return NodeInfo(id, label, open + expect(T.NODE_DEND))
    }
    if (types[i] == T.BLOCK_ARROW_START) {
      val open = texts[i++]
      val label = expect(T.STR)
      val directions = arrayListOf(expect(T.DIR))
      while (types[i] == T.DIR) directions.add(texts[i++])
      return NodeInfo(id, label, open + expect(T.BLOCK_ARROW_END), directions)
    }
    return NodeInfo(id)
  }

  fun nodeStatement(): Statement {
    val first = node()
    val size = if (types[i] == T.SIZE) jsParseInt(texts[i++], 10) else 1.0
    var from =
      Block(
        id = first.id,
        label = first.label,
        type = db.typeStr2Type(first.typeStr),
        directions = first.directions,
        widthInColumns = size,
      )
    var chain: MutableList<Block>? = null
    while (types[i] == T.LINK || types[i] == T.START_LINK) {
      var label = ""
      if (types[i++] == T.START_LINK) {
        expect(T.LINK_LABEL)
        label = expect(T.STR)
        expect(T.LINK)
      }
      val link = texts[i - 1]
      val to = node()
      val arrowTypeEnd = db.edgeStrToEdgeData(link)
      val arrowTypeStart = db.edgeStrToEdgeStartData(link)
      val thickness = db.edgeStrToThickness(link)
      val pattern = db.edgeStrToPattern(link)
      // Mermaid keeps only the last link of `A --> B --> C` and loses its start. Here every link
      // counts.
      if (chain == null)
        chain =
          arrayListOf(
            Block(id = from.id, label = from.label, type = from.type, directions = from.directions)
          )
      from =
        Block(
          id = to.id,
          label = to.label,
          type = db.typeStr2Type(to.typeStr),
          directions = to.directions,
        )
      val previous = chain[chain.size - 1].id
      chain.add(
        Block(
          id = previous + "-" + to.id,
          start = previous,
          end = to.id,
          label = label,
          type = "edge",
          thickness = thickness,
          pattern = pattern,
          directions = to.directions,
          arrowTypeEnd = arrowTypeEnd,
          arrowTypeStart = arrowTypeStart,
        )
      )
      chain.add(from)
    }
    return chain ?: from
  }

  expect(T.KEY)
  // Composites nest without recursion: each open one keeps the statement list of its parent.
  val open = ArrayList<OpenFrame>()
  var list: MutableList<Statement> = ArrayList()
  while (true) {
    when (types[i]) {
      T.NODE_ID -> list.add(nodeStatement())
      T.COLUMNS -> list.add(Block(type = "column-setting", columns = jsParseInt(texts[i++], 10)))
      T.SPACE_BLOCK -> {
        val width = jsParseInt(texts[i++], 10)
        list.add(
          Block(
            id = db.generateId(),
            type = "space",
            label = "",
            width = width,
            children = ArrayList(),
          )
        )
      }
      T.KEY -> {
        i++
        open.add(OpenFrame(list, null))
        list = ArrayList()
      }
      T.ID_BLOCK -> {
        i++
        if (types[i] != T.NODE_ID) fail("'NODE_ID'")
        open.add(OpenFrame(list, nodeStatement()))
        list = ArrayList()
      }
      T.end -> {
        val frame = if (open.size > 0) open.removeAt(open.size - 1) else null
        if (frame == null || list.size == 0) fail(STATEMENT_START)
        i++
        val id = db.generateId()
        val children = blocksOf(closeDocument(list))
        list = frame.list
        val header = frame.header
        list.add(
          if (header == null) {
            Block(id = id, type = "composite", label = "", children = children)
          } else if (header is Block) {
            // The header spread keeps every field of the block.
            header.copy().also {
              it.type = "composite"
              it.children = children
            }
          } else {
            // PORT: spreading an array into an object leaves no named fields.
            Block(type = "composite", children = children)
          }
        )
      }
      T.CLASSDEF -> {
        i++
        val id = expect(T.CLASSDEF_ID).jsTrim()
        list.add(Block(type = "classDef", id = id, css = expect(T.CLASSDEF_STYLEOPTS).jsTrim()))
      }
      T.CLASS -> {
        i++
        val id = expect(T.CLASSENTITY_IDS).jsTrim()
        list.add(Block(type = "applyClass", id = id, styleClass = expect(T.STYLECLASS).jsTrim()))
      }
      T.STYLE -> {
        i++
        val id = expect(T.STYLE_ENTITY_IDS).jsTrim()
        list.add(
          Block(type = "applyStyles", id = id, stylesStr = expect(T.STYLE_DEFINITION_DATA).jsTrim())
        )
      }
      T.EOF -> {
        if (open.size > 0) fail("$STATEMENT_START, 'end'")
        if (list.size == 0) fail(STATEMENT_START)
        db.setHierarchy(closeDocument(list))
        return
      }
      else ->
        fail(
          if (list.size == 0) STATEMENT_START
          else STATEMENT_START + (if (open.size > 0) ", 'end'" else ", 'EOF'")
        )
    }
  }
}
