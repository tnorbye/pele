// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele

import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

// Renders every golden record of every registered type on several threads at once. Catches state
// that the port shares between concurrent renders (UPDATE.md).
class ConcurrentGoldenSvgTest {
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

  @Test
  fun concurrentRenders() {
    val records = Goldens.types().filter(::registered).flatMap(Goldens::load)
    if (records.isEmpty()) return
    val work = ArrayList<GoldenRecord>()
    val random = Random(42)
    repeat(4) { work.addAll(records.shuffled(random)) }
    val chunks = work.chunked(maxOf(1, work.size / 32))
    val pool = Executors.newFixedThreadPool(8)
    try {
      val results =
        pool.invokeAll(
          chunks.map { chunk ->
            Callable { chunk.mapNotNull { r -> Goldens.check(r)?.let { "$r\n  $it" } } }
          }
        )
      val failures = results.flatMap { it.get() }
      if (failures.isNotEmpty())
        fail(
          "${failures.size} of ${work.size} concurrent renders differ:\n\n" +
            failures.take(5).joinToString("\n\n")
        )
    } finally {
      pool.shutdownNow()
    }
  }
}
