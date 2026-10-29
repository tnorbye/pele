// Ported from Pele src/diagrams/architecture/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.architecture

import pele.diagrams.common.ACC_DESCR
import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.NEWLINE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.STRING
import pele.diagrams.common.TokenType
import pele.diagrams.common.WHITESPACE
import pele.diagrams.common.YAML
import pele.diagrams.common.accDescrValue
import pele.diagrams.common.accTitleValue
import pele.diagrams.common.stringValue
import pele.diagrams.common.titleBefore
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsTrim

private object T {
  const val header = 0
  const val junction = 1
  const val service = 2
  const val column = 3
  const val group = 4
  const val align = 5
  const val row = 6
  const val dashes = 7
  const val `in` = 8
  const val colon = 9
  const val dash = 10
  const val direction = 11
  const val arrowGroup = 12
  const val into = 13
  const val accDescr = 14
  const val accTitle = 15
  const val title = 16
  const val string = 17
  const val id = 18
  const val newline = 19
  const val whitespace = 20
  const val yaml = 21
  const val directive = 22
  const val comment = 23
  const val icon = 24
  const val label = 25
}

private val ID = TokenType(name = "ID", pattern = JsRegExp("""[\w]([-\w]*\w)?""", "y"))

private fun word(text: String): TokenType = TokenType(name = text, pattern = text, longer = listOf(ID))

// The direction letters come before identifiers and have no longer alternative, so Mermaid
// reads an id that starts with L, R, T or B as a direction followed by the rest.
val ARCHITECTURE_TOKENS: List<TokenType> = listOf(
  word("architecture-beta"),
  word("junction"),
  word("service"),
  word("column"),
  word("group"),
  word("align"),
  word("row"),
  TokenType(name = "--", pattern = "--", longer = listOf(YAML)),
  word("in"),
  TokenType(name = ":", pattern = ":"),
  TokenType(name = "-", pattern = "-", longer = listOf(YAML)),
  TokenType(name = "ARROW_DIRECTION", pattern = JsRegExp("""L|R|T|B""", "y")),
  TokenType(name = "ARROW_GROUP", pattern = JsRegExp("""\{group\}""", "y")),
  TokenType(name = "ARROW_INTO", pattern = JsRegExp("""<|>""", "y")),
  ACC_DESCR,
  ACC_TITLE,
  titleBefore("""[-\w]*\w"""),
  STRING,
  ID,
  NEWLINE,
  WHITESPACE,
  YAML,
  DIRECTIVE,
  SINGLE_LINE_COMMENT,
  TokenType(name = "ARCH_ICON", pattern = JsRegExp("""\([\w-:]+\)""", "y")),
  TokenType(name = "ARCH_TITLE", pattern = JsRegExp("""\[(?:"([^"\\]|\\.)*"|'([^'\\]|\\.)*'|[^\[\]\r\n]+)\]""", "y")),
)

// 'L' | 'R' | 'T' | 'B'
typealias Side = String

class GroupAst(
  val id: String,
  var icon: String? = null,
  var title: String? = null,
  var `in`: String? = null,
)

class ServiceAst(
  val id: String,
  var icon: String? = null,
  var iconText: String? = null,
  var title: String? = null,
  var `in`: String? = null,
)

class JunctionAst(
  val id: String,
  var `in`: String? = null,
)

class EdgeAst(
  val lhsId: String,
  val lhsGroup: Boolean,
  val lhsDir: Side,
  val lhsInto: Boolean,
  val title: String?,
  val rhsInto: Boolean,
  val rhsDir: Side,
  val rhsId: String,
  val rhsGroup: Boolean,
)

class AlignmentAst(
  // 'row' | 'column'
  val direction: String,
  val members: List<String>,
)

class ArchitectureAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val groups: MutableList<GroupAst> = ArrayList(),
  val services: MutableList<ServiceAst> = ArrayList(),
  val junctions: MutableList<JunctionAst> = ArrayList(),
  val edges: MutableList<EdgeAst> = ArrayList(),
  val alignments: MutableList<AlignmentAst> = ArrayList(),
)

