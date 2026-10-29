// Ported from Pele src/diagrams/requirement/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

import pele.js.jsTrim
import pele.syntaxError

// What the keyword tokens stand for, from REQUIREMENT to TRACES in token order.
private val VALUES: List<String> = listOf(
  RequirementType.REQUIREMENT,
  RequirementType.FUNCTIONAL_REQUIREMENT,
  RequirementType.INTERFACE_REQUIREMENT,
  RequirementType.PERFORMANCE_REQUIREMENT,
  RequirementType.PHYSICAL_REQUIREMENT,
  RequirementType.DESIGN_CONSTRAINT,
  RiskLevel.LOW_RISK,
  RiskLevel.MED_RISK,
  RiskLevel.HIGH_RISK,
  VerifyType.VERIFY_ANALYSIS,
  VerifyType.VERIFY_DEMONSTRATION,
  VerifyType.VERIFY_INSPECTION,
  VerifyType.VERIFY_TEST,
  "",
  Relationships.CONTAINS,
  Relationships.COPIES,
  Relationships.DERIVES,
  Relationships.SATISFIES,
  Relationships.VERIFIES,
  Relationships.REFINES,
  Relationships.TRACES,
)

private fun isStylePart(t: Int): Boolean =
  t == T.ALPHA || t == T.COLON || t == T.BRKT || t == T.MINUS || t == T.SEMICOLON

