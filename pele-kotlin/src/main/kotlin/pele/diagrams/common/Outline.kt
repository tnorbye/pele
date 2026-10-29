// Ported from Pele src/diagrams/common/outline.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsReplace
import pele.js.jsSlice
import pele.syntaxError
import pele.util.isWord

// The indented outline grammar that Mermaid's mindmap and kanban diagrams share.
// Kanban adds `@{ ... }` metadata after a node and keeps `@` out of node ids.

object T {
  const val END = 0
  const val ERROR = 1
  const val SPACELINE = 2
  const val NL = 3
  const val EOF = 4
  const val KEYWORD = 5
  const val SPACELIST = 6
  const val ICON = 7
  const val CLASS = 8
  const val NODE_DSTART = 9
  const val NODE_ID = 10
  const val NODE_DESCR = 11
  const val NODE_DEND = 12
  const val SHAPE_DATA = 13
}

// As the generated parser prints them. It has no name for the end of input and prints its number.
private val NAMES =
  listOf(
    "1",
    "INVALID",
    "SPACELINE",
    "NL",
    "EOF",
    "",
    "SPACELIST",
    "ICON",
    "CLASS",
    "NODE_DSTART",
    "NODE_ID",
    "NODE_DESCR",
    "NODE_DEND",
    "SHAPE_DATA",
  )

fun tokenName(type: Int, kanban: Boolean): String =
  if (type == T.KEYWORD) (if (kanban) "KANBAN" else "MINDMAP")
  else NAMES.getOrElse(type) { "undefined" }

object NODE_TYPE {
  const val DEFAULT = 0
  const val NO_BORDER = 0
  const val ROUNDED_RECT = 1
  const val RECT = 2
  const val CIRCLE = 3
  const val CLOUD = 4
  const val BANG = 5
  const val HEXAGON = 6
}

// Any opening delimiter pairs with any closing one, as in Mermaid.
fun getType(start: String, end: String): Int =
  when (start) {
    "[" -> NODE_TYPE.RECT
    "(" -> if (end == ")") NODE_TYPE.ROUNDED_RECT else NODE_TYPE.CLOUD
    "((" -> NODE_TYPE.CIRCLE
    ")" -> NODE_TYPE.CLOUD
    "))" -> NODE_TYPE.BANG
    "{{" -> NODE_TYPE.HEXAGON
    else -> NODE_TYPE.DEFAULT
  }

class Decoration(val icon: String? = null, val `class`: String? = null)

interface OutlineDb {
  fun getType(start: String, end: String): Int

  fun addNode(level: Int, id: String, descr: String, type: Int, shapeData: String? = null)

  fun decorateNode(decoration: Decoration)
}

private object S {
  const val INITIAL = 0
  const val CLASS = 1
  const val ICON = 2
  const val NODE = 3
  const val NSTR = 4
  const val NSTR2 = 5
  const val DATA = 6
  const val DATA_STR = 7
}

private val RE_SPACE = JsRegExp("""\s+""", "y")
private val RE_LINE = JsRegExp(""".+""", "y")
private val RE_ID = JsRegExp("""[^(\[\n){}]+""", "y")
private val RE_ID_KANBAN = JsRegExp("""[^(\[\n){}@]+""", "y")
private val RE_DESCR = JsRegExp("""[^)\](}]+""", "y")
private val RE_MD = JsRegExp("""[^`"]+""", "y")
private val RE_DATA = JsRegExp("""[^}^"]+""", "y")
private val RE_BR = JsRegExp("""\n\s*""", "g")

// Jison's case-insensitive flag folds ASCII letters only.
private fun letters(src: String, p: Int, word: String): Boolean {
  for (k in word.indices) if ((src.charCodeAt(p + k) or 32) != word[k].code) return false
  return true
}