private val RE_ESCAPED_DOUBLE = JsRegExp("""\\"""" , "g")
private val RE_ESCAPED_SINGLE = JsRegExp("""\\'""", "g")

// Mermaid's ArchitectureValueConverter for ARCH_TITLE.
private fun labelValue(text: String): String {
  var result = text.jsSlice(1, -1).jsTrim()
  if ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'"))) {
    result = result.jsSlice(1, -1).jsReplace(RE_ESCAPED_DOUBLE, "\"").jsReplace(RE_ESCAPED_SINGLE, "'")
  }
  return result.jsTrim()
}

fun parseArchitecture(src: String): ArchitectureAst {
  val r = Reader(tokenize(src, ARCHITECTURE_TOKENS, "architecture"), ARCHITECTURE_TOKENS, "architecture")
  val ast = ArchitectureAst()

  fun `is`(kind: Int): Boolean = r.kinds.getOrNull(r.i) == kind
  fun endOfLine() {
    if (r.kind == -1) return
    if (r.kind != T.newline) r.fail("a line break or the end of input")
    while (r.kind == T.newline) r.i++
  }

  while (r.kind == T.newline) r.i++
  r.expect(T.header)
  while (r.kind != -1) {
    when (r.kind) {
      T.newline -> {
        r.i++
        continue
      }
      T.accDescr -> ast.accDescr = accDescrValue(r.take())
      T.accTitle -> ast.accTitle = accTitleValue(r.take())
      T.title -> ast.title = titleValue(r.take())
      T.group -> {
        r.i++
        val group = GroupAst(id = r.expect(T.id))
        if (`is`(T.icon)) group.icon = r.take().jsSlice(1, -1)
        if (`is`(T.label)) group.title = labelValue(r.take())
        if (r.accept(T.`in`)) group.`in` = r.expect(T.id)
        ast.groups.add(group)
      }
      T.service -> {
        r.i++
        val service = ServiceAst(id = r.expect(T.id))
        if (`is`(T.string)) service.iconText = stringValue(r.take())
        else if (`is`(T.icon)) service.icon = r.take().jsSlice(1, -1)
        if (`is`(T.label)) service.title = labelValue(r.take())
        if (r.accept(T.`in`)) service.`in` = r.expect(T.id)
        ast.services.add(service)
      }
      T.junction -> {
        r.i++
        val junction = JunctionAst(id = r.expect(T.id))
        if (r.accept(T.`in`)) junction.`in` = r.expect(T.id)
        ast.junctions.add(junction)
      }
      T.id -> {
        val lhsId = r.take()
        val lhsGroup = r.accept(T.arrowGroup)
        r.expect(T.colon)
        val lhsDir = r.expect(T.direction)
        val lhsInto = r.accept(T.into)
        var title: String? = null
        if (!r.accept(T.dashes)) {
          if (!`is`(T.dash)) r.fail("'--' or '-'")
          r.i++
          title = labelValue(r.expect(T.label))
          r.expect(T.dash)
        }
        val rhsInto = r.accept(T.into)
        val rhsDir = r.expect(T.direction)
        r.expect(T.colon)
        val rhsId = r.expect(T.id)
        val rhsGroup = r.accept(T.arrowGroup)
        ast.edges.add(EdgeAst(lhsId, lhsGroup, lhsDir, lhsInto, title, rhsInto, rhsDir, rhsId, rhsGroup))
      }
      T.align -> {
        r.i++
        if (!`is`(T.row) && !`is`(T.column)) r.fail("'row' or 'column'")
        val direction = r.take()
        val members = arrayListOf(r.expect(T.id), r.expect(T.id))
        while (`is`(T.id)) members.add(r.take())
        ast.alignments.add(AlignmentAst(direction, members))
      }
      else -> r.fail("a group, a service, a junction, an edge, an alignment, a title, or a line break")
    }
    endOfLine()
  }
  return ast
}
