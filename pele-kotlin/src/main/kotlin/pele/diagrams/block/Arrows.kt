// Ported from Pele src/diagrams/block/arrows.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

import pele.js.jsTrim
import pele.svg.num
import kotlin.math.max
import kotlin.math.min

const val RIGHT = 1
const val LEFT = 2
const val UP = 4
const val DOWN = 8

// How far a head reaches past each side of its shaft.
const val OVERHANG = 7.0
private const val MAX_DEPTH = 20.0
private const val NUB = 12.0

class ArrowSize(
  val w: Double,
  val h: Double,
  // Shift of the label from the center, away from the heads.
  val dx: Double,
  val dy: Double,
)

// Mermaid compares directions exactly, so `( up )` draws no arrow there. Here spaces are ignored.
fun arrowMask(directions: List<String>?): Int {
  var mask = 0
  if (directions != null) {
    for (direction in directions) {
      when (direction.jsTrim()) {
        "right" -> mask = mask or RIGHT
        "left" -> mask = mask or LEFT
        "up" -> mask = mask or UP
        "down" -> mask = mask or DOWN
        "x" -> mask = mask or RIGHT or LEFT
        "y" -> mask = mask or UP or DOWN
      }
    }
  }
  return mask
}

private fun bit(mask: Int, flag: Int): Double = if ((mask and flag) != 0) 1.0 else 0.0

// An arrow along one axis has a shaft and heads wider than it. One that points along both axes
// is a box with a small point on each side it names.
fun arrowAxis(mask: Int): String {
  if (mask != 0 && (mask and (UP or DOWN)) == 0) return "x"
  if (mask != 0 && (mask and (RIGHT or LEFT)) == 0) return "y"
  return ""
}

fun arrowSize(mask: Int, tw: Double, th: Double): ArrowSize {
  val axis = arrowAxis(mask)
  val across = bit(mask, LEFT) - bit(mask, RIGHT)
  val along = bit(mask, UP) - bit(mask, DOWN)
  if (axis == "x") {
    val h = max(th + 8, 14.0) + 2 * OVERHANG
    val depth = min(h / 2, MAX_DEPTH)
    return ArrowSize(max(tw + 12, 16.0) + depth * (if (mask == (RIGHT or LEFT)) 2 else 1), h, (across * depth) / 2, 0.0)
  }
  if (axis == "y") {
    val w = max(tw + 16, 14.0) + 2 * OVERHANG
    val depth = min(w / 2, MAX_DEPTH)
    return ArrowSize(w, max(th + 8, 16.0) + depth * (if (mask == (UP or DOWN)) 2 else 1), 0.0, (along * depth) / 2)
  }
  return ArrowSize(
    max(tw + 16, 24.0) + NUB * (bit(mask, LEFT) + bit(mask, RIGHT)),
    max(th + 8, 24.0) + NUB * (bit(mask, UP) + bit(mask, DOWN)),
    (across * NUB) / 2,
    (along * NUB) / 2,
  )
}

fun drawArrow(mask: Int, w: Double, h: Double, attrs: String): String {
  val axis = arrowAxis(mask)
  val x = w / 2
  val y = h / 2
  val p = ArrayList<Double>()
  if (axis == "x") {
    val depth = min(y, MAX_DEPTH)
    val s = y - OVERHANG
    val x0 = if ((mask and LEFT) != 0) -x + depth else -x
    val x1 = if ((mask and RIGHT) != 0) x - depth else x
    p.addAll(listOf(x0, -s, x1, -s))
    if ((mask and RIGHT) != 0) p.addAll(listOf(x1, -y, x, 0.0, x1, y))
    p.addAll(listOf(x1, s, x0, s))
    if ((mask and LEFT) != 0) p.addAll(listOf(x0, y, -x, 0.0, x0, -y))
  } else if (axis == "y") {
    val depth = min(x, MAX_DEPTH)
    val s = x - OVERHANG
    val y0 = if ((mask and UP) != 0) -y + depth else -y
    val y1 = if ((mask and DOWN) != 0) y - depth else y
    p.addAll(listOf(s, y0, s, y1))
    if ((mask and DOWN) != 0) p.addAll(listOf(x, y1, 0.0, y, -x, y1))
    p.addAll(listOf(-s, y1, -s, y0))
    if ((mask and UP) != 0) p.addAll(listOf(-x, y0, 0.0, -y, x, y0))
  } else {
    val x0 = if ((mask and LEFT) != 0) -x + NUB else -x
    val x1 = if ((mask and RIGHT) != 0) x - NUB else x
    val y0 = if ((mask and UP) != 0) -y + NUB else -y
    val y1 = if ((mask and DOWN) != 0) y - NUB else y
    val cx = (x0 + x1) / 2
    val cy = (y0 + y1) / 2
    val kx = min(10.0, (x1 - x0) / 2)
    val ky = min(10.0, (y1 - y0) / 2)
    p.addAll(listOf(x0, y0))
    if ((mask and UP) != 0) p.addAll(listOf(cx - kx, y0, cx, -y, cx + kx, y0))
    p.addAll(listOf(x1, y0))
    if ((mask and RIGHT) != 0) p.addAll(listOf(x1, cy - ky, x, cy, x1, cy + ky))
    p.addAll(listOf(x1, y1))
    if ((mask and DOWN) != 0) p.addAll(listOf(cx + kx, y1, cx, y, cx - kx, y1))
    p.addAll(listOf(x0, y1))
    if ((mask and LEFT) != 0) p.addAll(listOf(x0, cy + ky, -x, cy, x0, cy - ky))
  }
  var points = ""
  var i = 0
  while (i < p.size) {
    points += (if (i != 0) " " else "") + num(p[i]) + "," + num(p[i + 1])
    i += 2
  }
  return "<polygon points=\"$points\" stroke-linejoin=\"round\"$attrs/>"
}
