// Ported from Pele src/types.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele

import pele.text.TextMeasurer

typealias IconResolver = (name: String) -> String?

// Dimensions are in pixels. HTML requires the typesetter's CSS.
sealed interface MathMarkup {
  val width: Double
  val height: Double

  data class Svg(val svg: String, override val width: Double, override val height: Double) :
    MathMarkup

  data class Html(val html: String, override val width: Double, override val height: Double) :
    MathMarkup
}

// TeX excludes the $$ delimiters; font size is in pixels.
typealias MathResolver = (tex: String, size: Double) -> MathMarkup?

// 'members' | 'attributes' | 'files' | 'messages'
typealias MonospacePart = String

data class RenderOptions(
  val measurer: TextMeasurer? = null,
  // The fonts labels are measured in, and drawn in where the page sets no --pele-font or
  // --pele-font-mono.
  val fontFamily: String? = null,
  val fontFamilyMono: String? = null,
  val monospace: List<MonospacePart>? = null,
  val fontSize: Double? = null,
  val idPrefix: String? = null,
  val responsive: Boolean? = null,
  // The width the host has for the diagram, in pixels. A type that can draw itself narrower
  // does, so that its text is not shrunk with it.
  val maxWidth: Double? = null,
  // Whether a diagram that runs across may be drawn running down when it does not fit
  // `maxWidth`, and the width under which that is done. On by default, under 640 pixels.
  val autoDirection: Boolean? = null,
  val directionBreakpoint: Double? = null,
  val padding: Double? = null,
  val limit: Int? = null,
  val outputLimit: Int? = null,
  // The most edges a flowchart may have. One short line can ask for millions of them.
  val maxEdges: Int? = null,
  // Schemes a link or an image may use besides a relative address.
  val linkSchemes: List<String>? = null,
  val imageSchemes: List<String>? = null,
  val links: Boolean? = null,
  val images: Boolean? = null,
  // Link `rel` attribute. Defaults to `noopener`; an empty string omits it.
  val linkRel: String? = null,
  val icons: IconResolver? = null,
  val math: MathResolver? = null,
  val config: Map<String, Any?>? = null,
  // The time gantt charts treat as now, in epoch milliseconds. Defaults to the current time.
  val now: Double? = null,
)

data class LinkInfo(val id: String, val href: String, val internal: Boolean)

open class Rendered(
  val svg: String,
  val width: Double,
  val height: Double,
  val links: List<LinkInfo>,
)

class RenderResult(
  val type: DiagramType,
  val mermaidType: MermaidType,
  svg: String,
  width: Double,
  height: Double,
  links: List<LinkInfo>,
) : Rendered(svg, width, height, links)

// What a diagram type provides. `source` has been through preprocessing and entity encoding
// and ends with a newline, as Mermaid's parsers expect.
// Limits set by the host. A diagram's own configuration cannot change them.
data class ParseLimits(val maxEdges: Int)

interface Diagram<Model> {
  val type: DiagramType

  // The name of this type's section in Mermaid's config, when it is not the type's own name.
  val section: String?
    get() = null

  // A diagram that reports source positions is given the text with its `%%` comment lines kept.
  // `lineOffset` is the number of front matter lines that were removed before that text.
  val keepComments: Boolean
    get() = false

  // Enables $$...$$ in labels.
  val math: Boolean
    get() = false

  fun parse(
    source: String,
    config: Config,
    title: String?,
    lineOffset: Int,
    limits: ParseLimits,
  ): Model

  fun render(model: Model, config: Config, options: RenderOptions): Rendered
}
