// Ported from Pele src/diagrams/ishikawa/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.ishikawa

class IshikawaNode(
  val text: String,
  val children: MutableList<IshikawaNode>,
)

// The root is the effect; its children are the categories of causes, theirs the causes, and so on.
interface IshikawaModel {
  val type: String
  val title: String?
  val root: IshikawaNode?
}
