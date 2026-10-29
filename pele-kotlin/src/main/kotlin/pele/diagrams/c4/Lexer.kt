// Ported from Pele src/diagrams/c4/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.util.isSpace
import pele.util.isWord

// Token text is src.slice(starts[i], ends[i]); nothing is copied until the parser asks for it.
class Tokens(
  val types: MutableList<Int>,
  val starts: MutableList<Int>,
  val ends: MutableList<Int>,
)

private object S {
  const val INITIAL = 0
  const val struct = 1
  const val attribute = 2
  const val string = 3
  const val string_kv = 4
  const val string_kv_key = 5
  const val string_kv_value = 6
  const val acc = 7
  const val acc_descr_multiline = 8
}

private val KEYWORDS: Map<String, Int> =
  mapOf(
    "C4Context" to T.C4_CONTEXT,
    "C4Container" to T.C4_CONTAINER,
    "C4Component" to T.C4_COMPONENT,
    "C4Dynamic" to T.C4_DYNAMIC,
    "C4Deployment" to T.C4_DEPLOYMENT,
    "Person" to T.PERSON,
    "Person_Ext" to T.PERSON_EXT,
    "System" to T.SYSTEM,
    "SystemDb" to T.SYSTEM_DB,
    "SystemQueue" to T.SYSTEM_QUEUE,
    "System_Ext" to T.SYSTEM_EXT,
    "SystemDb_Ext" to T.SYSTEM_EXT_DB,
    "SystemQueue_Ext" to T.SYSTEM_EXT_QUEUE,
    "Container" to T.CONTAINER,
    "ContainerDb" to T.CONTAINER_DB,
    "ContainerQueue" to T.CONTAINER_QUEUE,
    "Container_Ext" to T.CONTAINER_EXT,
    "ContainerDb_Ext" to T.CONTAINER_EXT_DB,
    "ContainerQueue_Ext" to T.CONTAINER_EXT_QUEUE,
    "Component" to T.COMPONENT,
    "ComponentDb" to T.COMPONENT_DB,
    "ComponentQueue" to T.COMPONENT_QUEUE,
    "Component_Ext" to T.COMPONENT_EXT,
    "ComponentDb_Ext" to T.COMPONENT_EXT_DB,
    "ComponentQueue_Ext" to T.COMPONENT_EXT_QUEUE,
    "Enterprise_Boundary" to T.ENTERPRISE_BOUNDARY,
    "System_Boundary" to T.SYSTEM_BOUNDARY,
    "Boundary" to T.BOUNDARY,
    "Container_Boundary" to T.CONTAINER_BOUNDARY,
    "Deployment_Node" to T.NODE,
    "Node" to T.NODE,
    "Node_L" to T.NODE_L,
    "Node_R" to T.NODE_R,
    "Rel" to T.REL,
    "BiRel" to T.BIREL,
    "Rel_Up" to T.REL_U,
    "Rel_U" to T.REL_U,
    "Rel_Down" to T.REL_D,
    "Rel_D" to T.REL_D,
    "Rel_Left" to T.REL_L,
    "Rel_L" to T.REL_L,
    "Rel_Right" to T.REL_R,
    "Rel_R" to T.REL_R,
    "Rel_Back" to T.REL_B,
    "RelIndex" to T.REL_INDEX,
    "UpdateElementStyle" to T.UPDATE_EL_STYLE,
    "UpdateRelStyle" to T.UPDATE_REL_STYLE,
    "UpdateLayoutConfig" to T.UPDATE_LAYOUT_CONFIG,
  )

private val RE_DIRECTION =
  listOf(
    JsRegExp(""".*direction\s+TB[^\n]*""", "y"),
    JsRegExp(""".*direction\s+BT[^\n]*""", "y"),
    JsRegExp(""".*direction\s+RL[^\n]*""", "y"),
    JsRegExp(""".*direction\s+LR[^\n]*""", "y"),
  )
