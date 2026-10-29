// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import pele.text.TextMeasurer
import pele.text.metricsMeasurer

class PeleTest {
  // Expected sizes from upstream in Node.
  @Test
  fun hostConfigNumbersAreNumbers() {
    val source = "packet-beta\n0-15: \"Header\""
    val ints = render(source, RenderOptions(config = mapOf("packet" to mapOf("bitsPerRow" to 8))))
    val doubles = render(source, RenderOptions(config = mapOf("packet" to mapOf("bitsPerRow" to 8.0))))
    assertEquals(267.0 to 113.0, ints.width to ints.height)
    assertEquals(doubles.svg, ints.svg)
  }

  @Test
  fun keywordsAreNotMatchedBySpecialCaseLetters() {
    assertEquals(16.0 to 16.0, render("stateDiagram-v2\nstate A <<forK>>").let { it.width to it.height })
    val error = runCatching { render("gantt\nsectıon A\nt :a, 2024-01-01, 1d", RenderOptions(now = 1700000000000.0)) }
    assertEquals(PeleErrorCode.SYNTAX, (error.exceptionOrNull() as PeleError).code)
  }

  @Test
  fun largeFlatInputsDoNotNeedALargeThreadStack() {
    val text = "a".repeat(40_000)
    val options = RenderOptions(now = 1700000000000.0)
    val pie = "pie\n\"A\": 1"
    val directive = "%%{init: {\"theme\":\"$text\"}}%%\n$pie"
    assertTrue(supports(directive))
    assertEquals(render(pie, options).svg, render(directive, options).svg)
    for (source in listOf("railroad-ebnf-beta\nr = \"$text\";", "pie\n\"$text\": 1")) {
      assertTrue(render(source, options).svg.contains(text))
    }
  }

  @Test
  fun parsedModelsPreserveHostCallbacksAcrossNestedRendersAndFailures() {
    val source = "flowchart LR\nA[\"fa:fa-star \$\$x\$\$\"]"
    val model = parse(source)
    val options = RenderOptions(
      icons = { "<path d=\"M0,0H10\"/>" },
      math = { _, _ -> MathMarkup.Svg("<path d=\"M0,0H30\"/>", 30.0, 18.0) },
      measurer = TextMeasurer { text, size, style -> metricsMeasurer.width(text, size, style) * 1.2 },
    )
    val expected = render(source, options).svg
    assertTrue(expected.contains("data-icon=\"fa:fa-star\""))
    assertEquals(expected, render(model, options).svg)
    var calls = 0
    val nested = options.copy(math = { tex, size ->
      calls++
      render("pie\n\"A\": 1")
      options.math!!(tex, size)
    })
    assertEquals(expected, render(model, nested).svg)
    assertTrue(calls > 0)
    assertFailsWith<IllegalStateException> {
      render(model, options.copy(math = { _, _ -> error("typesetter failed") }))
    }
    assertEquals(expected, render(model, options).svg)
  }

  @Test
  fun agentflowSemanticModelAndDiagnostics() {
    val parsed = parse(
      """
      agentflow-beta TB
        In@{ shape: input, label: "Prompt" }
        Gate@{ shape: decision, label: "Valid?" }
        Old@{ shape: stadium, label: "Legacy" }
        In --> Gate --> Old
      """.trimIndent(),
    )
    val semantic = (parsed.model as pele.diagrams.agentflow.AgentflowModel).semanticModel()
    assertEquals("TB", semantic.direction)
    assertEquals(
      listOf("In" to "input", "Gate" to "decision", "Old" to "task"),
      semantic.vertices.map { it.id to it.vertexKind },
    )
    assertEquals(2, semantic.edges.size)
    assertEquals(listOf("SHAPE_REMOVED"), semantic.diagnostics.map { it.id })
  }

  @Test
  fun svgNumbersAndEscaping() {
    assertEquals("0", pele.svg.num(0))
    assertEquals("-42", pele.svg.num(-42))
    assertEquals("0", pele.svg.num(-0.004))
    assertEquals("12.3", pele.svg.num(12.3))
    assertEquals("12.35", pele.svg.num(12.345))
    assertEquals("100000000000000000000", pele.svg.num(1e20))
    assertEquals("-100000000000000000000", pele.svg.num(-1e20))
    assertEquals("NaN", pele.svg.num(Double.NaN))
    assertEquals("Infinity", pele.svg.num(Double.POSITIVE_INFINITY))
    assertEquals("-Infinity", pele.svg.num(Double.NEGATIVE_INFINITY))
    // A lone surrogate and C0 controls are dropped; a surrogate pair and tab, line feed and return stay.
    assertEquals("Hi &amp; &lt;ok&gt;", pele.svg.esc("Hi \u0000\uD83D& <ok>"))
    assertEquals("\uD83D\uDE00\t\n\r", pele.svg.esc("\uD83D\uDE00\t\n\r\u007f\uDE00\uFFFE"))
    assertEquals("a\uD83D\uDE00b", pele.svg.stripInvalidXml("a\u0001\uD83D\uDE00\uD83Db"))
  }

  @Test
  fun inputEdgeAndOutputLimitsRaiseLimitErrors() {
    // Messages from upstream in Node.
    val tooLong = assertFailsWith<PeleError> { parse("flowchart LR\nA --> B", ParseOptions(limit = 5)) }
    assertEquals(PeleErrorCode.LIMIT, tooLong.code)
    assertEquals("Diagram source is longer than the limit of 5 characters.", tooLong.message)
    val tooManyEdges = assertFailsWith<PeleError> { render("flowchart LR\nA & B & C --> D & E & F", RenderOptions(maxEdges = 4)) }
    assertEquals(PeleErrorCode.LIMIT, tooManyEdges.code)
    assertEquals("Edge limit exceeded. The limit is 4.", tooManyEdges.message)
    val tooLarge = assertFailsWith<PeleError> { render("flowchart LR\nA --> B", RenderOptions(outputLimit = 20)) }
    assertEquals(PeleErrorCode.LIMIT, tooLarge.code)
    assertEquals("Diagram output is longer than the limit of 20 characters.", tooLarge.message)
  }

  @Test
  fun publicValueTypes() {
    assertEquals(LinkInfo("a", "https://x", false), LinkInfo("a", "https://x", false))
    assertEquals("unsupported-diagram", PeleErrorCode.UNSUPPORTED_DIAGRAM.toString())
    val result = render("flowchart LR\nA\nclick A \"https://example.com\"")
    assertEquals(listOf(LinkInfo("A", "https://example.com/", false)), result.links)
  }
}