fun parseRequirement(src: String, db: RequirementDb) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0
  val fields = ArrayList<Int>()
  val values = ArrayList<String>()

  // Fails with Jison's wording, naming the token types `from` to `to` and any others as expected.
  fun fail(from: Int, to: Int = from, vararg others: Int): Nothing {
    val t = types[i]
    if (t == T.ERROR) throw syntaxError("requirement", src, starts[i], "", true)
    val list = ArrayList<Int>()
    for (k in from..to) list.add(k)
    for (o in others) list.add(o)
    val expected = list.joinToString(", ") { type -> "'${TOKEN_NAMES[type]}'" }
    throw syntaxError("requirement", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }

  fun expect(type: Int): String {
    if (types[i] != type) fail(type)
    return texts[i++]
  }

  // The value of the next token if it is a keyword in the given range of token types.
  fun keyword(from: Int, to: Int): String {
    val t = types[i]
    if (t < from || t > to) fail(from, to)
    i++
    return VALUES[t - T.REQUIREMENT]
  }

  fun string(): String {
    val t = types[i]
    if (t != T.unqString && t != T.qString) fail(T.qString, T.unqString)
    return texts[i++]
  }

  fun directive(): Boolean {
    val t = types[i]
    if (t == T.acc_title) {
      i++
      db.setAccTitle(expect(T.acc_title_value).jsTrim())
    } else if (t == T.acc_descr) {
      i++
      db.setAccDescription(expect(T.acc_descr_value).jsTrim())
    } else if (t == T.acc_descr_multiline_value) {
      db.setAccDescription(texts[i++].jsTrim())
    } else {
      return false
    }
    return true
  }

  fun idList(): List<String> {
    val list = ArrayList<String>()
    while (true) {
      val t = types[i]
      if (t != T.ALPHA && t != T.unqString && t != T.qString) fail(T.qString, T.unqString, T.ALPHA)
      list.add(texts[i++])
      if (types[i] != T.COMMA) return list
      i++
    }
  }

  fun stylesOpt(): List<String> {
    val styles = ArrayList<String>()
    while (true) {
      if (!isStylePart(types[i])) fail(T.ALPHA, T.SEMICOLON, T.MINUS, T.BRKT)
      var style = texts[i++]
      while (isStylePart(types[i])) style += texts[i++]
      styles.add(style)
      if (types[i] != T.COMMA) return styles
      i++
    }
  }

  fun classList(): List<String>? {
    var classes: List<String>? = null
    if (types[i] == T.STYLE_SEPARATOR) {
      i++
      classes = idList()
      expect(T.STRUCT_START)
    } else if (types[i] == T.STRUCT_START) {
      i++
    } else {
      fail(T.STRUCT_START, T.STRUCT_START, T.STYLE_SEPARATOR)
    }
    expect(T.NEWLINE)
    return classes
  }

  // Reads a body up to its closing brace. The grammar is right recursive, so Mermaid applies
  // the fields last to first and the first of two repeated fields wins.
  fun body(requirement: Boolean) {
    fields.clear()
    values.clear()
    while (true) {
      val t = types[i]
      if (t == T.STRUCT_STOP) {
        i++
        break
      }
      if (t == T.NEWLINE) {
        i++
        continue
      }
      if (if (requirement) t < T.ID || t > T.VERIFYMTHD else t != T.TYPE && t != T.DOCREF) {
        if (requirement) fail(T.ID, T.VERIFYMTHD, T.NEWLINE, T.STRUCT_STOP)
        fail(T.TYPE, T.DOCREF, T.NEWLINE, T.STRUCT_STOP)
      }
      i++
      expect(T.COLONSEP)
      values.add(
        if (t == T.RISK) keyword(T.LOW_RISK, T.HIGH_RISK) else if (t == T.VERIFYMTHD) keyword(T.VERIFY_ANALYSIS, T.VERIFY_TEST) else string(),
      )
      fields.add(t)
      expect(T.NEWLINE)
    }
    for (k in fields.indices.reversed()) {
      val value = values[k]
      when (fields[k]) {
        T.ID -> db.setNewReqId(value)
        T.TEXT -> db.setNewReqText(value)
        T.RISK -> db.setNewReqRisk(value)
        T.VERIFYMTHD -> db.setNewReqVerifyMethod(value)
        T.TYPE -> db.setNewElementType(value)
        else -> db.setNewElementDocRef(value)
      }
    }
  }

  while (directive()) {
    if (types[i] == T.NEWLINE) i++
  }
  if (types[i] != T.RD) fail(T.RD, T.RD, T.acc_title, T.acc_descr, T.acc_descr_multiline_value)
  i++
  expect(T.NEWLINE)

  while (true) {
    val t = types[i]
    if (t == T.EOF) return
    if (t == T.NEWLINE) {
      i++
    } else if (directive()) {
      continue
    } else if (t >= T.direction_tb && t <= T.direction_lr) {
      i++
      db.setDirection(if (t == T.direction_tb) "TB" else if (t == T.direction_bt) "BT" else if (t == T.direction_rl) "RL" else "LR")
    } else if (t >= T.REQUIREMENT && t <= T.DESIGN_CONSTRAINT) {
      val type = keyword(T.REQUIREMENT, T.DESIGN_CONSTRAINT)
      val name = string()
      val classes = classList()
      body(true)
      db.addRequirement(name, type)
      if (classes != null) db.setClass(listOf(name), classes)
    } else if (t == T.ELEMENT) {
      i++
      val name = string()
      val classes = classList()
      body(false)
      db.addElement(name)
      if (classes != null) db.setClass(listOf(name), classes)
    } else if (t == T.STYLE) {
      i++
      val ids = idList()
      db.setCssStyle(ids, stylesOpt())
    } else if (t == T.CLASSDEF) {
      i++
      val ids = idList()
      db.defineClass(ids, stylesOpt())
    } else if (t == T.CLASS) {
      i++
      val ids = idList()
      db.setClass(ids, idList())
    } else if (t == T.unqString || t == T.qString) {
      val id = texts[i++]
      val next = types[i]
      if (next == T.STYLE_SEPARATOR) {
        i++
        db.setClass(listOf(id), idList())
      } else if (next == T.END_ARROW_L || next == T.LINE) {
        i++
        val relationship = keyword(T.CONTAINS, T.TRACES)
        expect(if (next == T.LINE) T.END_ARROW_R else T.LINE)
        val other = string()
        if (next == T.LINE) db.addRelationship(relationship, id, other)
        else db.addRelationship(relationship, other, id)
      } else {
        fail(T.STYLE_SEPARATOR, T.STYLE_SEPARATOR, T.END_ARROW_L, T.LINE)
      }
    } else {
      fail(
        T.NEWLINE,
        T.EOF,
        T.acc_title,
        T.acc_descr,
        T.acc_descr_multiline_value,
        T.direction_tb,
        T.direction_bt,
        T.direction_rl,
        T.direction_lr,
        T.REQUIREMENT,
        T.FUNCTIONAL_REQUIREMENT,
        T.INTERFACE_REQUIREMENT,
        T.PERFORMANCE_REQUIREMENT,
        T.PHYSICAL_REQUIREMENT,
        T.DESIGN_CONSTRAINT,
        T.ELEMENT,
        T.CLASSDEF,
        T.CLASS,
        T.STYLE,
        T.unqString,
        T.qString,
      )
    }
  }
}
