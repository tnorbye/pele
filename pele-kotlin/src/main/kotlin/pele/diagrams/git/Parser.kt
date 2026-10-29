// Ported from Pele src/diagrams/git/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.git

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
import pele.diagrams.common.keyword
import pele.diagrams.common.stringValue
import pele.diagrams.common.titleBefore
import pele.diagrams.common.titleValue
import pele.diagrams.common.tokenize
import pele.js.JsRegExp
import pele.js.jsParseInt

private object T {
  const val cherryPick = 0
  const val gitGraphColon = 1
  const val highlight = 2
  const val gitGraph = 3
  const val checkout = 4
  const val reverse = 5
  const val parent = 6
  const val commit = 7
  const val normal = 8
  const val branch = 9
  const val order = 10
  const val switch = 11
  const val type = 12
  const val merge = 13
  const val msg = 14
  const val tag = 15
  const val id = 16
  const val lr = 17
  const val tb = 18
  const val bt = 19
  const val colon = 20
  const val accDescr = 21
  const val accTitle = 22
  const val title = 23
  const val int = 24
  const val string = 25
  const val newline = 26
  const val reference = 31
}

private val REFERENCE = TokenType(name = "REFERENCE", pattern = JsRegExp("""\w([-\.\/\w]*[-\w])?""", "y"))
private val NAME = listOf(REFERENCE)

// A keyword that is the start of a longer name is part of that name: `commits` is a branch.
private fun word(name: String): TokenType = TokenType(name = name, pattern = name, longer = NAME)

val GIT_TOKENS: List<TokenType> = listOf(
  word("cherry-pick"),
  TokenType(name = "gitGraph:", pattern = "gitGraph:"),
  word("HIGHLIGHT"),
  keyword("gitGraph").copy(longer = NAME),
  word("checkout"),
  word("REVERSE"),
  TokenType(name = "parent:", pattern = "parent:"),
  word("commit"),
  word("NORMAL"),
  word("branch"),
  TokenType(name = "order:", pattern = "order:"),
  word("switch"),
  TokenType(name = "type:", pattern = "type:"),
  word("merge"),
  TokenType(name = "msg:", pattern = "msg:"),
  TokenType(name = "tag:", pattern = "tag:"),
  TokenType(name = "id:", pattern = "id:"),
  word("LR"),
  word("TB"),
  word("BT"),
  TokenType(name = ":", pattern = ":"),
  // Without a closing brace the block form scans to the end of the text, and a brace is not a
  // token on its own, so the first failure is final.
  ACC_DESCR.copy(opener = JsRegExp("""[\t ]*accDescr\s*{""", "y")),
  ACC_TITLE,
  titleBefore("""[-./\w]*[-\w]"""),
  TokenType(name = "INT", pattern = JsRegExp("""0|[1-9][0-9]*(?!\.)""", "y")),
  STRING,
  NEWLINE,
  WHITESPACE,
  YAML,
  DIRECTIVE,
  SINGLE_LINE_COMMENT,
  REFERENCE,
)

// 'NORMAL' | 'REVERSE' | 'HIGHLIGHT'
typealias CommitTypeName = String

class CommitAst(
  var id: String? = null,
  var message: String? = null,
  val tags: MutableList<String> = ArrayList(),
  var type: CommitTypeName? = null,
) : StatementAst() {
  override val `$type` = "Commit"
}

class BranchAst(
  val name: String,
  var order: Double? = null,
) : StatementAst() {
  override val `$type` = "Branch"
}

class MergeAst(
  val branch: String,
  var id: String? = null,
  val tags: MutableList<String> = ArrayList(),
  var type: CommitTypeName? = null,
) : StatementAst() {
  override val `$type` = "Merge"
}

class CheckoutAst(
  val branch: String,
) : StatementAst() {
  override val `$type` = "Checkout"
}

class CherryPickingAst(
  var id: String? = null,
  val tags: MutableList<String> = ArrayList(),
  var parent: String? = null,
) : StatementAst() {
  override val `$type` = "CherryPicking"
}

sealed class StatementAst {
  abstract val `$type`: String
}

// Langium names the root after the `Direction` rule when the header gives a direction.
class GitGraphAst(
  // 'GitGraph' | 'Direction'
  var `$type`: String,
  // 'LR' | 'TB' | 'BT'
  var dir: String? = null,
  var title: String? = null,
  var accTitle: String? = null,
  var accDescr: String? = null,
  val statements: MutableList<StatementAst> = ArrayList(),
)

fun parseGit(src: String): GitGraphAst {
  val r = Reader(tokenize(src, GIT_TOKENS, "gitGraph"), GIT_TOKENS, "gitGraph")
  val ast = GitGraphAst("GitGraph")

  fun endOfLine() {
    if (r.kind == -1) return
    if (r.kind != T.newline) r.fail("a line break or the end of input")
    while (r.kind == T.newline) r.i++
  }
  fun name(): String {
    if (r.kind == T.reference) return r.take()
    if (r.kind == T.string) return stringValue(r.take())
    return r.fail("a branch name")
  }
  fun string(): String = stringValue(r.expect(T.string))
  fun commitType(): CommitTypeName {
    if (r.kind != T.normal && r.kind != T.reverse && r.kind != T.highlight) r.fail("NORMAL, REVERSE, or HIGHLIGHT")
    return r.take()
  }

  while (r.kind == T.newline) r.i++
  if (!r.accept(T.gitGraphColon)) {
    r.expect(T.gitGraph)
    val next = r.kinds.getOrNull(r.i + 1)
    if ((r.kind == T.lr || r.kind == T.tb || r.kind == T.bt) && next == T.colon) {
      ast.`$type` = "Direction"
      ast.dir = r.take()
      r.i++
    } else {
      r.accept(T.colon)
    }
  }

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
      T.commit -> {
        r.i++
        val node = CommitAst()
        while (true) {
          if (r.accept(T.id)) node.id = string()
          else if (r.accept(T.tag)) node.tags.add(string())
          else if (r.accept(T.type)) node.type = commitType()
          else if (r.accept(T.msg) || r.kinds.getOrNull(r.i) == T.string) node.message = string()
          else break
        }
        endOfLine()
        ast.statements.add(node)
      }
      T.branch -> {
        r.i++
        val node = BranchAst(name())
        if (r.accept(T.order)) node.order = jsParseInt(r.expect(T.int))
        endOfLine()
        ast.statements.add(node)
      }
      T.merge -> {
        r.i++
        val node = MergeAst(name())
        while (true) {
          if (r.accept(T.id)) node.id = string()
          else if (r.accept(T.tag)) node.tags.add(string())
          else if (r.accept(T.type)) node.type = commitType()
          else break
        }
        endOfLine()
        ast.statements.add(node)
      }
      T.checkout, T.switch -> {
        r.i++
        val node = CheckoutAst(name())
        endOfLine()
        ast.statements.add(node)
      }
      T.cherryPick -> {
        r.i++
        val node = CherryPickingAst()
        while (true) {
          if (r.accept(T.id)) node.id = string()
          else if (r.accept(T.tag)) node.tags.add(string())
          else if (r.accept(T.parent)) node.parent = string()
          else break
        }
        endOfLine()
        ast.statements.add(node)
      }
      else -> r.fail("a statement, a title, or a line break")
    }
  }
  return ast
}
