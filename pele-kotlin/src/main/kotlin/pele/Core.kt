// Ported from Pele src/core.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele

import pele.diagrams.all
import pele.js.jsOrdered
import pele.js.jsString
import pele.text.decodeEntities
import pele.text.withIcons
import pele.text.withMath
import pele.text.writtenCodes
import java.util.concurrent.ConcurrentHashMap

// PORT: mount() and enableZoom() are browser-only and not ported.

// PORT: the full entry point registers every type, so the map starts with all of them.
private val diagrams: MutableMap<DiagramType, Diagram<Any?>> = ConcurrentHashMap<DiagramType, Diagram<Any?>>().also { map ->
  @Suppress("UNCHECKED_CAST")
  for (diagram in all) map[diagram.type] = diagram as Diagram<Any?>
}

// Makes diagram types available to render() and parse(). The full entry point registers every
// type; an app that imports this module picks the ones it needs.
fun register(vararg list: Diagram<*>) {
  @Suppress("UNCHECKED_CAST")
  for (diagram in list) diagrams[diagram.type] = diagram as Diagram<Any?>
}

fun registered(type: DiagramType): Boolean = diagrams.containsKey(type)

// Mermaid's default maxTextSize.
private const val DEFAULT_LIMIT = 50000

// Mermaid has no limit on its output. A source within the limit can still describe a drawing
// of many megabytes, which would stall the page it is put into.
private const val DEFAULT_OUTPUT_LIMIT = 4_000_000

// Mermaid stops at 500. Pele draws 5,000 edges in tens of milliseconds, and `A & B --> C & D`
// multiplies: without a limit, a few kilobytes of source ask for millions of edges.
private const val DEFAULT_MAX_EDGES = 5000

// Deep nesting can exhaust the stack in a few recursive spots. Report it as a limit, not a crash.
private fun <T> guarded(run: () -> T): T {
  try {
    return run()
  } catch (error: StackOverflowError) {
    throw PeleError("Diagram is nested too deeply.", PeleErrorCode.LIMIT)
  }
}

private fun <T> tagErrors(found: Detected, run: () -> T): T {
  try {
    return run()
  } catch (error: StackOverflowError) {
    throw PeleError("Diagram is nested too deeply.", PeleErrorCode.LIMIT, found)
  } catch (error: PeleError) {
    if (error.type == null) error.type = found.type
    if (error.mermaidType == null) error.mermaidType = found.mermaidType
    val message = writtenCodes(error.message)
    if (message != error.message) error.message = message
    error.snippet = writtenCodes(error.snippet)
    throw error
  }
}

// PORT: upstream adds `mermaidType` to the model object and keeps the rest in a WeakMap keyed by
// it. Here parse() returns this wrapper, which holds what render() needs.
class ParsedDiagramModel internal constructor(
  val model: Any?,
  val mermaidType: MermaidType,
  internal val parsed: Parsed,
)

internal class Parsed(
  val type: DiagramType,
  val mermaidType: MermaidType,
  val diagram: Diagram<Any?>,
  val model: Any?,
  val config: Config,
)

// PORT: a host's config holds Kotlin values. Turn them into what front matter and directives give
// the diagrams, which read config as JS would: numbers as Double, objects as String-keyed maps.
@Suppress("UNCHECKED_CAST")
private fun jsValue(value: Any?): Any? = when (value) {
  is Double -> value
  is Number -> value.toDouble()
  is Map<*, *> -> jsValues(value as Map<Any?, Any?>)
  is Iterable<*> -> value.mapTo(ArrayList(), ::jsValue)
  is Array<*> -> value.mapTo(ArrayList(), ::jsValue)
  is Char -> value.toString()
  else -> value
}

private fun jsValues(map: Map<*, *>): Config =
  jsOrdered(LinkedHashMap<String, Any?>().also { out -> for ((k, v) in map) out[jsString(k)] = jsValue(v) })

