// Ported from Pele src/diagrams/class/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.js.jsTrim
import pele.syntaxError

private const val COUNT = T.PUNCTUATION + 1

private val ALPHANUM =
  IntArray(COUNT).also { for (t in intArrayOf(T.UNICODE_TEXT, T.ALPHA, T.MINUS)) it[t] = 1 }

private val STYLE_PART =
  IntArray(COUNT).also {
    for (t in intArrayOf(T.ALPHA, T.COLON, T.BRKT, T.STYLE, T.PCT, T.LABEL)) it[t] = 1
  }

// Mermaid's relationType numbers, plus one so that zero means "not an end marker".
private val END_TYPE =
  IntArray(COUNT).also {
    it[T.AGGREGATION] = 1
    it[T.EXTENSION] = 2
    it[T.COMPOSITION] = 3
    it[T.DEPENDENCY] = 4
    it[T.LOLLIPOP] = 5
  }

private const val NAME_TOKENS = "'UNICODE_TEXT', 'NUM', 'ALPHA', 'MINUS', 'BQUOTE_STR'"

private class Frame(
  val id: String,
  val classes: MutableList<String>,
  val notes: MutableList<String>,
)

fun parseClassDiagram(src: String, db: ClassDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  fun fail(expected: String): Nothing {
    val t = types[i]
    if (t == T.ERROR) throw syntaxError("class", src, starts[i], "", true)
    throw syntaxError("class", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }

  fun expect(type: Int): String {
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'")
    return texts[i++]
  }

  fun alphaNum(): String {
    if (ALPHANUM[types[i]] == 0) fail("'UNICODE_TEXT', 'NUM', 'ALPHA', 'MINUS'")
    return texts[i++]
  }

  fun name(generic: Boolean): String {
    var out = ""
    while (true) {
      if (types[i] == T.BQUOTE_STR) {
        out += texts[i++]
        break
      }
      if (ALPHANUM[types[i]] == 0) fail(NAME_TOKENS)
      out += texts[i++]
      val t = types[i]
      if (t == T.DOT) {
        out += "."
        i++
      } else if (ALPHANUM[t] == 0 && t != T.BQUOTE_STR) {
        break
      }
    }
    if (generic && types[i] == T.GENERICTYPE) out += "~" + texts[i++] + "~"
    return out
  }

  fun classLabel(): String {
    i++
    val label = expect(T.STR)
    expect(T.SQE)
    return label
  }

  fun members(): MutableList<String> {
    if (types[i] != T.MEMBER) fail("'STRUCT_STOP', 'MEMBER'")
    val list = ArrayList<String>()
    while (types[i] == T.MEMBER) list.add(texts[i++])
    expect(T.STRUCT_STOP)
    // The grammar collects members last to first, and addMembers turns them back.
    list.reverse()
    return list
  }

  fun classStatement(): String {
    i++
    val id = name(true)
    db.addClass(id)
    if (types[i] == T.SQS) db.setClassLabel(id, classLabel())
    when (types[i]) {
      T.STYLE_SEPARATOR -> {
        i++
        val css = alphaNum()
        db.setCssClass(id, css)
        if (types[i] == T.STRUCT_START) {
          i++
          db.addMembers(id, members())
        }
      }
      T.STRUCT_START -> {
        i++
        if (types[i] == T.STRUCT_STOP) i++ else db.addMembers(id, members())
      }
      T.ANNOTATION_START -> {
        i++
        val annotation = alphaNum()
        expect(T.ANNOTATION_END)
        db.addAnnotation(id, annotation)
        if (types[i] == T.STRUCT_START) {
          i++
          if (types[i] == T.STRUCT_STOP) i++ else db.addMembers(id, members())
        }
      }
    }
    return id
  }

  fun noteStatement(): String {
    if (types[i++] == T.NOTE) return db.addNote(expect(T.STR))
    val id = name(true)
    return db.addNote(expect(T.STR), id)
  }

  // Namespaces nest, so they are parsed with a stack of open ones.
  fun namespaceStatement() {
    val open = ArrayList<Frame>()
    while (true) {
      i++
      val ns = name(false)
      val id = if (types[i] == T.SQS) db.addNamespace(ns, classLabel()) else db.addNamespace(ns)
      expect(T.STRUCT_START)
      if (types[i] == T.NEWLINE) i++
      open.add(Frame(id, ArrayList(), ArrayList()))
      var item = true
      while (item) {
        val frame = open[open.size - 1]
        val t = types[i]
        if (t == T.NAMESPACE) break
        if (t == T.CLASS) frame.classes.add(classStatement())
        else if (t == T.NOTE || t == T.NOTE_FOR) frame.notes.add(noteStatement())
        else fail("'NAMESPACE', 'CLASS', 'NOTE_FOR', 'NOTE'")
        while (true) {
          if (types[i] == T.NEWLINE) {
            val next = types[i + 1]
            if (next == T.NAMESPACE || next == T.CLASS || next == T.NOTE || next == T.NOTE_FOR) {
              i++
              break
            }
            i++
          }
          expect(T.STRUCT_STOP)
          val done = open.removeAt(open.size - 1)
          db.addClassesToNamespace(done.id, done.classes, done.notes)
          db.popNamespace()
          if (open.size == 0) {
            item = false
            break
          }
        }
      }
      if (open.size == 0) return
    }
  }

  fun relationEnd(): RelationEnd {
    val type = END_TYPE[types[i]]
    if (type == 0) return RELATION_NONE
    i++
    return type - 1
  }

  fun classOrRelation() {
    val id1 = name(true)
    var t = types[i]
    if (t == T.LABEL) {
      db.addMember(id1, db.cleanupLabel(texts[i++]))
      return
    }
    var title1 = "none"
    if (t == T.STR) {
      title1 = texts[i++]
      t = types[i]
    } else if (END_TYPE[t] == 0 && t != T.LINE && t != T.DOTTED_LINE) {
      return
    }
    val type1 = relationEnd()
    t = types[i]
    if (t != T.LINE && t != T.DOTTED_LINE) {
      fail(
        "'AGGREGATION', 'EXTENSION', 'COMPOSITION', 'DEPENDENCY', 'LOLLIPOP', 'LINE', 'DOTTED_LINE'"
      )
    }
    i++
    val type2 = relationEnd()
    val title2 = if (types[i] == T.STR) texts[i++] else "none"
    val id2 = name(true)
    val relation =
      ClassRelation(
        id1 = id1,
        id2 = id2,
        relation = RelationKind(type1, type2, if (t == T.LINE) 0 else 1),
        relationTitle1 = title1,
        relationTitle2 = title2,
      )
    if (types[i] == T.LABEL) relation.title = db.cleanupLabel(texts[i++])
    db.addRelation(relation)
  }

  fun styles(): List<String> {
    val out = ArrayList<String>()
    while (true) {
      if (STYLE_PART[types[i]] == 0)
        fail("'ALPHA', 'NUM', 'COLON', 'UNIT', 'SPACE', 'BRKT', 'STYLE', 'PCT', 'LABEL'")
      var s = texts[i++]
      while (STYLE_PART[types[i]] == 1) s += texts[i++]
      out.add(s)
      if (types[i] != T.COMMA) return out
      i++
    }
  }

  fun tooltip(id: String) {
    if (types[i] == T.STR) db.setTooltip(id, texts[i++])
  }

  fun linkStatement(id: String) {
    val href = expect(T.STR)
    val tip = if (types[i] == T.STR) texts[i++] else null
    if (types[i] == T.LINK_TARGET) db.setLink(id, href, texts[i++]) else db.setLink(id, href)
    if (tip != null) db.setTooltip(id, tip)
  }

  fun statement() {
    val t = types[i]
    when (t) {
      T.UNICODE_TEXT,
      T.ALPHA,
      T.MINUS,
      T.BQUOTE_STR -> {
        classOrRelation()
        return
      }
      T.NAMESPACE -> {
        namespaceStatement()
        return
      }
      T.CLASS -> {
        classStatement()
        return
      }
      T.MEMBER -> {
        i++
        return
      }
      T.ANNOTATION_START -> {
        i++
        val annotation = alphaNum()
        expect(T.ANNOTATION_END)
        db.addAnnotation(name(true), annotation)
        return
      }
      T.CALLBACK -> {
        i++
        val id = name(true)
        db.setClickEvent(id, expect(T.STR))
        tooltip(id)
        return
      }
      T.LINK -> {
        i++
        linkStatement(name(true))
        return
      }
      T.CLICK -> {
        i++
        val id = name(true)
        if (types[i] == T.HREF) {
          i++
          linkStatement(id)
          return
        }
        val fn = expect(T.CALLBACK_NAME)
        if (types[i] == T.CALLBACK_ARGS) db.setClickEvent(id, fn, texts[i++])
        else db.setClickEvent(id, fn)
        tooltip(id)
        return
      }
      T.STYLE -> {
        i++
        val id = expect(T.ALPHA)
        db.setCssStyle(id, styles())
        return
      }
      T.CSSCLASS -> {
        i++
        val ids = expect(T.STR)
        db.setCssClass(ids, expect(T.ALPHA))
        return
      }
      T.NOTE,
      T.NOTE_FOR -> {
        noteStatement()
        return
      }
      T.CLASSDEF -> {
        i++
        val ids = arrayListOf(expect(T.ALPHA))
        while (types[i] == T.COMMA) {
          i++
          ids.add(expect(T.ALPHA))
        }
        db.defineClass(ids, styles())
        return
      }
      T.direction_tb,
      T.direction_bt,
      T.direction_rl,
      T.direction_lr -> {
        i++
        db.setDirection(
          if (t == T.direction_tb) "TB"
          else if (t == T.direction_bt) "BT" else if (t == T.direction_rl) "RL" else "LR"
        )
        return
      }
      T.acc_title -> {
        i++
        db.setAccTitle(expect(T.acc_title_value).jsTrim())
        return
      }
      T.acc_descr -> {
        i++
        db.setAccDescription(expect(T.acc_descr_value).jsTrim())
        return
      }
      T.acc_descr_multiline_value -> {
        db.setAccDescription(texts[i++].jsTrim())
        return
      }
    }
    fail(
      "'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'NAMESPACE', 'ANNOTATION_START', 'CLASS', 'MEMBER', 'NOTE_FOR', 'NOTE', 'CLASSDEF', 'direction_tb', 'CALLBACK', 'LINK', 'CLICK', 'STYLE', 'CSSCLASS', 'ALPHA', 'MINUS', 'BQUOTE_STR'"
    )
  }

  // Mermaid's grammar also accepts statements with no `classDiagram` line before them.
  val header = types[i] == T.CLASS_DIAGRAM
  if (header) {
    i++
    expect(T.NEWLINE)
  }
  while (true) {
    statement()
    if (types[i] != T.NEWLINE) break
    i++
    if (types[i] == T.EOF || types[i] == T.END) break
  }
  if (header) expect(T.EOF)
  expect(T.END)
}
