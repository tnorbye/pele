// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import pele.diagrams.common.quotedEnd
import pele.js.JsRegExp
import pele.js.jsMatchAll

class ScannerParityTest {
  @Test
  fun directivesMatchTheOriginalPatternOnShortInputs() {
    val original =
      JsRegExp(
        """%{2}{\s*(?:(\w+)\s*:|(\w+))\s*(?:(\w+)|((?:(?!}%{2}).|\r?\n)*))?\s*(?:}%{2})?""",
        "gi",
      )
    val fragments =
      listOf(
        "%%{",
        "init",
        "initialize",
        "wrap",
        ":",
        "}",
        "}%%",
        " ",
        "\t",
        "\n",
        "\r",
        "\u2028",
        "\u2029",
        "{\"pie\":{\"textPosition\":0.3}}",
        "x",
        "1",
        "😀",
      )
    val random = Random(812)
    val examples =
      listOf(
        "%%{wrap}%%",
        "%%{init:}%%",
        "%%{init: {\"pie\":{\"textPosition\":0.3}}}%%",
        "%%{ init abc }%%",
      ) +
        List(2000) {
          "%%{" + List(random.nextInt(1, 20)) { fragments.random(random) }.joinToString("")
        }
    for (text in examples) {
      val expected = text.jsMatchAll(original).map { Triple(it.index, it.end, it.groups) }
      val actual = directiveMatches(text).map { Triple(it.index, it.end, it.groups) }
      assertEquals(expected, actual, text)
    }
  }

  @Test
  fun quotesMatchTheOriginalPatternIncludingEscapesAndLineBreaks() {
    val original = JsRegExp("""\"([^"\\]|\\.)*"|'([^'\\]|\\.)*'""", "y")
    val fragments = listOf("a", "'", "\"", "\\", "\n", "\r", "\u2028", "\u2029", "😀", "\\n")
    val random = Random(918)
    repeat(2000) {
      val text =
        listOf("\"", "'").random(random) +
          List(random.nextInt(0, 20)) { fragments.random(random) }.joinToString("")
      assertEquals(original.matchAt(text, 0)?.end ?: -1, quotedEnd(text, 0, -1), text)
    }
  }
}
