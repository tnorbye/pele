// Ported from Pele src/diagrams/packet/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.packet

import pele.diagrams.common.ACC_DESCR
import pele.diagrams.common.ACC_TITLE
import pele.diagrams.common.DIRECTIVE
import pele.diagrams.common.NEWLINE
import pele.diagrams.common.Reader
import pele.diagrams.common.SINGLE_LINE_COMMENT
import pele.diagrams.common.STRING
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
import pele.js.jsNumber

private object T {
  const val packetBeta = 0
  const val packet = 1
  const val dash = 2
  const val plus = 3
  const val colon = 4
  const val accDescr = 5
  const val accTitle = 6
  const val title = 7
  const val int = 8
  const val string = 9
  const val newline = 10
}

val PACKET_TOKENS: List<TokenType> = listOf(
  TokenType(name = "packet-beta", pattern = "packet-beta"),
  keyword("packet"),
  // A front matter block also starts with dashes, so Langium lets it win over a dash.
  TokenType(name = "-", pattern = "-", longer = listOf(YAML)),
  TokenType(name = "+", pattern = "+"),
  TokenType(name = ":", pattern = ":"),
  ACC_DESCR,
  ACC_TITLE,
  TITLE,
  TokenType(name = "INT", pattern = JsRegExp("""0|[1-9][0-9]*(?!\.)""", "y")),
  STRING,
  NEWLINE,
  WHITESPACE,
  YAML,
  DIRECTIVE,
  SINGLE_LINE_COMMENT,
)

class PacketBlockAst(
  var start: Double? = null,
  var end: Double? = null,
  var bits: Double? = null,
  var label: String,
)

class PacketAst(
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val blocks: MutableList<PacketBlockAst> = ArrayList(),
)

fun parsePacket(src: String): PacketAst {
  val r = Reader(tokenize(src, PACKET_TOKENS, "packet"), PACKET_TOKENS, "packet")
  val ast = PacketAst()

  val endOfLine = {
    if (r.kind != -1) {
      if (r.kind != T.newline) r.fail("a line break or the end of input")
      while (r.kind == T.newline) r.i++
    }
  }

  while (r.kind == T.newline) r.i++
  if (!r.accept(T.packetBeta)) r.expect(T.packet)
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
      T.int, T.plus -> {
        val block = PacketBlockAst(label = "")
        if (r.accept(T.plus)) {
          block.bits = jsNumber(r.expect(T.int))
        } else {
          block.start = jsNumber(r.take())
          if (r.accept(T.dash)) block.end = jsNumber(r.expect(T.int))
        }
        r.expect(T.colon)
        block.label = stringValue(r.expect(T.string))
        ast.blocks.add(block)
        endOfLine()
      }
      else -> r.fail("a field, a title, or a line break")
    }
  }
  return ast
}
