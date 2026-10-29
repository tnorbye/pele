// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele

import java.lang.management.ManagementFactory

fun main() {
  val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
  if (bean?.isThreadAllocatedMemorySupported == true) bean.isThreadAllocatedMemoryEnabled = true
  val thread = Thread.currentThread().id
  fun allocated(): Long = if (bean?.isThreadAllocatedMemoryEnabled == true) bean.getThreadAllocatedBytes(thread) else -1
  val options = RenderOptions(maxWidth = 320.0)
  fun source(count: Int): String = "journey\nsection A\n" + "Task: 5: A\n".repeat(count)
  repeat(10) { render(source(100), options) }
  for (count in listOf(500, 1000, 2000, 3000)) {
    val input = source(count)
    val before = allocated()
    val start = System.nanoTime()
    val result = render(input, options)
    val elapsed = (System.nanoTime() - start) / 1_000_000
    val bytes = if (before >= 0) (allocated() - before).toString() else "unavailable"
    println("tasks=$count sourceChars=${input.length} svgChars=${result.svg.length} elapsedMs=$elapsed allocatedBytes=$bytes")
  }
}
