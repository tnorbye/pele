// Ported from Pele src/diagrams/common/generics.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.js.JsRegExp
import pele.js.jsSplitStrings

private fun tildes(text: String): Int {
  var count = 0
  var i = text.indexOf('~')
  while (i != -1) {
    count++
    i = text.indexOf('~', i + 1)
  }
  return count
}

// Pairs tildes from the outside in: the first with the last, the second with the one before it.
private fun pairTildes(text: String): String {
  val count = tildes(text)
  if (count <= 1) return text
  val lead = count % 2 != 0 && text.startsWith("~")
  val chars = text.toCharArray()
  val at = ArrayList<Int>()
  for (i in (if (lead) 1 else 0) until chars.size) if (chars[i] == '~') at.add(i)
  var lo = 0
  var hi = at.size - 1
  while (lo < hi) {
    chars[at[lo]] = '<'
    chars[at[hi]] = '>'
    lo++
    hi--
  }
  return String(chars)
}

private val RE_COMMA = JsRegExp("(,)")

// Turns `List~int~` into `List<int>`, with the quirks of Mermaid's parseGenericTypes around commas.
fun parseGenericTypes(input: String): String {
  if (!input.contains('~')) return input
  val sets = input.jsSplitStrings(RE_COMMA)
  val output = ArrayList<String>()
  var i = 0
  while (i < sets.size) {
    var set = sets[i]
    if (set == "," && i > 0 && i + 1 < sets.size && tildes(sets[i - 1]) == 1 && tildes(sets[i + 1]) == 1) {
      set = sets[i - 1] + "," + sets[i + 1]
      i++
      output.removeAt(output.size - 1)
    }
    output.add(pairTildes(set))
    i++
  }
  return output.joinToString("")
}
