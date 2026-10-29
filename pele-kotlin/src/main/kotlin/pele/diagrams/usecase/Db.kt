// Ported from Pele src/diagrams/usecase/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace

class DraftLabel(
  val text: String,
  val type: LabelType,
  val span: Span,
)

// keySpan is also where an error about the property points.
class DraftProperty(
  key: String,
  span: Span,
  keySpan: Span,
  valueSpan: Span,
  // String | Boolean
  val value: Any,
) : MetadataOccurrence(key, span, keySpan, valueSpan)

// What every mention of an id carries: where it is, and whether the id was made from a label.
interface Named {
  val id: String
  val label: DraftLabel
  val span: Span
  val generated: Boolean
}

class DraftElement(
  override val id: String,
  override val label: DraftLabel,
  override val span: Span,
  override val generated: Boolean,
  // 'actor' | 'usecase'
  val kind: String,
  val parent: DraftBoundary? = null,
  val shape: UseCaseShape? = null,
  val metadata: List<DraftProperty>? = null,
  val stereotype: String? = null,
  val classes: List<String>,
) : Named

class DraftBoundary(
  override val id: String,
  override val label: DraftLabel,
  override val span: Span,
  override val generated: Boolean,
  val classes: List<String>,
) : Named

class DraftJson(
  val id: String,
  var value: Map<String, Any?>,
  var propertyOrder: Map<String, List<String>>,
  val span: Span,
  val classes: List<String>,
)

class DraftEndpoint(
  override val id: String,
  override val label: DraftLabel,
  override val span: Span,
  override val generated: Boolean,
  // Whether this mention declares the element, and whether it carries a `:::` suffix.
  val declares: Boolean,
  val classed: Boolean,
) : Named

class DraftRelationship(
  val source: DraftEndpoint,
  val target: DraftEndpoint,
  val span: Span,
  val id: String? = null,
  val idSpan: Span? = null,
  val type: RelationshipType,
  val arrowType: Int,
  val label: DraftLabel? = null,
  val minlen: Int,
)

class DraftNote(val target: String, val span: Span, val label: DraftLabel)

class DraftMetadata(
  val target: String,
  val span: Span,
  val properties: List<DraftProperty>,
  val statement: GraphStatement,
)

class DraftClassTarget(val id: String, val span: Span)

class DraftClasses(val targets: List<DraftClassTarget>, val classes: List<String>)

class DraftStyle(val target: String, val span: Span, val styles: List<String>)

class DraftClassDef(val ids: List<String>, val styles: List<String>)

// Everything the parser read, in source order, before any of it is resolved.
class Drafts(
  val elements: MutableList<DraftElement> = ArrayList(),
  val boundaries: MutableList<DraftBoundary> = ArrayList(),
  val jsons: MutableList<DraftJson> = ArrayList(),
  val relationships: MutableList<DraftRelationship> = ArrayList(),
  val notes: MutableList<DraftNote> = ArrayList(),
  val metadata: MutableList<DraftMetadata> = ArrayList(),
  val classes: MutableList<DraftClasses> = ArrayList(),
  val styles: MutableList<DraftStyle> = ArrayList(),
  val classDefs: MutableList<DraftClassDef> = ArrayList(),
  var direction: Direction? = null,
  var accTitle: String = "",
  var accDescription: String = "",
)

fun emptyDrafts(): Drafts = Drafts()

private class Origin(
  val kind: SymbolKind,
  val span: Span,
  val generated: Boolean,
  // The label a generated id was derived from.
  val label: String? = null,
)

private class ElementState(
  override val id: String,
  override val label: DraftLabel,
  override val span: Span,
  override val generated: Boolean,
  // 'actor' | 'usecase'
  val kind: String,
  var parent: DraftBoundary? = null,
  var shape: UseCaseShape? = null,
  var stereotype: String? = null,
  var stereotypeSpan: Span? = null,
  val classes: MutableList<String>,
  // Exclude<ActorType, 'icon'>
  var actorType: ActorType? = null,
  var icon: String? = null,
  var business: Boolean? = null,
) : Named

