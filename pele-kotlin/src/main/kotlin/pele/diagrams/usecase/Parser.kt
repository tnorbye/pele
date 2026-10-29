// Ported from Pele src/diagrams/usecase/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.at
import pele.js.charCodeAt
import pele.js.jsReplace
import pele.js.jsReplaceAll
import pele.js.jsSlice
import pele.js.jsSplit
import pele.js.jsTrim
import kotlin.math.max

// Mermaid parses use case diagrams with a Chevrotain parser, which decides each choice by looking
// at most three tokens ahead and reports the first token that fits nowhere. This parser makes the
// same decisions from the same lookahead, so it accepts the same text and names the same token in
// its errors. It builds the model directly, where Mermaid builds a syntax tree and then visits it.

private const val LINE = 1
private const val RELATION = 2
private const val STYLE_PART = 4
private const val FOLLOWS_NAME = 8
private const val BOUNDARY_LINE = 16
private const val CLOSES_LABEL = 32
private const val METADATA_FIRST = 64

private const val ARROWS =
  "DEPENDENCY_ARROW|GENERALIZATION|FORWARD_SOLID|BACKWARD_SOLID|MARKERLESS_SOLID|FORWARD_CIRCLE|BACKWARD_CIRCLE|FORWARD_CROSS|BACKWARD_CROSS"
private const val NAMES = "IDENTIFIER|PLAIN_STRING|MARKDOWN_STRING"
private const val LABELS = "PLAIN_STRING|MARKDOWN_STRING|LABEL_TEXT"
private const val STATEMENTS =
  "ACC_TITLE_LINE|ACC_DESCR_LINE|ACC_DESCR_BLOCK|DIRECTION|ACTOR|SYSTEM_BOUNDARY|NOTE|JSON_DECLARATION_START|CLASS_DEF|CLASS|STYLE"
private const val STYLE_PARTS =
  "WORD|PLAIN_STRING|NUMBER|HASH_COLOR|CSS_IDENTIFIER|CSS_ESCAPED_COMMA|DASH|DOT|PERCENT|CSS_PUNCTUATION|COLON|LPAREN|RPAREN|LBRACKET|RBRACKET|LBRACE|RBRACE|AT|MARKERLESS_SOLID"

private val FLAGS = IntArray(T.RPAREN + 1).also { flags ->
  fun flag(names: String, bits: Int) {
    for (name in names.jsSplit("|")) {
      // WORD is a category: every keyword and IDENTIFIER.
      if (name == "WORD") for (t in T.USECASE..T.IDENTIFIER) flags[t] = flags[t] or bits
      else {
        val index = TOKEN_NAMES.indexOf(name)
        flags[index] = flags[index] or bits
      }
    }
  }
  flag("NEWLINE|COMMENT|$STATEMENTS|$NAMES", LINE)
  flag("IDENTIFIER|$ARROWS", RELATION)
  flag(STYLE_PARTS, STYLE_PART)
  flag("STEREOTYPE_START|CLASS_SEPARATOR|IDENTIFIER|$ARROWS|NEWLINE|EOF", FOLLOWS_NAME)
  flag("NEWLINE|COMMENT|ACTOR|$NAMES", BOUNDARY_LINE)
  flag("FORWARD_SOLID|MARKERLESS_SOLID|FORWARD_CIRCLE|FORWARD_CROSS", CLOSES_LABEL)
  flag("NEWLINE|IDENTIFIER|PLAIN_STRING|RBRACE", METADATA_FIRST)
}

private val FORBIDDEN = setOf("allowmixing", "newpage", "package", "rectangle", "skinparam")

private val RE_NON_WORD = JsRegExp("""\W""", "g")
private val RE_WORD = JsRegExp("""\w""")

private class Item(
  var id: String,
  var span: Span,
  var generated: Boolean,
  var classes: MutableList<String>,
  var classSpans: MutableList<Span>,
  // Whether this mention says something about the element, and so declares it.
  var explicit: Boolean,
  var nodeSpan: Span,
) {
  lateinit var label: DraftLabel
  var shape: UseCaseShape? = null
  var metadata: List<DraftProperty>? = null
  var stereotype: String? = null
  var stereotypeSpan: Span? = null
}

