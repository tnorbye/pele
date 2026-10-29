// Ported from Pele src/diagrams/class/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.js.JsRegExp
import pele.js.charCodeAt
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsTrim
import pele.text.decodeEntities
import pele.util.sanitizeUrl

object LINE_TYPE {
  const val LINE = 0
  const val DOTTED_LINE = 1
}

object RELATION_TYPE {
  const val AGGREGATION = 0
  const val EXTENSION = 1
  const val COMPOSITION = 2
  const val DEPENDENCY = 3
  const val LOLLIPOP = 4
}

// Mermaid looks up ids that start with a digit under this prefix, so they never match a class.
private const val DOM_ID_PREFIX = "classId-"

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

private class SplitName(val className: String, val type: String)

private fun splitName(id: String): SplitName {
  val at = id.indexOf('~')
  if (at <= 0) return SplitName(id, "")
  val end = id.indexOf('~', at + 1)
  return SplitName(id.jsSlice(0, at), id.jsSlice(at + 1, if (end == -1) id.length else end))
}

private fun lookupId(id: String): String {
  val c = id.charCodeAt(0)
  return splitName(if (c >= 48 && c <= 57) DOM_ID_PREFIX + id else id).className
}

class ClassDb : ClassModel {
  override val type = "class"
  val lineType = LINE_TYPE
  val relationType = RELATION_TYPE
  override var direction = "TB"
  override val classes = LinkedHashMap<String, ClassNode>()
  override val relations = ArrayList<ClassRelation>()
  override val notes = LinkedHashMap<String, ClassNote>()
  override val namespaces = LinkedHashMap<String, NamespaceNode>()
  override val interfaces = ArrayList<ClassInterface>()
  override val styleClasses = LinkedHashMap<String, StyleClass>()
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null

  private val namespaceStack = ArrayList<String>()
  private val withCssClass = LinkedHashMap<String, LinkedHashSet<ClassNode>>()

  fun addClass(id: String) {
    val split = splitName(id)
    val className = split.className
    val type = split.type
    if (classes.containsKey(className)) return
    val node =
      ClassNode(
        id = className,
        type = type,
        label = className,
        cssClasses = "default",
        methods = ArrayList(),
        members = ArrayList(),
        annotations = ArrayList(),
        styles = ArrayList(),
      )
    classes[className] = node
    tag(node, "default")
  }

  private fun tag(node: ClassNode, cssClass: String) {
    val tagged = withCssClass[cssClass]
    if (tagged != null) tagged.add(node) else withCssClass[cssClass] = linkedSetOf(node)
  }

  // Returns the class for a name that may carry a generic type, creating it if needed.
  // Mermaid throws a TypeError where this creates, as for an annotation on a class not yet
  // declared.
  private fun ensure(id: String): ClassNode {
    addClass(id)
    return classes[splitName(id).className]!!
  }

  fun setClassLabel(id: String, label: String) {
    ensure(id).label = label
  }

  fun addRelation(relation: ClassRelation) {
    val type1 = relation.relation.type1
    val type2 = relation.relation.type2
    if (type1 == RELATION_TYPE.LOLLIPOP && type2 == RELATION_NONE) {
      addClass(relation.id2)
      relation.id1 = addInterface(relation.id1, relation.id2)
    } else if (type2 == RELATION_TYPE.LOLLIPOP && type1 == RELATION_NONE) {
      addClass(relation.id1)
      relation.id2 = addInterface(relation.id2, relation.id1)
    } else {
      addClass(relation.id1)
      addClass(relation.id2)
    }
    relation.id1 = splitName(relation.id1).className
    relation.id2 = splitName(relation.id2).className
    relation.relationTitle1 = relation.relationTitle1.jsTrim()
    relation.relationTitle2 = relation.relationTitle2.jsTrim()
    relations.add(relation)
  }

  private fun addInterface(label: String, classId: String): String {
    val id = "interface${interfaces.size}"
    interfaces.add(ClassInterface(id, label, classId))
    return id
  }

  fun addAnnotation(className: String, annotation: String) {
    ensure(className).annotations.add(annotation)
  }

  fun addMember(className: String, member: String) {
    val node = ensure(className)
    val text = member.jsTrim()
    if (text.startsWith("<<") && text.endsWith(">>")) {
      node.annotations.add(text.substring(2, text.length - 2))
    } else if (text.indexOf(')') > 0) {
      val parsed = ClassMember(text, "method")
      (if (parsed.memberType == "method") node.methods else node.members).add(parsed)
    } else if (text.isNotEmpty()) {
      node.members.add(ClassMember(text, "attribute"))
    }
  }

  fun addMembers(className: String, members: MutableList<String>) {
    members.reverse()
    for (member in members) addMember(className, member)
  }

  fun addNote(text: String, className: String? = null): String {
    val index = notes.size
    val id = "note$index"
    notes[id] = ClassNote(id, className, text, index)
    return id
  }

  fun cleanupLabel(label: String): String =
    (if (label.startsWith(":")) label.substring(1) else label).jsTrim()

