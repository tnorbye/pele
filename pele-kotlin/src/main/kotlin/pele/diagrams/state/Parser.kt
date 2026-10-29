// Ported from Pele src/diagrams/state/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.jsSplit
import pele.js.jsTrim
import pele.locate
import pele.syntaxError

// The calls Mermaid's grammar makes on its `yy` object.
interface StateBuilder {
  fun setRootDoc(doc: MutableList<Any>)

  fun trimColon(text: String): String

  fun getDividerId(): String

  fun setDirection(dir: String)

  fun setAccTitle(text: String)

  fun setAccDescription(text: String)
}

private val DIRECTIONS = listOf("TB", "BT", "RL", "LR")
private const val STATEMENT =
  "'NL', 'ID', 'HIDE_EMPTY', 'scale', 'COMPOSIT_STATE', 'STATE_DESCR', 'FORK', 'JOIN', 'CHOICE', 'CONCURRENT', 'note', " +
    "'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'CLICK', 'classDef', 'style', 'class', 'direction_tb', " +
    "'direction_bt', 'direction_rl', 'direction_lr', 'EDGE_STATE'"

fun parseState(src: String, db: StateBuilder): MutableList<Any> {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  val error = tokens.error
  var i = 0

  fun fail(expected: String): Nothing {
    val t = types[i]
    if (t == T.ERROR) {
      if (error != null) {
        val loc = locate(src, starts[i])
        throw PeleError(
          error,
          PeleErrorCode.SYNTAX,
          PeleErrorInfo(
            type = "state",
            line = loc.line,
            column = loc.column,
            snippet = loc.snippet,
          ),
        )
      }
      throw syntaxError("state", src, starts[i], "", true)
    }
    throw syntaxError("state", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }
  fun expect(type: Int): String {
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'")
    return texts[i++]
  }
  fun idStatement(): StateStmt {
    val t = types[i]
    if (t != T.ID && t != T.EDGE_STATE) fail("'ID', 'EDGE_STATE'")
    val id = texts[i++].jsTrim()
    if (types[i] != T.STYLE_SEPARATOR) return StateStmt(id = id, type = "default", description = "")
    i++
    return StateStmt(
      id = id,
      classes = arrayListOf(expect(T.ID).jsTrim()),
      type = "default",
      description = "",
    )
  }

  while (types[i] == T.NL) i++
  if (types[i] != T.SD) fail("'NL', 'SD'")
  i++

  var doc = ArrayList<Any>()
  val outer = ArrayList<ArrayList<Any>>()
  // The grammar drops a statement whose value is the string it uses for a blank line.
  fun add(value: Any) {
    if (value != "nl") doc.add(value)
  }
  fun open(id: String, description: String) {
    i++
    val inner = ArrayList<Any>()
    doc.add(StateStmt(id = id, type = "default", description = description, doc = inner))
    outer.add(doc)
    doc = inner
  }

  while (true) {
    val t = types[i]
    when (t) {
      T.NL -> i++
      T.ID,
      T.EDGE_STATE -> {
        val first = idStatement()
        if (types[i] == T.DESCR) {
          first.description = db.trimColon(texts[i++])
          add(first)
        } else if (types[i] == T.ARROW) {
          i++
          val second = idStatement()
          if (types[i] == T.DESCR) {
            add(RelationStmt(first, second, db.trimColon(texts[i++])))
          } else {
            add(RelationStmt(first, second))
          }
        } else {
          add(first)
        }
      }
      T.HIDE_EMPTY -> add(texts[i++])
      T.scale -> {
        i++
        expect(T.WIDTH)
        add(texts[i - 2])
      }
      T.COMPOSIT_STATE ->
        if (types[i + 1] == T.STRUCT_START) open(texts[i++], "") else add(texts[i++])
      T.STATE_DESCR -> {
        val description = texts[i++]
        expect(T.AS)
        val id = expect(T.ID)
        if (types[i] == T.STRUCT_START) {
          open(id, description)
        } else if (id.contains(':')) {
          val parts = id.jsSplit(":")
          add(
            StateStmt(
              id = parts[0],
              type = "default",
              description = arrayListOf(description.jsTrim(), parts[1]),
            )
          )
        } else {
          add(StateStmt(id = id, type = "default", description = description.jsTrim()))
        }
      }
      T.FORK -> add(StateStmt(id = texts[i++], type = "fork"))
      T.JOIN -> add(StateStmt(id = texts[i++], type = "join"))
      T.CHOICE -> add(StateStmt(id = texts[i++], type = "choice"))
      T.CONCURRENT -> {
        i++
        add(StateStmt(id = db.getDividerId(), type = "divider"))
      }
      T.note -> {
        val keyword = texts[i++]
        if (types[i] == T.left_of || types[i] == T.right_of) {
          val position = texts[i++].jsTrim()
          val id = expect(T.ID).jsTrim()
          add(StateStmt(id = id, note = StateNote(position, expect(T.NOTE_TEXT).jsTrim())))
        } else {
          if (types[i] != T.NOTE_TEXT) fail("'NOTE_TEXT', 'left_of', 'right_of'")
          i++
          expect(T.AS)
          expect(T.ID)
          add(keyword)
        }
      }
      T.direction_tb,
      T.direction_bt,
      T.direction_rl,
      T.direction_lr -> {
        val value = DIRECTIONS[t - T.direction_tb]
        i++
        db.setDirection(value)
        add(DirectionStmt(value))
      }
      T.acc_title -> {
        i++
        val value = expect(T.acc_title_value).jsTrim()
        db.setAccTitle(value)
        add(value)
      }
      T.acc_descr -> {
        i++
        val value = expect(T.acc_descr_value).jsTrim()
        db.setAccDescription(value)
        add(value)
      }
      T.acc_descr_multiline_value -> {
        val value = texts[i++].jsTrim()
        db.setAccDescription(value)
        add(value)
      }
      T.CLICK -> {
        i++
        val id = idStatement()
        val url: String
        var tooltip = ""
        if (types[i] == T.HREF) {
          i++
          url = expect(T.STRING)
        } else {
          if (types[i] != T.STRING) fail("'STRING', 'HREF'")
          url = texts[i++]
          tooltip = expect(T.STRING)
        }
        expect(T.NL)
        add(ClickStmt(id, url, tooltip))
      }
      T.classDef -> {
        i++
        val id = expect(T.CLASSDEF_ID).jsTrim()
        add(ClassDefStmt(id, expect(T.CLASSDEF_STYLEOPTS).jsTrim()))
      }
      T.style -> {
        i++
        val id = expect(T.STYLE_IDS).jsTrim()
        add(StyleStmt(id, expect(T.STYLEDEF_STYLEOPTS).jsTrim()))
      }
      T.`class` -> {
        i++
        val id = expect(T.CLASSENTITY_IDS).jsTrim()
        add(ApplyClassStmt(id, expect(T.STYLECLASS).jsTrim()))
      }
      T.STRUCT_STOP -> {
        if (outer.size == 0) fail("'\$end', $STATEMENT")
        i++
        doc = outer.removeAt(outer.size - 1)
      }
      T.END -> {
        if (outer.size > 0) fail("'STRUCT_STOP', $STATEMENT")
        db.setRootDoc(doc)
        return doc
      }
      else -> fail(if (outer.size > 0) "'STRUCT_STOP', $STATEMENT" else "'\$end', $STATEMENT")
    }
  }
}