private class Tail(
  var type: RelationshipType,
  var arrowType: Int,
  var minlen: Int,
  var span: Span,
) {
  var explicitId: String? = null
  var explicitIdSpan: Span? = null
  var label: DraftLabel? = null
  lateinit var target: Item
}

// PORT: the parser's state is kept per call here, where upstream keeps it in module-level variables.
private class UsecaseParser(val src: String, tokens: Tokens) {
  val types: IntArray = tokens.types
  val starts: IntArray = tokens.starts
  val ends: IntArray = tokens.ends
  var p = 0

  // Where the last token before the most recent line end stops, which is where its statement stops.
  var lastEnd = 0
  var drafts: Drafts = emptyDrafts()
  var parent: DraftBoundary? = null
  var pendingJson = ArrayList<Pair<DraftJson, Int>>()
  var edgeCount = 0
  var noteCount = 0

  fun image(k: Int): String = src.jsSlice(starts[k], ends[k])

  fun span(k: Int): Span = span(starts[k], ends[k])

  fun fail(detail: String): Nothing {
    val (line, column) = lineColumn(src, starts[p])
    throw PeleError(
      "Error parsing usecase diagram: $detail at ${locationText(src, starts[p], ends[p])}",
      PeleErrorCode.SYNTAX,
      PeleErrorInfo(type = "usecase", line = line, column = column),
    )
  }

  fun mismatch(name: String): Nothing {
    fail("Expecting token of type --> $name <-- but found --> '${image(p)}' <--")
  }

  fun expect(type: Int): Int {
    if (types[p] != type) mismatch(TOKEN_NAMES[type])
    return p++
  }

  // Chevrotain lists every token sequence that would have been accepted.
  fun noAlternative(expected: String): Nothing {
    val list = expected.jsSplit("|").mapIndexed { i, sequence -> "  ${i + 1}. [$sequence]" }
    fail("Expecting: one of these possible Token sequences:\n${list.joinToString("\n")}\nbut found: '${image(p)}'")
  }

  fun statementSequences(): String {
    val names = NAMES.jsSplit("|")
    val metadata = names.flatMap { name ->
      listOf("NEWLINE", "IDENTIFIER", "PLAIN_STRING", "RBRACE").map { next -> "$name, METADATA_START, $next" }
    }
    val follows = "STEREOTYPE_START|CLASS_SEPARATOR|IDENTIFIER|$ARROWS|NEWLINE|EOF".jsSplit("|")
    val entities = names.flatMapIndexed { i, name ->
      (if (i == 0) listOf("LPAREN", "LBRACKET") + follows else follows).map { next -> "$name, $next" }
    }
    return (listOf(STATEMENTS) + metadata + entities + metadata).joinToString("|")
  }

  fun isWord(type: Int): Boolean = type >= T.USECASE && type <= T.IDENTIFIER

  fun isLabelText(type: Int): Boolean = type >= T.USECASE && type <= T.LABEL_SYMBOL

  fun lineEnd() {
    lastEnd = ends[p - 1]
    if (types[p] == T.NEWLINE) p++
    else if (types[p] != T.EOF) noAlternative("NEWLINE|EOF")
  }

  // The text of a name or string token, without its quotes.
  fun content(k: Int): Span {
    val trim = if (types[k] == T.MARKDOWN_STRING) 2 else if (types[k] == T.PLAIN_STRING) 1 else 0
    return span(starts[k] + trim, ends[k] - trim)
  }

  fun tokenLabel(k: Int): DraftLabel {
    val at = content(k)
    return DraftLabel(src.jsSlice(at[0], at[1]), if (types[k] == T.MARKDOWN_STRING) "markdown" else "text", at)
  }

  fun generatedId(label: String): String = label.jsReplace(RE_NON_WORD, "_")

  // An unquoted label runs to the next delimiter and is taken from the source as written.
  fun label(): DraftLabel {
    val type = types[p]
    if (type == T.PLAIN_STRING || type == T.MARKDOWN_STRING) return tokenLabel(p++)
    if (!isLabelText(type)) noAlternative(LABELS)
    val first = p
    while (isLabelText(types[p])) p++
    val at = span(starts[first], ends[p - 1])
    return DraftLabel(src.jsSlice(at[0], at[1]), "text", at)
  }

