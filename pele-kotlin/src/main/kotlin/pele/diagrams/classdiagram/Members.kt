// Ported from Pele src/diagrams/class/members.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.diagrams.common.parseGenericTypes
import pele.js.charCodeAt
import pele.js.jsSlice
import pele.js.jsSubstring
import pele.js.jsTrim
import pele.util.isLineEnd

// '#' | '+' | '~' | '-' | ''
typealias Visibility = String

private fun isVisibility(ch: String): Boolean = ch == "#" || ch == "+" || ch == "~" || ch == "-"

class ClassMember(input: String, memberType: String) {
  var id = ""
  // 'method' | 'attribute'
  var memberType: String = memberType
  var visibility: Visibility = ""
  var classifier = ""
  var parameters = ""
  var returnType = ""

  init {
    // Mermaid throws a TypeError for a method with no opening parenthesis, such as `run)`.
    // It is kept here as an attribute, so it is shown as written.
    if (memberType != "method" || !parseMethod(input)) parseAttribute(input)
    id = if (id.startsWith(" ")) " " + id.jsTrim() else id.jsTrim()
  }

  // The same split as Mermaid's /([#+~-])?(.+)\((.*)\)([\s$*])?(.*)([$*])?/, found in one pass:
  // the first line with an opening parenthesis after its first character and a closing one later.
  private fun parseMethod(input: String): Boolean {
    val n = input.length
    var start = 0
    while (start < n) {
      var end = start
      while (end < n && !isLineEnd(input.charCodeAt(end))) end++
      var close = end - 1
      while (close > start && input.charCodeAt(close) != 41) close--
      var open = close - 1
      while (open > start && input.charCodeAt(open) != 40) open--
      if (open > start) {
        val first = input[start].toString()
        val visible = isVisibility(first) && open > start + 1
        if (visible) visibility = first
        id = input.jsSlice(if (visible) start + 1 else start, open)
        parameters = input.jsSlice(open + 1, close).jsTrim()
        var rest = close + 1
        var classifier = ""
        if (rest < n) {
          val next = input[rest].toString()
          if (next == "$" || next == "*" || next.jsTrim() == "") {
            classifier = next.jsTrim()
            rest++
          }
        }
        var stop = rest
        while (stop < n && !isLineEnd(input.charCodeAt(stop))) stop++
        returnType = input.jsSlice(rest, stop).jsTrim()
        if (classifier == "") {
          val last = returnType.jsSlice(-1)
          if (last == "$" || last == "*") {
            classifier = last
            returnType = returnType.jsSlice(0, -1)
          }
        }
        this.classifier = classifier
        return true
      }
      start = end + 1
    }
    memberType = "attribute"
    return false
  }

  private fun parseAttribute(input: String) {
    val first = input.jsSlice(0, 1)
    val last = input.jsSlice(-1)
    if (isVisibility(first)) visibility = first
    if (last == "$" || last == "*") classifier = last
    id = input.jsSubstring(if (visibility == "") 0 else 1, if (classifier == "") input.length else input.length - 1)
  }

  fun getDisplayDetails(): DisplayDetails {
    var displayText = visibility + parseGenericTypes(id)
    if (memberType == "method") {
      displayText += "(${parseGenericTypes(parameters.jsTrim())})"
      if (returnType.isNotEmpty()) displayText += " : " + parseGenericTypes(returnType)
    }
    return DisplayDetails(displayText.jsTrim(), parseClassifier())
  }

  fun parseClassifier(): String =
    if (classifier == "*") "font-style:italic;" else if (classifier == "$") "text-decoration:underline;" else ""
}

// PORT: `{ displayText, cssStyle }`.
class DisplayDetails(val displayText: String, val cssStyle: String)
