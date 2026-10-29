// Ported from Pele src/diagrams/railroad/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace

class RailroadDb : RailroadModel {
  override val type = "railroad"
  override var notation: Notation = "railroad"
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  override val rules: MutableList<RailroadRule> = ArrayList()
  private val byName = LinkedHashMap<String, RailroadRule>()

  @JvmName("setTitleText") // PORT: avoids the property setter signature
  fun setTitle(text: String) {
    this.title = text
  }

  @JvmName("setAccTitleText") // PORT: avoids the property setter signature
  fun setAccTitle(text: String) {
    this.accTitle = text.jsReplace(RE_LEADING, "")
  }

  fun setAccDescription(text: String) {
    this.accDescr = text.jsReplace(RE_INDENT, "\n")
  }

  // A name defined again is drawn again; looking the name up gives the later definition.
  fun addRule(rule: RailroadRule) {
    this.rules.add(rule)
    this.byName[rule.name] = rule
  }

  fun getRule(name: String): RailroadRule? = this.byName[name]
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")
private val RE_KEYWORD = JsRegExp("""^\s*railroad-(ebnf-|abnf-|peg-)?beta""", "i")

// Mermaid has a detector per keyword, each matching without regard to case.
fun notationOf(source: String): Notation? {
  val m = RE_KEYWORD.exec(source) ?: return null
  val infix = m[1]?.lowercase()
  return if (infix == "ebnf-") "ebnf" else if (infix == "abnf-") "abnf" else if (infix == "peg-") "peg" else "railroad"
}

private fun grammar(source: String, notation: Notation): Pair<Grammar<*>, List<RailroadRule>> {
  return when (notation) {
    "ebnf" -> {
      val ast = parseEbnf(source)
      ast to ebnfRules(ast)
    }
    "abnf" -> {
      val ast = parseAbnf(source)
      ast to abnfRules(ast)
    }
    "peg" -> {
      val ast = parsePeg(source)
      ast to pegRules(ast)
    }
    else -> {
      val ast = parseIr(source)
      ast to irRules(ast)
    }
  }
}

// Parses the text in one notation and loads the result into the model, as Mermaid's four parsers do.
fun populate(db: RailroadDb, source: String, notation: Notation) {
  val (ast, rules) = grammar(source, notation)
  db.notation = notation
  if (!ast.accDescr.isNullOrEmpty()) db.setAccDescription(ast.accDescr!!)
  if (!ast.accTitle.isNullOrEmpty()) db.setAccTitle(ast.accTitle!!)
  if (!ast.title.isNullOrEmpty()) db.setTitle(ast.title!!)
  for (rule in rules) db.addRule(rule)
}

fun parseRailroad(source: String, title: String?): RailroadDb {
  val notation = notationOf(source)
    ?: throw PeleError("No railroad diagram keyword found.", PeleErrorCode.SYNTAX, PeleErrorInfo(type = "railroad"))
  val db = RailroadDb()
  if (!title.isNullOrEmpty()) db.title = title
  populate(db, source, notation)
  return db
}
