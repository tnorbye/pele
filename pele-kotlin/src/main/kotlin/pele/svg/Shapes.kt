// Ported from Pele src/svg/shapes.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.svg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import pele.js.jsReplace
import pele.layout.CNode

class ShapeSize(
  val w: Double,
  val h: Double,
  // Vertical shift of the label from the shape's center.
  val dy: Double,
)

const val PAD_X = 18.0
const val PAD_Y = 12.0

private typealias Sizer = (tw: Double, th: Double) -> ShapeSize

private typealias Drawer = (w: Double, h: Double, a: String, line: String) -> String

// Distance from the bounding box to the outline on a side: 0 top, 1 right, 2 bottom, 3 left.
// `t` is how far along the side from its middle, to the right or down.
private typealias Inset = (w: Double, h: Double, side: Int, t: Double) -> Double

// How much of a side edges may spread along, centered on its middle: the top and bottom, or the
// left and right when `sideways`. A shape without one keeps its edges at the middle.
private typealias Span = (w: Double, h: Double, sideways: Boolean) -> Double

private class ShapeDef(
  val size: Sizer,
  val draw: Drawer,
  val inset: Inset? = null,
  val span: Span? = null,
  val noLabel: Boolean = false,
  // Ignores style fills.
  val bare: Boolean = false,
)

// Kept clear at each end of a straight side, for the corner.
private const val CORNER = 12.0

private val flat: Span = { w, h, sideways -> (if (sideways) h else w) - 2 * CORNER }

// Straight along the top and bottom except for `cut(h)` at each end, and not straight at the sides.
private fun flatAcross(cut: (Double) -> Double): Span = { w, h, sideways ->
  if (sideways) 0.0 else w - 2 * cut(h) - 2 * CORNER
}

// How far an ellipse's outline has fallen away from its bounding box, `t` from the middle of a side
// `half` long.
private fun bulge(t: Double, half: Double, depth: Double): Double {
  val k = min(1.0, abs(t) / half)
  return depth * (1 - sqrt(1 - k * k))
}

private fun odd(side: Int): Boolean = (side and 1) != 0

private val roundInset: Inset = { w, h, side, t ->
  if (odd(side)) bulge(t, h / 2, w / 2) else bulge(t, w / 2, h / 2)
}
private val roundSpan: Span = { w, h, sideways -> (if (sideways) h else w) * 0.6 }
private val capInset: Inset = { w, _, side, t ->
  if (odd(side)) 0.0 else bulge(t, w / 2, min(12.0, max(6.0, w / 14)))
}

private fun fixed(w: Double, h: Double): Sizer = { _, _ -> ShapeSize(w, h, 0.0) }

private fun triSize(tw: Double, th: Double, flip: Boolean): ShapeSize {
  val w = max(1.6 * tw + 40, 80.0)
  val h = (th + 10) / (1 - (tw + 8) / w)
  val dy = h / 2 - 10 - th / 2
  return ShapeSize(w, h, if (flip) -dy else dy)
}

private fun path(d: String, a: String): String = "<path d=\"$d\"$a/>"

private val leanInset: Inset = { _, h, side, _ -> if (odd(side)) h / 8 else 0.0 }

private val box: Sizer = { tw, th ->
  ShapeSize(max(tw + 2 * PAD_X, 2 * PAD_X + 12), th + 2 * PAD_Y, 0.0)
}

private fun poly(points: DoubleArray, a: String): String {
  val d = StringBuilder()
  var i = 0
  while (i < points.size) {
    d.append(if (i != 0) " " else "").append(num(points[i])).append(",").append(num(points[i + 1]))
    i += 2
  }
  return "<polygon points=\"$d\"$a/>"
}

private fun rect(w: Double, h: Double, a: String, rx: String = ""): String =
  "<rect x=\"${num(-w / 2)}\" y=\"${num(-h / 2)}\" width=\"${num(w)}\" height=\"${num(h)}\"$rx$a/>"

private fun padded(extraW: Double, extraH: Double, dy: Double): Sizer = { tw, th ->
  ShapeSize(tw + 2 * PAD_X + extraW, th + 2 * PAD_Y + extraH, dy)
}

