// Ported from Pele src/diagrams/state/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.js.jsTrim
import pele.util.isSpace
import pele.util.isWord

class Tokens(
  val types: MutableList<Int>,
  val texts: MutableList<String>,
  val starts: MutableList<Int>,
  // Set when lexing stopped on the grammar's own error rather than on unrecognized text.
  val error: String?,
)

private object S {
  const val INITIAL = 0
  const val struct = 1
  const val STATE = 2
  const val STATE_STRING = 3
  const val STATE_ID = 4
  const val SCALE = 5
  const val acc_title = 6
  const val acc_descr = 7
  const val acc_descr_multiline = 8
  const val CLASSDEF = 9
  const val CLASSDEFID = 10
  const val CLASS = 11
  const val CLASS_STYLE = 12
  const val STYLE = 13
  const val STYLEDEF_STYLES = 14
  const val NOTE = 15
  const val NOTE_ID = 16
  const val NOTE_TEXT = 17
  const val FLOATING_NOTE = 18
  const val FLOATING_NOTE_ID = 19
}

private val RE_DIRECTION = JsRegExp("direction", "gi")
private val RE_STEREOTYPE = JsRegExp("""<<(?:fork|join|choice)>>|\[\[(?:fork|join|choice)\]\]""", "gi")
private val RE_LINE_END = JsRegExp("""[\n\r  ]""", "g")
private val RE_PERCENT = JsRegExp("%%", "g")
private val RE_BRACE = JsRegExp("""\{""", "g")

// Finds the next match of a pattern at or after a position, remembering the answer so that
// asking at each token costs one scan of the text in total.
private class Finder(val re: JsRegExp, val src: String) {
  var from = 0
  var next = -2

