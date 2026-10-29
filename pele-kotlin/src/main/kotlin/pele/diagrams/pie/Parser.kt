// Ported from Pele src/diagrams/pie/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.pie

import pele.diagrams.common.ACC_DESCR
import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.NEWLINE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.STRING
import pele.diagrams.common.Scanner
import pele.diagrams.common.TITLE
import pele.diagrams.common.TokenType
import pele.diagrams.common.WHITESPACE
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.keyword
import pele.diagrams.common.stringValue
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsNumber

private object T {
  const val showData = 0
  const val pie = 1
  const val colon = 2
  const val number = 3
  const val accDescr = 4
  const val accTitle = 5
  const val title = 6
  const val string = 7
  const val newline = 8
}

private fun digits(src: String, from: Int): Int {
  var i = from
  var c = src.charCodeAt(i)
  while (c >= 48 && c <= 57) {
    i++
    c = src.charCodeAt(i)
  }
  return i
}

// Answers as NUMBER_PIE's pattern does. The pattern reads a whole run of digits before it finds
// there is no fraction, and would do so again from every digit of a long run of zeros.
private fun numberScanner(): Scanner {
  var from = 0
  var end = 0
  var fraction = -1
  return { src, p, _ ->
    val at = if (src.charCodeAt(p) == 45) p + 1 else p
    if (at < from || at >= end) {
      from = at
      end = digits(src, at)
      fraction = -1
      if (end > at && src.charCodeAt(end) == 46) {
        val stop = digits(src, end + 1)
        val length = stop - end - 1
        if (length > 0 && src.charCodeAt(stop) != 46) {
          fraction = stop
        } else if (length > 1) {
          fraction = stop - 1
        }
      }
    }
    if (end == at) {
      -1
    } else if (fraction >= 0) {
      fraction
    } else if (src.charCodeAt(at) == 48) {
      if (src.charCodeAt(at + 1) == 46) -1 else at + 1
    } else if (src.charCodeAt(end) != 46) {
      end
    } else {
      if (end - 1 > at) end - 1 else -1
    }
  }
}

val PIE_TOKENS: List<TokenType> = listOf(
  keyword("showData"),
  keyword("pie"),
  TokenType(name = ":", pattern = ":"),
  TokenType(
    name = "NUMBER_PIE",
    pattern = JsRegExp("""(?:-?[0-9]+\.[0-9]+(?!\.))|(?:-?(0|[1-9][0-9]*)(?!\.))""", "y"),
    scanner = ::numberScanner,
    first = "-0123456789",
  ),
  ACC_DESCR,
  ACC_TITLE,
  TITLE,
  STRING,
  NEWLINE,
  WHITESPACE,
  YAML,
  DIRECTIVE,
  SINGLE_LINE_COMMENT,
)

class PieSection(val label: String, val value: Double)

class PieAst(
  var showData: Boolean = false,
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val sections: MutableList<PieSection> = ArrayList(),
)

fun parsePie(src: String): PieAst {
  val r = Reader(tokenize(src, PIE_TOKENS, "pie"), PIE_TOKENS, "pie")
  val ast = PieAst()

  val endOfLine = {
    if (r.kind != -1) {
      if (r.kind != T.newline) r.fail("a line break or the end of input")
      while (r.kind == T.newline) r.i++
    }
  }

  while (r.kind == T.newline) r.i++
  r.expect(T.pie)
  ast.showData = r.accept(T.showData)
  while (r.kind != -1) {
    when (r.kind) {
      T.newline -> r.i++
      T.accDescr -> {
        ast.accDescr = accDescrValue(r.take())
        endOfLine()
      }
      T.accTitle -> {
        ast.accTitle = accTitleValue(r.take())
        endOfLine()
      }
      T.title -> {
        ast.title = titleValue(r.take())
        endOfLine()
      }
      T.string -> {
        val label = stringValue(r.take())
        r.expect(T.colon)
        val value = jsNumber(r.expect(T.number))
        ast.sections.add(PieSection(label, value))
        endOfLine()
      }
      else -> r.fail("a section, a title, or a line break")
    }
  }
  return ast
}
