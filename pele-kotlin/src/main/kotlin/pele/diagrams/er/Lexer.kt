// Ported from Pele src/diagrams/er/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.util.isDigit
import pele.util.isSpace
import pele.util.isWord

class Tokens(
  val types: MutableList<Int>,
  val texts: MutableList<String>,
  val starts: MutableList<Int>,
)

private object S {
  const val INITIAL = 0
  const val block = 1
  const val block_bq = 2
  const val acc_title = 3
  const val acc_descr = 4
  const val acc_descr_multiline = 5
  const val style = 6
}

private const val TEXT = 1
private const val STYLE_TEXT = 2
private const val ATTR_START = 4
private const val ATTR_REST = 8

private val CLASS: IntArray = run {
  val table = IntArray(128)
  for (c in 0 until 128) {
    val word = (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c == 95
    val letter = word && (c < 48 || c > 57)
    if (word) table[c] = table[c] or (TEXT or STYLE_TEXT or ATTR_REST)
    if (letter) table[c] = table[c] or ATTR_START
  }
  for (ch in "-*") table[ch.code] = table[ch.code] or (TEXT or STYLE_TEXT or ATTR_REST)
  table[46] = table[46] or (TEXT or ATTR_REST)
  table[42] = table[42] or ATTR_START
  for (ch in "[](),") table[ch.code] = table[ch.code] or ATTR_REST
  table
}

// PORT: past the end (or below 0) JS reads undefined, which has no class.
private fun classOf(c: Int): Int = if (c >= 0 && c < 128) CLASS[c] else 0

private val RE_ACC_TITLE = JsRegExp("""accTitle\s*:\s*""", "iy")
private val RE_ACC_DESCR = JsRegExp("""accDescr\s*:\s*""", "iy")
private val RE_ACC_DESCR_ML = JsRegExp("""accDescr\s*\{\s*""", "iy")
private val RE_DIRECTION =
  listOf(
    JsRegExp(""".*direction\s+TB[^\n]*""", "iy"),
    JsRegExp(""".*direction\s+BT[^\n]*""", "iy"),
    JsRegExp(""".*direction\s+RL[^\n]*""", "iy"),
    JsRegExp(""".*direction\s+LR[^\n]*""", "iy"),
  )
private val RE_DIR_WORD = JsRegExp("direction", "gi")
private val RE_LINE_END = JsRegExp("""[\n\r  ]""", "g")

// Mermaid's attribute pattern is case-insensitive, which lets the micro sign into its À-￿ range.
private fun isAttr(c: Int, kind: Int): Boolean =
  if (c in 0..127) (CLASS[c] and kind) != 0 else c >= 0xc0 || c == 0xb5

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  var state = S.INITIAL
  var p = 0
  var dirNext = -2
  var dirFailed = 0
  var lineEnd = -1
  var tildeNext = -2
  var tildeLine = -1
  var tildeLast = -1
  var wsNext = -1

  fun emit(type: Int, s: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
  }
  fun at(re: JsRegExp, q: Int): Int {
    re.lastIndex = q
    return if (re.test(src)) re.lastIndex else -1
  }
  // Matches a lower-case keyword at p, ignoring case.
  fun kw(word: String): Boolean {
    for (i in word.indices) {
      val k = word[i].code
      val c = src.charCodeAt(p + i)
      if (c != k && (k < 97 || k > 122 || (c or 32) != k)) return false
    }
    return true
  }
  fun kwb(word: String): Boolean = kw(word) && !isWord(src.charCodeAt(p + word.length))
  fun lower(q: Int): Int {
    val c = src.charCodeAt(q)
    return if (c >= 65 && c <= 90) c + 32 else c
  }
  fun nextLineEnd(q: Int): Int {
    RE_LINE_END.lastIndex = q
    return if (RE_LINE_END.test(src)) RE_LINE_END.lastIndex - 1 else n
  }
  fun digits(q0: Int): Int {
    var q = q0
    while (isDigit(src.charCodeAt(q))) q++
    return q
  }

  // A direction statement swallows its whole line, from wherever the lexer stands on it.
  fun direction(): Boolean {
    if (dirNext == -1) return false
    if (dirNext < p) {
      RE_DIR_WORD.lastIndex = p
      dirNext = if (RE_DIR_WORD.test(src)) RE_DIR_WORD.lastIndex - 9 else -1
      if (dirNext == -1) return false
    }
    if (p < dirFailed) return false
    if (lineEnd < p) lineEnd = nextLineEnd(p)
    if (dirNext >= lineEnd) return false
    for (i in 0 until 4) {
      val e = at(RE_DIRECTION[i], p)
      if (e >= 0) {
        emit(T.direction_tb + i, p, e)
        return true
      }
    }
    dirFailed = lineEnd
    return false
  }

  fun quoted() {
    val e = src.indexOf('"', p + 1)
    if (e == -1) {
      emit(T.CHAR, p, p + 1)
      return
    }
    var name = e > p + 1
    var q = p + 1
    while (name && q < e) {
      val c = src.charCodeAt(q)
      if (c == 37 || c == 92 || c == 13 || c == 10 || c == 11 || c == 8) name = false
      q++
    }
    emit(if (name) T.ENTITY_NAME else T.WORD, p, e + 1)
  }

  fun number(c: Int) {
    val d = src.charCodeAt(p + 1)
    if (d == 43 && (c == 49 || c == 48)) {
      emit(if (c == 49) T.ONE_OR_MORE else T.ZERO_OR_MORE, p, p + 2)
      return
    }
    val q = digits(p + 1)
    if (src.charCodeAt(q) == 46 && isDigit(src.charCodeAt(q + 1))) {
      emit(T.DECIMAL_NUM, p, digits(q + 2))
      return
    }
    if (c == 49) {
      if (isSpace(d)) {
        var r = p + 2
        while (isSpace(src.charCodeAt(r))) r++
        val f = src.charCodeAt(r)
        if (isWord(f) || f == 34 || f == 39) {
          emit(T.ONLY_ONE, p, p + 1)
          return
        }
      } else if (d == 45 || d == 46) {
        val f = src.charCodeAt(p + 2)
        if (f == 45 || f == 46) {
          emit(T.ONLY_ONE, p, p + 1)
          return
        }
      }
      if (!isWord(d)) {
        emit(T.ENTITY_ONE, p, p + 1)
        return
      }
    }
    emit(T.NUM, p, q)
  }

  fun keyword(lc: Int): Boolean {
    val d = src.charCodeAt(p + 1)
    when (lc) {
      101 -> {
        if (kwb("erdiagram")) {
          emit(T.ER_DIAGRAM, p, p + 9)
        } else if (kwb("end")) {
          var e = p + 3
          while (isSpace(src.charCodeAt(e))) e++
          emit(T.END_KW, p, e)
        } else {
          return false
        }
        return true
      }
      115 -> {
        if (kwb("style")) {
          state = S.style
          emit(T.STYLE, p, p + 5)
        } else if (kwb("subgraph")) {
          emit(T.SUBGRAPH, p, p + 8)
        } else {
          return false
        }
        return true
      }
      99 -> {
        if (kwb("classdef")) {
          state = S.style
          emit(T.CLASSDEF, p, p + 8)
        } else if (kwb("class")) {
          emit(T.CLASS, p, p + 5)
        } else {
          return false
        }
        return true
      }
      111 -> {
        if (kwb("one or zero")) emit(T.ZERO_OR_ONE, p, p + 11)
        else if (kwb("one or more") || kwb("one or many")) emit(T.ONE_OR_MORE, p, p + 11)
        else if (kwb("one")) emit(T.ONLY_ONE, p, p + 3)
        else if (kwb("only one")) emit(T.ONLY_ONE, p, p + 8)
        else if (d == 124) emit(T.ZERO_OR_ONE, p, p + 2)
        else if (d == 123) emit(T.ZERO_OR_MORE, p, p + 2)
        else if (kwb("optionally to")) emit(T.NON_IDENTIFYING, p, p + 13) else return false
        return true
      }
      122 -> {
        if (kwb("zero or one")) emit(T.ZERO_OR_ONE, p, p + 11)
        else if (kwb("zero or more") || kwb("zero or many")) emit(T.ZERO_OR_MORE, p, p + 12)
        else return false
        return true
      }
      109 -> {
        if (kw("many(0)")) emit(T.ZERO_OR_MORE, p, p + 7)
        else if (kw("many(1)")) emit(T.ONE_OR_MORE, p, p + 7)
        else if (kwb("many")) emit(T.ZERO_OR_MORE, p, p + 4) else return false
        return true
      }
      116 -> {
        if (!kwb("to")) return false
        emit(T.IDENTIFYING, p, p + 2)
        return true
      }
      117 -> {
        if (d != 46 && d != 45 && d != 124) return false
        emit(T.MD_PARENT, p, p + 1)
        return true
      }
      124 -> {
        if (lower(p + 1) == 111 && !isWord(src.charCodeAt(p + 2))) emit(T.ZERO_OR_ONE, p, p + 2)
        else if (d == 124) emit(T.ONLY_ONE, p, p + 2)
        else if (d == 123) emit(T.ONE_OR_MORE, p, p + 2) else return false
        return true
      }
      125 -> {
        if (lower(p + 1) == 111 && !isWord(src.charCodeAt(p + 2))) emit(T.ZERO_OR_MORE, p, p + 2)
        else if (d == 124) emit(T.ONE_OR_MORE, p, p + 2) else return false
        return true
      }
      46 -> {
        if (d != 46 && d != 45) return false
        emit(T.NON_IDENTIFYING, p, p + 2)
        return true
      }
      45 -> {
        if (d == 45) emit(T.IDENTIFYING, p, p + 2)
        else if (d == 46) emit(T.NON_IDENTIFYING, p, p + 2) else return false
        return true
      }
    }
    return false
  }

  fun initial(c: Int) {
    var e: Int
    if ((c == 97 || c == 65) && kw("acc")) {
      e = at(RE_ACC_TITLE, p)
      if (e >= 0) {
        state = S.acc_title
        emit(T.acc_title, p, e)
        return
      }
      e = at(RE_ACC_DESCR, p)
      if (e >= 0) {
        state = S.acc_descr
        emit(T.acc_descr, p, e)
        return
      }
      e = at(RE_ACC_DESCR_ML, p)
      if (e >= 0) {
        state = S.acc_descr_multiline
        p = e
        return
      }
    }
    if (direction()) return

    when (c) {
      32,
      9,
      13 -> {
        e = p + 1
        var d = src.charCodeAt(e)
        while (d == 32 || d == 9 || d == 13) {
          e++
          d = src.charCodeAt(e)
        }
        p = e
        return
      }
      10 -> {
        e = p + 1
        while (src.charCodeAt(e) == 10) e++
        emit(T.NEWLINE, p, e)
        return
      }
      34 -> {
        quoted()
        return
      }
      123 -> {
        state = S.block
        emit(T.BLOCK_START, p, p + 1)
        return
      }
      35 -> {
        emit(T.BRKT, p, p + 1)
        return
      }
      44 -> {
        emit(T.COMMA, p, p + 1)
        return
      }
      58 -> {
        if (src.charCodeAt(p + 1) == 58 && src.charCodeAt(p + 2) == 58)
          emit(T.STYLE_SEPARATOR, p, p + 3)
        else emit(T.COLON, p, p + 1)
        return
      }
      91 -> {
        emit(T.SQS, p, p + 1)
        return
      }
      93 -> {
        emit(T.SQE, p, p + 1)
        return
      }
    }
    if (isDigit(c)) {
      number(c)
      return
    }
    if (keyword(if (c >= 65 && c <= 90) c + 32 else c)) return
    if (c >= 128 || (classOf(c) and TEXT) != 0) {
      e = p + 1
      var d = src.charCodeAt(e)
      while (d >= 128 || (classOf(d) and TEXT) != 0) {
        e++
        d = src.charCodeAt(e)
      }
      emit(T.UNICODE_TEXT, p, e)
      return
    }
    emit(T.CHAR, p, p + 1)
  }

  // Mermaid's generic type rule: a run of non-space characters holding a `~`, through the last `~`
  // on the line.
  fun generic(): Boolean {
    if (tildeNext == -1) return false
    if (tildeNext < p) {
      tildeNext = src.indexOf('~', p)
      if (tildeNext == -1) return false
    }
    if (wsNext < p) {
      var q = p
      while (q < n && !isSpace(src.charCodeAt(q))) q++
      wsNext = q
    }
    if (tildeNext >= wsNext) return false
    if (tildeLine < p) {
      tildeLine = nextLineEnd(p)
      tildeLast = src.lastIndexOf('~', tildeLine - 1)
    }
    if (tildeLast <= tildeNext) return false
    var e = tildeLast + 1
    while (e < n && !isSpace(src.charCodeAt(e))) e++
    emit(T.ATTRIBUTE_WORD, p, e)
    return true
  }

  fun block(c: Int) {
    if (isSpace(c)) {
      p++
      while (isSpace(src.charCodeAt(p))) p++
      return
    }
    val lc = c or 32
    if (
      (lc == 112 || lc == 102 || lc == 117) &&
        (src.charCodeAt(p + 1) or 32) == 107 &&
        !isWord(src.charCodeAt(p + 2))
    ) {
      emit(T.ATTRIBUTE_KEY, p, p + 2)
      return
    }
    if (generic()) return
    if (isAttr(c, ATTR_START)) {
      var e = p + 1
      while (e < n && isAttr(src.charCodeAt(e), ATTR_REST)) e++
      emit(T.ATTRIBUTE_WORD, p, e)
      return
    }
    if (c == 96) {
      state = S.block_bq
      p++
      return
    }
    if (c == 34) {
      val e = src.indexOf('"', p + 1)
      if (e != -1) {
        emit(T.COMMENT, p, e + 1)
        return
      }
    } else if (c == 125) {
      state = S.INITIAL
      emit(T.BLOCK_STOP, p, p + 1)
      return
    }
    emit(T.CHAR, p, p + 1)
  }

  while (p < n) {
    val c = src.charCodeAt(p)
    var e: Int
    when (state) {
      S.INITIAL -> initial(c)

      S.block -> block(c)

      S.block_bq ->
        if (c == 96) {
          state = S.block
          p++
        } else {
          e = src.indexOf('`', p)
          emit(T.ATTRIBUTE_WORD, p, if (e == -1) n else e)
        }

      S.acc_title,
      S.acc_descr -> {
        e = src.indexOf('\n', p)
        emit(
          if (state == S.acc_title) T.acc_title_value else T.acc_descr_value,
          p,
          if (e == -1) n else e,
        )
        state = S.INITIAL
      }

      S.acc_descr_multiline ->
        if (c == 125) {
          state = S.INITIAL
          p++
        } else {
          e = src.indexOf('}', p)
          emit(T.acc_descr_multiline_value, p, if (e == -1) n else e)
        }

      S.style ->
        if (c == 10) {
          e = p + 1
          while (src.charCodeAt(e) == 10) e++
          state = S.INITIAL
          emit(T.NEWLINE, p, e)
        } else if (isSpace(c)) {
          p++
          while (isSpace(src.charCodeAt(p))) p++
        } else if (c == 58) {
          emit(T.COLON, p, p + 1)
        } else if (c == 44) {
          emit(T.COMMA, p, p + 1)
        } else if (c == 35) {
          emit(T.BRKT, p, p + 1)
        } else if (c == 59) {
          emit(T.SEMI, p, p + 1)
        } else if (c >= 128 || (classOf(c) and STYLE_TEXT) != 0) {
          e = p + 1
          var d = src.charCodeAt(e)
          while (d >= 128 || (classOf(d) and STYLE_TEXT) != 0) {
            e++
            d = src.charCodeAt(e)
          }
          emit(T.STYLE_TEXT, p, e)
        } else {
          types.add(T.ERROR)
          texts.add("")
          starts.add(p)
          return Tokens(types, texts, starts)
        }
    }
  }

  // At the end of input Jison's INITIAL state yields EOF, the title and description states yield
  // an empty value, and the block and style states yield nothing.
  val last =
    if (state == S.INITIAL) T.EOF
    else if (state == S.acc_title) T.acc_title_value
    else if (state == S.acc_descr) T.acc_descr_value
    else if (state == S.acc_descr_multiline) T.acc_descr_multiline_value else -1
  if (last != -1) {
    types.add(last)
    texts.add("")
    starts.add(n)
  }
  types.add(T.END)
  texts.add("")
  starts.add(n)
  return Tokens(types, texts, starts)
}
