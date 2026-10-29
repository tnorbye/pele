// Ported from Pele src/diagrams/treeview/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treeview

import pele.PeleError
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsSlice
import pele.locate
import pele.svg.Accessible

// 'file' | 'directory'
typealias NodeType = String

class TreeNode(
  val id: Int,
  val level: Double,
  val name: String,
  val nodeType: NodeType,
  // An icon name as written (`pack:name`, `file`, `folder`), or `none` to hide the icon.
  val icon: String? = null,
  val cssClass: String? = null,
  val description: String? = null,
  val children: MutableList<TreeNode> = ArrayList(),
)

class TreeViewModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  // A directory named `/` at level -1 that holds the top-level entries.
  val root: TreeNode,
  val count: Int,
) : Accessible

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

// Mermaid's treeView database: nodes arrive in source order with their indentation.
class TreeViewDb {
  var count = 1
  val root: TreeNode = TreeNode(id = 0, level = -1.0, name = "/", nodeType = "directory")
  private val stack: MutableList<TreeNode> = arrayListOf(root)

  fun addNode(level: Double, name: String, nodeType: NodeType, cssClass: String? = null, icon: String? = null, description: String? = null) {
    val stack = this.stack
    while (level <= stack[stack.size - 1].level) stack.removeAt(stack.size - 1)
    val node = TreeNode(count++, level, name, nodeType, icon, cssClass, description)
    stack[stack.size - 1].children.add(node)
    stack.add(node)
  }
}

fun populate(ast: TreeViewAst, db: TreeViewDb) {
  for (node in ast.nodes) {
    val level = node.indent ?: 0.0
    var name = node.name
    val isDirectory = name.endsWith("/")
    if (isDirectory) name = name.jsSlice(0, -1)
    val rawIcon = node.iconAnnotation
    db.addNode(
      level,
      name,
      if (isDirectory) "directory" else "file",
      if (!node.classAnnotation.isNullOrEmpty()) node.classAnnotation else null,
      if (rawIcon != null) (if (rawIcon.isNotEmpty()) rawIcon else "none") else null,
      if (!node.descAnnotation.isNullOrEmpty()) node.descAnnotation else null,
    )
  }
}

// Reads the source to a syntax tree, accepting box-drawing input. Errors name lines of the original text.
fun readTreeView(source: String): TreeViewAst {
  val processed = preprocessBoxDrawing(source)
  val text = processed.text
  val lineMap = processed.lineMap
  val columns = processed.columns
  try {
    return parseTreeView(text)
  } catch (error: PeleError) {
    // The parser already located errors in unchanged text.
    if (text == source) throw error
    val line = lineMap[error.line] ?: throw error
    val at = if (error.column > 0) (columns[error.line]?.invoke(error.column - 1) ?: (error.column - 1)) else 0
    val written = locate(source, lineStart(source, line) + at)
    val head = if (error.message.contains('\n')) error.message.indexOf('\n') else error.message.length
    val rest = error.message.jsSlice(head)
    val message =
      remapErrorLines(error.message.jsSlice(0, head), mapOf(error.line to written.line)) +
        (if (error.snippet.isNotEmpty()) rest.jsReplace(error.snippet) { written.snippet } else rest)
    throw PeleError(
      message,
      error.code,
      PeleErrorInfo(
        type = error.type,
        line = written.line,
        column = if (error.column > 0) written.column else 0,
        snippet = if (error.snippet.isNotEmpty()) written.snippet else "",
      ),
    )
  }
}

fun buildTreeView(ast: TreeViewAst, title: String?): TreeViewModel {
  val db = TreeViewDb()
  populate(ast, db)
  return TreeViewModel(
    type = "treeView",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle = if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr = if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    root = db.root,
    count = db.count,
  )
}