  fun isKey(type: Int): Boolean = type == T.IDENTIFIER || type == T.PLAIN_STRING

  fun property(): DraftProperty {
    val key = p
    if (!isKey(types[p])) noAlternative("IDENTIFIER|PLAIN_STRING")
    p++
    expect(T.COLON)
    val value = p
    val type = types[p]
    if (!isKey(type) && type != T.TRUE && type != T.FALSE) noAlternative("IDENTIFIER|PLAIN_STRING|TRUE|FALSE")
    p++
    val keySpan = content(key)
    val valueSpan = content(value)
    return DraftProperty(
      key = src.jsSlice(keySpan[0], keySpan[1]),
      value = if (type == T.TRUE) true else if (type == T.FALSE) false else src.jsSlice(valueSpan[0], valueSpan[1]),
      span = span(starts[key], ends[value]),
      keySpan = keySpan,
      valueSpan = valueSpan,
    )
  }

  // Whether another property follows. A comma or line breaks before the closing brace do not count,
  // but Chevrotain looks only three tokens ahead, so a comma and two line breaks, or three line
  // breaks, are taken for a separator and the brace after them is then an error.
  fun separatorAhead(): Boolean {
    val a = types[p]
    val b = types[p + 1]
    val c = types[p + 2]
    if (a == T.COMMA) return isKey(b) || (b == T.NEWLINE && (c == T.NEWLINE || isKey(c)))
    if (a != T.NEWLINE) return false
    return b == T.COMMA || isKey(b) || (b == T.NEWLINE && (c == T.NEWLINE || c == T.COMMA || isKey(c)))
  }

  fun metadata(): List<DraftProperty> {
    expect(T.METADATA_START)
    val properties = ArrayList<DraftProperty>()
    while (types[p] == T.NEWLINE) p++
    if (isKey(types[p])) {
      properties.add(property())
      while (separatorAhead()) {
        if (types[p] == T.COMMA) p++
        else {
          while (types[p] == T.NEWLINE) p++
          if (types[p] == T.COMMA) p++
        }
        while (types[p] == T.NEWLINE) p++
        properties.add(property())
      }
      if (types[p] == T.COMMA) p++
      while (types[p] == T.NEWLINE) p++
    }
    expect(T.RBRACE)
    return properties
  }

  fun occurrences(properties: List<DraftProperty>): List<MetadataOccurrence> =
    properties.map { MetadataOccurrence(it.key, it.span, it.keySpan, it.valueSpan) }

  fun classSuffix(classes: MutableList<String>, spans: MutableList<Span>) {
    do {
      p++
      val id = expect(T.IDENTIFIER)
      classes.add(image(id))
      spans.add(span(id))
    } while (types[p] == T.COMMA)
  }

  // An id with an optional label in parentheses or brackets, or a string that stands for both.
  fun name(brackets: Boolean): Item {
    val k = p
    val type = types[p]
    val item = Item(
      id = "",
      span = content(k),
      generated = type != T.IDENTIFIER,
      classes = ArrayList(),
      classSpans = ArrayList(),
      explicit = false,
      nodeSpan = span(0, 0),
    )
    if (type == T.IDENTIFIER) {
      p++
      item.id = image(k)
      val open = types[p]
      if (open == T.LPAREN || (brackets && open == T.LBRACKET)) {
        p++
        item.label = label()
        expect(if (open == T.LPAREN) T.RPAREN else T.RBRACKET)
        item.shape = if (open == T.LPAREN) "ellipse" else "rect"
      } else item.label = tokenLabel(k)
    } else if (type == T.PLAIN_STRING || type == T.MARKDOWN_STRING) {
      p++
      item.label = tokenLabel(k)
      item.id = generatedId(item.label.text)
    } else noAlternative(NAMES)
    return item
  }

