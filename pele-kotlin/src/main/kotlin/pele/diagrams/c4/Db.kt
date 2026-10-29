// Ported from Pele src/diagrams/c4/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

import pele.js.JsRegExp
import pele.js.jsParseInt
import pele.js.jsReplace

private val ELEMENT_FIELDS: Set<String> = setOf(
  "label",
  "type",
  "descr",
  "techn",
  "sprite",
  "tags",
  "link",
  "bgColor",
  "fontColor",
  "borderColor",
  "shadowing",
  "shape",
  "legendText",
  "legendSprite",
)
private val REL_FIELDS: Set<String> = setOf("label", "descr", "techn", "sprite", "tags", "link", "textColor", "lineColor")

private val RE_INDENT = JsRegExp("""\n\s+""", "g")

// Target is a C4Element or a C4Rel.
private fun setField(target: Any, field: String, value: String) {
  if (target is C4Rel) {
    when (field) {
      "label" -> target.label = value
      "descr" -> target.descr = value
      "techn" -> target.techn = value
      "sprite" -> target.sprite = value
      "tags" -> target.tags = value
      "link" -> target.link = value
      "textColor" -> target.textColor = value
      "lineColor" -> target.lineColor = value
    }
  } else if (target is C4Element) {
    when (field) {
      "label" -> target.label = value
      "type" -> target.type = value
      "descr" -> target.descr = value
      "techn" -> target.techn = value
      "sprite" -> target.sprite = value
      "tags" -> target.tags = value
      "link" -> target.link = value
      "bgColor" -> target.bgColor = value
      "fontColor" -> target.fontColor = value
      "borderColor" -> target.borderColor = value
      "shadowing" -> target.shadowing = value
      "shape" -> target.shape = value
      "legendText" -> target.legendText = value
      "legendSprite" -> target.legendSprite = value
    }
  }
}

// Mermaid assigns a named argument to whatever property it names, structural ones included.
// Here it reaches only the fields above; any other name is kept aside in `extra`.
private fun named(target: Any, attr: C4Named) {
  val key = attr.key
  val value = attr.value
  if (target is C4Rel && key == "offsetX") {
    target.offsetX = jsParseInt(value)
  } else if (target is C4Rel && key == "offsetY") {
    target.offsetY = jsParseInt(value)
  } else if ((if (target is C4Rel) key in REL_FIELDS else key in ELEMENT_FIELDS)) {
    setField(target, key, value)
  } else if (target is C4Rel) {
    val extra = target.extra ?: LinkedHashMap<String, String>().also { target.extra = it }
    extra[key] = value
  } else if (target is C4Element) {
    val extra = target.extra ?: LinkedHashMap<String, String>().also { target.extra = it }
    extra[key] = value
  }
}

// A positional argument fills the field of its slot. A named one fills the field it names, whichever slot it is in.
private fun put(target: Any, field: String, attr: C4Attr?) {
  if (attr == null) return
  if (attr is String) setField(target, field, attr)
  else named(target, attr as C4Named)
}

private fun text(target: Any, field: String, attr: C4Attr?) {
  if (attr == null) {
    if (target is C4Rel) {
      if (field == "descr") {
        if (target.descr == null) target.descr = ""
      } else if (target.techn == null) target.techn = ""
    } else if (target is C4Element) {
      if (field == "descr") {
        if (target.descr == null) target.descr = ""
      } else if (target.techn == null) target.techn = ""
    }
  } else {
    put(target, field, attr)
  }
}

private fun label(target: Any, attr: C4Attr?) {
  if (target is C4Rel) target.labelAttr = null else if (target is C4Element) target.labelAttr = null
  if (attr is C4Named) {
    if (target is C4Rel) {
      target.label = ""
      target.labelAttr = attr
    } else if (target is C4Element) {
      target.label = ""
      target.labelAttr = attr
    }
    named(target, attr)
  } else {
    val value = (attr as String?) ?: ""
    if (target is C4Rel) target.label = value else if (target is C4Element) target.label = value
  }
}

