// Ported from Pele src/diagrams/common/caseless.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.js.charCodeAt

// Whether `word`, written in lower case, is at `at`. Only ASCII letters match in either case,
// as with the `i` flag on a regular expression without the `u` flag.
fun wordAt(src: String, at: Int, word: String): Boolean {
  for (k in word.indices) {
    val c = src.charCodeAt(at + k)
    val w = word[k].code
    if (c != w && !(w >= 97 && w <= 122 && c == w - 32)) return false
  }
  return true
}
