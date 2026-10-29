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
}
