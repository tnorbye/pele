// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele

import pele.js.jsStr
import pele.js.jsonQuote
import java.io.File
import java.util.TimeZone

// Renders each input of a fuzz file: one line per input, in the format of scripts/up.mjs.
fun main(args: Array<String>) {
  TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  val inputs = File(args[0]).readText().split("\n=====\n")
  val out = StringBuilder()
  for (src in inputs) {
    val line = try {
      val r = render(src, RenderOptions(now = 1700000000000.0))
      "OK ${r.type} ${jsStr(r.width)}x${jsStr(r.height)} ${Goldens.sha256(r.svg).take(16)}"
    } catch (e: PeleError) {
      "ERR ${e.code.wireName} ${jsonQuote(e.message)}"
    } catch (e: Throwable) {
      // The stack goes to stderr, so the comparison sees only the one line.
      System.err.println("CRASH in input #${out.lines().size - 1}: ${e.stackTraceToString().lines().take(12).joinToString("\n  ")}")
      "CRASH ${jsonQuote(e.toString())}"
    }
    out.append(line).append('\n')
  }
  print(out)
}