private val SHAPES: List<Pair<String, ShapeDef>> =
  listOf(
    "rect" to ShapeDef(size = box, span = flat, draw = { w, h, a, _ -> rect(w, h, rounded(a)) }),
    "rounded" to
      ShapeDef(
        size = box,
        span = flat,
        draw = { w, h, a, _ -> rect(w, h, a, " rx=\"${num(min(12.0, h / 2))}\"") },
      ),
    "stadium" to
      ShapeDef(
        inset = { _, h, side, t -> if (odd(side)) bulge(t, h / 2, h / 2) else 0.0 },
        span = { w, h, sideways -> if (sideways) h * 0.6 else w - h },
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + h * 0.5 + 2 * PAD_X - 8, h, 0.0)
        },
        draw = { w, h, a, _ -> rect(w, h, a, " rx=\"${num(h / 2)}\"") },
      ),
    "fr-rect" to
      ShapeDef(
        span = flat,
        size = padded(16.0, 0.0, 0.0),
        draw = { w, h, a, line ->
          val x = w / 2 - 8
          rect(w, h, rounded(a)) +
            "<path d=\"M${num(-x)},${num(-h / 2)}V${num(h / 2)}M${num(x)},${num(-h / 2)}V${num(h / 2)}\" fill=\"none\"$line/>"
        },
      ),
    "cyl" to
      ShapeDef(
        inset = capInset,
        span = { w, h, sideways -> if (sideways) h - 2 * CORNER - 12 else w * 0.6 },
        size = { tw, th ->
          val w = max(tw + 2 * PAD_X, 56.0)
          val ry = min(12.0, max(6.0, w / 14))
          ShapeSize(w, th + 2 * PAD_Y + 2 * ry, ry / 2)
        },
        draw = { w, h, a, line ->
          val rx = w / 2
          val ry = min(12.0, max(6.0, w / 14))
          val top = -h / 2 + ry
          val bottom = h / 2 - ry
          "<path d=\"M${num(-rx)},${num(top)}A${num(rx)},${num(ry)} 0 0 1 ${num(rx)},${num(top)}V${num(bottom)}A${num(
        rx
      )},${num(ry)} 0 0 1 ${num(-rx)},${num(bottom)}Z\"$a/>" +
            "<path d=\"M${num(-rx)},${num(top)}A${num(rx)},${num(ry)} 0 0 0 ${num(rx)},${num(top)}\" fill=\"none\"$line/>"
        },
      ),
    "circle" to
      ShapeDef(
        inset = roundInset,
        span = roundSpan,
        size = { tw, th ->
          val d = max(sqrt(tw * tw + th * th) + 16, 44.0)
          ShapeSize(d, d, 0.0)
        },
        draw = { w, _, a, _ -> "<circle r=\"${num(w / 2)}\"$a/>" },
      ),
    "dbl-circ" to
      ShapeDef(
        inset = roundInset,
        span = roundSpan,
        size = { tw, th ->
          val d = max(sqrt(tw * tw + th * th) + 26, 54.0)
          ShapeSize(d, d, 0.0)
        },
        draw = { w, _, a, line ->
          "<circle r=\"${num(w / 2)}\"$a/><circle r=\"${num(w / 2 - 5)}\" fill=\"none\"$line/>"
        },
      ),
    "ellipse" to
      ShapeDef(
        inset = roundInset,
        span = roundSpan,
        size = { tw, th -> ShapeSize(tw * 1.3 + 2 * PAD_X, th * 1.2 + 2 * PAD_Y, 0.0) },
        draw = { w, h, a, _ -> "<ellipse rx=\"${num(w / 2)}\" ry=\"${num(h / 2)}\"$a/>" },
      ),
    "diam" to
      ShapeDef(
        inset = { w, h, side, t -> if (odd(side)) (abs(t) * w) / h else (abs(t) * h) / w },
        span = { w, h, sideways -> (if (sideways) h else w) * 0.6 },
        size = { tw, th ->
          val a = tw + 16
          val b = th + 8
          ShapeSize(a + 1.6 * b, a / 1.6 + b, 0.0)
        },
        draw = { w, h, a, _ ->
          poly(doubleArrayOf(0.0, -h / 2, w / 2, 0.0, 0.0, h / 2, -w / 2, 0.0), a)
        },
      ),
    "hex" to
      ShapeDef(
        inset = { _, _, side, t -> if (odd(side)) abs(t) / 2 else 0.0 },
        span = { w, h, sideways -> if (sideways) h * 0.5 else w - h / 2 - CORNER },
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h / 2, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val m = h / 4
          poly(
            doubleArrayOf(
              -w / 2 + m,
              -h / 2,
              w / 2 - m,
              -h / 2,
              w / 2,
              0.0,
              w / 2 - m,
              h / 2,
              -w / 2 + m,
              h / 2,
              -w / 2,
              0.0,
            ),
            a,
          )
        },
      ),
    "odd" to
      ShapeDef(
        inset = { _, h, side, _ -> if (side == 3) h / 4 else 0.0 },
        span = flatAcross { 0.0 },
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h / 4, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val notch = h / 4
          poly(
            doubleArrayOf(
              -w / 2,
              -h / 2,
              w / 2,
              -h / 2,
              w / 2,
              h / 2,
              -w / 2,
              h / 2,
              -w / 2 + notch,
              0.0,
            ),
            a,
          )
        },
      ),
    "lean-r" to
      ShapeDef(
        inset = leanInset,
        span = flatAcross { h -> h / 4 },
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h / 2, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val s = h / 4
          poly(doubleArrayOf(-w / 2 + s, -h / 2, w / 2, -h / 2, w / 2 - s, h / 2, -w / 2, h / 2), a)
        },
      ),
    "lean-l" to
      ShapeDef(
        inset = leanInset,
        span = flatAcross { h -> h / 4 },
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h / 2, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val s = h / 4
          poly(doubleArrayOf(-w / 2, -h / 2, w / 2 - s, -h / 2, w / 2, h / 2, -w / 2 + s, h / 2), a)
        },
      ),
    "trap-b" to
      ShapeDef(
        inset = leanInset,
        span = flatAcross { h -> h / 4 },
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h / 2, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val s = h / 4
          poly(doubleArrayOf(-w / 2 + s, -h / 2, w / 2 - s, -h / 2, w / 2, h / 2, -w / 2, h / 2), a)
        },
      ),
    "trap-t" to
      ShapeDef(
        inset = leanInset,
        span = flatAcross { h -> h / 4 },
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h / 2, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val s = h / 4
          poly(doubleArrayOf(-w / 2, -h / 2, w / 2, -h / 2, w / 2 - s, h / 2, -w / 2 + s, h / 2), a)
        },
      ),
    "text" to
      ShapeDef(
        bare = true,
        span = flat,
        size = { tw, th -> ShapeSize(tw + 8, th + 8, 0.0) },
        draw = { w, h, _, _ -> rect(w, h, " fill=\"none\" stroke=\"none\"") },
      ),
    "sm-circ" to
      ShapeDef(
        noLabel = true,
        inset = roundInset,
        span = roundSpan,
        size = fixed(14.0, 14.0),
        draw = { w, _, a, _ -> "<circle r=\"${num(w / 2)}\"$a/>" },
      ),
    "f-circ" to
      ShapeDef(
        noLabel = true,
        inset = roundInset,
        span = roundSpan,
        size = fixed(14.0, 14.0),
        draw = { w, _, _, line -> "<circle r=\"${num(w / 2)}\" fill=\"var(--_l)\"$line/>" },
      ),
    "fr-circ" to
      ShapeDef(
        noLabel = true,
        inset = roundInset,
        span = roundSpan,
        size = fixed(18.0, 18.0),
        draw = { w, _, a, line ->
          "<circle r=\"${num(w / 2)}\"$a/><circle r=\"${num(w / 2 - 4)}\" fill=\"var(--_l)\"$line/>"
        },
      ),
    "fork" to
      ShapeDef(
        noLabel = true,
        span = { w, h, sideways -> (if (sideways) h else w) - 8 },
        size = fixed(72.0, 8.0),
        draw = { w, h, _, line -> rect(w, h, " fill=\"var(--_l)\"$line", " rx=\"2\"") },
      ),
    "notch-rect" to
      ShapeDef(
        size = box,
        span = flat,
        draw = { w, h, a, _ ->
          val c = 10.0
          poly(
            doubleArrayOf(
              -w / 2 + c,
              -h / 2,
              w / 2,
              -h / 2,
              w / 2,
              h / 2,
              -w / 2,
              h / 2,
              -w / 2,
              -h / 2 + c,
            ),
            a,
          )
        },
      ),
    "lin-rect" to
      ShapeDef(
        span = flat,
        size = padded(8.0, 0.0, 0.0),
        draw = { w, h, a, line ->
          rect(w, h, rounded(a)) +
            "<path d=\"M${num(-w / 2 + 8)},${num(-h / 2)}V${num(h / 2)}\" fill=\"none\"$line/>"
        },
      ),
    "div-rect" to
      ShapeDef(
        span = flat,
        size = padded(0.0, 8.0, 4.0),
        draw = { w, h, a, line ->
          rect(w, h, rounded(a)) +
            "<path d=\"M${num(-w / 2)},${num(-h / 2 + 8)}H${num(w / 2)}\" fill=\"none\"$line/>"
        },
      ),
    "win-pane" to
      ShapeDef(
        span = flat,
        size = padded(8.0, 8.0, 4.0),
        draw = { w, h, a, line ->
          rect(w, h, rounded(a)) +
            "<path d=\"M${num(-w / 2 + 8)},${num(-h / 2)}V${num(h / 2)}M${num(-w / 2)},${num(-h / 2 + 8)}H${num(
          w / 2
        )}\" fill=\"none\"$line/>"
        },
      ),
    "tri" to
      ShapeDef(
        inset = { w, _, side, _ -> if (odd(side)) w / 4 else 0.0 },
        size = { tw, th -> triSize(tw, th, false) },
        draw = { w, h, a, _ -> poly(doubleArrayOf(0.0, -h / 2, w / 2, h / 2, -w / 2, h / 2), a) },
      ),
    "flip-tri" to
      ShapeDef(
        inset = { w, _, side, _ -> if (odd(side)) w / 4 else 0.0 },
        size = { tw, th -> triSize(tw, th, true) },
        draw = { w, h, a, _ -> poly(doubleArrayOf(-w / 2, -h / 2, w / 2, -h / 2, 0.0, h / 2), a) },
      ),
    "sl-rect" to
      ShapeDef(
        inset = { _, _, side, _ -> if (side == 0) 5.0 else 0.0 },
        size = padded(0.0, 10.0, 5.0),
        draw = { w, h, a, _ ->
          poly(doubleArrayOf(-w / 2, -h / 2 + 10, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2), a)
        },
      ),
    "notch-pent" to
      ShapeDef(
        span = flat,
        size = padded(0.0, 0.0, 0.0),
        draw = { w, h, a, _ ->
          val c = 10.0
          poly(
            doubleArrayOf(
              -w / 2 + c,
              -h / 2,
              w / 2 - c,
              -h / 2,
              w / 2,
              -h / 2 + c,
              w / 2,
              h / 2,
              -w / 2,
              h / 2,
              -w / 2,
              -h / 2 + c,
            ),
            a,
          )
        },
      ),
    "hourglass" to
      ShapeDef(
        noLabel = true,
        inset = { w, _, side, _ -> if (odd(side)) w / 2 else 0.0 },
        size = fixed(32.0, 40.0),
        draw = { w, h, a, _ ->
          poly(doubleArrayOf(-w / 2, -h / 2, w / 2, -h / 2, -w / 2, h / 2, w / 2, h / 2), a)
        },
      ),
    "delay" to
      ShapeDef(
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h / 4, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val r = h / 2
          "<path d=\"M${num(-w / 2)},${num(-r)}H${num(w / 2 - r)}A${num(r)},${num(r)} 0 0 1 ${num(w / 2 - r)},${num(
        r
      )}H${num(-w / 2)}Z\"$a/>"
        },
      ),
    "h-cyl" to
      ShapeDef(
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          val rx = min(12.0, h / 4)
          ShapeSize(tw + 2 * PAD_X + 2 * rx, h, 0.0)
        },
        draw = { w, h, a, line ->
          val ry = h / 2
          val rx = min(12.0, h / 4)
          val l = -w / 2 + rx
          val r = w / 2 - rx
          "<path d=\"M${num(l)},${num(-ry)}H${num(r)}A${num(rx)},${num(ry)} 0 0 1 ${num(r)},${num(ry)}H${num(l)}A${num(
        rx
      )},${num(ry)} 0 0 1 ${num(l)},${num(-ry)}Z\"$a/>" +
            "<path d=\"M${num(r)},${num(-ry)}A${num(rx)},${num(ry)} 0 0 0 ${num(r)},${num(ry)}\" fill=\"none\"$line/>"
        },
      ),
    "cross-circ" to
      ShapeDef(
        noLabel = true,
        inset = roundInset,
        span = roundSpan,
        size = fixed(44.0, 44.0),
        draw = { w, _, a, line ->
          val r = w / 2
          val k = r * sqrt(0.5)
          "<circle r=\"${num(r)}\"$a/>" +
            "<path d=\"M${num(-k)},${num(-k)}L${num(k)},${num(k)}M${num(k)},${num(-k)}L${num(-k)},${num(k)}\" fill=\"none\"$line/>"
        },
      ),
    "st-rect" to
      ShapeDef(
        span = { w, h, sideways -> (if (sideways) h else w) - 2 * CORNER - 8 },
        size = padded(8.0, 8.0, 4.0),
        draw = { w, h, a, _ ->
          val bw = w - 8
          val bh = h - 8
          val out = StringBuilder()
          for (i in 0 until 3) {
            val x = -w / 2 + (2 - i) * 4
            val y = -h / 2 + i * 4
            out.append(
              "<rect x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(bw)}\" height=\"${num(bh)}\"${rounded(a)}/>"
            )
          }
          out.toString()
        },
      ),
    "doc" to
      ShapeDef(
        inset = { _, _, side, _ -> if (side == 2) 10.0 else 0.0 },
        size = padded(0.0, 10.0, -5.0),
        draw = { w, h, a, _ -> wave(w, h, a) },
      ),
    "flag" to
      ShapeDef(
        inset = { _, _, side, _ -> if (odd(side)) 0.0 else 8.0 },
        size = padded(0.0, 16.0, 0.0),
        draw = { w, h, a, _ ->
          val x = w / 2
          val y = h / 2 - 8
          val q = w / 4
          "<path d=\"M${num(-x)},${num(-y)}q${num(q)},-10 ${num(2 * q)},0t${num(2 * q)},0V${num(y)}q${num(-q)},10 ${num(
        -2 * q
      )},0t${num(-2 * q)},0Z\"$a/>"
        },
      ),
    "datastore" to
      ShapeDef(
        size = box,
        span = flat,
        draw = { w, h, a, line ->
          rect(w, h, a.jsReplace("stroke=\"var(--_b)\"", "stroke=\"none\"")) +
            "<path d=\"M${num(-w / 2)},${num(-h / 2)}H${num(w / 2)}M${num(-w / 2)},${num(h / 2)}H${num(w / 2)}\" fill=\"none\"$line/>"
        },
      ),
    "folder" to
      ShapeDef(
        size = { tw, th -> ShapeSize(max(tw + 2 * PAD_X, 64.0), th + 2 * PAD_Y + 8, 4.0) },
        draw = { w, h, a, _ ->
          val x = w / 2
          val y = h / 2
          val tab = min(w * 0.4, 48.0)
          path(
            "M${num(-x)},${num(-y)}H${num(-x + tab)}L${num(-x + tab + 8)},${num(-y + 8)}H${num(x)}V${num(y)}H${num(-x)}Z",
            a,
          )
        },
      ),
    "bucket" to
      ShapeDef(
        size = padded(20.0, 10.0, 3.0),
        inset = { _, h, side, _ -> if (odd(side)) h / 12 else 0.0 },
        draw = { w, h, a, line ->
          val x = w / 2
          val y = h / 2
          val ry = 5.0
          val taper = h / 6
          path(
            "M${num(-x)},${num(-y + ry)}A${num(x)},${num(ry)} 0 0 1 ${num(x)},${num(-y + ry)}L${num(x - taper)},${num(
          y - ry
        )}A${num(x - taper)},${num(ry)} 0 0 1 ${num(-x + taper)},${num(y - ry)}Z",
            a,
          ) +
            "<path d=\"M${num(-x)},${num(-y + ry)}A${num(x)},${num(ry)} 0 0 0 ${num(x)},${num(-y + ry)}\" fill=\"none\"$line/>"
        },
      ),
    "console" to
      ShapeDef(
        span = flat,
        size = { tw, th -> ShapeSize(max(tw + 2 * PAD_X, 72.0), th + 2 * PAD_Y + 12, 6.0) },
        draw = { w, h, a, line ->
          rect(w, h, rounded(a)) +
            "<path d=\"M${num(-w / 2)},${num(-h / 2 + 12)}H${num(w / 2)}M${num(-w / 2 + 7)},${num(-h / 2 + 4)}l3,2.5l-3,2.5M${num(
          -w / 2 + 13
        )},${num(-h / 2 + 9)}h4\" fill=\"none\"$line/>"
        },
      ),
    "browser" to
      ShapeDef(
        span = flat,
        size = { tw, th -> ShapeSize(max(tw + 2 * PAD_X, 72.0), th + 2 * PAD_Y + 12, 6.0) },
        draw = { w, h, a, line ->
          val dots = StringBuilder()
          for (i in 0 until 3) dots.append(
            "<circle cx=\"${num(-w / 2 + 8 + i * 7)}\" cy=\"${num(-h / 2 + 6)}\" r=\"1.5\" fill=\"none\"$line/>"
          )
          rect(w, h, rounded(a)) +
            "<path d=\"M${num(-w / 2)},${num(-h / 2 + 12)}H${num(w / 2)}\" fill=\"none\"$line/>" +
            dots
        },
      ),
    "person" to
      ShapeDef(
        size = { tw, th -> ShapeSize(max(tw + 2 * PAD_X, 56.0), th + 2 * PAD_Y + 26, 13.0) },
        draw = { w, h, a, _ ->
          val top = -h / 2
          "<rect x=\"${num(-w / 2)}\" y=\"${num(top + 26)}\" width=\"${num(w)}\" height=\"${num(h - 26)}\" rx=\"12\"$a/>" +
            "<circle cy=\"${num(top + 14)}\" r=\"13\"$a/>"
        },
      ),
    "bang" to
      ShapeDef(
        size = padded(28.0, 24.0, 0.0),
        inset = { _, _, _, _ -> 3.0 },
        draw = { w, h, a, _ ->
          val spikes = 14
          val pts = DoubleArray(spikes * 4)
          for (i in 0 until spikes * 2) {
            val angle = (PI * i) / spikes - PI / 2
            val k = if (i % 2 == 0) 1.0 else 0.82
            pts[2 * i] = StrictMath.cos(angle) * (w / 2) * k
            pts[2 * i + 1] = StrictMath.sin(angle) * (h / 2) * k
          }
          poly(pts, a)
        },
      ),
    "cloud" to
      ShapeDef(
        size = padded(32.0, 24.0, 0.0),
        inset = { _, _, side, _ -> if (odd(side)) 2.0 else 1.0 },
        draw = { w, h, a, _ ->
          val x = w / 2
          val y = h / 2
          val r = y * 0.55
          path(
            "M${num(-x + r)},${num(y)}A${num(r)},${num(r)} 0 0 1 ${num(-x + r * 0.6)},${num(-y * 0.1)}A${num(x * 0.42)},${num(
          y * 0.7
        )} 0 0 1 ${num(-x * 0.1)},${num(-y * 0.72)}A${num(x * 0.4)},${num(y * 0.62)} 0 0 1 ${num(x * 0.62)},${num(
          -y * 0.42
        )}A${num(r * 1.05)},${num(r * 1.05)} 0 0 1 ${num(x - r)},${num(y)}Z",
            a,
          )
        },
      ),
    "brace" to
      ShapeDef(
        bare = true,
        size = padded(12.0, 0.0, 0.0),
        draw = { w, h, _, line ->
          rect(w, h, " fill=\"none\" stroke=\"none\"") + brace(-w / 2 + 12, h, -1.0, line)
        },
      ),
    "brace-r" to
      ShapeDef(
        bare = true,
        size = padded(12.0, 0.0, 0.0),
        draw = { w, h, _, line ->
          rect(w, h, " fill=\"none\" stroke=\"none\"") + brace(w / 2 - 12, h, 1.0, line)
        },
      ),
    "braces" to
      ShapeDef(
        bare = true,
        size = padded(24.0, 0.0, 0.0),
        draw = { w, h, _, line ->
          rect(w, h, " fill=\"none\" stroke=\"none\"") +
            brace(-w / 2 + 12, h, -1.0, line) +
            brace(w / 2 - 12, h, 1.0, line)
        },
      ),
    "bolt" to
      ShapeDef(
        noLabel = true,
        size = fixed(36.0, 56.0),
        inset = { w, _, side, _ -> if (odd(side)) w / 4 else 0.0 },
        draw = { w, h, a, _ ->
          poly(
            doubleArrayOf(
              w * 0.15,
              -h / 2,
              -w / 2,
              h * 0.08,
              -w * 0.05,
              h * 0.08,
              -w * 0.15,
              h / 2,
              w / 2,
              -h * 0.08,
              w * 0.05,
              -h * 0.08,
            ),
            a,
          )
        },
      ),
    "lin-cyl" to
      ShapeDef(
        inset = capInset,
        span = { w, h, sideways -> if (sideways) h - 2 * CORNER - 20 else w * 0.6 },
        size = { tw, th ->
          val w = max(tw + 2 * PAD_X, 56.0)
          val ry = min(12.0, max(6.0, w / 14))
          ShapeSize(w, th + 2 * PAD_Y + 2 * ry + 8, ry / 2 + 4)
        },
        draw = { w, h, a, line ->
          val rx = w / 2
          val ry = min(12.0, max(6.0, w / 14))
          val top = -h / 2 + ry
          val bottom = h / 2 - ry
          val arc = { y: Double ->
            "M${num(-rx)},${num(y)}A${num(rx)},${num(ry)} 0 0 0 ${num(rx)},${num(y)}"
          }
          path(
            "M${num(-rx)},${num(top)}A${num(rx)},${num(ry)} 0 0 1 ${num(rx)},${num(top)}V${num(bottom)}A${num(rx)},${num(
          ry
        )} 0 0 1 ${num(-rx)},${num(bottom)}Z",
            a,
          ) + "<path d=\"${arc(top)}${arc(top + 8)}\" fill=\"none\"$line/>"
        },
      ),
    "curv-trap" to
      ShapeDef(
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h * 0.6, h, 0.0)
        },
        draw = { w, h, a, _ ->
          val x = w / 2
          val y = h / 2
          val k = h * 0.3
          path(
            "M${num(-x + k)},${num(-y)}H${num(x - k)}A${num(k)},${num(y)} 0 0 1 ${num(x - k)},${num(y)}H${num(-x + k)}L${num(
          -x
        )},0Z",
            a,
          )
        },
      ),
    "docs" to
      ShapeDef(
        size = padded(8.0, 18.0, -1.0),
        inset = { _, _, side, _ -> if (side == 2) 10.0 else 0.0 },
        draw = { w, h, a, _ ->
          val out = StringBuilder()
          for (i in 0 until 3) {
            out.append(
              "<g transform=\"translate(${(1 - i) * 4},${(i - 1) * 4})\">${wave(w - 8, h - 8, a)}</g>"
            )
          }
          out.toString()
        },
      ),
    "bow-rect" to
      ShapeDef(
        size = { tw, th ->
          val h = th + 2 * PAD_Y
          ShapeSize(tw + 2 * PAD_X + h * 0.4, h, 0.0)
        },
        inset = { _, h, side, _ -> if (side == 1) h * 0.2 else 0.0 },
        draw = { w, h, a, _ ->
          val x = w / 2
          val y = h / 2
          val k = h * 0.2
          path(
            "M${num(-x + k)},${num(-y)}H${num(x)}A${num(k)},${num(y)} 0 0 0 ${num(x)},${num(y)}H${num(-x + k)}A${num(k)},${num(
          y
        )} 0 0 1 ${num(-x + k)},${num(-y)}Z",
            a,
          )
        },
      ),
    "tag-rect" to
      ShapeDef(
        size = box,
        span = flat,
        draw = { w, h, a, line ->
          rect(w, h, rounded(a)) +
            "<path d=\"M${num(w / 2 - 12)},${num(h / 2)}L${num(w / 2)},${num(h / 2 - 12)}\" fill=\"none\"$line/>"
        },
      ),
    "tag-doc" to
      ShapeDef(
        size = padded(0.0, 10.0, -5.0),
        inset = { _, _, side, _ -> if (side == 2) 10.0 else 0.0 },
        draw = { w, h, a, line ->
          wave(w, h, a) +
            "<path d=\"M${num(w / 2 - 12)},${num(h / 2 - 14)}L${num(w / 2)},${num(h / 2 - 26)}\" fill=\"none\"$line/>"
        },
      ),
    "lin-doc" to
      ShapeDef(
        size = padded(8.0, 10.0, -5.0),
        inset = { _, _, side, _ -> if (side == 2) 10.0 else 0.0 },
        draw = { w, h, a, line ->
          wave(w, h, a) +
            "<path d=\"M${num(-w / 2 + 8)},${num(-h / 2)}V${num(h / 2 - 6)}\" fill=\"none\"$line/>"
        },
      ),
    "collapsedGroup" to
      ShapeDef(
        size = padded(8.0, 8.0, -2.0),
        draw = { w, h, a, _ ->
          "<rect x=\"${num(-w / 2 + 6)}\" y=\"${num(-h / 2 + 8)}\" width=\"${num(w - 12)}\" height=\"${num(h - 8)}\"${rounded(a)}/>" +
            "<rect x=\"${num(-w / 2)}\" y=\"${num(-h / 2)}\" width=\"${num(w)}\" height=\"${num(h - 4)}\"${rounded(a)}/>"
        },
      ),
  )

