// Ported from Pele src/diagrams/xychart/ticks.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.xychart

import pele.js.jsRound
import java.lang.StrictMath.log10
import java.lang.StrictMath.pow
import kotlin.math.floor

// A step of 1, 2 or 5 times a power of ten that divides `span` into about `count` parts.
// A step below one comes back as the negative of its inverse, which is a whole number, so that
// tick values are found by division and print without rounding noise (0.3, not 0.30000000000000004).
fun tickStep(span: Double, count: Double): Double {
  val raw = span / maxOf(count, 1.0)
  val power = floor(log10(raw))
  val unit = raw / pow(10.0, power)
  val factor = if (unit >= 7.07) 10.0 else if (unit >= 3.16) 5.0 else if (unit >= 1.41) 2.0 else 1.0
  return if (power < 0) -pow(10.0, -power) / factor else factor * pow(10.0, power)
}

fun tickIndex(step: Double, value: Double): Double = if (step > 0) value / step else value * -step

fun tickValue(step: Double, index: Double): Double = if (step > 0) index * step else index / -step

// The multiples of a step from `lo` to `hi`, both included.
fun ticksBetween(lo: Double, hi: Double, step: Double): List<Double> {
  var first = jsRound(tickIndex(step, lo))
  if (tickValue(step, first) < lo) first++
  var last = jsRound(tickIndex(step, hi))
  if (tickValue(step, last) > hi) last--
  val out = ArrayList<Double>()
  var i = first
  while (i <= last && out.size < 100) {
    out.add(tickValue(step, i))
    i++
  }
  return out
}