  fun element(actor: Boolean): Item {
    val first = p
    val item = name(!actor)
    if (actor) item.shape = null
    if (types[p] == T.METADATA_START) item.metadata = metadata()
    if (types[p] == T.STEREOTYPE_START) {
      p++
      val text = expect(T.STEREOTYPE_TEXT)
      expect(T.STEREOTYPE_END)
      item.stereotype = image(text).jsTrim()
      item.stereotypeSpan = span(text)
    }
    if (types[p] == T.CLASS_SEPARATOR) classSuffix(item.classes, item.classSpans)
    item.explicit = !item.shape.isNullOrEmpty() || item.metadata != null || !item.stereotype.isNullOrEmpty()
    item.nodeSpan = span(starts[first], ends[p - 1])
    return item
  }

  fun draft(item: Item, kind: String): DraftElement =
    DraftElement(
      id = item.id,
      kind = kind,
      label = item.label,
      span = item.span,
      generated = item.generated,
      parent = parent,
      shape = item.shape,
      metadata = item.metadata,
      stereotype = item.stereotype,
      classes = item.classes,
    )

  fun endpoint(item: Item, declares: Boolean): DraftEndpoint =
    DraftEndpoint(
      id = item.id,
      label = item.label,
      span = item.span,
      generated = item.generated,
      declares = declares,
      classed = item.classes.size > 0,
    )

  fun occurrence(item: Item, defines: Boolean): NodeOccurrence {
    val out = NodeOccurrence(item.id, item.nodeSpan, item.span, labelSpan = item.label.span)
    if (defines) out.defines = true
    if (item.stereotypeSpan != null) out.stereotypeSpan = item.stereotypeSpan
    if (item.metadata != null) out.metadata = occurrences(item.metadata!!)
    if (item.classSpans.size > 0) out.classSpans = item.classSpans
    return out
  }

  // Whether a label and then one of the operators that can close it come next.
  fun labeledRight(closers: Int): Boolean {
    if (types[p] < T.USECASE || types[p] > T.MARKDOWN_STRING) return false
    var k = p + 1
    while (true) {
      val type = types[k]
      if (type == T.MARKERLESS_SOLID || (FLAGS[type] and closers) != 0) return true
      if (type < T.USECASE || type > T.MARKDOWN_STRING) return false
      k++
    }
  }

  // Each dash beyond the second asks for one more rank between the two ends.
  fun minimumLength(k: Int): Int {
    val dashes = ends[k] - starts[k] - (if (types[k] == T.MARKERLESS_SOLID) 0 else 1)
    return max(1, dashes - 1)
  }

  fun relationTail(): Tail {
    val first = p
    val tail = Tail(
      type = "association",
      arrowType = Arrow.SOLID_ARROW,
      minlen = 1,
      span = span(0, 0),
    )
    if (types[p] == T.IDENTIFIER && types[p + 1] == T.AT) {
      tail.explicitId = image(p)
      tail.explicitIdSpan = span(p)
      p += 2
    }
    val k = p
    val type = types[p++]
    if (type == T.DEPENDENCY_ARROW) {
      expect(T.COLON)
      if (types[p] != T.INCLUDE && types[p] != T.EXTEND) noAlternative("INCLUDE|EXTEND")
      tail.type = if (types[p] == T.INCLUDE) "include" else "extend"
      tail.label = DraftLabel(tail.type, "text", span(p))
      p++
    } else if (type == T.GENERALIZATION) tail.type = "generalization"
    else if (type == T.FORWARD_SOLID) tail.minlen = minimumLength(k)
    else if (type == T.BACKWARD_SOLID) {
      tail.arrowType = Arrow.BACK_ARROW
      // Only the shortest operator can open a label: `A <-- label -- B`.
      if (ends[k] - starts[k] == 3 && labeledRight(0)) {
        tail.label = label()
        tail.minlen = minimumLength(expect(T.MARKERLESS_SOLID))
      } else tail.minlen = minimumLength(k)
    } else if (type == T.MARKERLESS_SOLID) {
      tail.arrowType = Arrow.LINE_SOLID
      if (ends[k] - starts[k] == 2 && labeledRight(CLOSES_LABEL)) {
        tail.label = label()
        val close = types[p]
        if (close == T.FORWARD_SOLID) tail.arrowType = Arrow.SOLID_ARROW
        else if (close == T.FORWARD_CIRCLE) tail.arrowType = Arrow.CIRCLE_ARROW
        else if (close == T.FORWARD_CROSS) tail.arrowType = Arrow.CROSS_ARROW
        else if (close != T.MARKERLESS_SOLID) noAlternative("FORWARD_SOLID|MARKERLESS_SOLID|FORWARD_CIRCLE|FORWARD_CROSS")
        if (tail.arrowType == Arrow.SOLID_ARROW || tail.arrowType == Arrow.LINE_SOLID) tail.minlen = minimumLength(p)
        p++
      } else tail.minlen = minimumLength(k)
    } else if (type == T.FORWARD_CIRCLE) tail.arrowType = Arrow.CIRCLE_ARROW
    else if (type == T.FORWARD_CROSS) tail.arrowType = Arrow.CROSS_ARROW
    else if (type == T.BACKWARD_CIRCLE || type == T.BACKWARD_CROSS) {
      tail.arrowType = if (type == T.BACKWARD_CIRCLE) Arrow.CIRCLE_ARROW_REVERSED else Arrow.CROSS_ARROW_REVERSED
      if (labeledRight(0)) {
        tail.label = label()
        expect(T.MARKERLESS_SOLID)
      }
    } else {
      p--
      noAlternative(ARROWS)
    }
    tail.target = element(false)
    tail.span = span(starts[first], ends[p - 1])
    return tail
  }