private fun brace(x: Double, h: Double, dir: Double, line: String): String {
  val y = h / 2
  val k = 6 * dir
  return "<path d=\"M${num(x)},${num(-y)}q${num(k)},0 ${num(k)},6V-6q0,6 ${num(k)},6q${num(-k)},0 ${num(-k)},6V${num(
    y - 6
  )}q0,6 ${num(-k)},6\" fill=\"none\"$line/>"
}

private fun wave(w: Double, h: Double, a: String): String {
  val x = w / 2
  val top = -h / 2
  val base = h / 2 - 10
  val q = w / 4
  return "<path d=\"M${num(-x)},${num(top)}H${num(x)}V${num(base)}q${num(-q)},-12 ${num(-2 * q)},0t${num(
    -2 * q
  )},0Z\"$a/>"
}

// Looked up through a Map so that names like "constructor" cannot reach Object.prototype.
private val BY_NAME: Map<String, ShapeDef> = SHAPES.toMap()

private val FALLBACK = BY_NAME.getValue("rect")

fun shapeSize(shape: String, tw: Double, th: Double): ShapeSize =
  (BY_NAME[shape] ?: FALLBACK).size(tw, th)

fun drawShape(shape: String, w: Double, h: Double, a: String, line: String): String =
  (BY_NAME[shape] ?: FALLBACK).draw(w, h, a, line)

