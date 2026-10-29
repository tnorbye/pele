// Ported from Pele src/diagrams/common/fit-width.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.RenderOptions
import pele.Rendered

// Draws a chart whose plot can be any width so that the whole diagram fits the width the host
// has. What surrounds the plot (labels, a legend) is only known once it is drawn, so the plot
// gives up the overflow and is drawn again. `narrow` tells the chart that it did not fit at
// its own width, for a chart that then arranges itself differently.
fun fitWidth(
  options: RenderOptions,
  natural: Double,
  least: Double,
  draw: (plot: Double, narrow: Boolean) -> Rendered,
): Rendered {
  var result = draw(natural, false)
  val available = options.maxWidth
  if (available == null || !(available > 0) || result.width <= available) return result
  val floor = minOf(least, natural)
  var plot = natural
  result = draw(plot, true)
  var round = 0
  while (round < 3 && result.width > available && plot > floor) {
    plot = maxOf(floor, plot - (result.width - available))
    result = draw(plot, true)
    round++
  }
  return result
}

// The width a title may take before it wraps.
fun titleRoom(options: RenderOptions): Double {
  val available = options.maxWidth
  return if (available != null && available > 0)
    maxOf(80.0, available - 2 * (options.padding ?: 8.0))
  else 4000.0
}

// For a chart that is drawn to a set width: its own, or the host's when that is smaller.
// `around` is what the chart adds outside that width.
fun capWidth(options: RenderOptions, natural: Double, least: Double, around: Double = 0.0): Double {
  val available = options.maxWidth
  if (available == null || !(available > 0)) return natural
  return maxOf(minOf(least, natural), minOf(natural, available - around))
}

private const val BREAKPOINT = 640.0

// For a diagram that can run across or down: draws it across, and down instead when the host
// allows it, the width it has is under the breakpoint, across does not fit, and down is narrower.
fun turnToFit(options: RenderOptions, draw: (down: Boolean) -> Rendered): Rendered {
  val across = draw(false)
  val available = options.maxWidth
  if (
    options.autoDirection == false ||
      available == null ||
      !(available > 0) ||
      across.width <= available
  )
    return across
  val breakpoint = options.directionBreakpoint
  if (available >= (if (breakpoint != null && breakpoint > 0) breakpoint else BREAKPOINT))
    return across
  val down = draw(true)
  return if (down.width < across.width) down else across
}

fun runsAcross(direction: String?): Boolean = direction == "LR" || direction == "RL"

// Draws a diagram with room to spare, and tightly instead when that does not fit the width the
// host has and tight is narrower.
fun tighten(options: RenderOptions, draw: (tight: Boolean) -> Rendered): Rendered {
  val loose = draw(false)
  val available = options.maxWidth
  if (available == null || !(available > 0) || loose.width <= available) return loose
  val tight = draw(true)
  return if (tight.width < loose.width) tight else loose
}