  fun statement(kind: String): GraphStatement = GraphStatement(kind, span(0, 0))

  fun relationship(source: Item, declaration: Boolean, tail: Tail, nodes: MutableList<NodeOccurrence>): GraphStatement {
    val target = tail.target
    if (target.explicit) drafts.elements.add(draft(target, "usecase"))
    drafts.relationships.add(
      DraftRelationship(
        source = endpoint(source, declaration),
        target = endpoint(target, target.explicit),
        span = tail.span,
        id = tail.explicitId,
        idSpan = tail.explicitIdSpan,
        type = tail.type,
        arrowType = tail.arrowType,
        label = tail.label,
        minlen = tail.minlen,
      ),
    )
    nodes.add(occurrence(target, target.explicit))
    val out = statement("edge")
    out.nodes = nodes
    val edge = EdgeOccurrence(tail.explicitId ?: "edge-${edgeCount++}", out.span)
    out.edges = listOf(edge)
    if (tail.explicitIdSpan != null) edge.idSpan = tail.explicitIdSpan
    if (tail.label != null) edge.labelSpan = tail.label!!.span
    return out
  }

  fun declaration(nodes: MutableList<NodeOccurrence>): GraphStatement {
    val out = statement("node")
    out.nodes = nodes
    return out
  }

  // `actor A, B` declares several actors; at the top level `actor A --> B` declares one and relates it.
  fun actors(top: Boolean): GraphStatement {
    p++
    val items = arrayListOf(element(true))
    var tail: Tail? = null
    if (types[p] == T.COMMA) {
      do {
        p++
        items.add(element(true))
      } while (types[p] == T.COMMA)
    } else if (top && (FLAGS[types[p]] and RELATION) != 0) tail = relationTail()
    lineEnd()
    for (item in items) drafts.elements.add(draft(item, "actor"))
    val nodes = items.map { item -> occurrence(item, true) }.toMutableList()
    return if (tail != null) relationship(items[0], true, tail, nodes) else declaration(nodes)
  }

  fun entity(top: Boolean): GraphStatement {
    val source = element(false)
    val tail = if (top && (FLAGS[types[p]] and RELATION) != 0) relationTail() else null
    lineEnd()
    if (tail == null) {
      source.explicit = true
      if (source.shape == null) source.shape = "ellipse"
    }
    if (source.explicit) drafts.elements.add(draft(source, "usecase"))
    val nodes = arrayListOf(occurrence(source, source.explicit))
    return if (tail != null) relationship(source, source.explicit, tail, nodes) else declaration(nodes)
  }

