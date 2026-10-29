// Ported from Pele src/diagrams/requirement/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSplit

object RequirementType {
  const val REQUIREMENT = "Requirement"
  const val FUNCTIONAL_REQUIREMENT = "Functional Requirement"
  const val INTERFACE_REQUIREMENT = "Interface Requirement"
  const val PERFORMANCE_REQUIREMENT = "Performance Requirement"
  const val PHYSICAL_REQUIREMENT = "Physical Requirement"
  const val DESIGN_CONSTRAINT = "Design Constraint"
}

object RiskLevel {
  const val LOW_RISK = "Low"
  const val MED_RISK = "Medium"
  const val HIGH_RISK = "High"
}

object VerifyType {
  const val VERIFY_ANALYSIS = "Analysis"
  const val VERIFY_DEMONSTRATION = "Demonstration"
  const val VERIFY_INSPECTION = "Inspection"
  const val VERIFY_TEST = "Test"
}

object Relationships {
  const val CONTAINS = "contains"
  const val COPIES = "copies"
  const val DERIVES = "derives"
  const val SATISFIES = "satisfies"
  const val VERIFIES = "verifies"
  const val REFINES = "refines"
  const val TRACES = "traces"
}

// Applying a class copies its declarations onto each node, so a short text can ask for
// hundreds of millions of copies. Past this many the diagram is refused.
private const val MAX_STYLE_COPIES = 1_000_000

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

class RequirementDb : RequirementModel {
  override val type = "requirement"
  override var direction = "TB"
  override val requirements = LinkedHashMap<String, Requirement>()
  override val elements = LinkedHashMap<String, RequirementElement>()
  override val relations = ArrayList<Relation>()
  override val classes = LinkedHashMap<String, RequirementClass>()
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null

  // The grammar reports a body's fields before the requirement or element they belong to.
  private var newId = ""
  private var newText = ""
  private var newRisk = ""
  private var newVerifyMethod = ""
  private var newElementType = ""
  private var newDocRef = ""
  private var styleCopies = 0

  private fun copy(target: MutableList<String>, styles: List<String>) {
    styleCopies += styles.size
    if (styleCopies > MAX_STYLE_COPIES) {
      throw PeleError(
        "Requirement diagram applies too many style declarations.",
        PeleErrorCode.LIMIT,
        PeleErrorInfo(type = "requirement"),
      )
    }
    for (style in styles) target.add(style)
  }

  @JvmName("applyDirection")
  fun setDirection(dir: String) {
    direction = dir
  }

  fun addRequirement(name: String, type: String): Requirement {
    var requirement = requirements[name]
    if (requirement == null) {
      requirement =
        Requirement(
          name = name,
          type = type,
          requirementId = newId,
          text = newText,
          risk = newRisk,
          verifyMethod = newVerifyMethod,
          cssStyles = ArrayList(),
          classes = arrayListOf("default"),
        )
      requirements[name] = requirement
    }
    newId = ""
    newText = ""
    newRisk = ""
    newVerifyMethod = ""
    return requirement
  }

  fun setNewReqId(id: String) {
    newId = id
  }

  fun setNewReqText(text: String) {
    newText = text
  }

  fun setNewReqRisk(risk: String) {
    newRisk = risk
  }

  fun setNewReqVerifyMethod(verifyMethod: String) {
    newVerifyMethod = verifyMethod
  }

  fun addElement(name: String): RequirementElement {
    var element = elements[name]
    if (element == null) {
      element =
        RequirementElement(name, newElementType, newDocRef, ArrayList(), arrayListOf("default"))
      elements[name] = element
    }
    newElementType = ""
    newDocRef = ""
    return element
  }

  fun setNewElementType(type: String) {
    newElementType = type
  }

  fun setNewElementDocRef(docRef: String) {
    newDocRef = docRef
  }

  fun addRelationship(type: String, src: String, dst: String) {
    relations.add(Relation(type, src, dst))
  }

  // PORT: a Requirement or a RequirementElement.
  private fun node(id: String): Any? = requirements[id] ?: elements[id]

  private fun cssStylesOf(node: Any): MutableList<String> =
    if (node is Requirement) node.cssStyles else (node as RequirementElement).cssStyles

  private fun classesOf(node: Any): MutableList<String> =
    if (node is Requirement) node.classes else (node as RequirementElement).classes

  fun setCssStyle(ids: List<String>, styles: List<String>) {
    for (id in ids) {
      val node = node(id)
      // Mermaid stops at the first unknown id, leaving the rest of the list unstyled.
      if (node == null) return
      for (s in styles) {
        copy(cssStylesOf(node), if (s.contains(",")) s.jsSplit(",") else listOf(s))
      }
    }
  }

  fun setClass(ids: List<String>, classNames: List<String>) {
    for (id in ids) {
      val node = node(id)
      if (node == null) continue
      for (name in classNames) {
        classesOf(node).add(name)
        val styles = classes[name]?.styles
        if (styles != null) copy(cssStylesOf(node), styles)
      }
    }
  }

  fun defineClass(ids: List<String>, style: List<String>) {
    for (id in ids) {
      var styleClass = classes[id]
      if (styleClass == null) {
        styleClass = RequirementClass(id, ArrayList(), ArrayList())
        classes[id] = styleClass
      }
      for (s in style) {
        if (s.contains("color")) styleClass.textStyles.add(s.jsReplace("fill", "bgFill"))
        styleClass.styles.add(s)
      }
      val split = style.flatMap { s -> s.jsSplit(",") }
      for (requirement in requirements.values) {
        if (requirement.classes.contains(id)) copy(requirement.cssStyles, split)
      }
      for (element in elements.values) {
        if (element.classes.contains(id)) copy(element.cssStyles, split)
      }
    }
  }

  @JvmName("applyAccTitle")
  fun setAccTitle(text: String) {
    accTitle = text.jsReplace(RE_LEADING, "")
  }

  @JvmName("applyAccDescription")
  fun setAccDescription(text: String) {
    accDescr = text.jsReplace(RE_INDENT, "\n")
  }
}
