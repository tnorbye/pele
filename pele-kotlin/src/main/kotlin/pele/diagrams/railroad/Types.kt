// Ported from Pele src/diagrams/railroad/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.railroad

import pele.svg.Accessible

sealed class RailroadNode : Parent {
  abstract val type: String

  class Terminal(val value: String) : RailroadNode() {
    override val type = "terminal"
  }

  class Nonterminal(val name: String) : RailroadNode() {
    override val type = "nonterminal"
  }

  class Special(val text: String) : RailroadNode() {
    override val type = "special"
  }

  class Sequence(override val elements: List<RailroadNode>) : RailroadNode() {
    override val type = "sequence"
  }

  class Choice(override val alternatives: List<RailroadNode>) : RailroadNode() {
    override val type = "choice"
  }

  class Optional(override val element: RailroadNode) : RailroadNode() {
    override val type = "optional"
  }

  // max is Infinity when the count has no upper bound.
  class Repetition(override val element: RailroadNode, val min: Double, val max: Double) : RailroadNode() {
    override val type = "repetition"
  }
}

class RailroadRule(
  val name: String,
  val definition: RailroadNode,
)

// Which of Mermaid's four grammars the text was written in: "railroad", "ebnf", "abnf" or "peg".
typealias Notation = String

interface RailroadModel : Accessible {
  val type: String
  val notation: Notation
  val title: String?
  override val accTitle: String?
  override val accDescr: String?

  // Every rule in source order. A name defined twice appears twice.
  val rules: List<RailroadRule>
}