private val RE_LINE_END = JsRegExp("""[\n\r  ]""", "g")
// Consume line breaks too: the grammar rejects blank statements.
private val RE_BARE_TITLE = JsRegExp("""title[^\S\r\n]*(?=[\r\n]|$)\s*""", "y")

// Follows the lexer Jison compiles from c4Diagram.jison: its rule order, its start conditions,
// and what it returns once the input runs out in each of them.
fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val starts = ArrayList<Int>()
  val ends = ArrayList<Int>()
  var state: Int = S.INITIAL
  var accValue: Int = T.acc_title_value
  var p = 0
  var done = false
  // Where the next `direction` is; -2 until the first search. The search is made inside the loop on
  // purpose:
  // made up here, one optimized version of this function in V8 ran it again for every statement,
  // which took seconds on 50,000 characters.
  var dirNext = -2
  var lineEnd = -1
  var dirFailed = 0

  fun emit(type: Int, s: Int, e: Int) {
    types.add(type)
    starts.add(s)
    ends.add(e)
    p = e
  }
  fun skipWs(q0: Int): Int {
    var q = q0
    while (q < n && isSpace(src.charCodeAt(q))) q++
    return q
  }
  fun upTo(ch: String, from: Int): Int {
    val q = src.indexOf(ch, from)
    return if (q == -1) n else q
  }
  // `word\s[^#\n;]+`
  fun statement(word: String, type: Int): Boolean {
    var q = p + word.length
    if (!src.startsWith(word, p) || !isSpace(src.charCodeAt(q))) return false
    val from = ++q
    while (q < n) {
      val c = src.charCodeAt(q)
      if (c == 35 || c == 10 || c == 59) break
      q++
    }
    if (q == from) return false
    emit(type, p, q)
    return true
  }

  while (true) {
    // Jison notes the end of input one call before it reports it, so a rule that matches
    // nothing gets one last turn.
    if (done) {
      emit(T.END, n, n)
      break
    }
    if (p >= n) done = true
    val c = src.charCodeAt(p)

    if (state == S.INITIAL) {
      if (p >= n) {
        emit(T.EOF, n, n)
        continue
      }
      if (dirNext != -1 && p >= dirFailed) {
        if (dirNext < p) dirNext = src.indexOf("direction", p)
        if (dirNext != -1) {
          if (lineEnd < p) {
            RE_LINE_END.lastIndex = p
            lineEnd = if (RE_LINE_END.test(src)) RE_LINE_END.lastIndex - 1 else n
          }
          if (dirNext < lineEnd) {
            var matched = false
            var i = 0
            while (i < 4 && !matched) {
              RE_DIRECTION[i].lastIndex = p
              if (RE_DIRECTION[i].test(src)) {
                emit(T.direction_tb + i, p, RE_DIRECTION[i].lastIndex)
                matched = true
              }
              i++
            }
            if (matched) continue
            // No direction statement starts anywhere in the rest of this line either.
            dirFailed = lineEnd
          }
        }
      }
      if (c == 116) {
        RE_BARE_TITLE.lastIndex = p
        if (RE_BARE_TITLE.test(src)) {
          p = RE_BARE_TITLE.lastIndex
          continue
        }
        if (statement("title", T.title)) continue
      }
      if (c == 97) {
        if (statement("accDescription", T.accDescription)) continue
        val title = src.startsWith("accTitle", p)
        if (title || src.startsWith("accDescr", p)) {
          val q = skipWs(p + 8)
          val d = src.charCodeAt(q)
          if (d == 58) {
            emit(if (title) T.acc_title else T.acc_descr, p, skipWs(q + 1))
            accValue = if (title) T.acc_title_value else T.acc_descr_value
            state = S.acc
            continue
          }
          if (d == 123 && !title) {
            p = skipWs(q + 1)
            state = S.acc_descr_multiline
            continue
          }
        }
      }
      if (c == 37 && src.charCodeAt(p + 1) == 37) {
        // A comment takes the line breaks after it, so no NEWLINE follows.
        var q = upTo("\n", p + 2)
        while (q < n && (src.charCodeAt(q) == 10 || src.charCodeAt(q) == 13)) q++
        p = q
        continue
      }
      if (isSpace(c)) {
        var q = p
        var last = -1
        while (q < n) {
          val d = src.charCodeAt(q)
          if (!isSpace(d)) break
          if (d == 10) last = q
          q++
        }
        if (last != -1) emit(T.NEWLINE, p, last + 1) else p = q
        continue
      }
      if (c == 123) {
        emit(T.LBRACE, p, p + 1)
        continue
      }
      if (c == 125) {
        emit(T.RBRACE, p, p + 1)
        continue
      }
      if (isWord(c)) {
        var q = p + 1
        while (q < n && isWord(src.charCodeAt(q))) q++
        val type = if (q - p <= 19) KEYWORDS[src.jsSlice(p, q)] else null
        if (type != null) {
          if (type > T.C4_DEPLOYMENT) state = S.struct
          emit(type, p, q)
          continue
        }
      }
      emit(T.ERROR, p, p)
      break
    }

    if (state == S.struct) {
      if (p >= n) {
        emit(T.EOF_IN_STRUCT, n, n)
      } else if (c == 40) {
        var q = p + 1
        while (src.charCodeAt(q) == 32) q++
        if (src.charCodeAt(q) == 44) emit(T.ATTRIBUTE_EMPTY, p, q + 1) else p++
        state = S.attribute
      } else if (c == 41) {
        p++
        state = S.INITIAL
      } else {
        emit(T.ERROR, p, p)
        break
      }
      continue
    }

    if (state == S.attribute) {
      if (p >= n) continue
      if (c == 41) {
        p++
        state = S.INITIAL
      } else if (c == 44) {
        if (src.charCodeAt(p + 1) == 44) emit(T.ATTRIBUTE_EMPTY, p, p + 2) else p++
      } else {
        var q = p
        while (src.charCodeAt(q) == 32) q++
        val d = src.charCodeAt(q)
        if (d == 34) {
          if (src.charCodeAt(q + 1) == 34) {
            emit(T.ATTRIBUTE_EMPTY, p, q + 2)
          } else {
            p = q + 1
            state = S.string
          }
        } else if (d == 36) {
          p = q + 1
          state = S.string_kv
        } else {
          // An unquoted value runs to the next comma, through closing parentheses and line breaks.
          emit(T.STR, p, upTo(",", p))
        }
      }
      continue
    }

    if (state == S.string) {
      if (c == 34) {
        p++
        state = S.attribute
      } else {
        emit(T.STR, p, upTo("\"", p))
      }
      continue
    }

    if (state == S.string_kv) {
      emit(T.STR_KEY, p, upTo("=", p))
      state = S.string_kv_key
      continue
    }

    if (state == S.string_kv_key) {
      if (p >= n) continue
      var q = p + 1
      if (c == 61) while (src.charCodeAt(q) == 32) q++
      if (c != 61 || src.charCodeAt(q) != 34) {
        emit(T.ERROR, p, p)
        break
      }
      p = q + 1
      state = S.string_kv_value
      continue
    }

    if (state == S.string_kv_value) {
      if (p >= n) continue
      if (c == 34) {
        p++
        state = S.attribute
      } else {
        emit(T.STR_VALUE, p, upTo("\"", p))
      }
      continue
    }

    if (state == S.acc) {
      emit(accValue, p, upTo("\n", p))
      state = S.INITIAL
      continue
    }

    if (c == 125) {
      p++
      state = S.INITIAL
    } else {
      emit(T.acc_descr_multiline_value, p, upTo("}", p))
    }
  }

  return Tokens(types, starts, ends)
}
