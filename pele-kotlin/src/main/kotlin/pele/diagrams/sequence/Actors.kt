// Ported from Pele src/diagrams/sequence/actors.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.IconResolver
import pele.js.jsStr
import pele.svg.ROUNDED
import pele.svg.labelSvg
import pele.svg.num
import pele.text.Label

private const val PAD_X = 16.0
private const val PAD_Y = 10.0
private const val GLYPH = 34.0
private const val GLYPH_GAP = 4.0
private const val STACK = 5.0
private const val CAP = 6.0
private const val SHAPE = " fill=\"var(--_s)\" stroke=\"var(--_b)\""
private const val LINE = " fill=\"none\" stroke=\"var(--_b)\""

private val KINDS = setOf("participant", "actor", "boundary", "control", "entity", "database", "collections", "queue")
private val FIGURES = setOf("actor", "boundary", "control", "entity", "database")

class ActorSize(val w: Double, val h: Double)

fun actorKind(type: String): String = if (KINDS.contains(type)) type else "participant"

// A figure stands above its label. The other kinds hold the label inside.
fun isFigure(kind: String): Boolean = FIGURES.contains(kind)

fun actorSize(kind: String, label: Label, minW: Double, minH: Double, paddingX: Double = PAD_X): ActorSize {
  if (isFigure(kind)) {
    return ActorSize(maxOf(40.0, label.width + 8), GLYPH + (if (label.height > 0) GLYPH_GAP + label.height else 0.0))
  }
  val extra = if (kind == "collections") STACK else if (kind == "queue") 2 * CAP else 0.0
  return ActorSize(
    maxOf(minW, label.width + 2 * paddingX) + extra,
    maxOf(minH, label.height + 2 * PAD_Y) + (if (kind == "collections") STACK else 0.0),
  )
}

private fun figure(kind: String, cx: Double, top: Double): String {
  val x = num(cx)
  val y = { dy: Double -> num(top + dy) }
  when (kind) {
    "actor" ->
      return "<circle cx=\"$x\" cy=\"${y(7.0)}\" r=\"6\"$SHAPE/>" +
        "<path d=\"M$x,${y(13.0)}V${y(24.0)}M${num(cx - 10)},${y(17.0)}H${num(cx + 10)}M${num(cx - 8)},${y(34.0)}L$x,${y(24.0)}L${num(
          cx + 8,
        )},${y(34.0)}\"$LINE/>"
    "boundary" ->
      return "<path d=\"M${num(cx - 19)},${y(6.0)}V${y(28.0)}M${num(cx - 19)},${y(17.0)}H${num(cx - 8)}\"$LINE/>" +
        "<circle cx=\"${num(cx + 3)}\" cy=\"${y(17.0)}\" r=\"11\"$SHAPE/>"
    "control" ->
      return "<circle cx=\"$x\" cy=\"${y(21.0)}\" r=\"11\"$SHAPE/>" +
        "<path d=\"M${num(cx + 5)},${y(5.0)}L${num(cx - 1)},${y(10.0)}L${num(cx + 5)},${y(15.0)}\"$LINE/>"
    "entity" ->
      return "<circle cx=\"$x\" cy=\"${y(15.0)}\" r=\"11\"$SHAPE/>" + "<path d=\"M${num(cx - 11)},${y(30.0)}H${num(cx + 11)}\"$LINE/>"
    else -> {
      val l = num(cx - 14)
      val r = num(cx + 14)
      return "<path d=\"M$l,${y(7.0)}V${y(27.0)}A14,5 0 0 0 $r,${y(27.0)}V${y(7.0)}A14,5 0 0 0 $l,${y(7.0)}Z\"$SHAPE/>" +
        "<path d=\"M$l,${y(7.0)}A14,5 0 0 0 $r,${y(7.0)}\"$LINE/>"
    }
  }
}

// Draws a participant whose bounding box has its top center at (cx, top).
fun drawActor(
  kind: String,
  cx: Double,
  top: Double,
  size: ActorSize,
  label: Label,
  icons: IconResolver?,
  // The fill attribute for a figure's label, which sits on whatever is behind the figure.
  ink: String = "",
): String {
  val w = size.w
  val h = size.h
  val text = " class=\"pele-label\""
  if (isFigure(kind)) {
    return figure(kind, cx, top) + labelSvg(label, cx, top + GLYPH + GLYPH_GAP + label.height / 2, text + ink, icons)
  }
  val left = cx - w / 2
  if (kind == "collections") {
    val bw = w - STACK
    val bh = h - STACK
    val back = "<rect x=\"${num(left + STACK)}\" y=\"${num(top)}\" width=\"${num(bw)}\" height=\"${num(bh)}\"$SHAPE$ROUNDED/>"
    val front = "<rect x=\"${num(left)}\" y=\"${num(top + STACK)}\" width=\"${num(bw)}\" height=\"${num(bh)}\"$SHAPE$ROUNDED/>"
    return back + front + labelSvg(label, left + bw / 2, top + STACK + bh / 2, text, icons)
  }
  if (kind == "queue") {
    val l = num(left + CAP)
    val r = num(left + w - CAP)
    val b = num(top + h)
    val ry = num(h / 2)
    return "<path d=\"M$l,${num(top)}H${r}A${jsStr(CAP)},$ry 0 0 1 $r,${b}H${l}A${jsStr(CAP)},$ry 0 0 1 $l,${num(top)}Z\"$SHAPE/>" +
      "<path d=\"M$r,${num(top)}A${jsStr(CAP)},$ry 0 0 0 $r,$b\"$LINE/>" +
      labelSvg(label, cx - CAP / 2, top + h / 2, text, icons)
  }
  return "<rect x=\"${num(left)}\" y=\"${num(top)}\" width=\"${num(w)}\" height=\"${num(h)}\"$SHAPE$ROUNDED/>" +
    labelSvg(label, cx, top + h / 2, text, icons)
}