  fun boundary(): GraphStatement {
    p++
    val named = name(true)
    val group = DraftBoundary(
      id = named.id,
      label = named.label,
      span = named.span,
      generated = named.generated,
      classes = named.classes,
    )
    val inline = if (types[p] == T.METADATA_START) metadata() else null
    if (types[p] == T.CLASS_SEPARATOR) classSuffix(named.classes, named.classSpans)
    lineEnd()
    drafts.boundaries.add(group)
    parent = group
    val children = ArrayList<GraphStatement>()
    while ((FLAGS[types[p]] and BOUNDARY_LINE) != 0) children.add(line(false))
    parent = null
    val end = expect(T.END)
    lineEnd()
    val out = statement("group")
    out.group = group.id
    out.idSpan = group.span
    out.titleSpan = group.label.span
    out.endSpan = span(end)
    out.classSpans = named.classSpans
    if (children.size > 0) out.children = children
    if (inline != null) {
      out.metadata = occurrences(inline)
      drafts.metadata.add(DraftMetadata(group.id, group.span, inline, out))
    }
    return out
  }

  fun style(): String {
    val first = p
    val type = types[p]
    if (isWord(type) || type == T.CSS_IDENTIFIER) p++
    else if (type == T.MARKERLESS_SOLID) {
      p++
      if (!isWord(types[p])) mismatch("WORD")
      p++
    } else noAlternative("WORD|CSS_IDENTIFIER|MARKERLESS_SOLID")
    expect(T.COLON)
    if ((FLAGS[types[p]] and STYLE_PART) == 0) {
      fail(
        "Expecting: expecting at least one iteration which starts with one of these possible Token sequences::\n  <[${STYLE_PARTS.jsReplaceAll("|", "] ,[")}]>\nbut found: '${image(p)}'",
      )
    }
    while ((FLAGS[types[p]] and STYLE_PART) != 0) p++
    // The value is taken from the source, so the spaces inside `1px solid red` survive.
    return src.jsSlice(starts[first], ends[p - 1]).jsReplaceAll("\\,", ",")
  }

  fun styles(): List<String> {
    val out = arrayListOf(style())
    while (types[p] == T.COMMA) {
      p++
      out.add(style())
    }
    return out
  }

  fun identifiers(): List<Int> {
    val out = arrayListOf(expect(T.IDENTIFIER))
    while (types[p] == T.COMMA) {
      p++
      out.add(expect(T.IDENTIFIER))
    }
    return out
  }

  fun reference(k: Int): NodeOccurrence {
    val at = content(k)
    return NodeOccurrence(image(k), at, at)
  }

