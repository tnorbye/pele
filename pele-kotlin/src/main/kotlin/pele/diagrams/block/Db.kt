// Ported from Pele src/diagrams/block/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsStr
import pele.js.jsTrim

class BlockDbOptions(val warn: ((message: String) -> Unit)? = null)

private const val MAX_SPACES = 100000.0

fun typeStr2Type(typeStr: String?): String {
  return when (typeStr) {
    "[]" -> "square"
    "()" -> "round"
    "(())" -> "circle"
    ">]" -> "rect_left_inv_arrow"
    "{}" -> "diamond"
    "{{}}" -> "hexagon"
    "([])" -> "stadium"
    "[[]]" -> "subroutine"
    "[()]" -> "cylinder"
    "((()))" -> "doublecircle"
    "[//]" -> "lean_right"
    "[\\\\]" -> "lean_left"
    "[/\\]" -> "trapezoid"
    "[\\/]" -> "inv_trapezoid"
    "<[]>" -> "block_arrow"
    else -> "na"
  }
}

fun edgeStrToEdgeData(typeStr: String): String {
  return when (typeStr.jsTrim().jsSlice(-1)) {
    "x" -> "arrow_cross"
    "o" -> "arrow_circle"
    ">" -> "arrow_point"
    else -> ""
  }
}

fun edgeStrToEdgeStartData(typeStr: String): String {
  return when (typeStr.jsTrim().take(1)) {
    "x" -> "arrow_cross"
    "o" -> "arrow_circle"
    "<" -> "arrow_point"
    else -> "arrow_open"
  }
}

fun edgeStrToThickness(typeStr: String): String {
  return if (typeStr.contains("==")) "thick" else "normal"
}

fun edgeStrToPattern(typeStr: String): String {
  return if (typeStr.contains(".-")) "dotted" else "solid"
}

// PORT: the members of BlockDb that the parser uses (upstream's `Pick<BlockDb, ...>`).
interface BlockBuilder {
  fun typeStr2Type(typeStr: String?): String

  fun edgeStrToEdgeData(typeStr: String): String

  fun edgeStrToEdgeStartData(typeStr: String): String

  fun edgeStrToThickness(typeStr: String): String

  fun edgeStrToPattern(typeStr: String): String

  fun generateId(): String

  fun setHierarchy(statements: List<Statement>)
}

private class Frame(
  val list: List<Block>,
  var at: Int,
  val parent: Block,
  val children: MutableList<Block>,
  val columns: Double,
)

// Flattens one level, as `statements.flat()` does.
@Suppress("UNCHECKED_CAST")
private fun flat(statements: List<Statement>): List<Block> {
  val out = ArrayList<Block>()
  for (s in statements) {
    if (s is List<*>) out.addAll(s as List<Block>) else out.add(s as Block)
  }
  return out
}

private fun frame(statements: List<Statement>, parent: Block): Frame {
  val list = flat(statements)
  var columns = -1.0
  for (block in list) {
    if (block.type == "column-setting") {
      columns = block.columns ?: -1.0
      break
    }
  }
  return Frame(list, 0, parent, ArrayList(), columns)
}

class BlockDb(options: BlockDbOptions = BlockDbOptions()) : BlockModel, BlockBuilder {
  override val type = "block"
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  override val root: Block =
    Block(id = "root", type = "composite", children = ArrayList(), columns = -1.0)
  override var blocks: List<Block> = ArrayList()
  override val edges: MutableList<Block> = ArrayList()
  override val classes: MutableMap<String, BlockClassDef> = LinkedHashMap()

  // Mermaid keeps the root in here too, which makes a block called `root` vanish. Here it stays
  // out.
  override val byId: MutableMap<String, Block> = LinkedHashMap()

  override fun typeStr2Type(typeStr: String?): String = pele.diagrams.block.typeStr2Type(typeStr)

  override fun edgeStrToEdgeData(typeStr: String): String =
    pele.diagrams.block.edgeStrToEdgeData(typeStr)

  override fun edgeStrToEdgeStartData(typeStr: String): String =
    pele.diagrams.block.edgeStrToEdgeStartData(typeStr)

  override fun edgeStrToThickness(typeStr: String): String =
    pele.diagrams.block.edgeStrToThickness(typeStr)

  override fun edgeStrToPattern(typeStr: String): String =
    pele.diagrams.block.edgeStrToPattern(typeStr)

  private val warn: ((message: String) -> Unit)? = options.warn

  // Ids that a class or style statement named before any block had them.
  private val pending = LinkedHashSet<String>()
  private val edgeCount = LinkedHashMap<String, Int>()
  private var ids = 0
  private var colors = 0
  private var spaces = 0.0

