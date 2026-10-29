// Ported from Pele src/diagrams/requirement/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.js.jsTrim
import pele.util.isLineEnd
import pele.util.isSpace
import pele.util.isWord

class Tokens(
  val types: MutableList<Int>,
  val texts: MutableList<String>,
  val starts: MutableList<Int>,
)

private object S {
  const val INITIAL = 0
  const val string = 1
  const val style = 2
  const val acc_title = 3
  const val acc_descr = 4
  const val acc_descr_multiline = 5
}

private val RE_ACC_TITLE = JsRegExp("""accTitle\s*:\s*""", "iy")
private val RE_ACC_DESCR = JsRegExp("""accDescr\s*:\s*""", "iy")
private val RE_ACC_DESCR_ML = JsRegExp("""accDescr\s*\{\s*""", "iy")
private val RE_DIRECTION = JsRegExp("""direction\s+(?:(TB)|(BT)|(RL)|LR)""", "gi")
private val RE_LINE_END = JsRegExp("""[\n\r  ]""", "g")
private const val LONGEST_KEYWORD = 22

// Characters that end an unquoted string: `:,{<>-=` and line breaks.
private val STOP: IntArray = run {
  val table = IntArray(128)
  for (ch in ":,\r\n{<>-=") table[ch.code] = 1
  table
}