  fun topStatement(): GraphStatement {
    val k = p
    val type = types[p]
    val out: GraphStatement
    if (type == T.ACC_TITLE_LINE || type == T.ACC_DESCR_LINE || type == T.ACC_DESCR_BLOCK) {
      p++
      val text = image(k)
      if (type == T.ACC_DESCR_BLOCK) {
        drafts.accDescription = text.jsSlice(text.indexOf('{') + 1, text.lastIndexOf('}')).jsTrim()
      } else if (type == T.ACC_DESCR_LINE) drafts.accDescription = text.jsSlice(text.indexOf(':') + 1).jsTrim()
      else drafts.accTitle = text.jsSlice(text.indexOf(':') + 1).jsTrim()
      out = statement(if (type == T.ACC_TITLE_LINE) "accTitle" else "accDescr")
    } else if (type == T.DIRECTION) {
      p++
      val value = types[p]
      if (value < T.TD || value > T.RL) noAlternative("TD|TB|BT|LR|RL")
      drafts.direction = if (value == T.TD) "TB" else image(p)
      p++
      out = statement("direction")
    } else if (type == T.ACTOR) return actors(true)
    else if (type == T.SYSTEM_BOUNDARY) return boundary()
    else if (type == T.NOTE) {
      p++
      expect(T.FOR)
      val target = expect(T.IDENTIFIER)
      val text = label()
      drafts.notes.add(DraftNote(image(target), span(target), text))
      out = statement("note")
      out.ref = "note-${noteCount++}"
      out.refSpan = text.span
      out.nodes = listOf(reference(target))
    } else if (type == T.JSON_DECLARATION_START) {
      p++
      val literal = expect(T.JSON_OBJECT_LITERAL)
      val classes = ArrayList<String>()
      val classSpans = ArrayList<Span>()
      if (types[p] == T.CLASS_SEPARATOR) classSuffix(classes, classSpans)
      var from = starts[k] + 4
      while (src.charCodeAt(from) == 32 || src.charCodeAt(from) == 9) from++
      var to = from
      while (RE_WORD.test(src.at(to) ?: "")) to++
      val json = DraftJson(src.jsSlice(from, to), LinkedHashMap(), LinkedHashMap(), span(from, to), classes)
      drafts.jsons.add(json)
      pendingJson.add(Pair(json, literal))
      out = statement("json")
      out.nodes = listOf(NodeOccurrence(json.id, json.span, json.span, defines = true, classSpans = classSpans))
      out.classSpans = classSpans
    } else if (type == T.CLASS_DEF) {
      p++
      val ids = identifiers()
      drafts.classDefs.add(DraftClassDef(ids.map { image(it) }, styles()))
      out = statement("classDef")
      out.ref = image(ids[0])
      out.refSpan = span(ids[0])
    } else if (type == T.CLASS) {
      p++
      val targets = identifiers()
      val classes = identifiers()
      drafts.classes.add(
        DraftClasses(
          targets.map { id -> DraftClassTarget(image(id), span(id)) },
          classes.map { image(it) },
        ),
      )
      out = statement("classAssign")
      out.ref = image(classes[0])
      out.refSpan = span(classes[0])
      out.nodes = targets.map { reference(it) }
    } else if (type == T.STYLE) {
      p++
      val target = expect(T.IDENTIFIER)
      drafts.styles.add(DraftStyle(image(target), span(target), styles()))
      out = statement("style")
      out.nodes = listOf(reference(target))
    } else {
      val next = types[p + 1]
      if (next == T.METADATA_START) {
        if ((FLAGS[types[p + 2]] and METADATA_FIRST) == 0) noAlternative(statementSequences())
        p++
        val target = reference(k)
        if (type != T.IDENTIFIER) target.id = generatedId(src.jsSlice(target.span[0], target.span[1]))
        val assigned = metadata()
        out = statement("metadata")
        out.nodes = listOf(target)
        out.metadata = occurrences(assigned)
        drafts.metadata.add(DraftMetadata(target.id, target.span, assigned, out))
      } else {
        val follows =
          (FLAGS[next] and FOLLOWS_NAME) != 0 || (type == T.IDENTIFIER && (next == T.LPAREN || next == T.LBRACKET))
        // Mermaid turns away the PlantUML statements people are most likely to try.
        if (!follows || (type == T.IDENTIFIER && FORBIDDEN.contains(image(k).lowercase()))) {
          noAlternative(statementSequences())
        }
        return entity(true)
      }
    }
    lineEnd()
    return out
  }

  fun line(top: Boolean): GraphStatement {
    val k = p
    val type = types[p]
    if (type == T.NEWLINE) {
      p++
      return GraphStatement("blank", span(k))
    }
    if (type == T.COMMENT) {
      p++
      lineEnd()
      return GraphStatement("comment", span(k))
    }
    val out = if (top) topStatement() else if (type == T.ACTOR) actors(false) else entity(false)
    out.span = span(starts[k], lastEnd)
    out.edges?.let { edges -> for (edge in edges) edge.span = out.span }
    return out
  }

  fun parseUsecase(title: String?): UsecaseModel {
    val header = expect(T.USECASE)
    lineEnd()
    val statements = ArrayList<GraphStatement>()
    while ((FLAGS[types[p]] and LINE) != 0) statements.add(line(true))
    if (types[p] != T.EOF) fail("Redundant input, expecting EOF but found: ${image(p)}")
    // The whole text has to be well formed before the JSON in it is looked at, as in Mermaid.
    for ((json, literal) in pendingJson) {
      val parsed = parseOrderedJson(image(literal)) { lineColumn(src, starts[literal]) }
      json.value = parsed.value
      json.propertyOrder = parsed.propertyOrder
    }
    return resolveUsecase(src, drafts, span(header), statements, title)
  }
}

fun parseUsecase(source: String, title: String? = null): UsecaseModel =
  UsecaseParser(source, tokenize(source)).parseUsecase(title)
