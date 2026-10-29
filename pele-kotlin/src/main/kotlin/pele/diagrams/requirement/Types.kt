// Ported from Pele src/diagrams/requirement/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

import pele.svg.Accessible

class Requirement(
  val name: String,
  val type: String,
  val requirementId: String,
  val text: String,
  val risk: String,
  val verifyMethod: String,
  val cssStyles: MutableList<String>,
  val classes: MutableList<String>,
)

class RequirementElement(
  val name: String,
  val type: String,
  val docRef: String,
  val cssStyles: MutableList<String>,
  val classes: MutableList<String>,
)

class Relation(
  val type: String,
  val src: String,
  val dst: String,
)

class RequirementClass(
  val id: String,
  val styles: MutableList<String>,
  val textStyles: MutableList<String>,
)

interface RequirementModel : Accessible {
  val type: String
  val direction: String
  val requirements: Map<String, Requirement>
  val elements: Map<String, RequirementElement>
  val relations: List<Relation>
  val classes: Map<String, RequirementClass>
  val title: String?
  override val accTitle: String?
  override val accDescr: String?
}
