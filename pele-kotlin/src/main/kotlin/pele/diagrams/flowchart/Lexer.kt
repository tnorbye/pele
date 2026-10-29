// Ported from Pele src/diagrams/flowchart/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsReplace
import pele.js.jsSlice
import pele.util.isSpace
import pele.util.isUnicodeLetter
import pele.util.isWord

class Tokens(
  val types: MutableList<Int>,
  val texts: MutableList<String>,
  val starts: MutableList<Int>,
)

private object S {
  const val INITIAL = 0
  const val string = 1
  const val md_string = 2
  const val acc_title = 3
  const val acc_descr = 4
  const val acc_descr_multiline = 5
  const val dir = 6
  const val text = 7
  const val ellipseText = 8
  const val trapText = 9
  const val edgeText = 10
  const val thickEdgeText = 11
  const val dottedEdgeText = 12
  const val click = 13
  const val callbackname = 14
  const val callbackargs = 15
  const val shapeData = 16
  const val shapeDataStr = 17
}

private val NODE_CHAR: BooleanArray =
  BooleanArray(128).also { table ->
    for (ch in
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!\"#\$%&'*+.`?\\_/") table[
      ch.code] = true
  }

private val RE_ACC_TITLE = JsRegExp("""accTitle\s*:\s*""", "y")
private val RE_ACC_DESCR = JsRegExp("""accDescr\s*:\s*""", "y")
private val RE_ACC_DESCR_ML = JsRegExp("""accDescr\s*\{\s*""", "y")
private val RE_CALL = JsRegExp("""call\s+""", "y")
private val RE_CLICK = JsRegExp("""click\s+""", "y")
private val RE_NODIR = JsRegExp("""(?:\r?\n)*\s*\n""", "y")
private val RE_DIR = JsRegExp("""\s*(?:LR|RL|TB|BT|TD|BR)\b""", "y")
private val RE_DIR_SYM = JsRegExp("""\s*[<>^]""", "y")
private val RE_DIR_V = JsRegExp("""\s*v\b""", "y")
private val RE_DIRECTION =
  listOf(
    JsRegExp(""".*direction\s+TB[^\n]*""", "y"),
    JsRegExp(""".*direction\s+BT[^\n]*""", "y"),
    JsRegExp(""".*direction\s+RL[^\n]*""", "y"),
    JsRegExp(""".*direction\s+LR[^\n]*""", "y"),
    JsRegExp(""".*direction\s+TD[^\n]*""", "y"),
  )
private val RE_LINE_END = JsRegExp("""[\n\r  ]""", "g")
private val RE_LINK = JsRegExp("""\s*[xo<]?--+[-xo>]\s*""", "y")
private val RE_START_LINK = JsRegExp("""\s*[xo<]?--\s*""", "y")
private val RE_THICK_LINK = JsRegExp("""\s*[xo<]?==+[=xo>]\s*""", "y")
private val RE_THICK_START = JsRegExp("""\s*[xo<]?==\s*""", "y")
private val RE_DOTTED_LINK = JsRegExp("""\s*[xo<]?-?\.+-[xo>]?\s*""", "y")
private val RE_DOTTED_START = JsRegExp("""\s*[xo<]?-\.\s*""", "y")
private val RE_INVIS_LINK = JsRegExp("""\s*~~~+\s*""", "y")
private val RE_NEWLINE = JsRegExp("""(?:\r?\n)+""", "y")
private val RE_SPACE = JsRegExp("""[^\S\n\r]+""", "y")
private val RE_EMPTY_CALL = JsRegExp("""\(\s*\)""", "y")
private val RE_BR = JsRegExp("""\n\s*""", "g")

