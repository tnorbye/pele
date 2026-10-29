// Ported from Pele src/diagrams/eventmodeling/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.eventmodeling

import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.TokenType
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescr
import pele.diagrams.common.keyword
import pele.diagrams.common.titleBefore
import pele.diagrams.common.tokenize
import pele.js.JsRegExp

private val EM_ID = TokenType(name = "EM_ID", pattern = JsRegExp("""[_a-zA-Z][\w_]*""", "y"))

// Langium gives every keyword that is also an identifier the identifier as a longer alternative.
private fun word(text: String): TokenType =
  TokenType(name = text, pattern = text, longer = listOf(EM_ID))

private fun sign(text: String): TokenType = TokenType(name = text, pattern = text)

private val WORDS =
  listOf(
    "resetframe",
    "readmodel",
    "processor",
    "timeframe",
    "command",
    "entity",
    "event",
    "jsobj",
    "figma",
    "given",
    "json",
    "salt",
    "html",
    "text",
    "data",
    "note",
    "when",
    "then",
    "rmo",
    "cmd",
    "evt",
    "pcr",
    "uri",
  )

val EVENTMODELING_TOKENS: List<TokenType> =
  listOf(
    keyword("eventmodeling").copy(longer = listOf(EM_ID)),
    *WORDS.map { text -> word(text) }.toTypedArray(),
    sign("->>"),
    word("gwt"),
    word("ui"),
    word("md"),
    word("tf"),
    sign("[["),
    sign("]]"),
    word("rf"),
    sign("`"),
    sign("."),
    EM_ID,
    TokenType(name = "EM_FID", pattern = JsRegExp("""\d{1,3}""", "y")),
    TokenType(name = "EM_DATA_INLINE", pattern = JsRegExp("""\{(.*)\}|"(.*)"|'(.*)'""", "y")),
    TokenType(
      name = "EM_DATA_BLOCK",
      pattern = JsRegExp("""\{[\t ]*\r?\n(?:[\S\s]*?\r?\n)?\}(?:\r?\n|(?!\S))""", "y"),
    ),
    accDescr("EM_ACC_DESCR"),
    ACC_TITLE.copy(name = "EM_ACC_TITLE"),
    titleBefore("""\w""").copy(name = "EM_TITLE"),
    TokenType(name = "EM_WS", pattern = JsRegExp("""\s+""", "y"), hidden = true),
    YAML.copy(name = "EM_YAML"),
    DIRECTIVE.copy(name = "EM_DIRECTIVE"),
    SINGLE_LINE_COMMENT.copy(name = "EM_SINGLE_LINE_COMMENT"),
    TokenType(
      name = "EM_ML_COMMENT",
      pattern = JsRegExp("""\/\*[\s\S]*?\*\/""", "y"),
      hidden = true,
    ),
    TokenType(name = "EM_SL_COMMENT", pattern = JsRegExp("""\/\/[^\n\r]*""", "y"), hidden = true),
  )

private fun index(name: String): Int = EVENTMODELING_TOKENS.indexOfFirst { t -> t.name == name }

private fun kinds(names: List<String>): Set<Int> = names.map { name -> index(name) }.toSet()

private object T {
  val keyword = 0
  val arrow = index("->>")
  val open = index("[[")
  val close = index("]]")
  val tick = index("`")
  val dot = index(".")
  val id = index("EM_ID")
  val frameId = index("EM_FID")
  val inline = index("EM_DATA_INLINE")
  val block = index("EM_DATA_BLOCK")
  val accDescr = index("EM_ACC_DESCR")
  val accTitle = index("EM_ACC_TITLE")
  val title = index("EM_TITLE")
  val entity = index("entity")
  val data = index("data")
  val note = index("note")
  val gwt = index("gwt")
  val given = index("given")
  val `when` = index("when")
  val then = index("then")
}

private val TIME_FRAME = kinds(listOf("tf", "timeframe"))
private val RESET_FRAME = kinds(listOf("rf", "resetframe"))
private val ENTITY_TYPE =
  kinds(listOf("rmo", "readmodel", "ui", "cmd", "command", "evt", "event", "pcr", "processor"))
private val DATA_TYPE = kinds(listOf("json", "jsobj", "figma", "salt", "uri", "md", "html", "text"))

// A cross-reference as Mermaid reads it: by its text, never linked.
// PORT: `$refText` is `refText`; likewise `$type`, `$offset` and `$source` below.
class Ref(val refText: String)

class EmFrame(
  // "EmTimeFrame" | "EmResetFrame"
  val type: String,
  var name: String,
  var modelEntityType: String,
  var entityIdentifier: String,
  val sourceFrames: MutableList<Ref>,
  var dataReference: Ref? = null,
  var dataType: String? = null,
  var dataInlineValue: String? = null,
  // Offset of the frame in the source, for error messages.
  val offset: Int,
)

