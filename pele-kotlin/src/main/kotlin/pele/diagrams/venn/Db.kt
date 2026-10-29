// Ported from Pele src/diagrams/venn/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.venn

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.jsSlice
import pele.js.jsTrim

// Mermaid takes one pair of quotes off an id, label or style value.
fun normalizeText(text: String): String {
  val trimmed = text.jsTrim()
  return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) trimmed.jsSlice(1, -1) else trimmed
}

private fun normalizeIds(ids: List<String>): List<String> = ids.map { normalizeText(it) }.sorted()

class VennDb : VennModel, IndentHost {
  override val type = "venn"
  override var title: String? = null
  override val subsets: MutableList<VennSubset> = ArrayList()
  override val textNodes: MutableList<VennText> = ArrayList()
  override val styles: MutableList<VennStyle> = ArrayList()
  private val known = LinkedHashSet<String>()
  private var current: List<String>? = null

  // Whether an indented `text` line belongs to the set or union before it. The lexer reads this.
  private var indent = false

  fun setDiagramTitle(title: String) {
    this.title = title
  }

  // A set without a size gets 10, and an overlap of n sets 10 / n².
  fun addSubsetData(ids: List<String>, label: String?, size: Double?) {
    val sets = normalizeIds(ids)
    this.current = sets
    if (sets.size == 1) this.known.add(sets[0])
    this.subsets.add(
      VennSubset(sets, size ?: (10.0 / (ids.size.toDouble() * ids.size)), if (!label.isNullOrEmpty()) normalizeText(label) else null),
    )
  }

  fun addTextData(ids: List<String>, id: String, label: String?) {
    this.textNodes.add(VennText(normalizeIds(ids), normalizeText(id), if (!label.isNullOrEmpty()) normalizeText(label) else null))
  }

  fun addStyleData(ids: List<String>, data: List<Pair<String, String>>) {
    val styles = LinkedHashMap<String, String>()
    for ((key, value) in data) styles[key] = if (value.isNotEmpty()) normalizeText(value) else value
    this.styles.add(VennStyle(normalizeIds(ids), styles))
  }

  fun validateUnionIdentifiers(ids: List<String>) {
    val unknown = ids.map { normalizeText(it) }.filter { !this.known.contains(it) }
    if (unknown.isNotEmpty()) {
      throw PeleError("unknown set identifier: ${unknown.joinToString(", ")}", PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "venn"))
    }
  }

  fun getCurrentSets(): List<String>? = this.current

  override fun getIndentMode(): Boolean = this.indent

  override fun setIndentMode(enabled: Boolean) {
    this.indent = enabled
  }
}