fun tokenize(src: String, kanban: Boolean): ScanTokens {
  val n = src.length
  val out = ScanTokens()
  val types = out.types
  val texts = out.texts
  val starts = out.starts
  val keyword = if (kanban) "kanban" else "mindmap"
  val reId = if (kanban) RE_ID_KANBAN else RE_ID
  var state = S.INITIAL
  var p = 0
  var nextBreak = -1

  val put = { type: Int, text: String, end: Int ->
    types.add(type)
    texts.add(text)
    starts.add(p)
    p = end
  }
  val emit = { type: Int, end: Int, next: Int ->
    put(type, src.jsSlice(p, end), end)
    state = next
  }
  val at = { re: JsRegExp, from: Int ->
    re.lastIndex = from
    if (re.test(src)) re.lastIndex else -1
  }
  val upTo = { ch: Char ->
    val e = src.indexOf(ch, maxOf(p, 0))
    if (e == -1) n else e
  }

  while (p < n) {
    val c = src.charCodeAt(p)
    val d = src.charCodeAt(p + 1)
    var e: Int
    if (state == S.INITIAL) {
      val w = if (c <= 32 || c >= 160) maxOf(at(RE_SPACE, p), p) else p
      if (kanban && c == 64 && d == 123) {
        put(T.SHAPE_DATA, "", p + 2)
        state = S.DATA
      } else if (src.charCodeAt(w) == 37 && src.charCodeAt(w + 1) == 37) {
        emit(T.SPACELINE, at(RE_LINE, w), S.INITIAL)
      } else if (w > p) {
        if (nextBreak < p) nextBreak = upTo('\n')
        if (nextBreak >= w) {
          emit(T.SPACELIST, w, S.INITIAL)
        } else {
          e = if (w - 1 < 0) -1 else src.lastIndexOf('\n', w - 1)
          if (e > p) emit(T.SPACELINE, e + 1, S.INITIAL) else emit(T.NL, p + 1, S.INITIAL)
        }
      } else if (letters(src, p, keyword) && !isWord(src.charCodeAt(p + keyword.length))) {
        emit(T.KEYWORD, p + keyword.length, S.INITIAL)
      } else if (c == 58 && d == 58 && src.charCodeAt(p + 2) == 58) {
        p += 3
        state = S.CLASS
      } else if (c == 58 && d == 58 && letters(src, p + 2, "icon") && src.charCodeAt(p + 6) == 40) {
        p += 7
        state = S.ICON
      } else if (
        (c == 45 && d == 41) ||
          (c == 40 && (d == 45 || d == 40)) ||
          ((c == 41 || c == 123) && d == c)
      ) {
        emit(T.NODE_DSTART, p + 2, S.NODE)
      } else if (c == 41 || c == 40 || c == 91) {
        emit(T.NODE_DSTART, p + 1, S.NODE)
      } else {
        e = at(reId, p)
        if (e != -1) emit(T.NODE_ID, e, S.INITIAL) else break
      }
    } else if (state == S.NODE) {
      if (c == 34) {
        state = if (d == 96) S.NSTR2 else S.NSTR
        p += if (d == 96) 2 else 1
      } else if (c == 41 || c == 40) {
        emit(T.NODE_DEND, if (d == c || (c == 40 && d == 45)) p + 2 else p + 1, S.INITIAL)
      } else if (c == 93) {
        emit(T.NODE_DEND, p + 1, S.INITIAL)
      } else if ((c == 125 && d == 125) || (c == 45 && d == 41)) {
        emit(T.NODE_DEND, p + 2, S.INITIAL)
      } else {
        // A lone closing brace takes the rest of its line as text.
        emit(T.NODE_DESCR, at(if (c == 125) RE_LINE else RE_DESCR, p), S.NODE)
      }
    } else if (state == S.NSTR) {
      if (c == 34) {
        p++
        state = S.NODE
      } else {
        emit(T.NODE_DESCR, upTo('"'), S.NSTR)
      }
    } else if (state == S.NSTR2) {
      if (c == 96 && d == 34) {
        p += 2
        state = S.NODE
      } else {
        e = at(RE_MD, p)
        if (e != -1) emit(T.NODE_DESCR, e, S.NSTR2) else break
      }
    } else if (state == S.CLASS) {
      e = at(RE_LINE, p)
      if (e != -1) {
        emit(T.CLASS, e, S.INITIAL)
      } else if (c == 10) {
        p++
        state = S.INITIAL
      } else {
        break
      }
    } else if (state == S.ICON) {
      if (c == 41) {
        p++
        state = S.INITIAL
      } else {
        emit(T.ICON, upTo(')'), S.ICON)
      }
    } else if (state == S.DATA) {
      if (c == 34) {
        emit(T.SHAPE_DATA, p + 1, S.DATA_STR)
      } else if (c == 125) {
        p++
        state = S.INITIAL
      } else {
        e = at(RE_DATA, p)
        if (e != -1) emit(T.SHAPE_DATA, e, S.DATA) else break
      }
    } else if (c == 34) {
      emit(T.SHAPE_DATA, p + 1, S.DATA)
    } else {
      e = upTo('"')
      put(T.SHAPE_DATA, src.jsSlice(p, e).jsReplace(RE_BR, "<br/>"), e)
    }
  }

  if (p < n) {
    put(T.ERROR, "", p)
  } else {
    if (state == S.INITIAL) put(T.EOF, "", n)
    put(T.END, "", n)
  }
  return out
}

