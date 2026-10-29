// Ported from Pele src/diagrams/sequence/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.js.charCodeAt
import pele.js.jsSlice
import pele.js.jsTrim
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
  const val ID = 1
  const val ALIAS = 2
  const val LINE = 3
  const val CONFIG = 4
  const val acc_title = 5
  const val acc_descr = 6
  const val acc_descr_multiline = 7
}

private object C {
  const val ActorStart = 1
  const val ActorRest = 2
  const val Id = 4
  const val IdWide = 8
  const val Line = 16
  const val Menu = 32
}

// Character classes of Mermaid's lexer rules, for ASCII. Other characters are tested by isSpace.
private val CLASS: IntArray = run {
  val table = IntArray(128)
  for (c in 0 until 128) {
    val ch = c.toChar()
    val ws = c == 32 || (c >= 9 && c <= 13)
    var bits = 0
    if (!"/\\+()<->:\n,;".contains(ch)) bits = bits or C.ActorStart
    if (!"+<->:\n,;".contains(ch)) bits = bits or C.ActorRest
    if (!"<>:\n,;@".contains(ch)) bits = bits or C.IdWide or (if (ws) 0 else C.Id)
    if (!"#\n;".contains(ch)) bits = bits or C.Line
    if (!"/\\+()<>:\n,;-".contains(ch) && !ws) bits = bits or C.Menu
    table[c] = bits
  }
  table
}

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state = S.INITIAL
  var p = 0
  var nextNl = -1

  fun emit(type: Int, s: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
  }
  fun emitText(type: Int, s: Int, e: Int, text: String) {
    types.add(type)
    texts.add(text)
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
  fun eol(q: Int): Int {
    if (nextNl < q) {
      nextNl = src.indexOf('\n', q)
      if (nextNl == -1) nextNl = n
    }
    return nextNl
  }
  fun has(q: Int, bit: Int): Boolean {
    val c = src.charCodeAt(q)
    // PORT: charCodeAt() is -1 past the end, where JS gives NaN, which none of the ASCII tests see.
    if (c in 0..127) return (CLASS[c] and bit) != 0
    return if (bit == C.Id || bit == C.Menu) !isSpace(c) else true
  }
  // Matches a lowercase literal without regard to the case of the source.
  fun ci(q: Int, word: String): Boolean {
    for (k in 0 until word.length) {
      val w = word[k].code
      val c = src.charCodeAt(q + k)
      if (if (w >= 97 && w <= 122) (c or 32) != w else c != w) return false
    }
    return true
  }
  fun word(lit: String): Boolean = ci(p, lit) && !isWord(src.charCodeAt(p + lit.length))
  fun skipWs(q0: Int): Int {
    var q = q0
    while (q < n && isSpace(src.charCodeAt(q))) q++
    return q
  }
  fun skipSameLine(): Boolean {
    var q = p
    while (q < n) {
      val c = src.charCodeAt(q)
      if (c == 10 || !isSpace(c)) break
      q++
    }
    if (q == p) return false
    p = q
    return true
  }
  fun lineRun(q0: Int): Int {
    var q = q0
    while (q < n && has(q, C.Line)) q++
    return q
  }
  fun begin(type: Int, len: Int, next: Int) {
    emit(type, p, p + len)
    push(next)
  }
  fun keyword(lit: String, type: Int, next: Int = -1): Boolean {
    if (!word(lit)) return false
    emit(type, p, p + lit.length)
    if (next >= 0) push(next)
    return true
  }
  // The actor menu keywords count only when spaces and the start of a name follow.
  fun menu(lit: String, type: Int): Boolean {
    if (!ci(p, lit)) return false
    var q = p + lit.length
    var c = src.charCodeAt(q)
    if (c != 32 && c != 9) return false
    while (c == 32 || c == 9) c = src.charCodeAt(++q)
    if (q >= n || !has(q, C.Menu)) return false
    emit(type, p, p + lit.length)
    return true
  }

  fun arrowAhead(q: Int): Boolean {
    val c = src.charCodeAt(q)
    val d = src.charCodeAt(q + 1)
    if (c == 45) {
      if (d == 120 || d == 88 || d == 41 || d == 92 || d == 47 || d == 45) return true
      val e = src.charCodeAt(q + 2)
      return d == 124 && (e == 92 || e == 47)
    }
    if (c == 47 || c == 92) return (d == 124 || d == c) && src.charCodeAt(q + 2) == 45
    return c == 40 && d == 41
  }

  fun actor() {
    var q = p + 1
    while (q < n && has(q, C.ActorStart)) q++
    while (q < n && !arrowAhead(q)) {
      var r = q
      while (src.charCodeAt(r) == 45) r++
      if (r >= n || !has(r, C.ActorRest)) break
      r++
      while (r < n && has(r, C.ActorRest)) r++
      q = r
    }
    emitText(T.ACTOR, p, q, src.jsSlice(p, q).jsTrim())
  }

  fun number(): Boolean {
    var q = p
    while (isDigit(src.charCodeAt(q))) q++
    val whole = q > p
    if (src.charCodeAt(q) == 46 && isDigit(src.charCodeAt(q + 1))) {
      val r = if (isDigit(src.charCodeAt(q + 2))) q + 3 else q + 2
      val after = src.charCodeAt(r)
      if (after == 32 || after == 10) {
        emit(T.NUM, p, r)
        return true
      }
    }
    val after = src.charCodeAt(q)
    if (whole && (after == 32 || after == 10)) {
      emit(T.NUM, p, q)
      return true
    }
    return false
  }

  fun dash() {
    val d = src.charCodeAt(p + 1)
    val e = src.charCodeAt(p + 2)
    if (d == 62)
      emit(if (e == 62) T.SOLID_ARROW else T.SOLID_OPEN_ARROW, p, p + (if (e == 62) 3 else 2))
    else if (d == 120 || d == 88) emit(T.SOLID_CROSS, p, p + 2)
    else if (d == 41) emit(T.SOLID_POINT, p, p + 2)
    else if (d == 124 && e == 92) emit(T.SOLID_ARROW_TOP, p, p + 3)
    else if (d == 124 && e == 47) emit(T.SOLID_ARROW_BOTTOM, p, p + 3)
    else if (d == 92 && e == 92) emit(T.STICK_ARROW_TOP, p, p + 3)
    else if (d == 47 && e == 47) emit(T.STICK_ARROW_BOTTOM, p, p + 3)
    else if (d == 45) {
      val f = src.charCodeAt(p + 3)
      if (e == 62)
        emit(if (f == 62) T.DOTTED_ARROW else T.DOTTED_OPEN_ARROW, p, p + (if (f == 62) 4 else 3))
      else if (e == 120 || e == 88) emit(T.DOTTED_CROSS, p, p + 3)
      else if (e == 41) emit(T.DOTTED_POINT, p, p + 3)
      else if (e == 124 && f == 92) emit(T.SOLID_ARROW_TOP_DOTTED, p, p + 4)
      else if (e == 124 && f == 47) emit(T.SOLID_ARROW_BOTTOM_DOTTED, p, p + 4)
      else if (e == 92 && f == 92) emit(T.STICK_ARROW_TOP_DOTTED, p, p + 4)
      else if (e == 47 && f == 47) emit(T.STICK_ARROW_BOTTOM_DOTTED, p, p + 4)
      else emit(T.MINUS, p, p + 1)
    } else emit(T.MINUS, p, p + 1)
  }

  fun reverse(c: Int): Boolean {
    val d = src.charCodeAt(p + 1)
    if ((d != 124 && d != c) || src.charCodeAt(p + 2) != 45) return false
    val dotted = src.charCodeAt(p + 3) == 45
    val top = c == 47
    val type: Int
    if (d == 124) {
      type =
        if (dotted)
          (if (top) T.SOLID_ARROW_TOP_REVERSE_DOTTED else T.SOLID_ARROW_BOTTOM_REVERSE_DOTTED)
        else (if (top) T.SOLID_ARROW_TOP_REVERSE else T.SOLID_ARROW_BOTTOM_REVERSE)
    } else if (dotted)
      type = if (top) T.STICK_ARROW_TOP_REVERSE_DOTTED else T.STICK_ARROW_BOTTOM_REVERSE_DOTTED
    else type = if (top) T.STICK_ARROW_TOP_REVERSE else T.STICK_ARROW_BOTTOM_REVERSE
    emit(type, p, p + (if (dotted) 4 else 3))
    return true
  }

  fun title(): Boolean {
    if (!ci(p, "title")) return false
    var q = p + 5
    val legacy = src.charCodeAt(q) == 58
    if (legacy) q++
    var e = q
    while (e < n && src.charCodeAt(e) != 10 && isSpace(src.charCodeAt(e))) e++
    if (e == n || src.charCodeAt(e) == 10) {
      p = e
      return true
    }
    val sep = src.charCodeAt(q)
    if (!isSpace(sep) || sep == 13 || !has(q + 1, C.Line)) return false
    emit(if (legacy) T.legacy_title else T.title, p, lineRun(q + 1))
    return true
  }

  fun acc(): Boolean {
    val descr = ci(p, "accdescr")
    if (!descr && !ci(p, "acctitle")) return false
    val q = skipWs(p + 8)
    val c = src.charCodeAt(q)
    if (c == 58) {
      emit(if (descr) T.acc_descr else T.acc_title, p, skipWs(q + 1))
      push(if (descr) S.acc_descr else S.acc_title)
      return true
    }
    if (descr && c == 123) {
      p = skipWs(q + 1)
      push(S.acc_descr_multiline)
      return true
    }
    return false
  }

  fun keywords(c: Int): Boolean {
    return when (c or 32) {
      97 ->
        keyword("actor", T.participant_actor, S.ID) ||
          keyword("alt", T.alt, S.LINE) ||
          keyword("and", T.and, S.LINE) ||
          keyword("activate", T.activate, S.ID) ||
          acc() ||
          keyword("autonumber", T.autonumber)
      98 -> keyword("box", T.box, S.LINE) || keyword("break", T.`break`, S.LINE)
      99 -> keyword("create", T.create) || keyword("critical", T.critical, S.LINE)
      100 ->
        keyword("destroy", T.destroy, S.ID) ||
          menu("details", T.details) ||
          keyword("deactivate", T.deactivate, S.ID)
      101 -> keyword("else", T.`else`, S.LINE) || keyword("end", T.end)
      108 ->
        keyword("loop", T.loop, S.LINE) ||
          keyword("left of", T.left_of) ||
          menu("links", T.links) ||
          menu("link", T.link)
      110 -> keyword("note", T.note)
      111 ->
        keyword("opt", T.opt, S.LINE) ||
          keyword("option", T.option, S.LINE) ||
          keyword("over", T.over) ||
          keyword("off", T.off)
      112 ->
        keyword("participant", T.participant, S.ID) ||
          keyword("par", T.par, S.LINE) ||
          keyword("par_over", T.par_over, S.LINE) ||
          menu("properties", T.properties)
      114 -> keyword("rect", T.rect, S.LINE) || keyword("right of", T.right_of)
      115 -> keyword("sequencediagram", T.SD)
      116 -> title()
      else -> false
    }
  }

  fun initial() {
    val c = src.charCodeAt(p)
    if (c == 10) {
      var q = p + 1
      while (src.charCodeAt(q) == 10) q++
      emit(T.NEWLINE, p, q)
      return
    }
    if (isSpace(c)) {
      p = skipWs(p + 1)
      return
    }
    if (c == 35 || (c == 37 && src.charCodeAt(p + 1) != 123)) {
      p = eol(p)
      return
    }
    if ((isDigit(c) || c == 46) && number()) return
    if (c < 128 && (c or 32) >= 97 && (c or 32) <= 122 && keywords(c)) return
    if (c == 44) emit(T.COMMA, p, p + 1)
    else if (c == 59) emit(T.NEWLINE, p, p + 1)
    else if (has(p, C.ActorStart)) actor()
    else if (c == 45) dash()
    else if (c == 60 && src.startsWith("<<->>", p)) emit(T.BIDIRECTIONAL_SOLID_ARROW, p, p + 5)
    else if (c == 60 && src.startsWith("<<-->>", p)) emit(T.BIDIRECTIONAL_DOTTED_ARROW, p, p + 6)
    else if ((c == 47 || c == 92) && reverse(c)) return
    else if (c == 58) emit(T.TXT, p, lineRun(p + 1))
    else if (c == 43) emit(T.PLUS, p, p + 1)
    else if (c == 40 && src.charCodeAt(p + 1) == 41) emit(T.CENTRAL, p, p + 2)
    else emit(T.INVALID, p, p + 1)
  }

  // A participant name, up to an alias, a config object, or the end of the statement.
  fun id(): Boolean {
    if (skipSameLine()) return true
    val c = src.charCodeAt(p)
    if (c == 35) {
      p = eol(p)
      return true
    }
    if (c == 64 && src.charCodeAt(p + 1) == 123) {
      begin(T.CONFIG_START, 2, S.CONFIG)
      return true
    }
    var a = p
    while (a < n && has(a, C.Id)) a++
    if (a > p) {
      val w = skipWs(a)
      if (src.charCodeAt(w) == 64 && src.charCodeAt(w + 1) == 123) {
        emit(T.ACTOR, p, a)
        return true
      }
      if (w > a && ci(w, "as") && isSpace(src.charCodeAt(w + 2))) {
        emit(T.ACTOR, p, a)
        push(S.ALIAS)
        return true
      }
    }
    var b = a
    while (b < n && has(b, C.IdWide)) b++
    if (b > p) {
      val end = src.charCodeAt(b)
      // Before any other delimiter the name must be followed by a comment, so it ends at the last
      // one.
      var cut = b
      if (b < n && end != 10 && end != 59) {
        cut--
        while (cut > p && src.charCodeAt(cut) != 35) cut--
      }
      if (cut > p) {
        emitText(T.ACTOR, p, cut, src.jsSlice(p, cut).jsTrim())
        pop()
        return true
      }
    }
    if (src.charCodeAt(b) == 60) {
      emit(T.INVALID, p, eol(p))
      pop()
      return true
    }
    if (c != 10) {
      val e = eol(p)
      emitText(T.INVALID, p, e, src.jsSlice(p, e).jsTrim())
      pop()
      return true
    }
    return false
  }

  fun step(): Boolean {
    when (state) {
      S.INITIAL -> {
        initial()
        return true
      }
      S.ID -> return id()
      S.ALIAS -> {
        if (skipSameLine()) return true
        if (src.charCodeAt(p) == 35) {
          p = eol(p)
          return true
        }
        pop()
        pop()
        if (word("as")) begin(T.AS, 2, S.LINE) else emit(T.NEWLINE, p, p)
        return true
      }
      S.LINE -> {
        if (skipSameLine()) return true
        if (src.charCodeAt(p) == 35) {
          p = eol(p)
          return true
        }
        emit(T.restOfLine, p, lineRun(p))
        pop()
        return true
      }
      S.CONFIG -> {
        if (src.charCodeAt(p) != 125) {
          var q = src.indexOf('}', p)
          if (q == -1) q = n
          emit(T.CONFIG_CONTENT, p, q)
          return true
        } else {
          val w = skipWs(p + 1)
          val alias = w > p + 1 && ci(w, "as") && isSpace(src.charCodeAt(w + 2))
          emit(T.CONFIG_END, p, p + 1)
          pop()
          if (alias) push(S.ALIAS) else pop()
          return true
        }
      }
      S.acc_title,
      S.acc_descr -> {
        emit(if (state == S.acc_title) T.acc_title_value else T.acc_descr_value, p, eol(p))
        pop()
        return true
      }
      else -> {
        if (src.charCodeAt(p) == 125) {
          p++
          pop()
        } else {
          var q = src.indexOf('}', p)
          if (q == -1) q = n
          emit(T.acc_descr_multiline_value, p, q)
        }
        return true
      }
    }
  }

  while (p < n) {
    if (!step()) {
      types.add(T.ERROR)
      texts.add("")
      starts.add(p)
      return Tokens(types, texts, starts)
    }
  }

  // Mermaid's lexer tries its rules once more on the empty remainder before reporting the end.
  if (state == S.INITIAL || state == S.ALIAS) emit(T.NEWLINE, n, n)
  else if (state == S.LINE) emit(T.restOfLine, n, n)
  else if (state == S.acc_title) emit(T.acc_title_value, n, n)
  else if (state == S.acc_descr) emit(T.acc_descr_value, n, n)
  else if (state == S.acc_descr_multiline) emit(T.acc_descr_multiline_value, n, n)
  types.add(T.END)
  texts.add("")
  starts.add(n)
  return Tokens(types, texts, starts)
}
