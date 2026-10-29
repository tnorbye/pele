// Ported from Pele src/diagrams/er/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsTrim

private fun labelType(type: String?): String =
  if (type == "markdown" || type == "string" || type == "text") type else "markdown"

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

class ErDb : ErModel {
  override val type = "er"
  override var direction = "TB"
  override val entities = LinkedHashMap<String, ErEntity>()
  override val relationships = ArrayList<ErRelationship>()
  override val classes = LinkedHashMap<String, ErClassDef>()
  override val subgraphs = ArrayList<ErSubgraph>()
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  private val subgraphLookup = LinkedHashMap<String, ErSubgraph>()
  private val claimed = LinkedHashSet<String>()

  fun addEntity(name: String, alias: String = ""): ErEntity {
    var entity = entities[name]
    if (entity == null) {
      entity = ErEntity(
        id = "entity-$name-${entities.size}",
        label = name,
        alias = alias,
        attributes = ArrayList(),
        cssClasses = "default",
        cssStyles = ArrayList(),
      )
      entities[name] = entity
    } else if (entity.alias.isEmpty() && alias.isNotEmpty()) {
      entity.alias = alias
    }
    return entity
  }

  // The grammar builds the list back to front, so the last attribute written comes first.
  fun addAttributes(name: String, attributes: List<ParsedAttribute>) {
    val entity = addEntity(name)
    for (i in attributes.indices.reversed()) {
      val attribute = attributes[i]
      if (attribute.keys == null) attribute.keys = emptyList()
      if (attribute.comment == null) attribute.comment = ""
      entity.attributes.add(ErAttribute(attribute.type, attribute.name, attribute.keys!!, attribute.comment!!))
    }
  }

  fun addRelationship(entA: String, roleA: String, entB: String, relSpec: RelSpec) {
    val entityA = if (subgraphLookup.containsKey(entA)) entA else addEntity(entA).id
    val entityB = if (subgraphLookup.containsKey(entB)) entB else addEntity(entB).id
    relationships.add(ErRelationship(entityA, roleA, entityB, relSpec))
  }

  @JvmName("applyDirection")
  fun setDirection(dir: String) {
    direction = dir
  }

  fun addCssStyles(ids: List<String>, styles: List<String>?) {
    if (styles == null) return
    for (id in ids) {
      val entity = entities[id]
      if (entity != null) for (style in styles) entity.cssStyles.add(style)
      val subgraph = subgraphLookup[id]
      if (subgraph != null) for (style in styles) subgraph.cssStyles.add(style)
    }
  }

  fun addClass(ids: List<String>, styles: List<String>?) {
    for (id in ids) {
      var def = classes[id]
      if (def == null) {
        def = ErClassDef(id, ArrayList(), ArrayList())
        classes[id] = def
      }
      if (styles == null) continue
      for (style in styles) {
        if (style.contains("color")) def.textStyles.add(style.jsReplace("fill", "bgFill"))
        def.styles.add(style)
      }
    }
  }

  fun setClass(ids: List<String>, classNames: List<String>) {
    for (id in ids) {
      val entity = entities[id]
      if (entity != null) for (name in classNames) entity.cssClasses += " " + name
      val subgraph = subgraphLookup[id]
      if (subgraph != null) for (name in classNames) subgraph.classes.add(name)
    }
  }

  // PORT: `{ text }` and `{ text, type }` objects are passed as their fields.
  fun addSubGraph(idText: String, list: List<DocItem>, titleText: String, titleType: String? = null): String {
    val id = idText.jsTrim()
    val seen = LinkedHashSet<String>()
    val nodes = ArrayList<String>()
    var dir: String? = null
    for (item in list) {
      if (item !is String) {
        if (item is DirStmt) dir = item.value
        continue
      }
      val trimmed = item.jsTrim()
      if (trimmed == "" || seen.contains(trimmed)) continue
      seen.add(trimmed)
      // A node belongs to the first subgraph that lists it.
      if (!claimed.contains(item)) nodes.add(item)
    }
    for (node in nodes) claimed.add(node)
    val subgraph = ErSubgraph(
      id = id,
      nodes = nodes,
      title = titleText.jsTrim(),
      classes = ArrayList(),
      cssStyles = ArrayList(),
      dir = dir,
      labelType = labelType(titleType),
    )
    subgraphs.add(subgraph)
    subgraphLookup[id] = subgraph
    return id
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