private fun parseSource(text: String, limit: Int?, maxEdges: Int?, extra: Map<String, Any?>?): Parsed {
  val max = limit ?: DEFAULT_LIMIT
  if (text.length > max) {
    throw PeleError("Diagram source is longer than the limit of $max characters.", PeleErrorCode.LIMIT)
  }
  val pre = preprocess(text)
  val found = detectDiagram(pre.text)
    ?: throw PeleError("No diagram type detected for the given text.", PeleErrorCode.UNSUPPORTED_DIAGRAM)
  val diagram = diagrams[found.type]
    ?: throw PeleError("Diagram type \"${found.type}\" is not supported yet.", PeleErrorCode.UNSUPPORTED_DIAGRAM, found)
  val config: Config = if (extra != null) jsValues(extra).also { it.putAll(pre.config) } else pre.config
  val keepComments = diagram.keepComments
  val parsed = encodeEntities(if (keepComments) pre.withComments else pre.text) + "\n"
  val model = tagErrors(found) {
    locatingIn(Origin(parsed, inSource(text, keepComments))) {
      diagram.parse(parsed, config, pre.title, pre.lineOffset, ParseLimits(maxEdges ?: DEFAULT_MAX_EDGES))
    }
  }
  return Parsed(found.type, found.mermaidType, diagram, model, config)
}

// Record preprocessing edits only when a source position is needed.
private fun inSource(text: String, keepComments: Boolean): (Int) -> Pair<String, Int> {
  var steps: MutableList<List<Edit>>? = null
  var source = ""
  return { at ->
    if (steps == null) {
      val s = ArrayList<List<Edit>>()
      steps = s
      val pre = preprocess(text, s)
      encodeEntities(if (keepComments) pre.withComments else cleanupComments(pre.withComments, s), s)
      // Match the line break appended for parsing.
      source = normalizeLineBreaks(text) + "\n"
    }
    source to inputOffset(steps!!, at)
  }
}

fun detectType(text: String): DiagramType? = detectDiagram(preprocess(text).text)?.type

fun supports(text: String): Boolean =
  try {
    val type = detectType(text)
    type != null && diagrams.containsKey(type)
  } catch (_: Exception) {
    false
  } catch (_: StackOverflowError) {
    // As in guarded(): deeply nested input can still exhaust the stack while preprocessing.
    false
  }

// The options that are read when the text is parsed. A render() that is given a model has
// nothing left to do with them.
data class ParseOptions(val limit: Int? = null, val maxEdges: Int? = null, val config: Map<String, Any?>? = null)

fun parse(text: String, options: ParseOptions = ParseOptions()): ParsedDiagramModel = guarded {
  val parsed = parseSource(text, options.limit, options.maxEdges, options.config)
  ParsedDiagramModel(parsed.model, parsed.mermaidType, parsed)
}

fun render(source: String, options: RenderOptions = RenderOptions()): RenderResult = guarded {
  renderParsed(parseSource(source, options.limit, options.maxEdges, options.config), options)
}

fun render(source: ParsedDiagramModel, options: RenderOptions = RenderOptions()): RenderResult = guarded {
  renderParsed(source.parsed, options)
}

private fun renderParsed(parsed: Parsed, options: RenderOptions): RenderResult {
  val type = parsed.type
  val mermaidType = parsed.mermaidType
  val diagram = parsed.diagram
  val config = parsed.config
  val section = config[diagram.section ?: type]
  val fixed = section is Map<*, *> && section["useMaxWidth"] == false
  val responsive = options.responsive ?: !fixed
  // The host's icon resolver and the ids in `links` see names as the author meant them, with
  // entity codes turned into their characters, as `data-icon` and `data-id` in the SVG do.
  val resolve = options.icons
  val icons: IconResolver? = if (resolve != null) { name -> resolve(decodeEntities(name)) } else null
  val rendered = tagErrors(Detected(type, mermaidType)) {
    withMath(if (diagram.math) options.math else null) {
      withIcons(icons) { diagram.render(parsed.model, config, options.copy(responsive = responsive, icons = icons)) }
    }
  }
  val outputLimit = options.outputLimit ?: DEFAULT_OUTPUT_LIMIT
  if (rendered.svg.length > outputLimit) {
    throw PeleError(
      "Diagram output is longer than the limit of $outputLimit characters.",
      PeleErrorCode.LIMIT,
      PeleErrorInfo(type = type, mermaidType = mermaidType),
    )
  }
  // PORT: LinkInfo is immutable, so the decoded ids go into new values instead of the old ones.
  val links = rendered.links.map { link ->
    val id = decodeEntities(link.id)
    if (id === link.id) link else link.copy(id = id)
  }
  return RenderResult(type, mermaidType, rendered.svg, rendered.width, rendered.height, links)
}