private class BoundaryState(
  override val id: String,
  override val label: DraftLabel,
  override val span: Span,
  override val generated: Boolean,
  val classes: MutableList<String>,
  var type: BoundaryType? = null,
  val members: MutableList<String>,
) : Named

private fun pushUnique(target: MutableList<String>, values: List<String>) {
  if (values.size == 0) return
  val seen = HashSet(target)
  for (value in values) {
    if (seen.contains(value)) continue
    seen.add(value)
    target.add(value)
  }
}

// Which kind of element the keys of a metadata block could only belong to, for the error message.
private fun inferKind(properties: List<DraftProperty>): String? {
  val possible = linkedSetOf("actor", "usecase", "boundary", "edge")
  for ((key, value) in properties.map { it.key to it.value }) {
    if (key == "icon") {
      possible.clear()
      possible.add("actor")
    } else if (key == "animate" || key == "animation") {
      possible.clear()
      possible.add("edge")
    } else if (key == "type") {
      possible.clear()
      if (value == "rect" || value == "package") possible.add("boundary")
      else if (value == "normal" || value == "hollow" || value == "awesome") possible.add("actor")
    } else if (key == "business") {
      possible.remove("boundary")
      possible.remove("edge")
    } else return null
  }
  return if (possible.size == 1) possible.first() else null
}

private val RE_INDENT = JsRegExp("""\n\s+""", "g")

