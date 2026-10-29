// Ported from Pele src/diagrams/c4/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import pele.js.jsSlice
import pele.js.jsSubstring
import pele.js.jsTrim
import pele.syntaxError

// Mermaid's typeC4Shape for each shape keyword, indexed from T.PERSON.
private val KINDS = listOf(
  "person",
  "external_person",
  "system",
  "system_db",
  "system_queue",
  "external_system",
  "external_system_db",
  "external_system_queue",
  "container",
  "container_db",
  "container_queue",
  "external_container",
  "external_container_db",
  "external_container_queue",
  "component",
  "component_db",
  "component_queue",
  "external_component",
  "external_component_db",
  "external_component_queue",
)
private val REL_TYPES = listOf("rel", "birel", "rel_u", "rel_d", "rel_l", "rel_r", "rel_b", "rel")
private val NODE_TYPES = listOf("node", "nodeL", "nodeR")

private const val HEADERS = "'direction_tb', 'direction_bt', 'direction_rl', 'direction_lr', 'C4_CONTEXT', 'C4_CONTAINER', 'C4_COMPONENT', 'C4_DYNAMIC', 'C4_DEPLOYMENT'"
private const val ATTRIBUTES = "'STR', 'STR_KEY', 'ATTRIBUTE', 'ATTRIBUTE_EMPTY'"

private object P {
  // A statement is required.
  const val Item = 0

  // Only a diagram statement may come next: the first one in a boundary, or one written straight after a title.
  const val Diagram = 1
  const val ItemOrEnd = 2
  const val AfterOther = 3
  const val AfterDiagram = 4
}

private fun isOther(t: Int): Boolean =
  t == T.title || t == T.accDescription || t == T.acc_title || t == T.acc_descr || t == T.acc_descr_multiline_value

private fun isDiagram(t: Int): Boolean = t >= T.PERSON && t <= T.UPDATE_LAYOUT_CONFIG

// Accepts what the parser Jison generates from c4Diagram.jison accepts, and calls the model
// builder in the order its semantic actions do. Boundaries nest through a counter, not recursion.
fun parseC4(src: String, db: C4Db) {
  val tokens = tokenize(src)
  val types = tokens.types
  val starts = tokens.starts
  val ends = tokens.ends
  var i = 0

  fun text(at: Int): String = src.jsSlice(starts[at], ends[at])
  fun fail(expected: String): Nothing {
    val t = types[i]
    if (t == T.ERROR) throw syntaxError("c4", src, starts[i], "", true)
    throw syntaxError("c4", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }
  fun expect(type: Int) {
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'")
    i++
  }

  fun attributes(): MutableList<C4Attr> {
    val attrs = ArrayList<C4Attr>()
    while (true) {
      val t = types[i]
      if (t == T.STR) {
        attrs.add(text(i++).jsTrim())
      } else if (t == T.ATTRIBUTE_EMPTY) {
        attrs.add("")
        i++
      } else if (t == T.STR_KEY) {
        i++
        if (types[i] != T.STR_VALUE) fail("'STR_VALUE'")
        attrs.add(C4Named(text(i - 1).jsTrim(), text(i).jsTrim()))
        i++
      } else {
        break
      }
    }
    if (attrs.size == 0) fail(ATTRIBUTES)
    return attrs
  }

  fun other(t: Int) {
    if (t == T.title) {
      db.setTitle(text(i++).jsSubstring(6))
    } else if (t == T.accDescription) {
      db.setAccDescription(text(i++).jsSubstring(15))
    } else if (t == T.acc_descr_multiline_value) {
      db.setAccDescription(text(i++).jsTrim())
    } else {
      i++
      if (t == T.acc_title) {
        if (types[i] != T.acc_title_value) fail("'acc_title_value'")
        // The grammar hands `accTitle:` to setTitle, so it becomes the visible title.
        db.setTitle(text(i++).jsTrim())
      } else {
        if (types[i] != T.acc_descr_value) fail("'acc_descr_value'")
        db.setAccDescription(text(i++).jsTrim())
      }
    }
  }

  // Returns true when the statement opens a boundary.
  fun diagram(t: Int): Boolean {
    i++
    val attrs = attributes()
    if (t <= T.SYSTEM_EXT_QUEUE) {
      db.addPersonOrSystem(KINDS[t - T.PERSON], attrs)
    } else if (t <= T.CONTAINER_EXT_QUEUE) {
      db.addContainer(KINDS[t - T.PERSON], attrs)
    } else if (t <= T.COMPONENT_EXT_QUEUE) {
      db.addComponent(KINDS[t - T.PERSON], attrs)
    } else if (t <= T.NODE_R) {
      if (t == T.ENTERPRISE_BOUNDARY) attrs.add(minOf(2, attrs.size), "ENTERPRISE")
      else if (t == T.SYSTEM_BOUNDARY) attrs.add(minOf(2, attrs.size), "SYSTEM")
      else if (t == T.CONTAINER_BOUNDARY) attrs.add(minOf(2, attrs.size), "CONTAINER")
      if (t >= T.NODE) db.addDeploymentNode(NODE_TYPES[t - T.NODE], attrs)
      else if (t == T.CONTAINER_BOUNDARY) db.addContainerBoundary(attrs)
      else db.addPersonOrSystemBoundary(attrs)
      if (types[i] == T.LBRACE) {
        i++
        expect(T.NEWLINE)
      } else if (types[i] == T.NEWLINE) {
        i++
        expect(T.LBRACE)
        if (types[i] == T.NEWLINE) i++
      } else {
        fail("'NEWLINE', 'LBRACE'")
      }
      return true
    } else if (t <= T.REL_INDEX) {
      // RelIndex takes a sequence number first, which Mermaid ignores.
      if (t == T.REL_INDEX) attrs.removeAt(0)
      db.addRel(REL_TYPES[t - T.REL], attrs)
    } else if (t == T.UPDATE_EL_STYLE) {
      db.updateElStyle("update_el_style", attrs)
    } else if (t == T.UPDATE_REL_STYLE) {
      db.updateRelStyle("update_rel_style", attrs)
    } else {
      db.updateLayoutConfig("update_layout_config", attrs)
    }
    return false
  }

  val header = types[i]
  if (header < T.C4_CONTEXT || header > T.C4_DEPLOYMENT) fail(HEADERS)
  i++
  expect(T.NEWLINE)

  var depth = 0
  var state: Int = P.Item
  while (true) {
    val t = types[i]
    if (state == P.AfterOther || state == P.AfterDiagram) {
      if (t == T.NEWLINE) {
        i++
        state = P.ItemOrEnd
        continue
      }
      if (state == P.AfterOther && isDiagram(t)) {
        state = P.Diagram
        continue
      }
    } else if (isDiagram(t)) {
      if (diagram(t)) {
        depth++
        state = P.Diagram
      } else {
        state = P.AfterDiagram
      }
      continue
    } else if (isOther(t) && state != P.Diagram) {
      other(t)
      state = P.AfterOther
      continue
    } else if (state != P.ItemOrEnd) {
      fail("'title', 'accDescription', 'acc_title', 'acc_descr', 'acc_descr_multiline_value', 'PERSON', 'REL'")
    }

    if (depth > 0) {
      if (t != T.RBRACE) fail("'NEWLINE', 'RBRACE'")
      i++
      db.popBoundaryParseStack()
      depth--
      state = P.AfterDiagram
    } else {
      if (t != T.EOF) fail("'NEWLINE', 'EOF'")
      i++
      expect(T.END)
      db.setC4Type(text(0))
      return
    }
  }
}
