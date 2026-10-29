// Ported from Pele src/diagrams/cynefin/boundaries.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.cynefin

import pele.js.charCodeAt
import pele.js.jsImul
import pele.js.toInt32
import pele.js.toUint32
import pele.svg.num

// The geometry of Mermaid's Cynefin boundaries: seeded wavy lines between the domains and the
// S-shaped cliff between Clear and Chaotic. Curves are flat lists of numbers: a start point, then
// six numbers (two control points and an end point) per cubic segment.

// mulberry32, one step.
fun seededRandom(seed: Double): Double {
  var t = toInt32(seed + 0x6d2b79f5)
  t = jsImul(t xor (t ushr 15), t or 1)
  t = t xor (t + jsImul(t xor (t ushr 7), t or 61))
  return toUint32(t xor (t ushr 14)) / 4294967296.0
}

fun hashString(str: String): Int {
  var hash = 0
  for (i in 0 until str.length) {
    hash = (hash shl 5) - hash + str.charCodeAt(i)
  }
  return hash
}

// A configured seed other than zero wins; otherwise the seed comes from the diagram's id.
fun resolveSeed(configured: Double?, id: String): Double {
  if (configured != null && configured.isFinite() && configured != 0.0) return configured
  return hashString(id).toDouble()
}

data class WaveOptions(
  val segments: Int,
  val vertical: Boolean,
  // Multipliers Mermaid uses to vary the seed per point and per control point.
  val pointStep: Int,
  val controlStep: Int,
  val controlShift: Int,
  val pinStart: Boolean = false,
  val pinEnd: Boolean = false,
)

val FOLD = WaveOptions(segments = 7, vertical = true, pointStep = 17, controlStep = 31, controlShift = 7)
val HORIZON = WaveOptions(segments = 7, vertical = false, pointStep = 23, controlStep = 37, controlShift = 11)

// A wavy line along one axis from `from` to `to`, centered on `center` in the other axis.
// Points jitter by up to the amplitude; a pinned end stays on the center line.
fun wave(from: Double, to: Double, center: Double, seed: Double, amplitude: Double, o: WaveOptions): List<Double> {
  val step = (to - from) / o.segments
  val along = ArrayList<Double>()
  val across = ArrayList<Double>()
  for (i in 0..o.segments) {
    val pinned = (i == 0 && o.pinStart) || (i == o.segments && o.pinEnd)
    along.add(from + i * step)
    across.add(if (pinned) center else center + seededRandom(seed + i * o.pointStep) * amplitude * 2 - amplitude)
  }
  val out: MutableList<Double> = if (o.vertical) mutableListOf(across[0], along[0]) else mutableListOf(along[0], across[0])
  for (i in 0 until o.segments) {
    val mid = (along[i] + along[i + 1]) / 2
    val offset = amplitude * 1.5 * (if (i % 2 == 0) 1 else -1) * seededRandom(seed + i * o.controlStep + o.controlShift)
    if (o.vertical) {
      out.addAll(listOf(across[i] + offset, mid, across[i + 1] - offset, mid, across[i + 1], along[i + 1]))
    } else {
      out.addAll(listOf(mid, across[i] + offset, mid, across[i + 1] - offset, along[i + 1], across[i + 1]))
    }
  }
  return out
}

// The cliff: a steep S from (cx, top) down to (cx, bottom).
fun cliff(cx: Double, top: Double, bottom: Double, amplitude: Double): List<Double> {
  val h = bottom - top
  return listOf(
    cx, top,
    cx + amplitude, top + h * 0.2, cx - amplitude * 1.5, top + h * 0.55, cx + amplitude * 0.5, top + h * 0.75,
    cx - amplitude, top + h * 0.85, cx + amplitude * 0.3, top + h * 0.95, cx, bottom,
  )
}

// Path commands that continue from the curve's start point to its end.
fun forward(curve: List<Double>): String {
  var d = ""
  var i = 2
  while (i < curve.size) {
    d += "C${num(curve[i])},${num(curve[i + 1])} ${num(curve[i + 2])},${num(curve[i + 3])} ${num(curve[i + 4])},${num(curve[i + 5])}"
    i += 6
  }
  return d
}

// Path commands that continue from the curve's end point back to its start.
fun backward(curve: List<Double>): String {
  var d = ""
  var i = curve.size - 6
  while (i >= 2) {
    d += "C${num(curve[i + 2])},${num(curve[i + 3])} ${num(curve[i])},${num(curve[i + 1])} ${num(curve[i - 2])},${num(curve[i - 1])}"
    i -= 6
  }
  return d
}

fun curvePath(curve: List<Double>): String = "M${num(curve[0])},${num(curve[1])}${forward(curve)}"

fun ellipsePath(cx: Double, cy: Double, rx: Double, ry: Double): String {
  val r = "${num(rx)},${num(ry)}"
  return "M${num(cx - rx)},${num(cy)}A$r 0 1,1 ${num(cx + rx)},${num(cy)}A$r 0 1,1 ${num(cx - rx)},${num(cy)}Z"
}
