// Ported from Pele src/diagrams/class/markers.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

import pele.js.jsStr
import pele.svg.marker
import pele.svg.markerTrim
import pele.svg.num

private const val TRIANGLE = 12.0
private const val DIAMOND = 16.0
private const val HALF = 6.0
private const val RING = 5.0

// How far the line stops short of the node so that it meets the back of the marker.
fun classMarkerTrim(type: EndMarker): Double {
  when (type) {
    "extension" -> return TRIANGLE
    "composition", "aggregation" -> return DIAMOND
    "lollipop" -> return 2 * RING
    "dependency" -> return markerTrim("arrow_point")
  }
  return 0.0
}

// Draws an end marker whose tip is at (x, y), pointing along (dx, dy).
fun classMarker(type: EndMarker, x: Double, y: Double, dx: Double, dy: Double, color: String): String {
  val nx = -dy
  val ny = dx
  when (type) {
    "extension" -> {
      val bx = x - dx * TRIANGLE
      val by = y - dy * TRIANGLE
      return "<path class=\"pele-marker\" d=\"M${num(x)},${num(y)}L${num(bx + nx * HALF)},${num(by + ny * HALF)}L${num(
        bx - nx * HALF
      )},${num(by - ny * HALF)}Z\" stroke-linejoin=\"round\"/>"
    }
    "composition", "aggregation" -> {
      val mx = x - (dx * DIAMOND) / 2
      val my = y - (dy * DIAMOND) / 2
      val half = HALF - 1
      return "<path class=\"pele-marker\" d=\"M${num(x)},${num(y)}L${num(mx + nx * half)},${num(my + ny * half)}L${num(
        x - dx * DIAMOND
      )},${num(y - dy * DIAMOND)}L${num(mx - nx * half)},${num(my - ny * half)}Z\" stroke-linejoin=\"round\"${
        if (type == "composition") " fill=\"$color\"" else ""
      }/>"
    }
    "lollipop" -> return "<circle class=\"pele-marker\" cx=\"${num(x - dx * RING)}\" cy=\"${num(y - dy * RING)}\" r=\"${jsStr(RING)}\"/>"
    "dependency" -> return marker("arrow_point", x, y, dx, dy, color)
  }
  return ""
}
