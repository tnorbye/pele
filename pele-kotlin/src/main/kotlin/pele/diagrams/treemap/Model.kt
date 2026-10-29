// Ported from Pele src/diagrams/treemap/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treemap

import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSplit
import pele.svg.Accessible

class TreemapNode(
  val name: String,
  // Sections have a list, which may be empty. Leaves have none.
  val children: MutableList<TreemapNode>? = null,
  var value: Double? = null,
  var classSelector: String? = null,
  var cssCompiledStyles: List<String>? = null,
)

class TreemapItem(
  val level: Double,
  val name: String,
  val type: String,
  val value: Double? = null,
  val classSelector: String? = null,
  val cssCompiledStyles: List<String>? = null,
)

class TreemapModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  val roots: List<TreemapNode>,
  val classes: LinkedHashMap<String, MutableList<String>>,
) : Accessible

private class Level(val node: TreemapNode, val level: Double)

// A row belongs to the nearest section above it that is indented less.
fun buildHierarchy(items: List<TreemapItem>): List<TreemapNode> {
  val roots = ArrayList<TreemapNode>()
  val stack = ArrayList<Level>()
  for (item in items) {
    val leaf = item.type == "Leaf"
    val node = TreemapNode(item.name, if (leaf) null else ArrayList())
    node.classSelector = item.classSelector
    if (item.cssCompiledStyles != null) node.cssCompiledStyles = item.cssCompiledStyles
    if (leaf && item.value != null) node.value = item.value

    while (stack.size > 0 && stack[stack.size - 1].level >= item.level) stack.removeAt(stack.size - 1)
    if (stack.size == 0) roots.add(node) else stack[stack.size - 1].node.children!!.add(node)
    if (!leaf) stack.add(Level(node, item.level))
  }
  return roots
}

private val RE_ESCAPED_COMMA = JsRegExp("""\\,""", "g")
private val RE_COMMA = JsRegExp(""",""", "g")
private val RE_MARK = JsRegExp("""§§§""", "g")
private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun buildTreemap(ast: TreemapAst, title: String?): TreemapModel {
  val classes = LinkedHashMap<String, MutableList<String>>()
  for (row in ast.TreemapRows) {
    if (row !is ClassDefAst) continue
    var styles = classes[row.className]
    if (styles == null) {
      styles = ArrayList()
      classes[row.className] = styles
    }
    // An escaped comma stays in the value; the others separate declarations.
    val text = (row.styleText ?: "").jsReplace(RE_ESCAPED_COMMA, "§§§").jsReplace(RE_COMMA, ";").jsReplace(RE_MARK, ",")
    for (style in text.jsSplit(";")) styles.add(style)
  }

  val items = ArrayList<TreemapItem>()
  for (row in ast.TreemapRows) {
    if (row !is TreemapRowAst) continue
    val item = row.item
    val styles = if (!item.classSelector.isNullOrEmpty()) classes[item.classSelector!!] else null
    items.add(
      TreemapItem(
        level = row.indent ?: 0.0,
        name = item.name,
        type = if (item is LeafAst) "Leaf" else "Section",
        value = if (item is LeafAst) item.value else null,
        classSelector = item.classSelector,
        cssCompiledStyles = if (styles != null && styles.size > 0) styles else null,
      ),
    )
  }

  return TreemapModel(
    type = "treemap",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle = if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr = if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    roots = buildHierarchy(items),
    classes = classes,
  )
}
