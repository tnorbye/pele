// Ported from Pele src/text/icons.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.text

import pele.IconResolver

// The host's icons for the render in progress. A label leaves room for an icon only when the
// host has one by that name, and layout has to know that before anything is drawn.
// PORT: per-thread state, as renders may run concurrently.
private class IconState(val resolver: IconResolver?, val found: MutableMap<String, String>)

private val state = ThreadLocal.withInitial { IconState(null, HashMap()) }

fun <T> withIcons(icons: IconResolver?, run: () -> T): T {
  val outer = state.get()
  state.set(IconState(icons, HashMap()))
  try {
    return run()
  } finally {
    state.set(outer)
  }
}

fun iconMarkup(name: String): String {
  val s = state.get()
  val resolver = s.resolver ?: return ""
  var markup = s.found[name]
  if (markup == null) {
    markup = resolver(name) ?: ""
    s.found[name] = markup
  }
  return markup
}
