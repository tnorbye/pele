// Ported from Pele src/diagrams/agentflow/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

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
  // Four numbers per token, counted the way Jison counts them:
  // first line, first column, last line, last column. Lines start at 1, columns at 0.
  val locs: MutableList<Int>,
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
  const val click = 11
  const val callbackname = 12
  const val callbackargs = 13
  const val shapeData = 14
  const val shapeDataStr = 15
}

private val NODE_CHAR: BooleanArray = BooleanArray(128).also { table ->
  for (ch in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!\"#\$%&'*+.`?\\_/") table[ch.code] = true
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
private val RE_DIRECTION = listOf(
  JsRegExp(""".*direction\s+TB[^\n]*""", "y"),
  JsRegExp(""".*direction\s+BT[^\n]*""", "y"),
  JsRegExp(""".*direction\s+RL[^\n]*""", "y"),
  JsRegExp(""".*direction\s+LR[^\n]*""", "y"),
  JsRegExp(""".*direction\s+TD[^\n]*""", "y"),
)
private val RE_LINE_END = JsRegExp("""[\n\r  ]""", "g")
private val RE_EMPTY_CALL = JsRegExp("""\(\s*\)""", "y")
private val RE_BR = JsRegExp("""\n\s*""", "g")

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val locs = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state = S.INITIAL
  var p = 0
  var line = 1
  var col = 0
  var firstGraph = true
  var dirNext = src.indexOf("direction")
  var atNext = src.indexOf('@')
  var lineEnd = -1
  var dirFailed = 0
  var runEnd = 0
  var runAt = -1
  var wsFrom = -1
  var wsTo = -1
  var blankFrom = -1
  var blankTo = -1
  var noLink = -1
  var noArrow = -1

  // Moves over one match. Jison counts \r\n, \r and \n as one line break each, and after a
  // break its column stops at the first U+2028 or U+2029.
  fun advance(e: Int) {
    var brk = -1
    var k = p
    while (k < e) {
      val ch = src.charCodeAt(k)
      if (ch == 10) {
        line++
        brk = k
      } else if (ch == 13) {
        if (k + 1 < e && src.charCodeAt(k + 1) == 10) k++
        line++
        brk = k
      }
      k++
    }
    if (brk < 0) {
      col += e - p
    } else {
      var j = brk + 1
      while (j < e) {
        val ch = src.charCodeAt(j)
        if (ch == 0x2028 || ch == 0x2029) break
        j++
      }
      col = j - brk - 1
    }
    p = e
  }

  fun emit(type: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(p, e))
    starts.add(p)
    locs.add(line)
    locs.add(col)
    advance(e)
    locs.add(line)
    locs.add(col)
  }
  // For a token that cannot hold a line break.
  fun emitFlat(type: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(p, e))
    starts.add(p)
    locs.add(line)
    locs.add(col)
    col += e - p
    p = e
    locs.add(line)
    locs.add(col)
  }
  // For a run that Jison matches one character at a time, where \r\n counts as two line breaks.
  fun emitChars(type: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(p, e))
    starts.add(p)
    locs.add(line)
    locs.add(col)
    for (k in p until e) {
      val ch = src.charCodeAt(k)
      if (ch == 10 || ch == 13) {
        line++
        col = 0
      } else {
        col++
      }
    }
    p = e
    locs.add(line)
    locs.add(col)
  }
  fun emitText(type: Int, text: String, e: Int) {
    types.add(type)
    texts.add(text)
    starts.add(p)
    locs.add(line)
    locs.add(col)
    advance(e)
    locs.add(line)
    locs.add(col)
  }
  fun push(s: Int) {
    stack.add(state)
    state = s
  }
  fun pop() {
    if (stack.size > 0) state = stack.removeAt(stack.size - 1)
  }
  fun at(re: JsRegExp, q: Int): Int {
    re.lastIndex = q
    return if (re.test(src)) re.lastIndex else -1
  }
  fun kw(word: String): Boolean = src.startsWith(word, p) && !isWord(src.charCodeAt(p + word.length))

  // End of the run of whitespace starting at q. The last run is kept, so asking again from inside it costs nothing.
  fun wsEnd(q: Int): Int {
    if (q >= wsFrom && q <= wsTo) return wsTo
    var j = q
    while (j < n && isSpace(src.charCodeAt(j))) j++
    wsFrom = q
    wsTo = j
    return j
  }
  fun blankEnd(q: Int): Int {
    if (q >= blankFrom && q <= blankTo) return blankTo
    var j = q
    while (j < n) {
      val ch = src.charCodeAt(j)
      if (ch != 32 && ch != 9) break
      j++
    }
    blankFrom = q
    blankTo = j
    return j
  }

  // End of `\s*--+[x>]\s*` starting at q, or -1.
  fun arrowEnd(q: Int): Int {
    val j = wsEnd(q)
    if (j == noArrow || src.charCodeAt(j) != 45 || src.charCodeAt(j + 1) != 45) return -1
    var k = j + 2
    while (src.charCodeAt(k) == 45) k++
    val t = src.charCodeAt(k)
    if (t == 120 || t == 62) return wsEnd(k + 1)
    noArrow = j
    return -1
  }

  fun link(): Boolean {
    val j = wsEnd(p)
    if (j == noLink || src.charCodeAt(j) != 45) return false
    val d = src.charCodeAt(j + 1)
    var k = j + 2
    if (d == 45) {
      while (src.charCodeAt(k) == 45) k++
      val t = src.charCodeAt(k)
      if (t == 120 || t == 62) {
        emit(T.LINK, wsEnd(k + 1))
      } else {
        push(S.edgeText)
        emit(T.START_LINK, wsEnd(j + 2))
      }
      return true
    }
    if (d == 46) {
      while (src.charCodeAt(k) == 46) k++
      if (src.charCodeAt(k) == 45) {
        emit(T.LINK, wsEnd(k + 1))
        return true
      }
    }
    noLink = j
    return false
  }

  // `%%` not followed by `{`, through the end of the line.
  fun commentEnd(q: Int): Int {
    if (src.charCodeAt(q) != 37 || src.charCodeAt(q + 1) != 37 || src.charCodeAt(q + 2) == 123) return -1
    val e = src.indexOf('\n', q + 2)
    return if (e == -1) n else e
  }

  fun quote() {
    if (src.charCodeAt(p + 1) == 96) {
      push(S.md_string)
      advance(p + 2)
    } else {
      push(S.string)
      advance(p + 1)
    }
  }

  fun opener(c: Int): Boolean {
    val d = src.charCodeAt(p + 1)
    if (c == 40) {
      if (d == 45) {
        push(S.ellipseText)
        emitFlat(T.ELLIPSE_START, p + 2)
      } else if (d == 91) {
        push(S.text)
        emitFlat(T.STADIUMSTART, p + 2)
      } else if (d == 40 && src.charCodeAt(p + 2) == 40) {
        push(S.text)
        emitFlat(T.DOUBLECIRCLESTART, p + 3)
      } else {
        push(S.text)
        emitFlat(T.PS, p + 1)
      }
      return true
    }
    if (c == 91) {
      if (d == 91) {
        push(S.text)
        emitFlat(T.SUBROUTINESTART, p + 2)
      } else if (d == 40) {
        push(S.text)
        emitFlat(T.CYLINDERSTART, p + 2)
      } else if (d == 47) {
        push(S.trapText)
        emitFlat(T.TRAPSTART, p + 2)
      } else if (d == 92) {
        push(S.trapText)
        emitFlat(T.INVTRAPSTART, p + 2)
      } else {
        push(S.text)
        emitFlat(T.SQS, p + 1)
      }
      return true
    }
    if (c == 124) {
      push(S.text)
      emitFlat(T.PIPE, p + 1)
      return true
    }
    if (c == 123) {
      push(S.text)
      emitFlat(T.DIAMOND_START, p + 1)
      return true
    }
    return false
  }

  fun nodeString(): Boolean {
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
    emitFlat(T.NODE_STRING, q)
    return true
  }

  fun blanks(from: Int): Int {
    var e = from
    while (e < n) {
      val ch = src.charCodeAt(e)
      if (ch != 32 && ch != 9) break
      e++
    }
    return e
  }

  fun initial(c: Int): Boolean {
    var e: Int
    when (c) {
      97 -> {
        if (src.startsWith("acc", p)) {
          e = at(RE_ACC_TITLE, p)
          if (e >= 0) {
            push(S.acc_title)
            emit(T.acc_title, e)
            return true
          }
          e = at(RE_ACC_DESCR, p)
          if (e >= 0) {
            push(S.acc_descr)
            emit(T.acc_descr, e)
            return true
          }
          e = at(RE_ACC_DESCR_ML, p)
          if (e >= 0) {
            push(S.acc_descr_multiline)
            advance(e)
            return true
          }
        }
        if (kw("agentflow-beta")) {
          if (firstGraph) {
            firstGraph = false
            push(S.dir)
          }
          emitFlat(T.GRAPH, p + 14)
          return true
        }
      }
      64 -> {
        if (src.charCodeAt(p + 1) == 123) {
          push(S.shapeData)
          emitText(T.SHAPE_DATA, "", p + 2)
          return true
        }
      }
      99 -> {
        e = at(RE_CALL, p)
        if (e >= 0) {
          push(S.callbackname)
          advance(e)
          return true
        }
        if (kw("classDef")) {
          emitFlat(T.CLASSDEF, p + 8)
          return true
        }
        if (kw("class")) {
          emitFlat(T.CLASS, p + 5)
          return true
        }
        e = at(RE_CLICK, p)
        if (e >= 0) {
          push(S.click)
          advance(e)
          return true
        }
        if (kw("connector")) {
          emitFlat(T.connector, p + 9)
          return true
        }
      }
      34 -> {
        quote()
        return true
      }
      115 -> {
        if (kw("style")) {
          emitFlat(T.STYLE, p + 5)
          return true
        }
      }
      100 -> {
        if (kw("default")) {
          emitFlat(T.DEFAULT, p + 7)
          return true
        }
      }
      108 -> {
        if (kw("linkStyle")) {
          emitFlat(T.LINKSTYLE, p + 9)
          return true
        }
      }
      105 -> {
        if (kw("interpolate")) {
          emitFlat(T.INTERPOLATE, p + 11)
          return true
        }
      }
      104 -> {
        if (src.startsWith("href", p) && isSpace(src.charCodeAt(p + 4))) {
          emit(T.HREF, p + 5)
          return true
        }
      }
      102 -> {
        if (kw("flow")) {
          emitFlat(T.flow, p + 4)
          return true
        }
      }
      103 -> {
        if (kw("global")) {
          emitFlat(T.global, blanks(p + 6))
          return true
        }
      }
      101 -> {
        if (kw("end")) {
          emitFlat(T.end, blanks(p + 3))
          return true
        }
      }
      95 -> {
        if (kw("_self")) {
          emitFlat(T.LINK_TARGET, p + 5)
          return true
        }
        if (kw("_blank")) {
          emitFlat(T.LINK_TARGET, p + 6)
          return true
        }
        if (kw("_parent")) {
          emitFlat(T.LINK_TARGET, p + 7)
          return true
        }
        if (kw("_top")) {
          emitFlat(T.LINK_TARGET, p + 4)
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
              emit(T.direction_tb + i, e)
              return true
            }
          }
          // No direction statement starts anywhere in the rest of this line either.
          dirFailed = lineEnd
        }
      }
    }

    // A link id is a run of non-space characters up to its last `@` that is not followed by `{` or `"`.
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
        emitFlat(T.LINK_ID, runAt + 1)
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
      emitFlat(T.NUM, q)
      return true
    }

    when (c) {
      35 -> {
        emitFlat(T.BRKT, p + 1)
        return true
      }
      58 -> {
        if (src.charCodeAt(p + 1) == 58 && src.charCodeAt(p + 2) == 58) emitFlat(T.STYLE_SEPARATOR, p + 3)
        else emitFlat(T.COLON, p + 1)
        return true
      }
      38 -> {
        emitFlat(T.AMP, p + 1)
        return true
      }
      59 -> {
        emitFlat(T.SEMI, p + 1)
        return true
      }
      44 -> {
        emitFlat(T.COMMA, p + 1)
        return true
      }
      42 -> {
        emitFlat(T.MULT, p + 1)
        return true
      }
      60 -> {
        emitFlat(T.TAGSTART, p + 1)
        return true
      }
      45 -> {
        if (link() || nodeString()) return true
        emitFlat(T.MINUS, p + 1)
        return true
      }
      40 -> return opener(c)
      91 -> {
        if (src.charCodeAt(p + 1) == 124) {
          emitFlat(T.VERTEX_WITH_PROPS_START, p + 2)
          return true
        }
        return opener(c)
      }
      124, 123 -> return opener(c)
      62 -> {
        push(S.text)
        emitFlat(T.TAGEND, p + 1)
        return true
      }
      94 -> {
        emitFlat(T.UP, p + 1)
        return true
      }
      92 -> {
        if (src.charCodeAt(p + 1) == 124) {
          emitFlat(T.SEP, p + 2)
          return true
        }
        return nodeString()
      }
      118 -> {
        if (!isWord(src.charCodeAt(p + 1))) {
          emitFlat(T.DOWN, p + 1)
          return true
        }
        return nodeString()
      }
      37 -> {
        e = commentEnd(p)
        if (e >= 0) {
          emit(T.COMMENT, e)
          return true
        }
        return nodeString()
      }
    }

    if (isSpace(c)) {
      if (link()) return true
      e = if (c == 32 || c == 9) commentEnd(blankEnd(p)) else -1
      if (e >= 0) {
        emit(T.COMMENT, e)
        return true
      }
      e = p
      while (true) {
        val ch = src.charCodeAt(e)
        if (ch == 10) e++
        else if (ch == 13 && src.charCodeAt(e + 1) == 10) e += 2
        else break
      }
      if (e > p) emit(T.NEWLINE, e)
      else emit(T.SPACE, p + 1)
      return true
    }

    if (c < 128) return nodeString()
    if (isUnicodeLetter(c)) {
      emitFlat(T.UNICODE_TEXT, p + 1)
      return true
    }
    return false
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
      if (type != -1) emitFlat(type, n)
      break
    }

    val c = src.charCodeAt(p)
    var ok = true

    when (state) {
      S.INITIAL -> ok = initial(c)

      S.string -> if (c == 34) {
        if (src.charCodeAt(p + 1) == 96) {
          push(S.md_string)
          advance(p + 2)
        } else {
          pop()
          advance(p + 1)
        }
      } else {
        val e = src.indexOf('"', p)
        emit(T.STR, if (e == -1) n else e)
      }

      S.md_string -> if (c == 96) {
        if (src.charCodeAt(p + 1) == 34) {
          pop()
          advance(p + 2)
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
        emit(T.MD_STR, q)
      }

      S.acc_title, S.acc_descr -> {
        var e = src.indexOf('\n', p)
        if (e == -1) e = n
        val type = if (state == S.acc_title) T.acc_title_value else T.acc_descr_value
        pop()
        emit(type, e)
      }

      S.acc_descr_multiline -> if (c == 125) {
        pop()
        advance(p + 1)
      } else {
        var e = src.indexOf('}', p)
        if (e == -1) e = n
        emit(T.acc_descr_multiline_value, e)
      }

      S.dir -> {
        var e: Int
        if (c == 34) {
          quote()
        } else {
          e = at(RE_NODIR, p)
          if (e >= 0) {
            pop()
            emit(T.NODIR, e)
          } else {
            e = at(RE_DIR, p)
            if (e < 0) e = at(RE_DIR_SYM, p)
            if (e < 0) e = at(RE_DIR_V, p)
            if (e >= 0) {
              pop()
              emit(T.DIR, e)
            } else {
              ok = opener(c)
            }
          }
        }
      }

      S.text -> {
        val d = src.charCodeAt(p + 1)
        when (c) {
          34 -> quote()
          40, 91, 123 -> opener(c)
          93 -> {
            pop()
            if (d == 41) emitFlat(T.STADIUMEND, p + 2)
            else if (d == 93) emitFlat(T.SUBROUTINEEND, p + 2)
            else emitFlat(T.SQE, p + 1)
          }
          41 -> {
            pop()
            if (d == 93) emitFlat(T.CYLINDEREND, p + 2)
            else if (d == 41 && src.charCodeAt(p + 2) == 41) emitFlat(T.DOUBLECIRCLEEND, p + 3)
            else emitFlat(T.PE, p + 1)
          }
          124 -> {
            pop()
            emitFlat(T.PIPE, p + 1)
          }
          125 -> {
            pop()
            emitFlat(T.DIAMOND_STOP, p + 1)
          }
          37 -> {
            // Jison drops the lone `%` from Mermaid's text pattern, so only a comment may start with one.
            val e = commentEnd(p)
            if (e >= 0) advance(e)
            else ok = false
          }
          else -> {
            var q = p + 1
            while (q < n) {
              val ch = src.charCodeAt(q)
              if (ch == 91 || ch == 93 || ch == 40 || ch == 41 || ch == 123 || ch == 125 || ch == 124 || ch == 34 || ch == 37) break
              q++
            }
            emit(T.TEXT, q)
          }
        }
      }

      S.ellipseText -> {
        val d = src.charCodeAt(p + 1)
        var e = -1
        if (c == 34) {
          quote()
        } else if (c == 37 &&commentEnd(p).also { e = it } >= 0) {
          advance(e)
        } else if ((c == 45 || c == 47 || c == 41) && d == 41) {
          pop()
          emitFlat(T.ELLIPSE_END, p + 2)
        } else if (c == 40 || c == 91 || c == 123) {
          opener(c)
        } else if (c == 41 || c == 93 || c == 125) {
          ok = false
        } else {
          var q = p + 1
          while (q < n) {
            val ch = src.charCodeAt(q)
            if (ch == 34 || ch == 40 || ch == 41 || ch == 91 || ch == 93 || ch == 123 || ch == 125) break
            if ((ch == 45 || ch == 47) && src.charCodeAt(q + 1) == 41) break
            if (ch == 37 && src.charCodeAt(q + 1) == 37 && src.charCodeAt(q + 2) != 123) break
            q++
          }
          emitChars(T.TEXT, q)
        }
      }

      S.trapText -> run trap@{
        val d = src.charCodeAt(p + 1)
        if (c == 34) {
          quote()
          return@trap
        }
        when (c) {
          40 -> if (d == 45 || d == 91 || (d == 40 && src.charCodeAt(p + 2) == 40)) {
            opener(c)
          } else if (d == 93) {
            pop()
            emitFlat(T.TRAPEND, p + 2)
          } else {
            opener(c)
          }
          91, 123 -> opener(c)
          92 -> if (d == 93) {
            pop()
            emitFlat(T.TRAPEND, p + 2)
          } else {
            emitFlat(T.TEXT, p + 1)
          }
          47 -> if (d == 93) {
            pop()
            emitFlat(T.INVTRAPEND, p + 2)
          } else {
            emitFlat(T.TEXT, p + 1)
          }
          93, 41 -> if (d == 93) {
            pop()
            emitFlat(T.TRAPEND, p + 2)
          } else {
            ok = false
          }
          125 -> ok = false
          37 -> {
            val e = commentEnd(p)
            if (e >= 0) advance(e)
            else ok = false
          }
          else -> {
            if ((c == 63 || c == 61) && d == 93) {
              pop()
              emitFlat(T.TRAPEND, p + 2)
              return@trap
            }
            var q = p + 1
            while (q < n) {
              val ch = src.charCodeAt(q)
              if (ch == 92 || ch == 91 || ch == 93 || ch == 40 || ch == 41 || ch == 123 || ch == 125 || ch == 47 || ch == 37) break
              q++
            }
            emit(T.TEXT, q)
          }
        }
      }

      S.edgeText -> run edge@{
        if (c == 34) {
          quote()
          return@edge
        }
        val e = arrowEnd(p)
        if (e >= 0) {
          pop()
          emit(T.LINK, e)
          return@edge
        }
        if (c == 45 && src.charCodeAt(p + 1) == 45) {
          ok = false
          return@edge
        }
        var q = p + 1
        while (q < n) {
          val ch = src.charCodeAt(q)
          if (ch == 34) break
          if (ch == 45) {
            if (src.charCodeAt(q + 1) == 45) break
          } else if (isSpace(ch) && arrowEnd(q) >= 0) {
            break
          }
          q++
        }
        emitChars(T.EDGE_TEXT, q)
      }

      S.click -> if (c == 34) {
        quote()
      } else if (isSpace(c)) {
        pop()
        advance(p + 1)
      } else {
        var q = p + 1
        while (q < n && !isSpace(src.charCodeAt(q))) q++
        emitFlat(T.CLICK, q)
      }

      S.callbackname -> if (c == 40) {
        val e = at(RE_EMPTY_CALL, p)
        pop()
        if (e >= 0) {
          advance(e)
        } else {
          push(S.callbackargs)
          advance(p + 1)
        }
      } else {
        var e = src.indexOf('(', p)
        if (e == -1) e = n
        emit(T.CALLBACKNAME, e)
      }

      S.callbackargs -> if (c == 41) {
        pop()
        advance(p + 1)
      } else {
        var e = src.indexOf(')', p)
        if (e == -1) e = n
        emit(T.CALLBACKARGS, e)
      }

      S.shapeData -> if (c == 34) {
        push(S.shapeDataStr)
        emitFlat(T.SHAPE_DATA, p + 1)
      } else if (c == 125) {
        pop()
        advance(p + 1)
      } else if (c == 94) {
        ok = false
      } else {
        var q = p + 1
        while (q < n) {
          val ch = src.charCodeAt(q)
          if (ch == 125 || ch == 94 || ch == 34) break
          q++
        }
        emit(T.SHAPE_DATA, q)
      }

      S.shapeDataStr -> if (c == 34) {
        pop()
        emitFlat(T.SHAPE_DATA, p + 1)
      } else {
        var e = src.indexOf('"', p)
        if (e == -1) e = n
        emitText(T.SHAPE_DATA, src.jsSlice(p, e).jsReplace(RE_BR, "<br/>"), e)
      }
    }

    if (!ok) {
      types.add(T.ERROR)
      texts.add("")
      starts.add(p)
      locs.add(line)
      locs.add(col)
      locs.add(line)
      locs.add(col)
      return Tokens(types, texts, starts, locs)
    }
  }

  types.add(T.END)
  texts.add("")
  starts.add(n)
  locs.add(line)
  locs.add(col)
  locs.add(line)
  locs.add(col)
  return Tokens(types, texts, starts, locs)
}