fun shapeInset(shape: String, w: Double, h: Double, side: Int, t: Double = 0.0): Double =
  BY_NAME[shape]?.inset?.invoke(w, h, side, t) ?: 0.0

// How much of a side of the shape edges may spread along, when the flow runs down or, if
// `sideways`, across.
fun shapeSpan(shape: String, w: Double, h: Double, sideways: Boolean): Double =
  max(0.0, BY_NAME[shape]?.span?.invoke(w, h, sideways) ?: 0.0)

fun shapeHasLabel(shape: String): Boolean = BY_NAME[shape]?.noLabel != true

fun shapeFilled(shape: String): Boolean = !(BY_NAME[shape] ?: FALLBACK).bare

// The side of a node's layout box that a route end lies on: 0 top, 1 right, 2 bottom, 3 left, or -1
// for none.
fun endSide(route: List<Double>, at: Int, c: CNode): Int {
  val dx = route[at] - c.x
  val dy = route[at + 1] - c.y
  if (abs(abs(dy) - c.h / 2) < 0.5) return if (dy < 0) 0 else 2
  if (abs(abs(dx) - c.w / 2) < 0.5) return if (dx < 0) 3 else 1
  return -1
}

// PORT: upstream takes `{ shape, w, h }`.
interface ShapeView {
  val shape: String
  val w: Double
  val h: Double
}

// Moves a route end from a node's layout box onto the outline of the shape drawn in it.
fun insetRoute(route: MutableList<Double>, at: Int, view: ShapeView, c: CNode) {
  if (c.isGroup) return
  val side = endSide(route, at, c)
  if (side == 0 || side == 2) {
    val amount = shapeInset(view.shape, view.w, view.h, side, route[at] - c.x) + (c.h - view.h) / 2
    route[at + 1] += if (side == 0) amount else -amount
  } else if (side >= 0) {
    val amount =
      shapeInset(view.shape, view.w, view.h, side, route[at + 1] - c.y) + (c.w - view.w) / 2
    route[at] += if (side == 3) amount else -amount
  }
}
