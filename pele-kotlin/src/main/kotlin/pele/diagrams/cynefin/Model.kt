// Ported from Pele src/diagrams/cynefin/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.cynefin

import pele.js.JsRegExp
import pele.js.jsReplace
import pele.svg.Accessible

// DomainName is "complex" | "complicated" | "clear" | "chaotic" | "confusion".

class CynefinItem(val label: String)

class CynefinDomain(val name: String, val items: List<CynefinItem>)

class CynefinTransition(
  val from: String,
  val to: String,
  val label: String? = null,
)

class CynefinModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  // The domains the source declares. All five are drawn either way.
  val domains: Map<String, CynefinDomain>,
  val transitions: List<CynefinTransition>,
) : Accessible

interface DomainBlock {
  val domain: String
  val items: List<DomainItemAst>?
}

interface Transition {
  val from: String
  val to: String
  val label: String?
}

class CynefinDb {
  var domains: MutableMap<String, CynefinDomain> = LinkedHashMap()
    private set

  var transitions: List<CynefinTransition> = ArrayList()
    private set

  // A later block for a domain replaces an earlier one.
  @JvmName("setDomainBlocks")
  fun setDomains(blocks: List<DomainBlock>?) {
    if (blocks == null) return
    for (block in blocks) {
      val name = block.domain
      this.domains[name] =
        CynefinDomain(name, (block.items ?: emptyList()).map { item -> CynefinItem(item.label) })
    }
  }

  @JvmName("setTransitionList")
  fun setTransitions(transitions: List<Transition>?) {
    if (transitions == null) return
    this.transitions =
      transitions
        .filter { t -> t.from != t.to }
        .map { t ->
          CynefinTransition(t.from, t.to, if (t.label.isNullOrEmpty()) null else t.label)
        }
  }
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun buildCynefin(ast: CynefinAst, title: String?): CynefinModel {
  val db = CynefinDb()
  db.setDomains(ast.domains)
  db.setTransitions(ast.transitions)
  return CynefinModel(
    type = "cynefin",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle =
      if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr =
      if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    domains = db.domains,
    transitions = db.transitions,
  )
}
