// Ported from Pele src/diagrams/er/markers.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

import pele.js.jsStr
import pele.svg.num

// How far a marker reaches back along its line from the box it touches.
const val MARKER_LENGTH = 18.0

private const val BAR = 5.0
private const val FOOT = 6.0
private const val FOOT_LENGTH = 10.0
private const val RING = 3.5

// Draws a crow's foot cardinality marker over the end of a line that meets a box at (x, y)
// while travelling along the unit vector (dx, dy). Types are Mermaid's lower-cased cardinalities.
fun erMarker(type: String, x: Double, y: Double, dx: Double, dy: Double): String {
  val at = { back: Double, side: Double -> num(x - dx * back - dy * side) + "," + num(y - dy * back + dx * side) }
  val bar = { back: Double -> "M${at(back, -BAR)}L${at(back, BAR)}" }
  val foot = "M${at(0.0, -FOOT)}L${at(FOOT_LENGTH, 0.0)}L${at(0.0, FOOT)}"
  val ring = { back: Double ->
    "<circle class=\"pele-marker\" cx=\"${num(x - dx * back)}\" cy=\"${num(y - dy * back)}\" r=\"${jsStr(RING)}\" fill=\"var(--_bg)\"/>"
  }
  val path = { d: String -> "<path class=\"pele-marker\" d=\"$d\"/>" }

  when (type) {
    "only_one" -> return path(bar(6.0) + bar(10.0))
    "zero_or_one" -> return path(bar(6.0)) + ring(13.5)
    "one_or_more" -> return path(foot + bar(FOOT_LENGTH + 3.5))
    "zero_or_more" -> return path(foot) + ring(FOOT_LENGTH + RING + 0.5)
    "md_parent" ->
      return "<path class=\"pele-marker\" d=\"M${at(0.0, 0.0)}L${at(6.0, 4.0)}L${at(12.0, 0.0)}L${at(6.0, -4.0)}Z\" fill=\"var(--_bg)\"/>"
  }
  return ""
}