  // Mermaid's ids are random. These are counted, so the same text always gives the same output.
  override fun generateId(): String {
    return "id-" + ++this.ids
  }

  fun addStyleClass(id: String, styleAttributes: String = "") {
    var found = this.classes[id]
    if (found == null) {
      found = BlockClassDef(id, ArrayList(), ArrayList())
      this.classes[id] = found
    }
    for (attribute in styleAttributes.jsSplit(",")) {
      val semi = attribute.indexOf(';')
      val fixed =
        (if (semi == -1) attribute else attribute.jsSlice(0, semi) + attribute.jsSlice(semi + 1))
          .jsTrim()
      if (attribute.contains("color"))
        found.textStyles.add(fixed.jsReplace("fill", "bgFill").jsReplace("color", "fill"))
      found.styles.add(fixed)
    }
  }

  fun addStyle2Node(id: String, styles: String = "") {
    this.named(id).styles = styles.jsSplit(",")
  }

  // Mermaid looks each id up before trimming it, so `class a, b hot` misses b. Here it is trimmed
  // first.
  fun setCssClass(itemIds: String, cssClassName: String) {
    for (id in itemIds.jsSplit(",")) {
      val block = this.named(id.jsTrim())
      val list = block.classes ?: ArrayList<String>().also { block.classes = it }
      list.add(cssClassName)
    }
  }

  // Mermaid throws on a style for an unknown id, and a class for one makes the block of that id
  // disappear when it is declared later. Here both wait for the block.
  private fun named(id: String): Block {
    var block = this.byId[id]
    if (block == null) {
      block = Block(id = id, type = "na", children = ArrayList())
      this.byId[id] = block
      this.pending.add(id)
    }
    return block
  }

  override fun setHierarchy(statements: List<Statement>) {
    val stack = arrayListOf(frame(statements, this.root))
    while (stack.size > 0) {
      val f = stack[stack.size - 1]
      if (f.at == f.list.size) {
        f.parent.children = f.children
        stack.removeAt(stack.size - 1)
        continue
      }
      val block = f.list[f.at++]
      val widthInColumns = block.widthInColumns
      if (
        f.columns > 0 &&
          block.type != "column-setting" &&
          widthInColumns != null &&
          widthInColumns > f.columns
      ) {
        this.warn?.invoke(
          "Block ${block.id} width ${jsStr(widthInColumns)} exceeds configured column width ${jsStr(f.columns)}"
        )
      }
      when (block.type) {
        "classDef" -> {
          this.addStyleClass(block.id!!, block.css ?: "")
          continue
        }
        "applyClass" -> {
          this.setCssClass(block.id!!, block.styleClass ?: "")
          continue
        }
        "applyStyles" -> {
          if (!block.stylesStr.isNullOrEmpty()) this.addStyle2Node(block.id!!, block.stylesStr!!)
          continue
        }
        "column-setting" -> {
          f.parent.columns = block.columns ?: -1.0
          continue
        }
        "edge" -> {
          val count = (this.edgeCount[block.id!!] ?: 0) + 1
          this.edgeCount[block.id!!] = count
          block.id = count.toString() + "-" + block.id
          this.edges.add(block)
          continue
        }
      }

      // A composite whose header was a link statement has no id of its own.
      if (block.id == null) block.id = this.generateId()
      val id = block.id!!
      if (block.label.isNullOrEmpty()) block.label = if (block.type == "composite") "" else id
      val known = this.byId[id]
      val fresh = known == null || this.pending.remove(id)
      if (!fresh) {
        if (block.type != "na") known!!.type = block.type
        if (block.label != id) known!!.label = block.label
      } else {
        if (known != null) {
          block.classes = known.classes
          block.styles = known.styles
        }
        if (block.type == "composite") block.colorIndex = (this.colors++).toDouble()
        this.byId[id] = block
      }

      val inner = block.children
      block.children = ArrayList()
      if (block.type == "space") {
        val width = block.width ?: 1.0
        if (!(width <= MAX_SPACES - this.spaces)) {
          throw PeleError(
            "A block diagram can hold at most ${jsStr(MAX_SPACES)} space blocks.",
            PeleErrorCode.LIMIT,
            PeleErrorInfo(type = "block"),
          )
        }
        this.spaces += maxOf(width, 0.0)
        var j = 0
        while (j < width) {
          val copy = block.copy()
          copy.id = id + "-" + j
          copy.children = ArrayList()
          this.byId[copy.id!!] = copy
          f.children.add(copy)
          j++
        }
      } else if (fresh) {
        f.children.add(block)
      }
      if (inner != null && inner.size > 0) stack.add(frame(inner, block))
    }
    this.blocks = this.root.children ?: ArrayList()
  }
}
