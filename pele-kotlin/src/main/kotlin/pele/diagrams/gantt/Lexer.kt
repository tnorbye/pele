// Ported from Pele src/diagrams/gantt/lexer.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.gantt

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.util.isSpace

class Tokens(val types: List<Int>, val texts: List<String>, val starts: List<Int>)

private object S {
  const val INITIAL = 0
  const val acc_title = 1
  const val acc_descr = 2
  const val acc_descr_multiline = 3
  const val href = 4
  const val callbackname = 5
  const val callbackargs = 6
  const val click = 7
}

private val RE_ACC_TITLE = JsRegExp("""accTitle\s*:\s*""", "iy")
private val RE_ACC_DESCR = JsRegExp("""accDescr\s*:\s*""", "iy")
private val RE_ACC_DESCR_ML = JsRegExp("""accDescr\s*\{\s*""", "iy")
private val RE_HREF = JsRegExp("""href\s+"""", "iy")
private val RE_CALL = JsRegExp("""call\s+""", "iy")
private val RE_CLICK = JsRegExp("""click\s+""", "iy")
private val RE_EMPTY_CALL = JsRegExp("""\(\s*\)""", "y")
private val RE_WEEKDAY =
  JsRegExp("""weekday\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", "iy")
private val DAYS =
  listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
private val RE_WEEKEND = JsRegExp("""weekend\s+(?:(friday)|saturday)\b""", "iy")
private val RE_DATE = JsRegExp("""\d\d\d\d-\d\d-\d\d\b""", "y")
private val RE_BARE_TITLE = JsRegExp("""title[^\S\r\n]*(?=[\r\n]|$)""", "iy")

// Statements that start with a keyword, grouped by the keyword's first letter and kept in the
// grammar's rule order.
private val KEYWORDS: Map<Int, List<Pair<JsRegExp, Int>>> =
  mapOf(
    97 to
      listOf(
        JsRegExp("""axisFormat\s[^#\n;]+""", "iy") to T.axisFormat,
        JsRegExp("""accDescription\s[^#\n;]+""", "iy") to T.accDescription,
      ),
    103 to listOf(JsRegExp("""gantt\b""", "iy") to T.gantt),
    100 to listOf(JsRegExp("""dateFormat\s[^#\n;]+""", "iy") to T.dateFormat),
    105 to
      listOf(
        JsRegExp("""inclusiveEndDates\b""", "iy") to T.inclusiveEndDates,
        JsRegExp("""includes\s[^#\n;]+""", "iy") to T.includes,
      ),
    116 to
      listOf(
        JsRegExp("""topAxis\b""", "iy") to T.topAxis,
        JsRegExp("""tickInterval\s[^#\n;]+""", "iy") to T.tickInterval,
        JsRegExp("""todayMarker\s[^\n;]+""", "iy") to T.todayMarker,
        JsRegExp("""title[^\S\r\n][^\n]+""", "iy") to T.title,
      ),
    101 to listOf(JsRegExp("""excludes\s[^#\n;]+""", "iy") to T.excludes),
    115 to listOf(JsRegExp("""section\s[^\n]+""", "iy") to T.section),
  )

fun tokenize(src: String): Tokens {
  val n = src.length
  val types = ArrayList<Int>()
  val texts = ArrayList<String>()
  val starts = ArrayList<Int>()
  var state = S.INITIAL
  var p = 0

  fun emit(type: Int, s: Int, e: Int) {
    types.add(type)
    texts.add(src.jsSlice(s, e))
    starts.add(s)
    p = e
  }
  fun at(re: JsRegExp): Int {
    re.lastIndex = p
    return if (re.test(src)) re.lastIndex else -1
  }
  fun until(ch: Char, from: Int): Int {
    val e = src.indexOf(ch, from)
    return if (e == -1) n else e
  }

  fun keyword(c: Int): Boolean {
    var e: Int
    when (c or 32) {
      97 -> {
        e = at(RE_ACC_TITLE)
        if (e >= 0) {
          state = S.acc_title
          emit(T.acc_title, p, e)
          return true
        }
        e = at(RE_ACC_DESCR)
        if (e >= 0) {
          state = S.acc_descr
          emit(T.acc_descr, p, e)
          return true
        }
        e = at(RE_ACC_DESCR_ML)
        if (e >= 0) {
          state = S.acc_descr_multiline
          p = e
          return true
        }
      }
      104 -> {
        e = at(RE_HREF)
        if (e >= 0) {
          state = S.href
          p = e
          return true
        }
      }
      99 -> {
        e = at(RE_CALL)
        if (e >= 0) {
          state = S.callbackname
          p = e
          return true
        }
        e = at(RE_CLICK)
        if (e >= 0) {
          state = S.click
          p = e
          return true
        }
      }
      116 -> {
        e = at(RE_BARE_TITLE)
        if (e >= 0) {
          p = e
          return true
        }
      }
      119 -> {
        RE_WEEKDAY.lastIndex = p
        var m = RE_WEEKDAY.exec(src)
        if (m != null) {
          emit(T.weekday_monday + DAYS.indexOf(m[1]!!.lowercase()), p, RE_WEEKDAY.lastIndex)
          return true
        }
        RE_WEEKEND.lastIndex = p
        m = RE_WEEKEND.exec(src)
        if (m != null) {
          emit(if (m[1] != null) T.weekend_friday else T.weekend_saturday, p, RE_WEEKEND.lastIndex)
          return true
        }
      }
    }
    val rules = KEYWORDS[c or 32]
    if (rules != null) {
      for ((re, type) in rules) {
        e = at(re)
        if (e >= 0) {
          emit(type, p, e)
          return true
        }
      }
    }
    return false
  }

  loop@ while (true) {
    if (p >= n) {
      var type = T.EOF
      when (state) {
        S.acc_title -> type = T.acc_title_value
        S.acc_descr -> type = T.acc_descr_value
        S.acc_descr_multiline -> type = T.acc_descr_multiline_value
        S.href -> type = T.href
        S.callbackname -> type = T.callbackname
        S.callbackargs -> type = T.callbackargs
        S.click -> type = T.click
      }
      emit(type, n, n)
      break@loop
    }

    val c = src.charCodeAt(p)

    when (state) {
      S.INITIAL -> {
        val d = src.charCodeAt(p + 1)
        if (c == 37) {
          if (d != 37) {
            var e = until('\n', p + 1)
            while (src.charCodeAt(e) == 10) e++
            p = e
          } else if (src.charCodeAt(p + 2) == 123) {
            // The grammar enters a lexer state it never defines, so nothing can follow.
            emit(T.open_directive, p, p + 3)
            emit(T.ERROR, p, p)
            return Tokens(types, texts, starts)
          } else {
            p = until('\n', p + 2)
          }
        } else if (c == 10) {
          var e = p + 1
          while (src.charCodeAt(e) == 10) e++
          emit(T.NL, p, e)
        } else if (isSpace(c)) {
          p++
          while (p < n && isSpace(src.charCodeAt(p))) p++
        } else if (keyword(c)) {
          // handled
        } else if (c >= 48 && c <= 57 && at(RE_DATE) >= 0) {
          emit(T.date, p, p + 10)
        } else if (c != 58) {
          var e = p + 1
          while (e < n) {
            val ch = src.charCodeAt(e)
            if (ch == 58 || ch == 10) break
            e++
          }
          emit(T.taskTxt, p, e)
        } else {
          var e = p + 1
          while (e < n) {
            val ch = src.charCodeAt(e)
            if (ch == 35 || ch == 10 || ch == 59) break
            e++
          }
          emit(if (e > p + 1) T.taskData else T.COLON, p, e)
        }
      }

      S.acc_title,
      S.acc_descr -> {
        val type = if (state == S.acc_title) T.acc_title_value else T.acc_descr_value
        state = S.INITIAL
        emit(type, p, until('\n', p))
      }

      S.acc_descr_multiline ->
        if (c == 125) {
          state = S.INITIAL
          p++
        } else {
          emit(T.acc_descr_multiline_value, p, until('}', p))
        }

      S.href ->
        if (c == 34) {
          state = S.INITIAL
          p++
        } else {
          emit(T.href, p, until('"', p))
        }

      S.callbackname ->
        if (c == 40) {
          val e = at(RE_EMPTY_CALL)
          if (e >= 0) {
            state = S.INITIAL
            p = e
          } else {
            state = S.callbackargs
            p++
          }
        } else {
          emit(T.callbackname, p, until('(', p))
        }

      S.callbackargs ->
        if (c == 41) {
          state = S.INITIAL
          p++
        } else {
          emit(T.callbackargs, p, until(')', p))
        }

      S.click ->
        if (isSpace(c)) {
          state = S.INITIAL
          p++
        } else {
          var e = p + 1
          while (e < n && !isSpace(src.charCodeAt(e))) e++
          emit(T.click, p, e)
        }
    }
  }

  types.add(T.END)
  texts.add("")
  starts.add(n)
  return Tokens(types, texts, starts)
}