  fun at(q: Int): Int {
    if (next == -2 || q < from || (next != -1 && q > next)) {
      re.lastIndex = q
      val m = re.exec(src)
      next = m?.index ?: -1
      from = q
    }
    return next
  }
}

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state: Int = S.INITIAL
  var p = 0
  var error: String? = null

  val nextDirection = Finder(RE_DIRECTION, src)
  val nextStereotype = Finder(RE_STEREOTYPE, src)
  val nextLineEnd = Finder(RE_LINE_END, src)
  val nextPercent = Finder(RE_PERCENT, src)
  val nextBrace = Finder(RE_BRACE, src)

  // The last `direction <dir>` of each kind on the line being lexed, and where its keyword ends.
  var dirFrom = -1
  var dirTo = -1
  val dirAt = intArrayOf(-1, -1, -1, -1)
  val dirEnd = intArrayOf(0, 0, 0, 0)
  // The same for <<fork>>, <<join>>, <<choice>> and their [[ ]] spellings.
  var stereoFrom = -1
  var stereoTo = -1
  val stereoAt = intArrayOf(-1, -1, -1, -1, -1, -1)

  fun emit(type: Int, s: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
  }
  fun emitText(type: Int, text: String, s: Int, e: Int) {
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
  // Case-insensitive match of a lowercase ASCII word, as the grammar's /i flag gives.
  fun word(w: String, q: Int): Boolean {
    for (i in w.indices) {
      val a = w[i].code
      val c = src.charCodeAt(q + i)
      if (c != a && !(a >= 97 && a <= 122 && c == a - 32)) return false
    }
    return true
  }
  fun wsEnd(q: Int): Int {
    var j = q
    while (j < n && isSpace(src.charCodeAt(j))) j++
    return j
  }
  fun wordEnd(q: Int): Int {
    var j = q
    while (j < n && isWord(src.charCodeAt(j))) j++
    return j
  }
  fun eol(q: Int): Int {
    val k = src.indexOf('\n', q)
    return if (k == -1) n else k
  }
  fun lineEnd(q: Int): Int {
    val k = nextLineEnd.at(q)
    return if (k == -1) n else k
  }

  // Rules `.*direction\s+TB[^\n]*` and its three siblings: the first kind, in grammar order,
  // found between q and the end of its line.
  fun direction(q: Int): Int {
    if (q < dirFrom || q > dirTo) {
      val first = nextDirection.at(q)
      val end = if (first == -1) n else lineEnd(q)
      dirFrom = q
      dirTo = end
      dirAt[0] = -1
      dirAt[1] = -1
      dirAt[2] = -1
      dirAt[3] = -1
      var d = first
      while (d != -1 && d < end) {
        val w = wsEnd(d + 9)
        if (w != d + 9) {
          val kind = if (word("tb", w)) 0 else if (word("bt", w)) 1 else if (word("rl", w)) 2 else if (word("lr", w)) 3 else -1
          if (kind != -1) {
            dirAt[kind] = d
            dirEnd[kind] = w + 2
          }
        }
        d = nextDirection.at(d + 1)
      }
    }
    for (kind in 0 until 4) if (dirAt[kind] >= q) return kind
    return -1
  }

  fun stereotype(q: Int): Int {
    if (q < stereoFrom || q > stereoTo) {
      val first = nextStereotype.at(q)
      val end = if (first == -1) n else lineEnd(q)
      stereoFrom = q
      stereoTo = end
      stereoAt.fill(-1)
      var d = first
      while (d != -1 && d < end) {
        val letter = src.charCodeAt(d + 2) or 32
        stereoAt[(if (src.charCodeAt(d) == 60) 0 else 3) + (if (letter == 102) 0 else if (letter == 106) 1 else 2)] = d
        d = nextStereotype.at(d + 1)
      }
    }
    for (kind in 0 until 6) if (stereoAt[kind] >= q) return kind
    return -1
  }

  // The grammar's processId(): an id ends at an inline `%%`, and a text that starts with one is dropped.
  fun emitId(s: Int, e: Int): Boolean {
    val at = nextPercent.at(s)
    if (at == -1 || at + 2 > e) {
      emit(T.ID, s, e)
      return true
    }
    if (at == s) {
      p = e
      return false
    }
    emit(T.ID, s, at)
    return true
  }

  fun finish(type: Int): Tokens {
    types.add(type)
    texts.add("")
    starts.add(p)
    return Tokens(types, texts, starts, error)
  }

  while (true) {
    if (p >= n) {
      // Jison tries the current state's rules once more on the empty remainder.
      when (state) {
        S.INITIAL -> emit(T.NL, n, n)
        S.acc_title -> emit(T.acc_title_value, n, n)
        S.acc_descr -> emit(T.acc_descr_value, n, n)
        S.acc_descr_multiline -> emit(T.acc_descr_multiline_value, n, n)
        S.CLASSDEFID -> emit(T.CLASSDEF_STYLEOPTS, n, n)
        S.CLASS_STYLE -> emit(T.STYLECLASS, n, n)
        S.STYLEDEF_STYLES -> emit(T.STYLEDEF_STYLEOPTS, n, n)
        S.STATE_STRING -> emit(T.STATE_DESCR, n, n)
        S.FLOATING_NOTE -> emit(T.NOTE_TEXT, n, n)
        S.STATE_ID, S.FLOATING_NOTE_ID -> emit(T.ID, n, n)
      }
      return finish(T.END)
    }

    val c = src.charCodeAt(p)
    when (state) {
      S.INITIAL, S.struct -> {
        val root = state == S.INITIAL
        val lc = c or 32
        if (root) {
          if (lc == 99 && word("click", p) && !isWord(src.charCodeAt(p + 5))) {
            emit(T.CLICK, p, p + 5)
            continue
          }
          if (lc == 104 && word("href", p) && !isWord(src.charCodeAt(p + 4))) {
            emit(T.HREF, p, p + 4)
            continue
          }
          if (c == 34) {
            val close = src.indexOf('"', p + 1)
            if (close != -1) {
              emit(T.STRING, p, close + 1)
              continue
            }
          }
          if (lc == 100 && word("default", p) && !isWord(src.charCodeAt(p + 7))) {
            emit(T.DEFAULT, p, p + 7)
            continue
          }
          val kind = direction(p)
          if (kind != -1) {
            emit(T.direction_tb + kind, p, eol(dirEnd[kind]))
            continue
          }
          if (c == 10) {
            var j = p + 1
            while (src.charCodeAt(j) == 10) j++
            emit(T.NL, p, j)
            continue
          }
          if (isSpace(c)) {
            p = wsEnd(p)
            continue
          }
        } else if (c != 10 && isSpace(c)) {
          var j = p + 1
          while (j < n && src.charCodeAt(j) != 10 && isSpace(src.charCodeAt(j))) j++
          p = j
          continue
        }
        if (c == 35 || (c == 37 && src.charCodeAt(p + 1) == 37 && src.charCodeAt(p + 2) != 123)) {
          p = eol(p)
          continue
        }
        if (lc == 115) {
          if (root && word("scale", p)) {
            val e = wsEnd(p + 5)
            if (e > p + 5) {
              emit(T.scale, p, e)
              push(S.SCALE)
              continue
            }
          }
          if (word("style", p)) {
            val e = wsEnd(p + 5)
            if (e > p + 5) {
              emit(T.style, p, e)
              push(S.STYLE)
              continue
            }
          }
          if (word("state", p)) {
            var e = wsEnd(p + 5)
            if (e > p + 5) {
              p = e
              push(S.STATE)
              continue
            }
            if (root && word("diagram", p + 5)) {
              val from = if (word("-v2", p + 12)) p + 15 else p + 12
              e = wsEnd(from)
              if (e > from) {
                emit(T.SD, p, e)
                continue
              }
            }
          }
        } else if (lc == 99) {
          if (word("class", p)) {
            val def = word("def", p + 5)
            val from = if (def) p + 8 else p + 5
            val e = wsEnd(from)
            if (e > from) {
              emit(if (def) T.classDef else T.`class`, p, e)
              push(if (def) S.CLASSDEF else S.CLASS)
              continue
            }
          }
        } else if (lc == 97 && root) {
          if (word("acctitle", p) || word("accdescr", p)) {
            val title = (src.charCodeAt(p + 3) or 32) == 116
            val q = wsEnd(p + 8)
            val d = src.charCodeAt(q)
            if (d == 58) {
              emit(if (title) T.acc_title else T.acc_descr, p, wsEnd(q + 1))
              push(if (title) S.acc_title else S.acc_descr)
              continue
            }
            if (d == 123 && !title) {
              p = wsEnd(q + 1)
              push(S.acc_descr_multiline)
              continue
            }
          }
        }
        if (root) {
          if (c == 123) {
            emit(T.STRUCT_START, p, p + 1)
            push(S.struct)
            continue
          }
        } else {
          val kind = direction(p)
          if (kind != -1) {
            emit(T.direction_tb + kind, p, eol(dirEnd[kind]))
            continue
          }
          if (c == 125) {
            emit(T.STRUCT_STOP, p, p + 1)
            pop()
            continue
          }
          if (c == 10) {
            p++
            continue
          }
        }
        if (lc == 110 && word("note", p)) {
          val e = wsEnd(p + 4)
          if (e > p + 4) {
            emit(T.note, p, e)
            push(S.NOTE)
            continue
          }
        }
        if (root && lc == 104 && word("hide empty description", p) && !isWord(src.charCodeAt(p + 22))) {
          emit(T.HIDE_EMPTY, p, p + 22)
          continue
        }
        if (c == 91 && src.charCodeAt(p + 1) == 42 && src.charCodeAt(p + 2) == 93) {
          emit(T.EDGE_STATE, p, p + 3)
          continue
        }
        var j = p
        while (j < n) {
          val d = src.charCodeAt(j)
          if (d == 58 || d == 45 || d == 123 || isSpace(d)) break
          j++
        }
        if (j > p) {
          emitId(p, j)
          continue
        }
        if (c == 58) {
          j = p + 1
          while (j < n) {
            val d = src.charCodeAt(j)
            if (d == 10 || d == 59) break
            if (d == 58) {
              val after = src.charCodeAt(j + 1)
              if (j + 1 >= n || after == 58 || after == 10 || after == 59) break
              j++
            }
            j++
          }
          if (j > p + 1) {
            emitText(T.DESCR, src.jsSlice(p, j).jsTrim(), p, j)
            continue
          }
          if (src.charCodeAt(p + 1) == 58 && src.charCodeAt(p + 2) == 58) {
            emit(T.STYLE_SEPARATOR, p, p + 3)
            continue
          }
        } else if (c == 45 && src.charCodeAt(p + 1) == 45) {
          if (src.charCodeAt(p + 2) == 62) {
            emit(T.ARROW, p, p + 3)
            continue
          }
          if (!root) {
            emit(T.CONCURRENT, p, p + 2)
            continue
          }
        }
        if (!root) return finish(T.ERROR)
        emit(T.INVALID, p, p + 1)
        continue
      }

      S.STATE -> {
        if (c != 10 && isSpace(c)) {
          var j = p + 1
          while (j < n && src.charCodeAt(j) != 10 && isSpace(src.charCodeAt(j))) j++
          p = j
          continue
        }
        if (c == 35 || (c == 37 && src.charCodeAt(p + 1) == 37 && src.charCodeAt(p + 2) != 123)) {
          p = eol(p)
          continue
        }
        val kind = stereotype(p)
        if (kind != -1) {
          val at = stereoAt[kind]
          val choice = kind == 2 || kind == 5
          emitText(
            if (choice) T.CHOICE else if (kind % 3 == 0) T.FORK else T.JOIN,
            src.jsSlice(p, at).jsTrim(),
            p,
            at + (if (choice) 10 else 8),
          )
          pop()
          continue
        }
        if (c == 34) {
          p++
          push(S.STATE_STRING)
          continue
        }
        val q = if (c == 10) wsEnd(p) else p
        if (word("as", q)) {
          val e = wsEnd(q + 2)
          if (e > q + 2) {
            emit(T.AS, p, e)
            push(S.STATE_ID)
            continue
          }
        }
        if (c == 10) {
          p++
          pop()
          continue
        }
        if (c == 123) {
          emit(T.STRUCT_START, p, p + 1)
          pop()
          push(S.struct)
          continue
        }
        if (isWord(c)) {
          val first = wordEnd(p)
          val second = wsEnd(first)
          if (second > first && isWord(src.charCodeAt(second))) {
            val brace = nextBrace.at(second + 1)
            if (brace != -1 && brace < lineEnd(second)) {
              error = "Error: State name must be a single word. Found: \"${src.jsSlice(p, brace + 1).jsTrim()}\""
              return finish(T.ERROR)
            }
          }
        }
        var j = p + 1
        while (j < n) {
          val d = src.charCodeAt(j)
          if (d == 123 || isSpace(d)) break
          j++
        }
        emit(T.COMPOSIT_STATE, p, j)
        continue
      }

      S.STATE_STRING -> {
        if (c == 34) {
          p++
          pop()
          continue
        }
        val close = src.indexOf('"', p)
        emit(T.STATE_DESCR, p, if (close == -1) n else close)
        continue
      }

      S.STATE_ID -> {
        var j = p
        while (j < n) {
          val d = src.charCodeAt(j)
          if (d == 10 || d == 123) break
          j++
        }
        if (emitId(p, j)) pop()
        continue
      }

      S.SCALE -> {
        if (c >= 48 && c <= 57) {
          var j = p + 1
          while (j < n && src.charCodeAt(j) >= 48 && src.charCodeAt(j) <= 57) j++
          emit(T.WIDTH, p, j)
          continue
        }
        val q = wsEnd(p)
        if (q > p && word("width", q) && !isWord(src.charCodeAt(q + 5))) {
          p = q + 5
          pop()
          continue
        }
        return finish(T.ERROR)
      }

      S.acc_title -> {
        emit(T.acc_title_value, p, eol(p))
        pop()
        continue
      }

      S.acc_descr -> {
        emit(T.acc_descr_value, p, eol(p))
        pop()
        continue
      }

      S.acc_descr_multiline -> {
        if (c == 125) {
          p++
          pop()
          continue
        }
        val close = src.indexOf('}', p)
        emit(T.acc_descr_multiline_value, p, if (close == -1) n else close)
        continue
      }

      S.CLASSDEF -> {
        val isDefault = word("default", p) && wsEnd(p + 7) > p + 7
        val j = if (isDefault) p + 7 else wordEnd(p)
        val e = wsEnd(j)
        if (j == p || e == j) return finish(T.ERROR)
        emit(if (isDefault) T.DEFAULT_CLASSDEF_ID else T.CLASSDEF_ID, p, e)
        pop()
        push(S.CLASSDEFID)
        continue
      }

      S.CLASSDEFID -> {
        emit(T.CLASSDEF_STYLEOPTS, p, eol(p))
        pop()
        continue
      }

      S.CLASS -> {
        var j = wordEnd(p)
        if (j == p) return finish(T.ERROR)
        while (src.charCodeAt(j) == 44) {
          val k = wsEnd(j + 1)
          val e = wordEnd(k)
          if (e == k) break
          j = e
        }
        emit(T.CLASSENTITY_IDS, p, j)
        pop()
        push(S.CLASS_STYLE)
        continue
      }

      S.CLASS_STYLE -> {
        emit(T.STYLECLASS, p, eol(p))
        pop()
        continue
      }

      S.STYLE -> {
        var j = p
        while (j < n && (isWord(src.charCodeAt(j)) || src.charCodeAt(j) == 44)) j++
        val e = wsEnd(j)
        if (j == p || e == j) return finish(T.ERROR)
        emit(T.STYLE_IDS, p, e)
        pop()
        push(S.STYLEDEF_STYLES)
        continue
      }

      S.STYLEDEF_STYLES -> {
        emit(T.STYLEDEF_STYLEOPTS, p, eol(p))
        pop()
        continue
      }

      S.NOTE -> {
        if (word("left of", p) && !isWord(src.charCodeAt(p + 7))) {
          emit(T.left_of, p, p + 7)
          pop()
          push(S.NOTE_ID)
          continue
        }
        if (word("right of", p) && !isWord(src.charCodeAt(p + 8))) {
          emit(T.right_of, p, p + 8)
          pop()
          push(S.NOTE_ID)
          continue
        }
        if (c == 34) {
          p++
          pop()
          push(S.FLOATING_NOTE)
          continue
        }
        return finish(T.ERROR)
      }

      S.NOTE_ID -> {
        val q = wsEnd(p)
        var j = q
        while (j < n) {
          val d = src.charCodeAt(j)
          if (d == 58 || d == 45 || isSpace(d)) break
          j++
        }
        if (j == q) return finish(T.ERROR)
        if (emitId(p, j)) {
          pop()
          push(S.NOTE_TEXT)
        }
        continue
      }

      S.NOTE_TEXT -> {
        val q = wsEnd(p)
        if (src.charCodeAt(q) == 58) {
          var j = q + 1
          while (j < n) {
            val d = src.charCodeAt(j)
            if (d == 58 || d == 10 || d == 59) break
            j++
          }
          if (j > q + 1) {
            // Mermaid drops two characters here, expecting " :"; with no space before the colon
            // the first character of the note goes too.
            emitText(T.NOTE_TEXT, src.jsSlice(p + 2, j).jsTrim(), p, j)
            pop()
            continue
          }
        }
        var k = src.indexOf('\n', p)
        var e = -1
        while (k != -1) {
          e = wsEnd(k)
          if (word("end note", e) && !isWord(src.charCodeAt(e + 8))) break
          k = src.indexOf('\n', e)
        }
        if (k == -1) return finish(T.ERROR)
        emitText(T.NOTE_TEXT, src.jsSlice(p, e).jsTrim(), p, e + 8)
        pop()
        continue
      }

      S.FLOATING_NOTE -> {
        val q = wsEnd(p)
        if (word("as", q)) {
          emit(T.AS, p, wsEnd(q + 2))
          pop()
          push(S.FLOATING_NOTE_ID)
          continue
        }
        if (c == 34) {
          p++
          continue
        }
        val close = src.indexOf('"', p)
        emit(T.NOTE_TEXT, p, if (close == -1) n else close)
        continue
      }

      S.FLOATING_NOTE_ID -> {
        if (emitId(p, eol(p))) pop()
        continue
      }
    }
  }
}
