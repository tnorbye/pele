// Ported from Pele src/diagrams/block/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import pele.js.charCodeAt
import pele.js.jsSlice
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
  const val md_string = 2
  const val NODE = 3
  const val BLOCK_ARROW = 4
  const val ARROW_DIR = 5
  const val LLABEL = 6
  const val CLASSDEF = 7
  const val CLASSDEFID = 8
  const val CLASS = 9
  const val CLASS_STYLE = 10
  const val STYLE_STMNT = 11
  const val STYLE_DEFINITION = 12
  const val acc_title = 13
  const val acc_descr = 14
  const val acc_descr_multiline = 15
}

private val DIRECTIONS = listOf("right", "left", "x", "y", "up", "down")

// Characters that end a node id: ( ) [ { } < > - : = and whitespace.
private val ID_STOP: IntArray = run {
  val table = IntArray(128)
  for (c in intArrayOf(9, 10, 11, 12, 13, 32, 40, 41, 45, 58, 60, 61, 62, 91, 123, 125)) table[c] =
    1
  table
}

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  val stack = ArrayList<Int>()
  var state: Int = S.INITIAL
  var p = 0

  fun put(type: Int, text: String, end: Int): Boolean {
    types.add(type)
    texts.add(text)
    starts.add(p)
    p = end
    return true
  }
  fun emit(type: Int, end: Int): Boolean = put(type, src.jsSlice(p, end), end)
  fun push(s: Int) {
    stack.add(state)
    state = s
  }
  fun pop() {
    if (stack.size > 0) state = stack.removeAt(stack.size - 1)
  }
  fun ws(q0: Int): Int {
    var q = q0
    while (q < n && isSpace(src.charCodeAt(q))) q++
    return q
  }
  fun digits(q0: Int): Int {
    var q = q0
    while (q < n) {
      val c = src.charCodeAt(q)
      if (c < 48 || c > 57) break
      q++
    }
    return q
  }
  fun word(q0: Int): Int {
    var q = q0
    while (q < n && isWord(src.charCodeAt(q))) q++
    return q
  }
  fun keyword(text: String): Boolean =
    src.startsWith(text, p) && !isWord(src.charCodeAt(p + text.length))

  fun quote(): Boolean {
    if (src.charCodeAt(p + 1) == 96) {
      push(S.md_string)
      p += 2
    } else {
      push(S.string)
      p++
    }
    return true
  }

  // End of `.+-[xo>]?` at q0, or -1.
  fun dotted(q0: Int): Int {
    var q = q0
    if (src.charCodeAt(q) != 46) return -1
    do q++ while (src.charCodeAt(q) == 46)
    if (src.charCodeAt(q) != 45) return -1
    val c = src.charCodeAt(q + 1)
    return if (c == 120 || c == 111 || c == 62) q + 2 else q + 1
  }

  // End of a complete link at q0, before its trailing whitespace, or -1.
  fun linkEnd(q0: Int): Int {
    var q = q0
    val c = src.charCodeAt(q)
    if (c == 120 || c == 111 || c == 60) q++
    val line = src.charCodeAt(q)
    if (line == 46) return dotted(q)
    if (line != 45 && line != 61) return -1
    var k = q + 1
    while (src.charCodeAt(k) == line) k++
    if (k - q == 1) return if (line == 45) dotted(k) else -1
    val head = src.charCodeAt(k)
    if (head == 120 || head == 111 || head == 62) return k + 1
    return if (k - q >= 3) k else -1
  }

  fun link(): Boolean {
    val e = linkEnd(p)
    if (e >= 0) return emit(T.LINK, ws(e))
    val q = if (src.charCodeAt(p) == 60) p + 1 else p
    val a = src.charCodeAt(q)
    val b = src.charCodeAt(q + 1)
    if ((a == 45 && (b == 45 || b == 46)) || (a == 61 && b == 61)) {
      push(S.LLABEL)
      return emit(T.START_LINK, ws(q + 2))
    }
    return false
  }

  fun lineState(type: Int): Boolean {
    var e = src.indexOf('\n', p)
    if (e == -1) e = n
    pop()
    return emit(type, e)
  }

  // `\w+(,\s*\w+)*`
  fun idList(type: Int, next: Int): Boolean {
    var k = word(p)
    if (k == p) return false
    while (src.charCodeAt(k) == 44) {
      val from = ws(k + 1)
      val to = word(from)
      if (to == from) break
      k = to
    }
    state = next
    return emit(type, k)
  }

  fun initial(c: Int): Boolean {
    if (isSpace(c)) {
      p = ws(p + 1)
      return true
    }
    var q: Int
    if (c == 98) {
      if (src.startsWith("block", p)) {
        if (keyword("block-beta")) return emit(T.KEY, p + 10)
        if (src.charCodeAt(p + 5) == 58) return emit(T.ID_BLOCK, p + 6)
        if (!isWord(src.charCodeAt(p + 5))) return emit(T.KEY, p + 5)
      }
    } else if (c == 99) {
      if (src.startsWith("columns", p)) {
        q = ws(p + 7)
        if (q > p + 7) {
          if (src.startsWith("auto", q) && !isWord(src.charCodeAt(q + 4)))
            return put(T.COLUMNS, "-1", q + 4)
          val e = digits(q)
          if (e > q) return put(T.COLUMNS, src.jsSlice(q, e), e)
        }
      } else if (src.startsWith("class", p)) {
        val def = src.startsWith("Def", p + 5)
        val from = if (def) p + 8 else p + 5
        q = ws(from)
        if (q > from) {
          push(if (def) S.CLASSDEF else S.CLASS)
          return emit(if (def) T.CLASSDEF else T.CLASS, q)
        }
      }
    } else if (c == 34) {
      return quote()
    } else if (c == 115) {
      if (src.startsWith("space", p)) {
        if (src.charCodeAt(p + 5) == 58) {
          val e = digits(p + 6)
          if (e > p + 6) return put(T.SPACE_BLOCK, src.jsSlice(p + 6, e), e)
        }
        if (!isWord(src.charCodeAt(p + 5))) return put(T.SPACE_BLOCK, "1", p + 5)
      } else if (src.startsWith("style", p)) {
        q = ws(p + 5)
        if (q > p + 5) {
          push(S.STYLE_STMNT)
          return emit(T.STYLE, q)
        }
      }
    } else if (c == 100) {
      if (keyword("default")) return emit(T.DEFAULT, p + 7)
    } else if (c == 108) {
      if (keyword("linkStyle")) return emit(T.LINKSTYLE, p + 9)
    } else if (c == 105) {
      if (keyword("interpolate")) return emit(T.INTERPOLATE, p + 11)
    } else if (c == 97) {
      if (src.startsWith("accTitle", p)) {
        q = ws(p + 8)
        if (src.charCodeAt(q) == 58) {
          push(S.acc_title)
          return emit(T.acc_title, ws(q + 1))
        }
      } else if (src.startsWith("accDescr", p)) {
        q = ws(p + 8)
        val d = src.charCodeAt(q)
        if (d == 58) {
          push(S.acc_descr)
          return emit(T.acc_descr, ws(q + 1))
        }
        if (d == 123) {
          push(S.acc_descr_multiline)
          p = ws(q + 1)
          return true
        }
      }
    } else if (c == 101) {
      if (keyword("end")) return emit(T.end, ws(p + 3))
    } else if (c == 45) {
      if (src.charCodeAt(p + 1) == 41) {
        push(S.NODE)
        return emit(T.NODE_DSTART, p + 2)
      }
      return link()
    } else if (c == 61) {
      return link()
    } else if (c == 60) {
      if (src.charCodeAt(p + 1) == 91) {
        push(S.BLOCK_ARROW)
        return emit(T.BLOCK_ARROW_START, p + 2)
      }
      return link()
    } else if (c == 40) {
      val d = src.charCodeAt(p + 1)
      val len =
        if (d == 45 || d == 91) 2
        else if (d == 40) (if (src.charCodeAt(p + 2) == 40) 3 else 2) else 1
      push(S.NODE)
      return emit(T.NODE_DSTART, p + len)
    } else if (c == 41 || c == 123) {
      push(S.NODE)
      return emit(T.NODE_DSTART, if (src.charCodeAt(p + 1) == c) p + 2 else p + 1)
    } else if (c == 62) {
      push(S.NODE)
      return emit(T.NODE_DSTART, p + 1)
    } else if (c == 91) {
      val d = src.charCodeAt(p + 1)
      push(S.NODE)
      return emit(
        T.NODE_DSTART,
        if (d == 91 || d == 124 || d == 40 || d == 92 || d == 47) p + 2 else p + 1,
      )
    } else if (c == 58) {
      val e = digits(p + 1)
      if (e > p + 1) return put(T.SIZE, src.jsSlice(p + 1, e), e)
      return false
    }

    q = p
    while (q < n) {
      val ch = src.charCodeAt(q)
      if (if (ch < 128) ID_STOP[ch] == 1 else isSpace(ch)) break
      q++
    }
    return q > p && emit(T.NODE_ID, q)
  }

  fun nodeEnd(c: Int): Boolean {
    val d = src.charCodeAt(p + 1)
    var len = 0
    if (c == 34) {
      return quote()
    } else if (c == 40) {
      len = if (d == 45) 2 else if (d == 40) (if (src.charCodeAt(p + 2) == 40) 3 else 2) else 1
    } else if (c == 41) {
      len = if (d == 41) (if (src.charCodeAt(p + 2) == 41) 3 else 2) else if (d == 93) 2 else 1
    } else if (c == 125) {
      len = if (d == 125) 2 else 1
    } else if (c == 45) {
      len = if (d == 41) 2 else 0
    } else if (c == 93) {
      len = if (d == 93 || d == 41 || d == 62) 2 else 1
    } else if (c == 92 || c == 47) {
      len = if (d == 93) 2 else 0
    }
    if (len == 0) return false
    pop()
    return emit(T.NODE_DEND, p + len)
  }

  fun arrowDir(c: Int): Boolean {
    if (c == 41) {
      pop()
      pop()
      return put(T.BLOCK_ARROW_END, "]>", ws(p + 1))
    }
    val from = ws(if (c == 44) p + 1 else p)
    for (dir in DIRECTIONS) {
      if (src.startsWith(dir, from)) {
        val e = ws(from + dir.length)
        return put(T.DIR, src.jsSlice(if (c == 44) from else p, e), e)
      }
    }
    return false
  }

  while (true) {
    if (p >= n) {
      var type = -1
      when (state) {
        S.INITIAL -> type = T.EOF
        S.string -> type = T.STR
        S.CLASSDEFID -> type = T.CLASSDEF_STYLEOPTS
        S.CLASS_STYLE -> type = T.STYLECLASS
        S.STYLE_DEFINITION -> type = T.STYLE_DEFINITION_DATA
        S.acc_title -> type = T.acc_title_value
        S.acc_descr -> type = T.acc_descr_value
        S.acc_descr_multiline -> type = T.acc_descr_multiline_value
      }
      p = n
      if (type != -1) put(type, "", n)
      break
    }

    val c = src.charCodeAt(p)
    var ok = true

    when (state) {
      S.INITIAL -> ok = initial(c)

      S.string -> {
        if (c == 34) {
          pop()
          p++
        } else {
          val e = src.indexOf('"', p)
          emit(T.STR, if (e == -1) n else e)
        }
      }

      S.md_string -> {
        if (c == 96) {
          ok = src.charCodeAt(p + 1) == 34
          if (ok) {
            pop()
            p += 2
          }
        } else if (c == 34) {
          ok = false
        } else {
          var q = p + 1
          while (q < n) {
            val ch = src.charCodeAt(q)
            if (ch == 96 || ch == 34) break
            q++
          }
          emit(T.MD_STR, q)
        }
      }

      S.NODE -> ok = nodeEnd(c)

      S.BLOCK_ARROW -> {
        if (c == 34) {
          quote()
        } else if (c == 93 && src.charCodeAt(p + 1) == 62) {
          val q = ws(p + 2)
          ok = src.charCodeAt(q) == 40
          if (ok) {
            push(S.ARROW_DIR)
            p = q + 1
          }
        } else {
          ok = false
        }
      }

      S.ARROW_DIR -> ok = arrowDir(c)

      S.LLABEL -> {
        if (c == 34) {
          if (src.charCodeAt(p + 1) == 96) {
            quote()
          } else {
            emit(T.LINK_LABEL, p + 1)
            push(S.string)
          }
        } else {
          val e = linkEnd(ws(p))
          ok = e >= 0
          if (ok) {
            pop()
            emit(T.LINK, ws(e))
          }
        }
      }

      S.CLASSDEF -> {
        var type = T.DEFAULT_CLASSDEF_ID
        var k = if (src.startsWith("DEFAULT", p)) p + 7 else p
        var q = ws(k)
        if (k == p || q == k) {
          type = T.CLASSDEF_ID
          k = word(p)
          q = ws(k)
        }
        ok = k > p && q > k
        if (ok) {
          state = S.CLASSDEFID
          emit(type, q)
        }
      }

      S.CLASSDEFID -> lineState(T.CLASSDEF_STYLEOPTS)

      S.CLASS -> ok = idList(T.CLASSENTITY_IDS, S.CLASS_STYLE)

      S.CLASS_STYLE -> lineState(T.STYLECLASS)

      S.STYLE_STMNT -> ok = idList(T.STYLE_ENTITY_IDS, S.STYLE_DEFINITION)

      S.STYLE_DEFINITION -> lineState(T.STYLE_DEFINITION_DATA)

      S.acc_title -> lineState(T.acc_title_value)

      S.acc_descr -> lineState(T.acc_descr_value)

      S.acc_descr_multiline -> {
        if (c == 125) {
          pop()
          p++
        } else {
          val e = src.indexOf('}', p)
          emit(T.acc_descr_multiline_value, if (e == -1) n else e)
        }
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