// Resolves what the parser read into a model, once the whole diagram is read: which ids are
// actors, use cases, boundaries, JSON nodes or edges, in what order they appear, and whether the
// statements about them agree. Ports Mermaid's UsecaseModelBuilder, with its messages.
fun resolveUsecase(
  src: String,
  drafts: Drafts,
  headerSpan: Span,
  statements: List<GraphStatement>,
  title: String?,
): UsecaseModel {
  val model = emptyModel()
  val actors = model.actors
  val useCases = model.useCases
  val systemBoundaries = model.systemBoundaries
  val symbols = LinkedHashMap<String, Origin>()
  val elements = LinkedHashMap<String, ElementState>()
  val boundaries = LinkedHashMap<String, BoundaryState>()
  val edges = LinkedHashMap<String, Relationship>()
  // The offset at which each id is first mentioned, which decides the order of the model.
  val first = LinkedHashMap<String, Int>()

  fun at(span: Span): String = locationText(src, span[0], span[1])

  fun error(message: String, span: Span): PeleError {
    val (line, column) = lineColumn(src, span[0])
    return PeleError(
      message,
      PeleErrorCode.SEMANTIC,
      PeleErrorInfo(type = "usecase", line = line, column = column),
    )
  }

  fun fail(message: String, span: Span): Nothing {
    throw error("$message at ${at(span)}", span)
  }

  fun conflict(
    message: String,
    current: Span,
    previous: Span,
    currentLabel: String? = null,
    previousLabel: String? = null,
  ): Nothing {
    fun suffix(label: String?): String = if (label == null) "" else " (label \"$label\")"
    throw error(
      "$message at ${at(current)}${suffix(currentLabel)}; previous declaration at ${at(previous)}${suffix(previousLabel)}",
      current,
    )
  }

  fun invalid(id: String, kind: String, property: DraftProperty): Nothing =
    fail("Metadata property '${property.key}' is invalid for $kind '$id'", property.keySpan)

  fun mention(id: String, offset: Int) {
    val previous = first[id]
    if (previous == null || offset < previous) first[id] = offset
  }

  fun <V> inOrder(values: Iterable<V>, id: (V) -> String): List<V> =
    values.toList().sortedWith { a, b -> (first[id(a)] ?: 0) - (first[id(b)] ?: 0) }

  fun register(named: Named, kind: SymbolKind) {
    symbols[named.id] = Origin(kind, named.span, named.generated, named.label.text)
  }

  // An id means one thing in the whole diagram, and an id made from a label may be declared once.
  fun claim(draft: Named, kind: SymbolKind) {
    val origin = symbols[draft.id] ?: return
    val label = if (draft.generated) draft.label.text else null
    val originLabel = if (origin.generated) origin.label else null
    if (origin.kind != kind) {
      conflict(
        "ID '${draft.id}' is declared as both ${origin.kind} and $kind",
        draft.span,
        origin.span,
        label,
        originLabel,
      )
    }
    if (origin.generated || draft.generated) {
      conflict(
        "Generated ID '${draft.id}' collides with another declaration",
        draft.span,
        origin.span,
        label,
        originLabel,
      )
    }
  }

  fun unique(id: String, kind: SymbolKind, span: Span) {
    val previous = symbols[id]
    if (previous != null) {
      conflict(
        "ID '$id' is declared more than once (${previous.kind} and $kind)",
        span,
        previous.span,
        null,
        if (previous.generated) previous.label else null,
      )
    }
    symbols[id] = Origin(kind, span, false)
  }

  // A metadata statement replaces what a declaration said; two declarations have to agree.
  fun actorProperty(state: ElementState, property: DraftProperty, replace: Boolean) {
    val key = property.key
    val value = property.value
    fun clash(previous: Any?) {
      if (!replace && previous != null && previous != value) {
        conflict("Actor '${state.id}' has conflicting $key metadata", property.keySpan, state.span)
      }
    }
    if (key == "type" && (value == "normal" || value == "hollow" || value == "awesome")) {
      clash(state.actorType)
      state.actorType = value as String
    } else if (key == "icon" && value is String) {
      clash(state.icon)
      state.icon = value
    } else if (key == "business" && value is Boolean) {
      clash(state.business)
      state.business = value
    } else invalid(state.id, "actor", property)
  }

  fun elementMetadata(state: ElementState, properties: List<DraftProperty>?, replace: Boolean) {
    for (property in properties ?: listOf()) {
      if (state.kind == "actor") actorProperty(state, property, replace)
      else if (property.key == "business" && property.value is Boolean) {
        if (!replace && state.business != null && state.business != property.value) {
          conflict(
            "Use case '${state.id}' has conflicting business metadata",
            property.keySpan,
            state.span,
          )
        }
        state.business = property.value
      } else invalid(state.id, "usecase", property)
    }
  }

  fun publish(state: ElementState) {
    if (state.kind == "actor") {
      useCases.remove(state.id)
      val actor =
        Actor(
          id = state.id,
          label = state.label.text,
          labelType = state.label.type,
          business = state.business ?: false,
          classes = ArrayList(state.classes),
          type = if (!state.icon.isNullOrEmpty()) "icon" else (state.actorType ?: "normal"),
          styles = actors[state.id]?.styles ?: ArrayList(),
        )
      if (!state.icon.isNullOrEmpty()) actor.icon = state.icon
      actors[state.id] = actor
      if (!state.stereotype.isNullOrEmpty()) actor.stereotype = state.stereotype
      if (!state.parent?.id.isNullOrEmpty()) actor.parentId = state.parent!!.id
    } else {
      actors.remove(state.id)
      val useCase =
        UseCase(
          id = state.id,
          label = state.label.text,
          labelType = state.label.type,
          business = state.business ?: false,
          classes = ArrayList(state.classes),
          shape = state.shape ?: "ellipse",
          styles = useCases[state.id]?.styles ?: ArrayList(),
        )
      useCases[state.id] = useCase
      if (!state.stereotype.isNullOrEmpty()) useCase.stereotype = state.stereotype
      if (!state.parent?.id.isNullOrEmpty()) useCase.parentId = state.parent!!.id
    }
  }

  fun declareElement(draft: DraftElement) {
    claim(draft, draft.kind)
    val parent = if (!draft.parent?.id.isNullOrEmpty()) draft.parent else null
    val existing = elements[draft.id]
    if (existing == null) {
      val state =
        ElementState(
          kind = draft.kind,
          id = draft.id,
          label = draft.label,
          span = draft.span,
          generated = draft.generated,
          classes = ArrayList(draft.classes),
          parent = parent,
          shape = draft.shape,
          stereotype = draft.stereotype,
          stereotypeSpan = if (!draft.stereotype.isNullOrEmpty()) draft.span else null,
        )
      elementMetadata(state, draft.metadata, false)
      elements[draft.id] = state
      register(draft, draft.kind)
      return
    }
    if (existing.label.text != draft.label.text || existing.label.type != draft.label.type) {
      conflict("ID '${draft.id}' has conflicting labels", draft.span, existing.span)
    }
    if (
      !draft.shape.isNullOrEmpty() &&
        !existing.shape.isNullOrEmpty() &&
        draft.shape != existing.shape
    ) {
      conflict("Use case '${draft.id}' has conflicting shapes", draft.span, existing.span)
    }
    // A boundary whose title makes an empty id is no parent, but it still takes the place of one.
    if (
      parent != null && !existing.parent?.id.isNullOrEmpty() && parent.id != existing.parent?.id
    ) {
      conflict(
        "Element '${draft.id}' belongs to more than one system boundary",
        parent.span,
        existing.parent!!.span,
      )
    }
    if (
      !draft.stereotype.isNullOrEmpty() &&
        !existing.stereotype.isNullOrEmpty() &&
        draft.stereotype != existing.stereotype
    ) {
      conflict(
        "Element '${draft.id}' has conflicting stereotypes",
        draft.span,
        existing.stereotypeSpan ?: existing.span,
      )
    }
    if (existing.shape == null) existing.shape = draft.shape
    if (existing.parent == null) existing.parent = draft.parent
    if (existing.stereotype == null) existing.stereotype = draft.stereotype
    if (existing.stereotypeSpan == null)
      existing.stereotypeSpan = if (!draft.stereotype.isNullOrEmpty()) draft.span else null
    pushUnique(existing.classes, draft.classes)
    elementMetadata(existing, draft.metadata, false)
  }

  fun declareBoundary(draft: DraftBoundary) {
    claim(draft, "boundary")
    val existing = boundaries[draft.id]
    if (existing != null) {
      if (existing.label.text != draft.label.text || existing.label.type != draft.label.type) {
        conflict("Boundary '${draft.id}' has conflicting titles", draft.span, existing.span)
      }
      pushUnique(existing.classes, draft.classes)
      return
    }
    boundaries[draft.id] =
      BoundaryState(
        draft.id,
        draft.label,
        draft.span,
        draft.generated,
        ArrayList(draft.classes),
        null,
        ArrayList(),
      )
    register(draft, "boundary")
  }

  // An end of a relationship that nothing declares becomes a use case.
  fun endpoint(end: DraftEndpoint): SymbolKind {
    if (end.classed && !end.declares) {
      fail("Relationship endpoint '${end.id}' uses ::: without declaring the node", end.span)
    }
    val origin = symbols[end.id]
    if (origin != null) return origin.kind
    elements[end.id] =
      ElementState(
        id = end.id,
        label = end.label,
        span = end.span,
        generated = end.generated,
        kind = "usecase",
        shape = "ellipse",
        classes = ArrayList(),
      )
    register(end, "usecase")
    mention(end.id, end.span[0])
    return "usecase"
  }

  fun stylable(id: String, span: Span): Pair<MutableList<String>, MutableList<String>> {
    val kind = symbols[id]?.kind
    val target: Pair<MutableList<String>, MutableList<String>>? =
      if (kind == "actor") actors[id]?.let { Pair(it.classes, it.styles) }
      else if (kind == "usecase") useCases[id]?.let { Pair(it.classes, it.styles) }
      else if (kind == "boundary") systemBoundaries[id]?.let { Pair(it.classes, it.styles) }
      else if (kind == "json") model.jsonNodes[id]?.let { Pair(it.classes, it.styles) }
      else edges[id]?.let { Pair(it.classes, it.styles) }
    return target ?: fail("Class/style target '$id' is unresolved or anonymous", span)
  }

  class Declaration(val offset: Int, val run: () -> Unit)

  val declarations = ArrayList<Declaration>()
  for (relationship in drafts.relationships) {
    mention(relationship.source.id, relationship.source.span[0])
    mention(relationship.target.id, relationship.target.span[0])
  }
  for (draft in drafts.elements) {
    mention(draft.id, draft.span[0])
    declarations.add(Declaration(draft.span[0]) { declareElement(draft) })
  }
  for (draft in drafts.boundaries) {
    mention(draft.id, draft.span[0])
    declarations.add(Declaration(draft.span[0]) { declareBoundary(draft) })
  }
  for (draft in drafts.jsons) {
    mention(draft.id, draft.span[0])
    declarations.add(Declaration(draft.span[0]) { unique(draft.id, "json", draft.span) })
  }
  for (relationship in drafts.relationships) {
    val id = relationship.id
    val idSpan = relationship.idSpan
    if (!id.isNullOrEmpty() && idSpan != null)
      declarations.add(Declaration(idSpan[0]) { unique(id, "edge", idSpan) })
  }
  declarations.sortWith { a, b -> a.offset - b.offset }
  for (declaration in declarations) declaration.run()

  for (state in inOrder(elements.values) { it.id }) publish(state)
  for (state in inOrder(boundaries.values) { it.id }) {
    systemBoundaries[state.id] =
      SystemBoundary(
        id = state.id,
        label = state.label.text,
        labelType = state.label.type,
        type = "rect",
        members = ArrayList(),
        classes = ArrayList(),
        styles = ArrayList(),
      )
  }
  for (draft in inOrder(drafts.jsons) { it.id }) {
    model.jsonNodes[draft.id] =
      UsecaseJsonNode(
        id = draft.id,
        value = draft.value,
        propertyOrder = draft.propertyOrder,
        classes = ArrayList(draft.classes),
        styles = ArrayList(),
      )
  }

  var anonymous = 0
  for (draft in drafts.relationships) {
    val source = draft.source
    val target = draft.target
    val type = draft.type
    val sourceKind = endpoint(source)
    val targetKind = endpoint(target)
    val related = { kind: SymbolKind -> kind == "actor" || kind == "usecase" || kind == "json" }
    if (!related(sourceKind)) {
      conflict(
        "Relationship source '${source.id}' cannot be $sourceKind",
        source.span,
        symbols[source.id]!!.span,
      )
    }
    if (!related(targetKind)) {
      conflict(
        "Relationship target '${target.id}' cannot be $targetKind",
        target.span,
        symbols[target.id]!!.span,
      )
    }
    if (
      (type == "include" || type == "extend") &&
        (sourceKind != "usecase" || targetKind != "usecase")
    ) {
      fail("$type relationship requires use-case endpoints", draft.span)
    }
    if (type == "generalization" && (sourceKind == "json" || sourceKind != targetKind)) {
      fail("Generalization requires actor-to-actor or use-case-to-use-case endpoints", draft.span)
    }
    if (
      type == "association" && (sourceKind == "json" || targetKind == "json") && draft.arrowType > 2
    ) {
      fail(
        "JSON relationship '${source.id}' to '${target.id}' permits only point, reversed-point, or markerless solid association",
        draft.span,
      )
    }
    val id = draft.id ?: "edge-${anonymous++}"
    val relationship =
      Relationship(
        id = id,
        explicitId = !draft.id.isNullOrEmpty(),
        source = source.id,
        target = target.id,
        type = type,
        arrowType = draft.arrowType,
        minlen = draft.minlen,
        classes = ArrayList(),
        styles = ArrayList(),
        animate = false,
      )
    if (draft.label != null) {
      relationship.label = draft.label.text
      relationship.labelType = draft.label.type
    }
    model.relationships.add(relationship)
    edges[id] = relationship
  }

  // The relationships may have added use cases; put all elements back in the order of first
  // mention.
  val declaredActors = LinkedHashMap(actors)
  val declaredUseCases = LinkedHashMap(useCases)
  actors.clear()
  useCases.clear()
  for (state in inOrder(elements.values) { it.id }) {
    val actor = declaredActors[state.id]
    val useCase = declaredUseCases[state.id]
    if (actor != null) actors[state.id] = actor
    else if (useCase != null) useCases[state.id] = useCase else publish(state)
  }

  for (entry in drafts.metadata) {
    val target = entry.target
    val span = entry.span
    val properties = entry.properties
    val statement = entry.statement
    val origin = symbols[target]
    if (origin == null) {
      val inferred = inferKind(properties)
      return fail(
        "Metadata target '$target' is unresolved${if (inferred != null) " (metadata implies $inferred)" else ""}",
        span,
      )
    }
    val kind = origin.kind
    if (kind == "actor" || kind == "usecase") elementMetadata(elements[target]!!, properties, true)
    else if (kind == "edge") {
      statement.kind = "edgeMetadata"
      statement.edges =
        listOf(EdgeOccurrence(target, statement.span, idSpan = span, metadata = statement.metadata))
      statement.nodes = null
    }
    val boundary = boundaries[target]
    val edge = edges[target]
    for (property in if (kind == "actor" || kind == "usecase") listOf() else properties) {
      val key = property.key
      val value = property.value
      if (
        kind == "boundary" &&
          boundary != null &&
          key == "type" &&
          (value == "rect" || value == "package")
      ) {
        boundary.type = value as String
      } else if (kind == "edge" && edge != null && key == "animate" && value is Boolean)
        edge.animate = value
      else if (
        kind == "edge" && edge != null && key == "animation" && (value == "fast" || value == "slow")
      ) {
        edge.animation = value as Animation
        edge.animate = true
      } else invalid(target, kind, property)
    }
  }
  for (relationship in model.relationships) if (relationship.animation != null)
    relationship.animate = true

  for (state in elements.values) {
    if (state.kind == "actor") {
      if (
        !state.icon.isNullOrEmpty() &&
          !state.actorType.isNullOrEmpty() &&
          state.actorType != "normal"
      ) {
        fail("Actor '${state.id}' cannot combine icon with type '${state.actorType}'", state.span)
      }
      if (state.business == true && (!state.icon.isNullOrEmpty() || state.actorType == "awesome")) {
        fail("Business actor '${state.id}' must use normal or hollow geometry", state.span)
      }
    } else if (state.shape == "rect" && state.business == true) {
      fail("Rectangular use case '${state.id}' cannot be a business use case", state.span)
    }
    publish(state)
  }

  for (draft in drafts.elements.sortedWith { a, b -> a.span[0] - b.span[0] }) {
    if (draft.parent?.id.isNullOrEmpty()) continue
    val members = boundaries[draft.parent!!.id]!!.members
    if (!members.contains(draft.id)) members.add(draft.id)
  }
  for (state in boundaries.values) {
    val boundary = systemBoundaries[state.id]!!
    boundary.type = state.type ?: "rect"
    boundary.members = state.members
    boundary.classes = ArrayList(state.classes)
  }

  var notes = 0
  for (draft in drafts.notes) {
    val origin = symbols[draft.target]
    if (origin == null) return fail("Note target '${draft.target}' is unresolved", draft.span)
    if (origin.kind != "actor" && origin.kind != "usecase") {
      conflict(
        "Note target '${draft.target}' must be an actor or use case, not ${origin.kind}",
        draft.span,
        origin.span,
      )
    }
    val id = "note-${notes++}"
    model.notes[id] = UsecaseNote(id, draft.target, draft.label.text, draft.label.type)
  }

  for (definition in drafts.classDefs) {
    for (id in definition.ids) model.classDefs[id] = ClassDef(id, ArrayList(definition.styles))
  }
  for (assignment in drafts.classes) {
    for (target in assignment.targets) pushUnique(
      stylable(target.id, target.span).first,
      assignment.classes,
    )
  }
  for (assignment in drafts.styles) {
    stylable(assignment.target, assignment.span).second.addAll(assignment.styles)
  }

  model.direction = drafts.direction ?: model.direction
  model.title = title
  model.accTitle = drafts.accTitle
  model.accDescription = drafts.accDescription
  model.accDescr = drafts.accDescription.jsReplace(RE_INDENT, "\n")
  model.source = src
  model.headerSpan = headerSpan
  model.statements = statements
  return model
}
