// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

import pele.Goldens
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.fail

// Compares whatwgHref with Node's `new URL(x).href` on URLs from scripts/generate_urls.mjs.
class UrlTest {
  @Test
  fun matchesNode() {
    val file = File(Goldens.dir.parentFile, "urls/urls.json.gz")
    val cases = jsonParse(GZIPInputStream(file.inputStream()).readBytes().toString(Charsets.UTF_8)) as List<*>
    val failures = cases.mapNotNull { c ->
      val (input, expected) = c as List<*>
      val actual = whatwgHref(input as String)
      if (actual == expected) null else "${jsonQuote(input)}: expected ${expected?.let { jsonQuote(it as String) }}, got ${actual?.let(::jsonQuote)}"
    }
    if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} URLs differ:\n" + failures.take(25).joinToString("\n"))
  }
}