// PORT: the "no second character" argument of edgeText. Upstream passes -1, which never equals a
// NaN char code; here charCodeAt returns -1 past the end, so another value is used.
private const val NONE = -2

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state = S.INITIAL
  var p = 0
  var firstGraph = true
  var dirNext = src.indexOf("direction")
  var atNext = src.indexOf('@')
  var lineEnd = -1
  var dirFailed = 0
  var runEnd = 0
  var runAt = -1
  var wsFrom = -1
  var wsTo = -1

  val emit = { type: Int, s: Int, e: Int ->
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
  }
  val push = { s: Int ->
    stack.add(state)
    state = s
  }
  val pop = { if (stack.size > 0) state = stack.removeAt(stack.size - 1) }
  val at = { re: JsRegExp, q: Int ->
    re.lastIndex = q
    if (re.test(src)) re.lastIndex else -1
  }
  val kw = { word: String -> src.startsWith(word, p) && !isWord(src.charCodeAt(p + word.length)) }

  // First character after any run of whitespace starting at q, skipping one optional x, o or <.
  val linkChar = { q: Int ->
    var j = q
    if (q >= wsFrom && q <= wsTo) {
      j = wsTo
    } else if (isSpace(src.charCodeAt(q))) {
      while (j < n && isSpace(src.charCodeAt(j))) j++
      wsFrom = q
      wsTo = j
    }
    val d = src.charCodeAt(j)
    if (d == 120 || d == 111 || d == 60) src.charCodeAt(j + 1) else d
  }
  val invisAhead = { q: Int ->
    var j = q
    if (q >= wsFrom && q <= wsTo) {
      j = wsTo
    } else if (isSpace(src.charCodeAt(q))) {
      while (j < n && isSpace(src.charCodeAt(j))) j++
      wsFrom = q
      wsTo = j
    }
    src.charCodeAt(j) == 126
  }

  val quote = {
    if (src.charCodeAt(p + 1) == 96) {
      push(S.md_string)
      p += 2
    } else {
      push(S.string)
      p++
    }
  }

  val invisLink =
    fun(): Boolean {
      if (!invisAhead(p)) return false
      val e = at(RE_INVIS_LINK, p)
      if (e < 0) return false
      emit(T.LINK, p, e)
      return true
    }

  val opener =
    fun(c: Int): Boolean {
      val d = src.charCodeAt(p + 1)
      if (c == 40) {
        if (d == 45) {
          push(S.ellipseText)
          emit(T.ELLIPSE_START, p, p + 2)
        } else if (d == 91) {
          push(S.text)
          emit(T.STADIUMSTART, p, p + 2)
        } else if (d == 40 && src.charCodeAt(p + 2) == 40) {
          push(S.text)
          emit(T.DOUBLECIRCLESTART, p, p + 3)
        } else {
          push(S.text)
          emit(T.PS, p, p + 1)
        }
        return true
      }
      if (c == 91) {
        if (d == 91) {
          push(S.text)
          emit(T.SUBROUTINESTART, p, p + 2)
        } else if (d == 40) {
          push(S.text)
          emit(T.CYLINDERSTART, p, p + 2)
        } else if (d == 47) {
          push(S.trapText)
          emit(T.TRAPSTART, p, p + 2)
        } else if (d == 92) {
          push(S.trapText)
          emit(T.INVTRAPSTART, p, p + 2)
        } else {
          push(S.text)
          emit(T.SQS, p, p + 1)
        }
        return true
      }
      if (c == 124) {
        push(S.text)
        emit(T.PIPE, p, p + 1)
        return true
      }
      if (c == 123) {
        push(S.text)
        emit(T.DIAMOND_START, p, p + 1)
        return true
      }
      return false
    }

  val graph = { len: Int ->
    if (firstGraph) {
      firstGraph = false
      push(S.dir)
    }
    emit(T.GRAPH, p, p + len)
  }

  val nodeString =
    fun(): Boolean {
      var q = p
      while (q < n) {
        val ch = src.charCodeAt(q)
        if (ch < 128 && NODE_CHAR[ch]) {
          q++
        } else if (ch == 45 && q + 1 < n) {
          val d = src.charCodeAt(q + 1)
          if (d == 62 || d == 45 || d == 46) break
          q++
        } else {
          break
        }
      }
      if (q == p) return false
      emit(T.NODE_STRING, p, q)
      return true
    }

  val link =
    fun(c: Int): Boolean {
      val d = linkChar(p)
      var e: Int
      if (d == 45) {
        e = at(RE_LINK, p)
        if (e >= 0) {
          emit(T.LINK, p, e)
          return true
        }
        e = at(RE_START_LINK, p)
        if (e >= 0) {
          push(S.edgeText)
          emit(T.START_LINK, p, e)
          return true
        }
        e = at(RE_DOTTED_LINK, p)
        if (e >= 0) {
          emit(T.LINK, p, e)
          return true
        }
        e = at(RE_DOTTED_START, p)
        if (e >= 0) {
          push(S.dottedEdgeText)
          emit(T.START_LINK, p, e)
          return true
        }
      } else if (d == 61) {
        e = at(RE_THICK_LINK, p)
        if (e >= 0) {
          emit(T.LINK, p, e)
          return true
        }
        e = at(RE_THICK_START, p)
        if (e >= 0) {
          push(S.thickEdgeText)
          emit(T.START_LINK, p, e)
          return true
        }
      } else if (d == 46) {
        e = at(RE_DOTTED_LINK, p)
        if (e >= 0) {
          emit(T.LINK, p, e)
          return true
        }
      }
      return c != 45 && c != 61 && c != 46 && invisLink()
    }

  val initial =
    fun(c: Int): Boolean {
      var e: Int
      when (c) {
        97 ->
          if (src.startsWith("acc", p)) {
            e = at(RE_ACC_TITLE, p)
            if (e >= 0) {
              push(S.acc_title)
              emit(T.acc_title, p, e)
              return true
            }
            e = at(RE_ACC_DESCR, p)
            if (e >= 0) {
              push(S.acc_descr)
              emit(T.acc_descr, p, e)
              return true
            }
            e = at(RE_ACC_DESCR_ML, p)
            if (e >= 0) {
              push(S.acc_descr_multiline)
              p = e
              return true
            }
          }
        64 ->
          if (src.charCodeAt(p + 1) == 123) {
            push(S.shapeData)
            types.add(T.SHAPE_DATA)
            texts.add("")
            starts.add(p)
            p += 2
            return true
          }
        99 -> {
          e = at(RE_CALL, p)
          if (e >= 0) {
            push(S.callbackname)
            p = e
            return true
          }
          if (kw("classDef")) {
            emit(T.CLASSDEF, p, p + 8)
            return true
          }
          if (kw("class")) {
            emit(T.CLASS, p, p + 5)
            return true
          }
          e = at(RE_CLICK, p)
          if (e >= 0) {
            push(S.click)
            p = e
            return true
          }
        }
        34 -> {
          quote()
          return true
        }
        115 -> {
          if (kw("style")) {
            emit(T.STYLE, p, p + 5)
            return true
          }
          if (kw("swimlane-beta")) {
            graph(13)
            return true
          }
          if (kw("subgraph")) {
            emit(T.subgraph, p, p + 8)
            return true
          }
        }
        100 ->
          if (kw("default")) {
            emit(T.DEFAULT, p, p + 7)
            return true
          }
        108 ->
          if (kw("linkStyle")) {
            emit(T.LINKSTYLE, p, p + 9)
            return true
          }
        105 ->
          if (kw("interpolate")) {
            emit(T.INTERPOLATE, p, p + 11)
            return true
          }
        104 ->
          if (src.startsWith("href", p) && isSpace(src.charCodeAt(p + 4))) {
            emit(T.HREF, p, p + 5)
            return true
          }
        102 -> {
          if (kw("flowchart-elk")) {
            graph(13)
            return true
          }
          if (kw("flowchart")) {
            graph(9)
            return true
          }
        }
        103 ->
          if (kw("graph")) {
            graph(5)
            return true
          }
        101 ->
          if (kw("end")) {
            e = p + 3
            while (e < n && isSpace(src.charCodeAt(e))) e++
            emit(T.end, p, e)
            return true
          }
        95 -> {
          if (kw("_self")) {
            emit(T.LINK_TARGET, p, p + 5)
            return true
          }
          if (kw("_blank")) {
            emit(T.LINK_TARGET, p, p + 6)
            return true
          }
          if (kw("_parent")) {
            emit(T.LINK_TARGET, p, p + 7)
            return true
          }
          if (kw("_top")) {
            emit(T.LINK_TARGET, p, p + 4)
            return true
          }
        }
      }

      if (dirNext != -1 && p >= dirFailed) {
        if (dirNext < p) dirNext = src.indexOf("direction", p)
        if (dirNext != -1) {
          if (lineEnd < p) {
            RE_LINE_END.lastIndex = p
            lineEnd = if (RE_LINE_END.test(src)) RE_LINE_END.lastIndex - 1 else n
          }
          if (dirNext < lineEnd) {
            for (i in 0 until 5) {
              e = at(RE_DIRECTION[i], p)
              if (e >= 0) {
                emit(T.direction_tb + i, p, e)
                return true
              }
            }
            // No direction statement starts anywhere in the rest of this line either.
            dirFailed = lineEnd
          }
        }
      }

      // A link id is a run of non-space characters up to its last `@` that is not followed by `{`
      // or `"`.
      if (atNext != -1 && c != 34 && !isSpace(c)) {
        if (p >= runEnd) {
          if (atNext < p) atNext = src.indexOf('@', p)
          var q = p
          while (q < n) {
            val ch = src.charCodeAt(q)
            if (ch == 34 || isSpace(ch)) break
            q++
          }
          runEnd = q
          runAt = -1
          if (atNext != -1 && atNext < q) {
            var k = minOf(q, n - 1) - 1
            while (k >= atNext) {
              if (src.charCodeAt(k) != 64) {
                k--
                continue
              }
              val after = src.charCodeAt(k + 1)
              if (after != 123 && after != 34) {
                runAt = k
                break
              }
              k--
            }
          }
        }
        if (runAt > p) {
          emit(T.LINK_ID, p, runAt + 1)
          return true
        }
      }

      if (c >= 48 && c <= 57) {
        var q = p + 1
        while (q < n) {
          val ch = src.charCodeAt(q)
          if (ch < 48 || ch > 57) break
          q++
        }
        emit(T.NUM, p, q)
        return true
      }

      when (c) {
        35 -> {
          emit(T.BRKT, p, p + 1)
          return true
        }
        58 -> {
          if (src.charCodeAt(p + 1) == 58 && src.charCodeAt(p + 2) == 58)
            emit(T.STYLE_SEPARATOR, p, p + 3)
          else emit(T.COLON, p, p + 1)
          return true
        }
        38 -> {
          emit(T.AMP, p, p + 1)
          return true
        }
        59 -> {
          emit(T.SEMI, p, p + 1)
          return true
        }
        44 -> {
          emit(T.COMMA, p, p + 1)
          return true
        }
        42 -> {
          emit(T.MULT, p, p + 1)
          return true
        }
        120,
        111 -> return link(c) || nodeString()
        60 -> {
          if (link(c)) return true
          emit(T.TAGSTART, p, p + 1)
          return true
        }
        45 -> {
          if (link(c) || nodeString()) return true
          emit(T.MINUS, p, p + 1)
          return true
        }
        61 -> return link(c)
        46 -> return link(c) || nodeString()
        126 -> return invisLink()
        40 -> return opener(c)
        91 -> {
          if (src.charCodeAt(p + 1) == 124) {
            emit(T.VERTEX_WITH_PROPS_START, p, p + 2)
            return true
          }
          return opener(c)
        }
        124,
        123 -> return opener(c)
        62 -> {
          push(S.text)
          emit(T.TAGEND, p, p + 1)
          return true
        }
        94 -> {
          emit(T.UP, p, p + 1)
          return true
        }
        92 -> {
          if (src.charCodeAt(p + 1) == 124) {
            emit(T.SEP, p, p + 2)
            return true
          }
          return nodeString()
        }
        118 -> {
          if (!isWord(src.charCodeAt(p + 1))) {
            emit(T.DOWN, p, p + 1)
            return true
          }
          return nodeString()
        }
      }

      if (isSpace(c)) {
        if (link(c)) return true
        e = at(RE_NEWLINE, p)
        if (e >= 0) {
          emit(T.NEWLINE, p, e)
          return true
        }
        e = at(RE_SPACE, p)
        if (e >= 0) {
          emit(T.SPACE, p, e)
          return true
        }
        return false
      }

      if (c < 128) return nodeString()
      if (isUnicodeLetter(c)) {
        emit(T.UNICODE_TEXT, p, p + 1)
        return true
      }
      return false
    }

  val edgeText =
    fun(c: Int, linkRe: JsRegExp, a: Int, b: Int): Boolean {
      if (c == 34) {
        quote()
        return true
      }
      val d = linkChar(p)
      if (d == a || d == b) {
        val e = at(linkRe, p)
        if (e >= 0) {
          pop()
          emit(T.LINK, p, e)
          return true
        }
      }
      if (c == a && (a != 45 || src.charCodeAt(p + 1) == 45)) return false
      var q = p + 1
      while (q < n) {
        val ch = src.charCodeAt(q)
        if (ch == 34) break
        if (ch == a) {
          if (a != 45 || src.charCodeAt(q + 1) == 45) break
        } else if (ch == 120 || ch == 111 || ch == 60 || ch == b || isSpace(ch)) {
          val dd = linkChar(q)
          if ((dd == a || dd == b) && at(linkRe, q) >= 0) break
        }
        q++
      }
      emit(T.EDGE_TEXT, p, q)
      return true
    }

  while (true) {
    if (p >= n) {
      var type = -1
      when (state) {
        S.INITIAL -> type = T.EOF
        S.acc_title -> type = T.acc_title_value
        S.acc_descr -> type = T.acc_descr_value
        S.acc_descr_multiline -> type = T.acc_descr_multiline_value
        S.click -> type = T.CLICK
        S.callbackname -> type = T.CALLBACKNAME
        S.callbackargs -> type = T.CALLBACKARGS
      }
      if (type != -1) emit(type, n, n)
      break
    }

    val c = src.charCodeAt(p)
    var ok = true

    when (state) {
      S.INITIAL -> ok = initial(c)

      S.string ->
        if (c == 34) {
          if (src.charCodeAt(p + 1) == 96) {
            push(S.md_string)
            p += 2
          } else {
            pop()
            p++
          }
        } else {
          val e = src.indexOf('"', p)
          emit(T.STR, p, if (e == -1) n else e)
        }

      S.md_string ->
        if (c == 96) {
          if (src.charCodeAt(p + 1) == 34) {
            pop()
            p += 2
          } else {
            ok = false
          }
        } else if (c == 34) {
          quote()
        } else {
          var q = p + 1
          while (q < n) {
            val ch = src.charCodeAt(q)
            if (ch == 96 || ch == 34) break
            q++
          }
          emit(T.MD_STR, p, q)
        }

      S.acc_title,
      S.acc_descr -> {
        var e = src.indexOf('\n', p)
        if (e == -1) e = n
        val type = if (state == S.acc_title) T.acc_title_value else T.acc_descr_value
        pop()
        emit(type, p, e)
      }

      S.acc_descr_multiline ->
        if (c == 125) {
          pop()
          p++
        } else {
          var e = src.indexOf('}', p)
          if (e == -1) e = n
          emit(T.acc_descr_multiline_value, p, e)
        }

      S.dir -> {
        if (c == 34) {
          quote()
        } else {
          val nodir = at(RE_NODIR, p)
          if (nodir >= 0) {
            pop()
            emit(T.NODIR, p, nodir)
          } else {
            var e = at(RE_DIR, p)
            if (e < 0) e = at(RE_DIR_SYM, p)
            if (e < 0) e = at(RE_DIR_V, p)
            if (e >= 0) {
              pop()
              emit(T.DIR, p, e)
            } else {
              ok = invisLink() || opener(c)
            }
          }
        }
      }

      S.text -> {
        val d = src.charCodeAt(p + 1)
        when (c) {
          34 -> quote()
          40,
          91,
          123 -> opener(c)
          93 -> {
            pop()
            if (d == 41) emit(T.STADIUMEND, p, p + 2)
            else if (d == 93) emit(T.SUBROUTINEEND, p, p + 2) else emit(T.SQE, p, p + 1)
          }
          41 -> {
            pop()
            if (d == 93) emit(T.CYLINDEREND, p, p + 2)
            else if (d == 41 && src.charCodeAt(p + 2) == 41) emit(T.DOUBLECIRCLEEND, p, p + 3)
            else emit(T.PE, p, p + 1)
          }
          124 -> {
            pop()
            emit(T.PIPE, p, p + 1)
          }
          125 -> {
            pop()
            emit(T.DIAMOND_STOP, p, p + 1)
          }
          else -> {
            if (!((c == 126 || isSpace(c)) && invisLink())) {
              var q = p + 1
              while (q < n) {
                val ch = src.charCodeAt(q)
                if (
                  ch == 91 ||
                    ch == 93 ||
                    ch == 40 ||
                    ch == 41 ||
                    ch == 123 ||
                    ch == 125 ||
                    ch == 124 ||
                    ch == 34
                )
                  break
                q++
              }
              emit(T.TEXT, p, q)
            }
          }
        }
      }

      S.ellipseText -> {
        val d = src.charCodeAt(p + 1)
        if (c == 34) {
          quote()
        } else if ((c == 126 || isSpace(c)) && invisLink()) {
          // Emitted.
        } else if ((c == 45 || c == 47 || c == 41) && d == 41) {
          pop()
          emit(T.ELLIPSE_END, p, p + 2)
        } else if (c == 40 || c == 91 || c == 123) {
          opener(c)
        } else if (c == 41 || c == 93 || c == 125) {
          ok = false
        } else {
          var q = p + 1
          while (q < n) {
            val ch = src.charCodeAt(q)
            if (ch == 34 || ch == 40 || ch == 41 || ch == 91 || ch == 93 || ch == 123 || ch == 125)
              break
            if ((ch == 45 || ch == 47) && src.charCodeAt(q + 1) == 41) break
            if ((ch == 126 || isSpace(ch)) && invisAhead(q) && at(RE_INVIS_LINK, q) >= 0) break
            q++
          }
          emit(T.TEXT, p, q)
        }
      }

      S.trapText ->
        run trap@{
          val d = src.charCodeAt(p + 1)
          if (c == 34) {
            quote()
            return@trap
          }
          if ((c == 126 || isSpace(c)) && invisLink()) return@trap
          when (c) {
            40 ->
              if (d == 45 || d == 91 || (d == 40 && src.charCodeAt(p + 2) == 40)) {
                opener(c)
              } else if (d == 93) {
                pop()
                emit(T.TRAPEND, p, p + 2)
              } else {
                opener(c)
              }
            91,
            123 -> opener(c)
            92 ->
              if (d == 93) {
                pop()
                emit(T.TRAPEND, p, p + 2)
              } else {
                emit(T.TEXT, p, p + 1)
              }
            47 ->
              if (d == 93) {
                pop()
                emit(T.INVTRAPEND, p, p + 2)
              } else {
                emit(T.TEXT, p, p + 1)
              }
            93,
            41 ->
              if (d == 93) {
                pop()
                emit(T.TRAPEND, p, p + 2)
              } else {
                ok = false
              }
            125 -> ok = false
            else -> {
              if ((c == 63 || c == 61) && d == 93) {
                pop()
                emit(T.TRAPEND, p, p + 2)
                return@trap
              }
              var q = p + 1
              while (q < n) {
                val ch = src.charCodeAt(q)
                if (
                  ch == 92 ||
                    ch == 91 ||
                    ch == 93 ||
                    ch == 40 ||
                    ch == 41 ||
                    ch == 123 ||
                    ch == 125 ||
                    ch == 47
                )
                  break
                q++
              }
              emit(T.TEXT, p, q)
            }
          }
        }

      S.edgeText -> ok = edgeText(c, RE_LINK, 45, NONE)
      S.thickEdgeText -> ok = edgeText(c, RE_THICK_LINK, 61, NONE)
      S.dottedEdgeText -> ok = edgeText(c, RE_DOTTED_LINK, 46, 45)

      S.click ->
        if (c == 34) {
          quote()
        } else if (isSpace(c)) {
          pop()
          p++
        } else {
          var q = p + 1
          while (q < n && !isSpace(src.charCodeAt(q))) q++
          emit(T.CLICK, p, q)
        }

      S.callbackname ->
        if (c == 40) {
          val e = at(RE_EMPTY_CALL, p)
          pop()
          if (e >= 0) {
            p = e
          } else {
            push(S.callbackargs)
            p++
          }
        } else {
          var e = src.indexOf('(', p)
          if (e == -1) e = n
          emit(T.CALLBACKNAME, p, e)
        }

      S.callbackargs ->
        if (c == 41) {
          pop()
          p++
        } else {
          var e = src.indexOf(')', p)
          if (e == -1) e = n
          emit(T.CALLBACKARGS, p, e)
        }

      S.shapeData ->
        if (c == 34) {
          push(S.shapeDataStr)
          emit(T.SHAPE_DATA, p, p + 1)
        } else if (c == 125) {
          pop()
          p++
        } else if (c == 94) {
          ok = false
        } else {
          var q = p + 1
          while (q < n) {
            val ch = src.charCodeAt(q)
            if (ch == 125 || ch == 94 || ch == 34) break
            q++
          }
          emit(T.SHAPE_DATA, p, q)
        }

      S.shapeDataStr ->
        if (c == 34) {
          pop()
          emit(T.SHAPE_DATA, p, p + 1)
        } else {
          var e = src.indexOf('"', p)
          if (e == -1) e = n
          types.add(T.SHAPE_DATA)
          texts.add(src.jsSlice(p, e).jsReplace(RE_BR, "<br/>"))
          starts.add(p)
          p = e
        }
    }

    if (!ok) {
      types.add(T.ERROR)
      texts.add("")
      starts.add(p)
      return Tokens(types, texts, starts)
    }
  }

  types.add(T.END)
  texts.add("")
  starts.add(n)
  return Tokens(types, texts, starts)
}