class C4Db : C4Model {
  override val type = "c4"
  override var c4Type: String? = null
    private set
  override var title: String? = null
    private set
  override var accTitle: String? = null
  override var accDescr: String? = null
  override val shapes: MutableList<C4Shape> = ArrayList()
  override val boundaries: MutableList<C4Boundary> = arrayListOf(
    C4Boundary().also {
      it.alias = "global"
      it.label = "global"
      it.type = "global"
    },
  )
  override val rels: MutableList<C4Rel> = ArrayList()
  override var shapeInRow: Double = 4.0
  override var boundaryInRow: Double = 2.0

  private val shapeIndex = LinkedHashMap<String, C4Shape>()

  // The top-level boundary is not listed, so a boundary a diagram names `global` is its own box.
  // In Mermaid it replaces the top level and nothing is drawn.
  private val boundaryIndex = LinkedHashMap<String, C4Boundary>()
  private val relIndex = LinkedHashMap<String, LinkedHashMap<String, C4Rel>>()
  private var current: C4Boundary = boundaries[0]
  private val stack = ArrayList<C4Boundary>()

  fun setC4Type(type: String) {
    this.c4Type = type
  }

  fun setTitle(title: String) {
    this.title = title
  }

  fun setAccDescription(descr: String) {
    this.accDescr = descr.jsReplace(RE_INDENT, "\n")
  }

  private fun <E : C4Element> element(list: MutableList<E>, index: MutableMap<String, E>, alias: C4Attr, title: C4Attr?, make: () -> E): E {
    var el = if (alias is String) index[alias] else null
    if (el == null) {
      el = make()
      list.add(el)
      if (alias is String) {
        el.alias = alias
        index[alias] = el
      } else {
        el.aliasAttr = alias as C4Named
        named(el, alias)
      }
    }
    label(el, title)
    return el
  }

  // alias, label, ?descr, ?sprite, ?tags, $link
  fun addPersonOrSystem(kind: String, attrs: List<C4Attr>) {
    val shape = element(shapes, shapeIndex, attrs[0], attrs.getOrNull(1)) { C4Shape() }
    text(shape, "descr", attrs.getOrNull(2))
    finishShape(shape, kind, attrs, 3)
  }

  // alias, label, ?techn, ?descr, ?sprite, ?tags, $link
  fun addContainer(kind: String, attrs: List<C4Attr>) {
    val shape = element(shapes, shapeIndex, attrs[0], attrs.getOrNull(1)) { C4Shape() }
    text(shape, "techn", attrs.getOrNull(2))
    text(shape, "descr", attrs.getOrNull(3))
    finishShape(shape, kind, attrs, 4)
  }

  fun addComponent(kind: String, attrs: List<C4Attr>) {
    addContainer(kind, attrs)
  }

  private fun finishShape(shape: C4Shape, kind: String, attrs: List<C4Attr>, at: Int) {
    put(shape, "sprite", attrs.getOrNull(at))
    put(shape, "tags", attrs.getOrNull(at + 1))
    put(shape, "link", attrs.getOrNull(at + 2))
    shape.kind = kind
    shape.parent = this.current
  }

  private fun boundary(attrs: List<C4Attr>, fallback: String): C4Boundary {
    val boundary = element(boundaries, boundaryIndex, attrs[0], attrs.getOrNull(1)) { C4Boundary() }
    if (attrs.getOrNull(2) == null) boundary.type = fallback
    else put(boundary, "type", attrs[2])
    return boundary
  }

  private fun open(boundary: C4Boundary) {
    boundary.parent = this.current
    this.stack.add(this.current)
    this.current = boundary
  }

  // alias, label, ?type, ?tags, $link
  fun addPersonOrSystemBoundary(attrs: List<C4Attr>, fallback: String = "system") {
    val boundary = boundary(attrs, fallback)
    put(boundary, "tags", attrs.getOrNull(3))
    put(boundary, "link", attrs.getOrNull(4))
    open(boundary)
  }

  fun addContainerBoundary(attrs: List<C4Attr>) {
    addPersonOrSystemBoundary(attrs, "container")
  }

  // alias, label, ?type, ?descr, ?sprite, ?tags, $link
  fun addDeploymentNode(nodeType: String, attrs: List<C4Attr>) {
    val boundary = boundary(attrs, "node")
    text(boundary, "descr", attrs.getOrNull(3))
    // Mermaid drops whatever is in the sprite slot, a named argument included.
    put(boundary, "sprite", attrs.getOrNull(4))
    put(boundary, "tags", attrs.getOrNull(5))
    put(boundary, "link", attrs.getOrNull(6))
    boundary.nodeType = nodeType
    open(boundary)
  }