  fun setCssClass(ids: String, className: String) {
    for (id in ids.jsSplit(",")) {
      val node = classes[lookupId(id)] ?: continue
      node.cssClasses += " $className"
      for (word in className.jsSplit(" ")) tag(node, word)
    }
  }

  fun defineClass(ids: List<String>, style: List<String>) {
    for (id in ids) {
      var styleClass = styleClasses[id]
      if (styleClass == null) {
        styleClass = StyleClass(id, ArrayList(), ArrayList())
        styleClasses[id] = styleClass
      }
      for (s in style) {
        if (s.contains("color")) styleClass.textStyles.add(s.jsReplace("fill", "bgFill"))
        styleClass.styles.add(s)
      }
      // Mermaid tests `cssClasses.includes(id)`, which also matches part of a longer name
      // (a classDef named `def` styles every class, through `default`). Whole names are compared
      // here.
      val tagged = withCssClass[id] ?: continue
      val parts = style.flatMap { it.jsSplit(",") }
      for (node in tagged) for (part in parts) node.styles.add(part)
    }
  }

  fun setTooltip(ids: String, tooltip: String? = null) {
    if (tooltip == null) return
    for (id in ids.jsSplit(",")) {
      val node = classes[splitName(id).className]
      if (node != null) node.tooltip = tooltip
    }
  }

  fun setLink(ids: String, link: String, target: String? = null) {
    // Entity codes are resolved first, so that what is checked is what a browser would follow.
    val url = decodeEntities(link).jsTrim()
    for (id in ids.jsSplit(",")) {
      val node = classes[lookupId(id)]
      if (node != null) {
        node.link = if (url.isNotEmpty()) sanitizeUrl(url) else null
        node.linkTarget = target ?: "_blank"
      }
    }
    setCssClass(ids, "clickable")
  }

  // Callbacks are never executed; the class is only marked clickable, as in Mermaid's strict mode.
  fun setClickEvent(ids: String, functionName: String? = null, functionArgs: String? = null) {
    for (id in ids.jsSplit(",")) {
      val node = classes[splitName(id).className]
      if (node != null) node.haveCallback = true
    }
    setCssClass(ids, "clickable")
  }

  @JvmName("applyDirection")
  fun setDirection(dir: String) {
    direction = dir
  }

  @JvmName("applyAccTitle")
  fun setAccTitle(text: String) {
    accTitle = text.jsReplace(RE_LEADING, "")
  }

  fun setAccDescription(text: String) {
    accDescr = text.jsReplace(RE_INDENT, "\n")
  }

  fun addNamespace(id: String, label: String? = null): String {
    val prefix = namespaceStack.lastOrNull()
    val qualified = if (!prefix.isNullOrEmpty()) "$prefix.$id" else id
    namespaceStack.add(qualified)

    val existing = namespaces[qualified]
    if (existing != null) {
      existing.explicit = true
      if (!label.isNullOrEmpty()) existing.label = label
      return qualified
    }

    // The enclosing namespace and its ancestors exist already, so only the parts of `id` are
    // walked.
    var parentId: String? = if (!prefix.isNullOrEmpty()) prefix else null
    var from = if (!prefix.isNullOrEmpty()) prefix.length + 1 else 0
    while (true) {
      val dot = qualified.indexOf('.', from)
      val leaf = dot == -1
      val current = if (leaf) qualified else qualified.jsSlice(0, dot)
      var node = namespaces[current]
      if (node == null) {
        val part = qualified.jsSlice(from, if (leaf) qualified.length else dot)
        node =
          NamespaceNode(
            id = current,
            label = if (leaf && !label.isNullOrEmpty()) label else part,
            classes = LinkedHashMap(),
            notes = LinkedHashMap(),
            children = LinkedHashMap(),
            parent = parentId,
            explicit = leaf,
          )
        namespaces[current] = node
      } else if (leaf) {
        node.explicit = true
      }
      val parent = if (parentId != null) namespaces[parentId] else null
      if (parent != null) {
        if (!parent.children.containsKey(current)) parent.children[current] = node
        if (node.parent == null) node.parent = parentId
      }
      if (leaf) return qualified
      parentId = current
      from = dot + 1
    }
  }

  fun popNamespace() {
    if (namespaceStack.isNotEmpty()) namespaceStack.removeAt(namespaceStack.size - 1)
  }

  fun addClassesToNamespace(id: String, classNames: List<String>, noteNames: List<String>) {
    val namespace = namespaces[id] ?: return
    for (name in classNames) {
      val className = splitName(name).className
      val node = classes[className] ?: continue
      node.parent = id
      namespace.classes[className] = node
    }
    for (name in noteNames) {
      val note = notes[name] ?: continue
      note.parent = id
      namespace.notes[name] = note
    }
  }

  fun setCssStyle(id: String, styles: List<String>) {
    val node = classes[id]
    if (node == null) return
    for (s in styles) for (part in s.jsSplit(",")) node.styles.add(part)
  }
}
