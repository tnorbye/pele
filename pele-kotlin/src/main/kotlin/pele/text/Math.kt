// Ported from Pele src/text/math.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.text

import pele.MathMarkup
import pele.MathResolver
import pele.js.jsStr

// PORT: per-thread state, as renders may run concurrently.
private class MathState(var resolver: MathResolver?, val found: MutableMap<String, MathMarkup?>)

private val state = ThreadLocal.withInitial { MathState(null, HashMap()) }

fun <T> withMath(math: MathResolver?, run: () -> T): T {
  val outer = state.get()
  state.set(MathState(math, HashMap()))
  try {
    return run()
  } finally {
    state.set(outer)
  }
}

fun mathActive(): Boolean = state.get().resolver != null

private fun valid(size: Double): Boolean = !size.isNaN() && !size.isInfinite() && size > 0

fun typeset(tex: String, size: Double): MathMarkup? {
  val s = state.get()
  val resolver = s.resolver ?: return null
  val key = jsStr(size) + "\n" + tex
  if (s.found.containsKey(key)) return s.found[key]
  val result = resolver(tex, size)
  val markup = if (result != null && valid(result.width) && valid(result.height)) result else null
  s.found[key] = markup
  return markup
}

// Match Mermaid: diagram titles stay plain text.
fun <T> withoutMath(run: () -> T): T {
  val s = state.get()
  if (s.resolver == null) return run()
  val outer = s.resolver
  s.resolver = null
  try {
    return run()
  } finally {
    s.resolver = outer
  }
}
