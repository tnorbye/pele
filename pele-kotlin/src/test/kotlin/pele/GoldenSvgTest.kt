// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele

import java.io.File
import java.security.MessageDigest
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.test.fail
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import pele.js.jsStr
import pele.js.jsonParse
import pele.js.jsonQuote

/** One record of testData/golden/<type>.jsonl.gz, written by scripts/generate_goldens.mjs. */
class GoldenRecord(val type: String, val index: Int, val json: Map<String, Any?>) {
  val src: String
    get() = json["src"] as String

  @Suppress("UNCHECKED_CAST")
  val options: Map<String, Any?>
    get() = json["options"] as Map<String, Any?>

  val ok: Boolean
    get() = json["ok"] == true

  fun renderOptions(): RenderOptions =
    RenderOptions(
      now = 1700000000000.0,
      maxWidth = options["maxWidth"] as Double?,
      links = options["links"] as Boolean?,
      images = options["images"] as Boolean?,
    )

  override fun toString(): String =
    "$type #$index ${jsonStringifyOptions()} ${jsonQuote(src).take(300)}"

  private fun jsonStringifyOptions(): String =
    options.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" }
}

object Goldens {
  val dir: File by lazy {
    val prop = System.getProperty("pele.testData")
    val candidates = listOfNotNull(prop, "testData", "pele-kotlin/testData").map { File(it) }
    candidates.firstOrNull { File(it, "golden").isDirectory }?.let { File(it, "golden") }
      ?: error("testData/golden not found; set -Dpele.testData")
  }

  fun types(): List<String> {
    val only =
      System.getProperty("pele.type")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    val all =
      dir
        .listFiles { f -> f.name.endsWith(".jsonl.gz") }!!
        .map { it.name.removeSuffix(".jsonl.gz") }
        .sorted()
    return if (only.isNullOrEmpty()) all else all.filter { it in only }
  }

  @Suppress("UNCHECKED_CAST")
  fun load(type: String): List<GoldenRecord> {
    val text =
      GZIPInputStream(File(dir, "$type.jsonl.gz").inputStream())
        .readBytes()
        .toString(Charsets.UTF_8)
    return text
      .split('\n')
      .filter { it.isNotEmpty() }
      .mapIndexed { i, line -> GoldenRecord(type, i, jsonParse(line) as Map<String, Any?>) }
  }

  fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {
      "%02x".format(it)
    }

  private val RE_JSON_MESSAGE =
    Regex("""^Invalid JSON: .* (\(line \d+, column \d+\))$""", RegexOption.DOT_MATCHES_ALL)

  // The only accepted difference: engines word JSON.parse errors differently.
  fun normalizeMessage(message: String): String =
    RE_JSON_MESSAGE.replace(message) { "Invalid JSON: ${it.groupValues[1]}" }

  /** Renders the record and returns a description of the first mismatch, or null. */
  fun check(record: GoldenRecord, svgOut: File? = null): String? {
    val json = record.json
    val result: RenderResult
    try {
      result = render(record.src, record.renderOptions())
    } catch (e: PeleError) {
      if (record.ok) return "threw PeleError(${e.code.wireName}): ${e.message}"
      val code = json["errorCode"] as String
      val message = json["errorMessage"] as String
      if (e.code.wireName != code) return "error code ${e.code.wireName} != $code (${e.message})"
      if (normalizeMessage(e.message) != normalizeMessage(message)) {
        return "error message differs:\n  expected: ${jsonQuote(message)}\n  actual:   ${jsonQuote(e.message)}"
      }
      return null
    } catch (e: Throwable) {
      return "crashed: ${e.stackTraceToString().lines().take(12).joinToString("\n    ")}"
    }
    if (!record.ok) return "rendered, expected error ${json["errorCode"]}: ${json["errorMessage"]}"
    val problems = ArrayList<String>()
    if (result.type != json["type"]) problems.add("type ${result.type} != ${json["type"]}")
    if (result.mermaidType != json["mermaidType"])
      problems.add("mermaidType ${result.mermaidType} != ${json["mermaidType"]}")
    if (jsStr(result.width) != jsStr(json["width"] as Double))
      problems.add("width ${jsStr(result.width)} != ${jsStr(json["width"] as Double)}")
    if (jsStr(result.height) != jsStr(json["height"] as Double))
      problems.add("height ${jsStr(result.height)} != ${jsStr(json["height"] as Double)}")
    val links = json["links"] as? List<*>
    if (links == null) {
      problems.add("record has no links")
    } else {
      val expected = links.map { l ->
        (l as Map<*, *>).let { "${it["id"]}|${it["href"]}|${it["internal"]}" }
      }
      val actual = result.links.map { "${it.id}|${it.href}|${it.internal}" }
      if (expected != actual) problems.add("links $actual != $expected")
    }
    if (sha256(result.svg) != json["sha256"]) {
      val svg = json["svg"] as String?
      if (svg != null) {
        problems.add("svg differs: ${firstDifference(svg, result.svg)}")
      } else {
        problems.add("svg sha256 differs")
      }
      if (svgOut != null) {
        svgOut.mkdirs()
        File(svgOut, "${record.type}-${record.index}.actual.svg").writeText(result.svg)
        if (svg != null) File(svgOut, "${record.type}-${record.index}.expected.svg").writeText(svg)
      }
    }
    return if (problems.isEmpty()) null else problems.joinToString("\n  ")
  }

  fun firstDifference(expected: String, actual: String): String {
    var i = 0
    while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
    val from = maxOf(0, i - 120)
    return "at offset $i\n    expected: …${expected.substring(from, minOf(expected.length, i + 160))}\n" +
      "    actual:   …${actual.substring(from, minOf(actual.length, i + 160))}"
  }
}

class GoldenSvgTest {
  // The goldens were rendered in UTC, and gantt reads local time. Gradle sets UTC as well; this
  // keeps the test right when an IDE runs it directly.
  companion object {
    private var savedTimeZone: TimeZone? = null

    @JvmStatic
    @BeforeAll
    fun pinUtcTimeZone() {
      savedTimeZone = TimeZone.getDefault()
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @JvmStatic
    @AfterAll
    fun restoreTimeZone() {
      savedTimeZone?.let(TimeZone::setDefault)
    }
  }

  @TestFactory
  fun goldens(): List<DynamicTest> =
    Goldens.types().map { type ->
      DynamicTest.dynamicTest(type) {
        // Types not ported yet are skipped rather than failed.
        assumeTrue(registered(type), "$type is not registered")
        val svgOut = System.getProperty("pele.svgOut")?.let(::File)
        val records = Goldens.load(type)
        val failures = ArrayList<String>()
        for (record in records) {
          val problem = Goldens.check(record, svgOut) ?: continue
          failures.add("$record\n  $problem")
        }
        if (failures.isNotEmpty()) {
          fail(
            "${failures.size} of ${records.size} $type records differ:\n\n" +
              failures.take(8).joinToString("\n\n")
          )
        }
      }
    }
}