  fun popBoundaryParseStack() {
    this.current = if (this.stack.isEmpty()) this.boundaries[0] else this.stack.removeAt(this.stack.size - 1)
  }

  // from, to, label, ?techn, ?descr, ?sprite, ?tags, $link
  fun addRel(type: String, attrs: List<C4Attr>) {
    val from = attrs.getOrNull(0)
    val to = attrs.getOrNull(1)
    if (from == null || to == null || attrs.getOrNull(2) == null) return
    val known = from is String && to is String
    var rel = if (known) this.relIndex[from as String]?.get(to as String) else null
    if (rel == null) {
      rel = C4Rel(type, "", "", "")
      this.rels.add(rel)
      if (known) {
        var targets = this.relIndex[from as String]
        if (targets == null) {
          targets = LinkedHashMap()
          this.relIndex[from] = targets
        }
        targets[to as String] = rel
      }
      if (from is String) rel.from = from
      else rel.fromAttr = from as C4Named
      if (to is String) rel.to = to
      else rel.toAttr = to as C4Named
    }
    rel.type = type
    label(rel, attrs[2])
    text(rel, "techn", attrs.getOrNull(3))
    text(rel, "descr", attrs.getOrNull(4))
    put(rel, "sprite", attrs.getOrNull(5))
    put(rel, "tags", attrs.getOrNull(6))
    put(rel, "link", attrs.getOrNull(7))
  }

  fun getC4Shape(alias: String): C4Element? {
    return this.shapeIndex[alias] ?: this.boundaryIndex[alias]
  }

  // elementName, ?bgColor, ?fontColor, ?borderColor, ?shadowing, ?shape, ?sprite, ?techn, ?legendText, ?legendSprite
  fun updateElStyle(@Suppress("UNUSED_PARAMETER") type: String, attrs: List<C4Attr>) {
    val first = attrs.getOrNull(0)
    val el = if (first is String) this.getC4Shape(first) else null
    if (el == null) return
    put(el, "bgColor", attrs.getOrNull(1))
    put(el, "fontColor", attrs.getOrNull(2))
    put(el, "borderColor", attrs.getOrNull(3))
    put(el, "shadowing", attrs.getOrNull(4))
    put(el, "shape", attrs.getOrNull(5))
    put(el, "sprite", attrs.getOrNull(6))
    put(el, "techn", attrs.getOrNull(7))
    put(el, "legendText", attrs.getOrNull(8))
    put(el, "legendSprite", attrs.getOrNull(9))
  }

  // from, to, ?textColor, ?lineColor, ?offsetX, ?offsetY
  fun updateRelStyle(@Suppress("UNUSED_PARAMETER") type: String, attrs: List<C4Attr>) {
    val from = attrs.getOrNull(0)
    val to = attrs.getOrNull(1)
    val rel = if (from is String && to is String) this.relIndex[from]?.get(to) else null
    if (rel == null) return
    put(rel, "textColor", attrs.getOrNull(2))
    put(rel, "lineColor", attrs.getOrNull(3))
    val x = attrs.getOrNull(4)
    val y = attrs.getOrNull(5)
    if (x is String) rel.offsetX = jsParseInt(x)
    else if (x != null) named(rel, x as C4Named)
    if (y is String) rel.offsetY = jsParseInt(y)
    else if (y != null) named(rel, y as C4Named)
  }

  // ?c4ShapeInRow, ?c4BoundaryInRow. As in Mermaid, the slot decides which one a value sets, not its name.
  fun updateLayoutConfig(@Suppress("UNUSED_PARAMETER") type: String, attrs: List<C4Attr>) {
    fun count(attr: C4Attr?): Double =
      if (attr == null) Double.NaN else jsParseInt(if (attr is String) attr else (attr as C4Named).value)
    val shapes = count(attrs.getOrNull(0))
    val boundaries = count(attrs.getOrNull(1))
    if (shapes >= 1) this.shapeInRow = shapes
    if (boundaries >= 1) this.boundaryInRow = boundaries
  }
}