class EmDataEntity(
  val name: String,
  var dataType: String? = null,
  var dataBlockValue: String,
)

class EmNoteEntity(
  val sourceFrame: Ref,
  var dataType: String? = null,
  var dataBlockValue: String,
)

class EmGwtStatement(val entityIdentifier: Ref)

class EmGwt(
  val sourceFrame: Ref,
  var givenStatements: List<EmGwtStatement>,
  var whenStatements: List<EmGwtStatement>,
  var thenStatements: List<EmGwtStatement>,
)

class EmModelEntity(val name: String)

class EventModelAst(
  // The whole token, keyword included: the grammar's own terminals get no value conversion.
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val modelEntities: MutableList<EmModelEntity> = ArrayList(),
  val frames: MutableList<EmFrame> = ArrayList(),
  val dataEntities: MutableList<EmDataEntity> = ArrayList(),
  val noteEntities: MutableList<EmNoteEntity> = ArrayList(),
  val gwtEntities: MutableList<EmGwt> = ArrayList(),
  val source: String,
)

fun parseEventModel(src: String): EventModelAst {
  val r =
    Reader(
      tokenize(src, EVENTMODELING_TOKENS, "eventmodeling"),
      EVENTMODELING_TOKENS,
      "eventmodeling",
    )
  val ast = EventModelAst(source = src)
  fun at(kind: Int): Boolean = r.kinds.getOrNull(r.i) == kind
  fun has(set: Set<Int>): Boolean {
    val k = r.kinds.getOrNull(r.i)
    return k != null && set.contains(k)
  }
  fun one(set: Set<Int>, expected: String): String = if (has(set)) r.take() else r.fail(expected)
  fun qualifiedName(): String {
    var name = r.expect(T.id)
    while (r.accept(T.dot)) name += "." + r.expect(T.id)
    return name
  }
  fun dataType(): String? {
    if (!r.accept(T.tick)) return null
    val type = one(DATA_TYPE, "a data type")
    r.expect(T.tick)
    return type
  }
  fun statements(): List<EmGwtStatement> {
    val out = ArrayList<EmGwtStatement>()
    do {
      one(ENTITY_TYPE, "an entity type")
      out.add(EmGwtStatement(Ref(r.expect(T.id))))
    } while (has(ENTITY_TYPE))
    return out
  }

  r.expect(T.keyword)
  while (!at(-1)) {
    val kind = r.kinds.getOrNull(r.i)
    if (kind == T.accDescr) {
      ast.accDescr = r.take()
    } else if (kind == T.accTitle) {
      ast.accTitle = r.take()
    } else if (kind == T.title) {
      ast.title = r.take()
    } else if (kind == T.entity) {
      r.i++
      ast.modelEntities.add(EmModelEntity(qualifiedName()))
    } else if (kind != null && (TIME_FRAME.contains(kind) || RESET_FRAME.contains(kind))) {
      val frame =
        EmFrame(
          type = if (TIME_FRAME.contains(kind)) "EmTimeFrame" else "EmResetFrame",
          name = "",
          modelEntityType = "",
          entityIdentifier = "",
          sourceFrames = ArrayList(),
          offset = r.tokens.starts[r.i],
        )
      r.i++
      frame.name = r.expect(T.frameId)
      frame.modelEntityType = one(ENTITY_TYPE, "an entity type")
      frame.entityIdentifier = qualifiedName()
      while (r.accept(T.arrow)) frame.sourceFrames.add(Ref(r.expect(T.frameId)))
      if (r.accept(T.open)) {
        frame.dataReference = Ref(r.expect(T.id))
        r.expect(T.close)
      }
      if (at(T.tick) || at(T.inline)) {
        val type = dataType()
        if (type != null) frame.dataType = type
        frame.dataInlineValue = r.expect(T.inline)
      }
      ast.frames.add(frame)
    } else if (kind == T.data) {
      r.i++
      val entity = EmDataEntity(r.expect(T.id), dataBlockValue = "")
      val type = dataType()
      if (type != null) entity.dataType = type
      entity.dataBlockValue = r.expect(T.block)
      ast.dataEntities.add(entity)
    } else if (kind == T.note) {
      r.i++
      val note = EmNoteEntity(Ref(r.expect(T.frameId)), dataBlockValue = "")
      val type = dataType()
      if (type != null) note.dataType = type
      note.dataBlockValue = r.expect(T.block)
      ast.noteEntities.add(note)
    } else if (kind == T.gwt) {
      r.i++
      val gwt = EmGwt(Ref(r.expect(T.frameId)), ArrayList(), ArrayList(), ArrayList())
      r.expect(T.given)
      gwt.givenStatements = statements()
      if (r.accept(T.`when`)) gwt.whenStatements = statements()
      r.expect(T.then)
      gwt.thenStatements = statements()
      ast.gwtEntities.add(gwt)
    } else {
      r.fail("a frame, an entity, a data block, a note, or a specification")
    }
  }
  return ast
}