// Produces the token stream of Mermaid's requirement lexer. Its rules are tried in order and
// the first match wins, so a line that holds a direction statement anywhere is one token.
fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state = S.INITIAL
  var p = 0

  // Where each kind of direction statement starts and where its keyword ends, found in one pass.
  val dirStarts = listOf(ArrayList<Int>(), ArrayList<Int>(), ArrayList<Int>(), ArrayList<Int>())
  val dirEnds = listOf(ArrayList<Int>(), ArrayList<Int>(), ArrayList<Int>(), ArrayList<Int>())
  val dirSeen = intArrayOf(0, 0, 0, 0)
  var anyDir = false
  var lineEnd = -1
  RE_DIRECTION.lastIndex = 0
  var m = RE_DIRECTION.exec(src)
  while (m != null) {
    val kind = if (m[1] != null) 0 else if (m[2] != null) 1 else if (m[3] != null) 2 else 3
    dirStarts[kind].add(m.index)
    dirEnds[kind].add(RE_DIRECTION.lastIndex)
    anyDir = true
    m = RE_DIRECTION.exec(src)
  }

  fun emit(type: Int, s: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
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
  fun lineBreak(q: Int): Int {
    val e = src.indexOf('\n', q)
    return if (e == -1) n else e
  }

  // `.*direction\s+XX[^\n]*`: the last such statement that starts on this line decides the token.
  fun direction(): Boolean {
    if (lineEnd < p) {
      RE_LINE_END.lastIndex = p
      lineEnd = if (RE_LINE_END.test(src)) RE_LINE_END.lastIndex - 1 else n
      for (k in 0 until 4) {
        val list = dirStarts[k]
        var j = dirSeen[k]
        while (j < list.size && list[j] <= lineEnd) j++
        dirSeen[k] = j
      }
    }
    for (k in 0 until 4) {
      val j = dirSeen[k] - 1
      if (j >= 0 && dirStarts[k][j] >= p) {
        emit(T.direction_tb + k, p, lineBreak(dirEnds[k][j]))
        return true
      }
    }
    return false
  }

  fun initial(c: Int): Boolean {
    val lower = c or 32
    if (lower == 116 && src.jsSlice(p + 1, p + 5).lowercase() == "itle" && isSpace(src.charCodeAt(p + 5))) {
      var q = p + 6
      while (q < n) {
        val d = src.charCodeAt(q)
        if (d == 35 || d == 10 || d == 59) break
        q++
      }
      if (q > p + 6) {
        emit(T.title, p, q)
        return true
      }
    }
    if (lower == 97 && (src.charCodeAt(p + 1) or 32) == 99 && (src.charCodeAt(p + 2) or 32) == 99) {
      var e: Int
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
    if (anyDir && !isLineEnd(c) && direction()) return true

    if (c == 10 || (c == 13 && src.charCodeAt(p + 1) == 10)) {
      var q = p
      while (q < n) {
        val d = src.charCodeAt(q)
        if (d == 10) q++
        else if (d == 13 && src.charCodeAt(q + 1) == 10) q += 2
        else break
      }
      emit(T.NEWLINE, p, q)
      return true
    }
    if (isSpace(c)) {
      p++
      while (p < n && isSpace(src.charCodeAt(p))) p++
      return true
    }
    if (c == 35 || c == 37) {
      p = lineBreak(p)
      return true
    }

    if (isWord(c)) {
      var q = p + 1
      while (q < n && isWord(src.charCodeAt(q))) q++
      if (q - p <= LONGEST_KEYWORD) {
        val keyword = KEYWORDS[src.jsSlice(p, q).lowercase()]
        if (keyword != null) {
          if (keyword == T.STYLE || keyword == T.CLASSDEF || keyword == T.CLASS) push(S.style)
          emit(keyword, p, q)
          return true
        }
      }
      while (q < n) {
        val d = src.charCodeAt(q)
        if (d < 128 && STOP[d] == 1) break
        q++
      }
      types.add(T.unqString)
      texts.add(src.jsSlice(p, q).jsTrim())
      starts.add(p)
      p = q
      return true
    }

    when (c) {
      123 -> {
        emit(T.STRUCT_START, p, p + 1)
        return true
      }
      125 -> {
        emit(T.STRUCT_STOP, p, p + 1)
        return true
      }
      58 -> {
        if (src.charCodeAt(p + 1) == 58 && src.charCodeAt(p + 2) == 58) emit(T.STYLE_SEPARATOR, p, p + 3)
        else emit(T.COLONSEP, p, p + 1)
        return true
      }
      60 -> {
        if (src.charCodeAt(p + 1) != 45) return false
        emit(T.END_ARROW_L, p, p + 2)
        return true
      }
      45 -> {
        if (src.charCodeAt(p + 1) == 62) emit(T.END_ARROW_R, p, p + 2)
        else emit(T.LINE, p, p + 1)
        return true
      }
      34 -> {
        push(S.string)
        p++
        return true
      }
      44 -> {
        emit(T.COMMA, p, p + 1)
        return true
      }
    }
    return false
  }

  fun style(c: Int): Boolean {
    if (isWord(c)) {
      var q = p + 1
      while (q < n && isWord(src.charCodeAt(q))) q++
      emit(T.ALPHA, p, q)
      return true
    }
    when (c) {
      58 -> {
        emit(T.COLON, p, p + 1)
        return true
      }
      59 -> {
        emit(T.SEMICOLON, p, p + 1)
        return true
      }
      37 -> {
        emit(T.PERCENT, p, p + 1)
        return true
      }
      45 -> {
        emit(T.MINUS, p, p + 1)
        return true
      }
      35 -> {
        emit(T.BRKT, p, p + 1)
        return true
      }
      32 -> {
        p++
        return true
      }
      34 -> {
        push(S.string)
        p++
        return true
      }
      10 -> {
        pop()
        p++
        return true
      }
      44 -> {
        emit(T.COMMA, p, p + 1)
        return true
      }
    }
    return false
  }

  while (p < n) {
    val c = src.charCodeAt(p)
    var ok = true
    when (state) {
      S.INITIAL -> ok = initial(c)
      S.style -> ok = style(c)
      S.string ->
        if (c == 34) {
          pop()
          p++
        } else {
          val e = src.indexOf('"', p)
          emit(T.qString, p, if (e == -1) n else e)
        }
      S.acc_title -> {
        pop()
        emit(T.acc_title_value, p, lineBreak(p))
      }
      S.acc_descr -> {
        pop()
        emit(T.acc_descr_value, p, lineBreak(p))
      }
      S.acc_descr_multiline ->
        if (c == 125) {
          pop()
          p++
        } else {
          val e = src.indexOf('}', p)
          emit(T.acc_descr_multiline_value, p, if (e == -1) n else e)
        }
    }
    if (!ok) {
      types.add(T.ERROR)
      texts.add("")
      starts.add(p)
      return Tokens(types, texts, starts)
    }
  }

  // At the end of input Jison still tries the rules that can match nothing, once.
  val last =
    if (state == S.INITIAL) T.EOF
    else if (state == S.string) T.qString
    else if (state == S.acc_title) T.acc_title_value
    else if (state == S.acc_descr) T.acc_descr_value
    else if (state == S.acc_descr_multiline) T.acc_descr_multiline_value
    else -1
  if (last != -1) emit(last, n, n)
  emit(T.END, n, n)
  return Tokens(types, texts, starts)
}
