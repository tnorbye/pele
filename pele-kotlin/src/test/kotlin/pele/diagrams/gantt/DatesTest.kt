// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.diagrams.gantt

import org.junit.jupiter.api.Test
import pele.js.jsStr
import pele.js.jsonParse
import java.io.File
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.test.fail

/** JsDate against V8, with values recorded by scripts/generate_dates.mjs. */
class DatesTest {
  @Suppress("UNCHECKED_CAST")
  private val data: Map<String, Any?> by lazy {
    val candidates = listOf("testData/dates/dates.json.gz", "pele-kotlin/testData/dates/dates.json.gz").map { File(it) }
    val file = candidates.firstOrNull { it.isFile } ?: error("testData/dates/dates.json.gz not found")
    jsonParse(GZIPInputStream(file.inputStream()).readBytes().toString(Charsets.UTF_8)) as Map<String, Any?>
  }

  private fun number(v: Any?): Double = if (v is String) Double.NaN else v as Double

  private fun withZone(id: String, body: () -> Unit) {
    val saved = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(id))
    try {
      body()
    } finally {
      TimeZone.setDefault(saved)
    }
  }

  private fun check(kind: String, failures: List<String>, total: Int) {
    if (failures.isNotEmpty()) fail("${failures.size} of $total $kind differ:\n" + failures.take(15).joinToString("\n"))
  }

  @Suppress("UNCHECKED_CAST")
  private fun forEachZone(body: (zone: String, expected: Map<String, Any?>) -> Unit) {
    val zones = data["zones"] as Map<String, Map<String, Any?>>
    for ((zone, expected) in zones) withZone(zone) { body(zone, expected) }
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun parseMatchesV8() {
    val strings = data["strings"] as List<String>
    forEachZone { zone, expected ->
      val results = expected["parse"] as List<Any?>
      val failures = ArrayList<String>()
      strings.forEachIndexed { i, s ->
        val want = number(results[i])
        val got = JsDate.parse(s)
        if (jsStr(want) != jsStr(got)) failures.add("$zone ${pele.js.jsonQuote(s)}: expected ${jsStr(want)}, got ${jsStr(got)}")
      }
      check("Date.parse results in $zone", failures, strings.size)
    }
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun constructorMatchesV8() {
    val cases = data["ctor"] as List<List<Double>>
    forEachZone { zone, expected ->
      val results = expected["ctor"] as List<Any?>
      val failures = ArrayList<String>()
      cases.forEachIndexed { i, c ->
        val want = number(results[i])
        val got = JsDate.local(c[0], c[1], c[2], c[3], c[4], c[5], c[6]).getTime()
        if (jsStr(want) != jsStr(got)) failures.add("$zone $c: expected ${jsStr(want)}, got ${jsStr(got)}")
      }
      check("constructor results in $zone", failures, cases.size)
    }
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun fieldsMatchV8() {
    val instants = data["instants"] as List<Double>
    forEachZone { zone, expected ->
      val results = expected["fields"] as List<List<Any?>>
      val failures = ArrayList<String>()
      instants.forEachIndexed { i, t ->
        val d = JsDate(t)
        val got = listOf(
          d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds(), d.getMilliseconds(), d.getDay(),
          d.getTimezoneOffset(),
        ).map { jsStr(it) }
        val want = results[i].map { jsStr(number(it)) }
        if (got != want) failures.add("$zone ${jsStr(t)}: expected $want, got $got")
      }
      check("local fields in $zone", failures, instants.size)
    }
  }

  @Test
  fun hugeValuesAreInvalidNotErrors() {
    withZone("America/Los_Angeles") {
      for (v in listOf(1e20, -1e20, 8.64e15 + 3e12, -8.64e15 - 3e12, 1e300, Double.MAX_VALUE)) {
        val d = JsDate.local(2014.0, 0.0, 1.0, 0.0, 0.0, 0.0, v)
        if (!d.getTime().isNaN()) fail("expected an invalid date for ${jsStr(v)}")
        if (!JsDate(v).getTime().isNaN()) fail("expected an invalid date for new Date(${jsStr(v)})")
      }
      val d = JsDate.local(2014.0, 0.0, 1.0)
      if (!d.setDate(1e20).isNaN() || !d.setFullYear(1e20).isNaN()) fail("setters must give NaN")
    }
  }

  @Test
  fun hugeDurationSeedRenders() {
    val dir = listOf("testData/seeds", "pele-kotlin/testData/seeds").map { File(it) }.first { it.isDirectory }
    val src = File(dir, "gantt-huge-duration.mmd").readText()
    val result = pele.render(src, pele.RenderOptions(now = 1700000000000.0))
    if (result.width != 800.0 || result.height != 197.0) fail("expected 800x197, got ${result.width}x${result.height}")
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(result.svg.toByteArray()).joinToString("") { "%02x".format(it) }
    if (!sha.startsWith("9e4fb9e59fbdbd92")) fail("svg differs from upstream: $sha")
  }
}
