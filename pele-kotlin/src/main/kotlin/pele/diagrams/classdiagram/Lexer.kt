// Ported from Pele src/diagrams/class/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.js.JsRegExp
import pele.js.charCodeAt
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
  const val bqstring = 2
  const val generic = 3
  const val callbackName = 4
  const val callbackArgs = 5
  const val accTitle = 6
  const val accDescr = 7
  const val accDescrMultiline = 8
  const val cls = 9
  const val classBody = 10
  const val namespace = 11
  const val namespaceBody = 12
}

// The token each state gives at the end of input, before the parser sees the end itself.
private val AT_END = intArrayOf(
  T.EOF,
  T.STR,
  T.EOF,
  T.GENERICTYPE,
  T.CALLBACK_NAME,
  T.CALLBACK_ARGS,
  T.acc_title_value,
  T.acc_descr_value,
  T.acc_descr_multiline_value,
  T.EOF,
  T.EOF_IN_STRUCT,
  T.EOF,
  T.EOF_IN_STRUCT,
)

private val RE_DIRECTION = listOf(
  JsRegExp(""".*direction\s+TB[^\n]*""", "y"),
  JsRegExp(""".*direction\s+BT[^\n]*""", "y"),
  JsRegExp(""".*direction\s+RL[^\n]*""", "y"),
  JsRegExp(""".*direction\s+LR[^\n]*""", "y"),
)
private val RE_LINE_END = JsRegExp("""[\n\r  ]""", "g")

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state: Int = S.INITIAL
  var p = 0
  var dirNext = src.indexOf("direction")
  var lineEnd = -1
  var dirFailed = 0

  fun emit(type: Int, s: Int, e: Int): Boolean {
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
    return true
  }
  fun push(s: Int) {
    stack.add(state)
    state = s
  }
  fun pop() {
    if (stack.size > 0) state = stack.removeAt(stack.size - 1)
  }
  fun kw(word: String): Boolean = src.startsWith(word, p) && !isWord(src.charCodeAt(p + word.length))
  fun skipWs(q0: Int): Int {
    var q = q0
    while (q < n && isSpace(src.charCodeAt(q))) q++
    return q
  }
  fun until(ch: Char): Int {
    val e = src.indexOf(ch, p)
    return if (e == -1) n else e
  }

  // A run of whitespace is a NEWLINE up to its last line feed; the rest is skipped.
  fun space(leaves: Boolean) {
    var q = p
    var k = -1
    var c = src.charCodeAt(q)
    while (q < n && isSpace(c)) {
      if (c == 10) k = q
      c = src.charCodeAt(++q)
    }
    if (k < 0) {
      p = q
      return
    }
    if (leaves) pop()
    emit(T.NEWLINE, p, k + 1)
  }

  // The keywords that every state recognizes, even inside generics and backtick names.
  fun keyword(c: Int): Boolean {
    when (c) {
      99 -> {
        if (kw("cssClass")) return emit(T.CSSCLASS, p, p + 8)
        if (kw("callback")) return emit(T.CALLBACK, p, p + 8)
        if (kw("click")) return emit(T.CLICK, p, p + 5)
      }
      108 -> {
        if (kw("link")) return emit(T.LINK, p, p + 4)
      }
      110 -> {
        if (kw("note for")) return emit(T.NOTE_FOR, p, p + 8)
        if (kw("note")) return emit(T.NOTE, p, p + 4)
      }
      60 -> {
        if (src.charCodeAt(p + 1) == 60) return emit(T.ANNOTATION_START, p, p + 2)
      }
      62 -> {
        if (src.charCodeAt(p + 1) == 62) return emit(T.ANNOTATION_END, p, p + 2)
      }
      104 -> {
        if (kw("href")) return emit(T.HREF, p, p + 4)
      }
    }
    return false
  }

  fun common(c: Int): Boolean {
    if (keyword(c)) return true
    val d = src.charCodeAt(p + 1)
    when (c) {
      34 -> {
        push(S.string)
        p++
        return true
      }
      126 -> {
        push(S.generic)
        p++
        return true
      }
      96 -> {
        push(S.bqstring)
        p++
        return true
      }
      95 -> {
        if (kw("_self")) return emit(T.LINK_TARGET, p, p + 5)
        if (kw("_blank")) return emit(T.LINK_TARGET, p, p + 6)
        if (kw("_parent")) return emit(T.LINK_TARGET, p, p + 7)
        if (kw("_top")) return emit(T.LINK_TARGET, p, p + 4)
      }
      60 -> return if (d == 124) emit(T.EXTENSION, p, p + 2) else emit(T.DEPENDENCY, p, p + 1)
      124 -> return d == 62 && emit(T.EXTENSION, p, p + 2)
      62 -> return emit(T.DEPENDENCY, p, p + 1)
      42 -> return emit(T.COMPOSITION, p, p + 1)
      111 -> {
        if (!isWord(d)) return emit(T.AGGREGATION, p, p + 1)
      }
      40 -> return d == 41 && emit(T.LOLLIPOP, p, p + 2)
      45 -> return if (d == 45) emit(T.LINE, p, p + 2) else emit(T.MINUS, p, p + 1)
      46 -> return if (d == 46) emit(T.DOTTED_LINE, p, p + 2) else emit(T.DOT, p, p + 1)
      58 -> {
        var q = p + 1
        while (q < n) {
          val ch = src.charCodeAt(q)
          if (ch == 58 || ch == 10 || ch == 59) break
          q++
        }
        if (q > p + 1) return emit(T.LABEL, p, q)
        return d == 58 && src.charCodeAt(p + 2) == 58 && emit(T.STYLE_SEPARATOR, p, p + 3)
      }
      43 -> return emit(T.PLUS, p, p + 1)
      37 -> return emit(T.PCT, p, p + 1)
      61 -> return emit(T.EQUALS, p, p + 1)
      91 -> return emit(T.SQS, p, p + 1)
      93 -> return emit(T.SQE, p, p + 1)
      33, 35, 36, 38, 39, 44, 63, 92, 47 -> return emit(T.PUNCTUATION, p, p + 1)
    }
    if (isWord(c)) {
      var q = p + 1
      while (isWord(src.charCodeAt(q))) q++
      return emit(T.ALPHA, p, q)
    }
    return isUnicodeLetter(c) && emit(T.UNICODE_TEXT, p, p + 1)
  }

  fun direction(): Boolean {
    if (dirNext == -1 || p < dirFailed) return false
    if (dirNext < p) dirNext = src.indexOf("direction", p)
    if (dirNext == -1) return false
    if (lineEnd < p) {
      RE_LINE_END.lastIndex = p
      lineEnd = if (RE_LINE_END.test(src)) RE_LINE_END.lastIndex - 1 else n
    }
    if (dirNext >= lineEnd) return false
    for (i in 0 until 4) {
      val re = RE_DIRECTION[i]
      re.lastIndex = p
      if (re.test(src)) return emit(T.direction_tb + i, p, re.lastIndex)
    }
    // No direction statement starts anywhere in the rest of this line either.
    dirFailed = lineEnd
    return false
  }

  fun initial(c: Int): Boolean {
    if (direction()) return true
    if (isSpace(c)) {
      space(false)
      return true
    }
    when (c) {
      37 -> {
        if (src.charCodeAt(p + 1) == 37) {
          var e = until('\n')
          while (e < n && (src.charCodeAt(e) == 10 || src.charCodeAt(e) == 13)) e++
          p = e
          return true
        }
      }
      97 -> {
        val title = src.startsWith("accTitle", p)
        if (title || src.startsWith("accDescr", p)) {
          val q = skipWs(p + 8)
          val d = src.charCodeAt(q)
          if (d == 58) {
            emit(if (title) T.acc_title else T.acc_descr, p, skipWs(q + 1))
            push(if (title) S.accTitle else S.accDescr)
            return true
          }
          if (d == 123 && !title) {
            p = skipWs(q + 1)
            push(S.accDescrMultiline)
            return true
          }
        }
      }
      99 -> {
        if (kw("classDiagram-v2")) return emit(T.CLASS_DIAGRAM, p, p + 15)
        if (kw("classDiagram")) return emit(T.CLASS_DIAGRAM, p, p + 12)
        if (src.startsWith("call", p) && isSpace(src.charCodeAt(p + 4))) {
          p = skipWs(p + 4)
          push(S.callbackName)
          return true
        }
        if (kw("classDef")) return emit(T.CLASSDEF, p, p + 8)
        if (kw("class")) {
          push(S.cls)
          return emit(T.CLASS, p, p + 5)
        }
      }
      91 -> {
        if (src.startsWith("*]", p + 1)) return emit(T.EDGE_STATE, p, p + 3)
      }
      115 -> {
        if (kw("style")) return emit(T.STYLE, p, p + 5)
      }
      110 -> {
        if (kw("namespace")) {
          push(S.namespace)
          return emit(T.NAMESPACE, p, p + 9)
        }
      }
      44 -> return emit(T.COMMA, p, p + 1)
      35 -> return emit(T.BRKT, p, p + 1)
    }
    return common(c) || (c == 58 && emit(T.COLON, p, p + 1))
  }

  while (true) {
    if (p >= n) {
      types.add(AT_END[state])
      texts.add("")
      starts.add(n)
      break
    }
    val c = src.charCodeAt(p)
    var ok = true
    when (state) {
      S.INITIAL -> ok = initial(c)

      S.cls ->
        if (isSpace(c)) {
          space(true)
        } else if (c == 125) {
          pop()
          pop()
          emit(T.STRUCT_STOP, p, p + 1)
        } else if (c == 123) {
          push(S.classBody)
          emit(T.STRUCT_START, p, p + 1)
        } else {
          ok = common(c)
        }

      S.classBody ->
        if (c == 34) {
          push(S.string)
          p++
        } else if (c == 125) {
          pop()
          emit(T.STRUCT_STOP, p, p + 1)
        } else if (c == 91 && src.startsWith("*]", p + 1)) {
          emit(T.EDGE_STATE, p, p + 3)
        } else if (c == 123) {
          emit(T.OPEN_IN_STRUCT, p, p + 1)
        } else if (c == 10) {
          p++
        } else {
          var q = p + 1
          while (q < n) {
            val ch = src.charCodeAt(q)
            if (ch == 123 || ch == 125 || ch == 10) break
            q++
          }
          emit(T.MEMBER, p, q)
        }

      S.namespace ->
        if (isSpace(c)) {
          space(true)
        } else if (c == 123) {
          push(S.namespaceBody)
          emit(T.STRUCT_START, p, p + 1)
        } else if (c == 125) {
          // The brace is read again in the state the namespace was opened from.
          pop()
        } else if (c == 110 && kw("namespace")) {
          push(S.namespace)
          emit(T.NAMESPACE, p, p + 9)
        } else {
          ok = common(c)
        }

      S.namespaceBody ->
        if (isSpace(c)) {
          space(false)
        } else if (c == 125) {
          pop()
          emit(T.STRUCT_STOP, p, p + 1)
        } else if (c == 110 && kw("namespace")) {
          push(S.namespace)
          emit(T.NAMESPACE, p, p + 9)
        } else if (c == 91 && src.startsWith("*]", p + 1)) {
          emit(T.EDGE_STATE, p, p + 3)
        } else if (c == 99 && kw("class")) {
          push(S.cls)
          emit(T.CLASS, p, p + 5)
        } else {
          ok = common(c)
        }

      S.string ->
        if (c == 34) {
          pop()
          p++
        } else {
          emit(T.STR, p, until('"'))
        }

      S.generic ->
        if (c == 34) {
          push(S.string)
          p++
        } else if (keyword(c)) {
          // Handled.
        } else if (c == 126) {
          pop()
          p++
        } else {
          emit(T.GENERICTYPE, p, until('~'))
        }

      S.bqstring ->
        if (c == 34) {
          push(S.string)
          p++
        } else if (keyword(c)) {
          // Handled.
        } else if (c == 126) {
          push(S.generic)
          p++
        } else if (c == 96) {
          pop()
          p++
        } else {
          emit(T.BQUOTE_STR, p, until('`'))
        }

      S.callbackName ->
        if (c == 40) {
          val q = skipWs(p + 1)
          pop()
          if (src.charCodeAt(q) == 41) {
            p = q + 1
          } else {
            push(S.callbackArgs)
            p++
          }
        } else {
          emit(T.CALLBACK_NAME, p, until('('))
        }

      S.callbackArgs ->
        if (c == 41) {
          pop()
          p++
        } else {
          emit(T.CALLBACK_ARGS, p, until(')'))
        }

      S.accTitle, S.accDescr -> {
        emit(if (state == S.accTitle) T.acc_title_value else T.acc_descr_value, p, until('\n'))
        pop()
      }

      S.accDescrMultiline ->
        if (c == 125) {
          pop()
          p++
        } else {
          emit(T.acc_descr_multiline_value, p, until('}'))
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
