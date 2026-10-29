// Ported from Pele src/diagrams/venn/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.venn

// A set (one id) or an overlap of sets (two or more ids, sorted), with its relative area.
class VennSubset(
  val sets: List<String>,
  val size: Double,
  val label: String?,
)

// A line of text shown inside the region of the given sets.
class VennText(
  val sets: List<String>,
  val id: String,
  val label: String?,
)

// Targets are the sorted ids of a set or overlap, or the id of a text.
class VennStyle(
  val targets: List<String>,
  val styles: LinkedHashMap<String, String>,
)

interface VennModel {
  val type: String
  val title: String?
  val subsets: List<VennSubset>
  val textNodes: List<VennText>
  val styles: List<VennStyle>
}