private const val STOP = "'SPACELINE', 'NL', 'EOF'"
private val START =
  IntArray(T.SHAPE_DATA + 1).also { a ->
    for (t in listOf(T.SPACELINE, T.SPACELIST, T.ICON, T.CLASS, T.NODE_DSTART, T.NODE_ID)) a[t] = 1
  }

private fun isStop(type: Int): Boolean = type == T.SPACELINE || type == T.NL || type == T.EOF

// Makes the calls on `db` that the grammar's actions make, in the same order, and reports
// errors in the state where the generated parser would.
fun parseOutline(src: String, db: OutlineDb, kanban: Boolean) {
  val diagram = if (kanban) "kanban" else "mindmap"
  val tokens = tokenize(src, kanban)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  val data = if (kanban) ", 'SHAPE_DATA'" else ""
  var i = 0
  // PORT: reading past the token list gives undefined in JS, which is no token type.
  fun ty(k: Int): Int = types.getOrElse(k) { -1 }

  fun fail(expected: String): Nothing {
    val t = ty(i)
    if (t == T.ERROR) throw syntaxError(diagram, src, starts[i], "", true)
    throw syntaxError(diagram, src, starts[i], "Expecting $expected, got '${tokenName(t, kanban)}'")
  }

  val keyword = "'${tokenName(T.KEYWORD, kanban)}'"
  if (ty(i) == T.SPACELINE) {
    i++
    while (ty(i) == T.SPACELINE || ty(i) == T.NL) i++
    if (ty(i) != T.KEYWORD) fail("'SPACELINE', 'NL', $keyword")
  } else if (ty(i) != T.KEYWORD) {
    fail("'SPACELINE', $keyword")
  }
  i++
  var first = "'SPACELINE', 'NL', 'SPACELIST', 'ICON', 'CLASS', 'NODE_DSTART', 'NODE_ID'"
  if (ty(i) == T.NL) {
    i++
    first = "'SPACELINE', 'SPACELIST', 'ICON', 'CLASS', 'NODE_DSTART', 'NODE_ID'"
  }

  while (true) {
    var t = ty(i)
    var level = 0
    val indented = t == T.SPACELIST
    if (indented) {
      level = texts[i++].length
      t = ty(i)
    }
    if (t == T.NODE_ID || t == T.NODE_DSTART) {
      var id: String? = null
      val descr: String
      var type = 0
      if (t == T.NODE_ID) id = texts[i++]
      if (ty(i) == T.NODE_DSTART) {
        val start = texts[i++]
        if (ty(i) != T.NODE_DESCR) fail("'NODE_DESCR'")
        descr = texts[i++]
        if (ty(i) != T.NODE_DEND) fail("'NODE_DEND'")
        val end = texts[i++]
        if (!isStop(ty(i)) && ty(i) != T.SHAPE_DATA) fail(STOP + data)
        type = db.getType(start, end)
      } else {
        if (!isStop(ty(i)) && ty(i) != T.SHAPE_DATA) fail("$STOP, 'NODE_DSTART'$data")
        descr = id!!
      }
      if (ty(i) == T.SHAPE_DATA) {
        var shapeData = texts[i++]
        while (ty(i) == T.SHAPE_DATA) shapeData += texts[i++]
        if (!isStop(ty(i))) fail(STOP + data)
        db.addNode(level, id ?: descr, descr, type, shapeData)
      } else {
        db.addNode(level, id ?: descr, descr, type)
      }
    } else if (t == T.ICON || t == T.CLASS) {
      val text = texts[i++]
      if (!isStop(ty(i))) fail(STOP)
      db.decorateNode(if (t == T.ICON) Decoration(icon = text) else Decoration(`class` = text))
    } else if (indented) {
      if (!isStop(t)) fail("$STOP, 'ICON', 'CLASS', 'NODE_DSTART', 'NODE_ID'")
    } else if (t == T.SPACELINE) {
      if (!isStop(ty(++i))) fail(STOP)
    } else {
      fail(first)
    }

    i++
    while (ty(i) == T.NL || ty(i) == T.EOF) i++
    if (ty(i) == T.END) return
    if (START.getOrElse(ty(i)) { -1 } == 0)
      fail("$STOP, 'SPACELIST', 'ICON', 'CLASS', 'NODE_DSTART', 'NODE_ID'")
  }
}
