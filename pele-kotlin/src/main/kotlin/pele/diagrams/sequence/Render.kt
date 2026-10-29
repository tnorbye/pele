// Ported from Pele src/diagrams/sequence/render.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.Config
import pele.RenderOptions
import pele.Rendered
import pele.diagrams.common.breakLabel
import pele.diagrams.common.tighten
import pele.diagrams.common.titleRoom
import pele.js.jsOr
import pele.js.jsRound
import pele.js.jsStr
import pele.svg.ARROW
import pele.svg.FONT_MONO
import pele.svg.ROUNDED
import pele.svg.classNames
import pele.svg.esc
import pele.svg.escText
import pele.svg.ink
import pele.svg.labelSvg
import pele.svg.marker
import pele.svg.num
import pele.svg.resolveStyle
import pele.svg.rounded
import pele.svg.svgDocument
import pele.svg.titleLabel
import pele.text.Label
import pele.text.Style
import pele.text.TextMeasurer
import pele.text.defaultMeasurer
import pele.text.layoutLabel
import kotlin.math.ceil
import kotlin.math.floor

private object Head {
  const val None = 0
  const val Arrow = 1
  const val Cross = 2
  const val Open = 3
  const val HalfSolid = 4
  const val HalfStick = 5
}

private class LineStyle(
  val dotted: Boolean,
  val head: Int,
  // Where the heads are: at the receiver, at the sender only (the reverse arrows), or at both.
  val atStart: Boolean,
  val atEnd: Boolean,
  val bottom: Boolean,
)

private val STYLES: Array<LineStyle?> = arrayOfNulls<LineStyle>(64).also { styles ->
  fun style(type: Int, dotted: Boolean, head: Int, ends: Int = 1, bottom: Boolean = false) {
    styles[type] = LineStyle(dotted, head, ends != 1, ends != 0, bottom)
  }
  style(LINETYPE.SOLID, false, Head.Arrow)
  style(LINETYPE.DOTTED, true, Head.Arrow)
  style(LINETYPE.SOLID_CROSS, false, Head.Cross)
  style(LINETYPE.DOTTED_CROSS, true, Head.Cross)
  style(LINETYPE.SOLID_OPEN, false, Head.None)
  style(LINETYPE.DOTTED_OPEN, true, Head.None)
  style(LINETYPE.SOLID_POINT, false, Head.Open)
  style(LINETYPE.DOTTED_POINT, true, Head.Open)
  style(LINETYPE.BIDIRECTIONAL_SOLID, false, Head.Arrow, 2)
  style(LINETYPE.BIDIRECTIONAL_DOTTED, true, Head.Arrow, 2)
  for (dotted in listOf(false, true)) {
    val d = if (dotted) 10 else 0
    style(LINETYPE.SOLID_TOP + d, dotted, Head.HalfSolid)
    style(LINETYPE.SOLID_BOTTOM + d, dotted, Head.HalfSolid, 1, true)
    style(LINETYPE.STICK_TOP + d, dotted, Head.HalfStick)
    style(LINETYPE.STICK_BOTTOM + d, dotted, Head.HalfStick, 1, true)
    style(LINETYPE.SOLID_ARROW_TOP_REVERSE + d, dotted, Head.HalfSolid, 0)
    style(LINETYPE.SOLID_ARROW_BOTTOM_REVERSE + d, dotted, Head.HalfSolid, 0, true)
    style(LINETYPE.STICK_ARROW_TOP_REVERSE + d, dotted, Head.HalfStick, 0)
    style(LINETYPE.STICK_ARROW_BOTTOM_REVERSE + d, dotted, Head.HalfStick, 0, true)
  }
}

private val FRAME_KIND: Map<Int, String> = mapOf(
  LINETYPE.LOOP_START to "loop",
  LINETYPE.ALT_START to "alt",
  LINETYPE.OPT_START to "opt",
  LINETYPE.PAR_START to "par",
  LINETYPE.PAR_OVER_START to "par",
  LINETYPE.CRITICAL_START to "critical",
  LINETYPE.BREAK_START to "break",
  LINETYPE.RECT_START to "rect",
)

private const val MSG_PAD = 12.0
private const val SELF_W = 28.0
private const val SELF_H = 22.0
private const val ACT_W = 10.0
private const val ACT_STEP = 4.0
private const val ACT_MIN = 14.0
private const val NOTE_GAP = 10.0
private const val NOTE_OVER = 16.0
private const val FRAME_PAD = 20.0
private const val FRAME_NEST = 10.0
private const val TAB_H = 20.0
private const val FRAME_TEXT = 240.0
private const val BADGE_H = 16.0
private const val CENTRAL_R = 4.0
private const val DASH = " stroke-dasharray=\"4 4\""

class Numbering(
  // The number each message line carries, whether or not it is shown. NaN for everything else.
  val index: DoubleArray,
  val shown: BooleanArray,
  // Whether numbers are on after the last statement.
  val visible: Boolean,
)

fun isLineMessage(type: Int?): Boolean = type != null && type >= 0 && type < STYLES.size && STYLES[type] != null

// Counts message lines the way Mermaid's renderer does while it draws.
fun numberMessages(messages: List<SeqMessage>, always: Boolean): Numbering {
  val index = DoubleArray(messages.size)
  val shown = BooleanArray(messages.size)
  var current = 1.0
  var step = 1.0
  var visible = false
  for (i in messages.indices) {
    val m = messages[i]
    index[i] = Double.NaN
    shown[i] = false
    if (m.type == LINETYPE.AUTONUMBER) {
      val spec = m.message as SeqNumbering
      current = (spec.start ?: Double.NaN).jsOr { current }
      step = (spec.step ?: Double.NaN).jsOr { step }
      visible = spec.visible
    } else if (isLineMessage(m.type)) {
      index[i] = current
      shown[i] = visible || always
      current = jsRound((current + step) * 100) / 100
    }
  }
  return Numbering(index, shown, visible)
}

private class Column(
  val id: String,
  val actor: SeqActor,
  val kind: String,
  val label: Label,
  val size: ActorSize,
  var x: Double,
  var createAt: Int,
  var destroyAt: Int,
  var top: Double,
  var lifeStart: Double,
  var lifeEnd: Double,
  val activations: MutableList<Double>,
)

private object V {
  const val Line = 0
  const val Self = 1
  const val Note = 2
}

private class View(
  val kind: Int,
  val a: Int,
  val b: Int,
  var label: Label,
  var x: Double,
  var w: Double,
)

private class Frame(
  val kind: String,
  var x0: Double,
  var x1: Double,
  var top: Double,
  var title: Label,
  var tabW: Double,
  val fill: String,
  val dividers: MutableList<Int>,
  val dividerY: MutableList<Double>,
  val dividerLabels: MutableList<Label>,
)

private class BoxRun(
  val box: SeqBox,
  val first: Int,
  var last: Int,
  val title: Label,
  var grow: Double,
)

private val NO_LABEL = Label(lines = emptyList(), widths = emptyList(), width = 0.0, height = 0.0, size = 0.0, lineHeight = 0.0)

private fun option(conf: Map<String, Any?>, key: String, fallback: Double): Double {
  val value = conf[key]
  return if (value is Double && value >= 0 && value <= 2000) value else fallback
}

private fun half(x: Double, y: Double, dir: Double, up: Double, stick: Boolean, color: String): String {
  val bx = num(x - dir * ARROW)
  if (stick) return "<path class=\"pele-marker\" d=\"M${num(x)},${num(y)}L$bx,${num(y + up * 5)}\"/>"
  return "<path class=\"pele-marker\" d=\"M${num(x)},${num(y)}L$bx,${num(y + up * 4.5)}V${num(y)}Z\" fill=\"$color\" stroke=\"none\"/>"
}

// Draws the head whose tip is at (x, y) and points along dir. `up` is the side a half head sits on.
private fun head(type: Int, x: Double, y: Double, dir: Double, up: Double, color: String): String {
  return when (type) {
    Head.Arrow -> marker("arrow_point", x, y, dir, 0.0, color)
    Head.Cross -> marker("arrow_cross", x, y, dir, 0.0, color)
    Head.Open ->
      "<path class=\"pele-marker\" d=\"M${num(x - dir * ARROW)},${num(y - 4.5)}L${num(x)},${num(y)}L${num(x - dir * ARROW)},${num(
        y + 4.5,
      )}\" stroke-linejoin=\"round\"/>"
    Head.HalfSolid -> half(x, y, dir, up, false, color)
    Head.HalfStick -> half(x, y, dir, up, true, color)
    else -> ""
  }
}

private fun trim(type: Int): Double = if (type == Head.Arrow) ARROW - 1 else if (type == Head.Cross) 4.0 else 0.0

fun renderSequence(db: SeqDb, config: Config, options: RenderOptions): Rendered =
  tighten(options) { tight -> draw(db, config, options, tight) }

private fun draw(db: SeqDb, config: Config, options: RenderOptions, tight: Boolean, hold: Double = 0.0, byNeed: Boolean = true): Rendered {
  @Suppress("UNCHECKED_CAST")
  val conf: Map<String, Any?> = (config["sequence"] as? Map<String, Any?>) ?: emptyMap()
  val size = options.fontSize ?: 16.0
  val small = jsRound(size * 0.875)
  val tiny = jsRound(size * 0.75)
  val measurer = options.measurer ?: defaultMeasurer(options.fontFamily, options.fontFamilyMono)
  val monospaced = options.monospace != null && options.monospace.contains("messages")
  val messageMeasurer: TextMeasurer = if (monospaced) TextMeasurer { t, s, style -> measurer.width(t, s, style or Style.Mono) } else measurer
  val icons = options.icons
  val pad = options.padding ?: 8.0
  val mirror = conf["mirrorActors"] != false
  val rightAngles = conf["rightAngles"] == true
  val actorMargin = minOf(option(conf, "actorMargin", 40.0), if (tight) 8.0 else Double.POSITIVE_INFINITY)
  val minW = minOf(option(conf, "width", 64.0), if (tight) 48.0 else Double.POSITIVE_INFINITY)
  val minH = option(conf, "height", jsRound(size * 1.5) + 20)
  val wrapW = minOf(
    option(conf, "width", 160.0),
    if (tight) maxOf(32.0, ((options.maxWidth ?: 320.0) - 2 * pad) / maxOf(1.0, db.actors.size.toDouble()) - actorMargin - 24) else Double.POSITIVE_INFINITY,
  )
  // Mermaid's default of 35 corresponds to Pele's default gap. Other values move it by the difference.
  val msgGap = maxOf(4.0, 14 + option(conf, "messageMargin", 35.0) - 35)
  val notePad = minOf(option(conf, "noteMargin", 10.0), if (tight) 6.0 else Double.POSITIVE_INFINITY)
  val boxPad = minOf(option(conf, "boxMargin", 10.0), if (tight) 6.0 else Double.POSITIVE_INFINITY)
  val align = if (conf["messageAlign"] == "left") -1 else if (conf["messageAlign"] == "right") 1 else 0

  val msgPad = if (tight) 6.0 else MSG_PAD
  val noteOver = if (tight) 8.0 else NOTE_OVER
  val framePad = if (tight) 12.0 else FRAME_PAD
  val frameNest = if (tight) 6.0 else FRAME_NEST
  val messages = db.messages
  val count = messages.size
  val numbering = numberMessages(messages, conf["showSequenceNumbers"] == true)
  fun text(raw: String?, fontSize: Double, wrap: Boolean, width: Double = wrapW, by: TextMeasurer = measurer): Label {
    val label = layoutLabel(raw, false, by, fontSize, if (tight || wrap) width else Double.POSITIVE_INFINITY)
    return if (tight) breakLabel(label, by, maxOf(120.0, width)) else label
  }
  fun messageText(raw: String, wrap: Boolean, width: Double = wrapW): Label = text(raw, small, wrap, width, messageMeasurer)

  var used: MutableSet<String?>? = null
  if (conf["hideUnusedParticipants"] == true) {
    val set = LinkedHashSet<String?>()
    for (m in messages) {
      set.add(m.from)
      set.add(m.to)
    }
    used = set
  }

  val nextLine = IntArray(count + 1)
  nextLine[count] = -1
  for (i in count - 1 downTo 0) nextLine[i] = if (isLineMessage(messages[i].type)) i else nextLine[i + 1]

  val cols = ArrayList<Column>()
  val colOf = LinkedHashMap<String, Int>()
  for ((id, actor) in db.actors) {
    if (used != null && !used.contains(id)) continue
    val kind = actorKind(actor.type)
    val label = text(actor.description, size, actor.wrap)
    val created = db.createdActors[id]
    val destroyed = db.destroyedActors[id]
    var createAt = if (created == null) -1 else nextLine[minOf(created, count)]
    if (createAt >= 0 && messages[createAt].to != id) createAt = -1
    var destroyAt = if (destroyed == null) -1 else nextLine[minOf(destroyed, count)]
    if (destroyAt >= 0 && messages[destroyAt].to != id && messages[destroyAt].from != id) destroyAt = -1
    colOf[id] = cols.size
    cols.add(
      Column(
        id = id,
        actor = actor,
        kind = kind,
        label = label,
        size = actorSize(kind, label, minW, minH, if (tight) 8.0 else 16.0),
        x = 0.0,
        createAt = createAt,
        destroyAt = destroyAt,
        top = 0.0,
        lifeStart = 0.0,
        lifeEnd = -1.0,
        activations = ArrayList(),
      ),
    )
  }
  val last = cols.size - 1

  // Widths between lifelines. Every message and note says how far apart the lifelines it touches must be.
  val needs = LinkedHashMap<Int, Double>()
  fun need(lo: Int, hi: Int, width: Double) {
    if (lo < 0 || hi > last) return
    val key = lo * cols.size + hi
    if ((needs[key] ?: 0.0) < width) needs[key] = width
  }
  fun badgeWidth(i: Int): Double =
    if (numbering.shown[i]) maxOf(BADGE_H, measurer.width(jsStr(numbering.index[i]), tiny, 0) + 8) else 0.0

  val views = arrayOfNulls<View>(count)
  val sized = arrayOfNulls<MutableMap<Double, Label>>(count)
  fun sizedText(i: Int, room: Double): Label {
    var known = sized[i]
    if (known == null) {
      if (room == wrapW) return views[i]!!.label
      known = LinkedHashMap()
      known[wrapW + 0.0] = views[i]!!.label
      sized[i] = known
    }
    var label = known[room + 0.0]
    if (label == null) {
      label = messageText(messages[i].message as String, true, room)
      known[room + 0.0] = label
    }
    return label
  }
  for (i in 0 until count) {
    val m = messages[i]
    val a = if (m.from == null) null else colOf[m.from]
    val b = if (m.to == null) null else colOf[m.to]
    if (a == null || b == null || m.message !is String) continue
    if (m.type == LINETYPE.NOTE) {
      val label = text(m.message, small, m.wrap)
      val w = label.width + 2 * notePad
      if (m.placement == PLACEMENT.LEFTOF) {
        need(a - 1, a, w + 2 * NOTE_GAP + ACT_W)
      } else if (m.placement == PLACEMENT.RIGHTOF) {
        need(a, a + 1, w + 2 * NOTE_GAP + ACT_W)
      } else if (a == b) {
        need(a - 1, a, w / 2 + NOTE_GAP)
        need(a, a + 1, w / 2 + NOTE_GAP)
      } else {
        need(minOf(a, b), maxOf(a, b), w - 2 * noteOver)
      }
      views[i] = View(V.Note, a, b, label, 0.0, w)
    } else if (isLineMessage(m.type)) {
      val label = messageText(m.message, m.wrap)
      val badge = badgeWidth(i)
      if (a == b) {
        need(a, a + 1, SELF_W + 8 + label.width + 16 + ACT_W + badge)
        views[i] = View(V.Self, a, b, label, 0.0, 0.0)
      } else {
        val created = if (cols[b].createAt == i) cols[b].size.w / 2 else 0.0
        val central = if ((m.centralConnection ?: 0) != 0) 4 * CENTRAL_R else 0.0
        need(
          minOf(a, b),
          maxOf(a, b),
          (if (tight) minOf(label.width, maxOf(16.0, wrapW - 16)) else label.width) + 2 * msgPad + ACT_W + badge + created + central,
        )
        views[i] = View(V.Line, a, b, label, 0.0, 0.0)
      }
    }
  }

  val runs = ArrayList<BoxRun>()
  for (i in 0..last) {
    val box = cols[i].actor.box
    val run = if (runs.isEmpty()) null else runs[runs.size - 1]
    if (box != null && run != null && run.box === box && run.last == i - 1) run.last = i
    else if (box != null) runs.add(BoxRun(box, i, i, text(box.name, small, box.wrap), 0.0))
  }

  val gaps = DoubleArray(maxOf(last, 0))
  for (i in 0 until last) {
    val left = cols[i]
    val right = cols[i + 1]
    val boxed = if (left.actor.box !== right.actor.box) (if (left.actor.box != null) boxPad else 0.0) + (if (right.actor.box != null) boxPad else 0.0) else 0.0
    gaps[i] = maxOf(left.size.w / 2 + right.size.w / 2 + actorMargin + boxed, needs[i * cols.size + i + 1] ?: 0.0)
  }
  // A message across several lifelines widens each gap it crosses by an equal share of what is missing.
  class Span(val lo: Int, val hi: Int, val width: Double)
  val spans = ArrayList<Span>()
  for ((key, width) in needs) {
    val lo = key / cols.size
    val hi = key % cols.size
    if (hi - lo > 1) spans.add(Span(lo, hi, width))
  }
  spans.sortWith { p, q ->
    val d = p.hi - p.lo - (q.hi - q.lo)
    if (d != 0) d else p.lo - q.lo
  }
  for (span in spans) {
    val lo = span.lo
    val hi = span.hi
    var total = 0.0
    for (i in lo until hi) total += gaps[i]
    if (total >= span.width) continue
    val share = (span.width - total) / (hi - lo)
    for (i in lo until hi) gaps[i] += share
  }
  fun place() {
    for (i in 1..last) cols[i].x = cols[i - 1].x + gaps[i - 1]
  }
  place()
  for (run in runs) {
    val inner = cols[run.last].x + cols[run.last].size.w / 2 - (cols[run.first].x - cols[run.first].size.w / 2)
    run.grow = maxOf(0.0, (run.title.width - inner) / 2)
    if (run.grow == 0.0) continue
    if (run.first > 0) gaps[run.first - 1] += run.grow
    if (run.last < last) gaps[run.last] += run.grow
  }

  var minX = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  fun extend(x0: Double, x1: Double) {
    if (x0 < minX) minX = x0
    if (x1 > maxX) maxX = x1
  }
  fun boxEdges(run: BoxRun): Pair<Double, Double> = Pair(
    cols[run.first].x - cols[run.first].size.w / 2 - boxPad - run.grow,
    cols[run.last].x + cols[run.last].size.w / 2 + boxPad + run.grow,
  )
  val frames = arrayOfNulls<Frame>(count)
  fun layout() {
    place()
    minX = Double.POSITIVE_INFINITY
    maxX = Double.NEGATIVE_INFINITY
    for (col in cols) extend(col.x - col.size.w / 2, col.x + col.size.w / 2)
    for (run in runs) {
      val edges = boxEdges(run)
      extend(edges.first, edges.second)
    }

    // Horizontal extents of every block, from what it holds.
    val open = ArrayList<Frame>()
    fun spanAll(): Pair<Double, Double> = if (last < 0) Pair(0.0, 120.0) else Pair(cols[0].x - framePad, cols[last].x + framePad)
    for (i in 0 until count) {
      val m = messages[i]
      val type = m.type
      val frameKind = if (type == null) null else FRAME_KIND[type]
      val view = views[i]
      if (frameKind != null) {
        val frame = Frame(
          kind = frameKind,
          x0 = Double.POSITIVE_INFINITY,
          x1 = Double.NEGATIVE_INFINITY,
          top = 0.0,
          title = NO_LABEL,
          tabW = 0.0,
          fill = if (frameKind == "rect" && m.message is String) m.message else "",
          dividers = arrayListOf(i),
          dividerY = ArrayList(),
          dividerLabels = ArrayList(),
        )
        frames[i] = frame
        open.add(frame)
      } else if (type == LINETYPE.ALT_ELSE || type == LINETYPE.PAR_AND || type == LINETYPE.CRITICAL_OPTION) {
        if (open.isNotEmpty()) open[open.size - 1].dividers.add(i)
      } else if (
        type == LINETYPE.LOOP_END ||
        type == LINETYPE.ALT_END ||
        type == LINETYPE.OPT_END ||
        type == LINETYPE.PAR_END ||
        type == LINETYPE.CRITICAL_END ||
        type == LINETYPE.BREAK_END ||
        type == LINETYPE.RECT_END
      ) {
        val frame = if (open.isEmpty()) null else open.removeAt(open.size - 1)
        if (frame == null) continue
        frames[i] = frame
        if (frame.x0 > frame.x1) {
          val all = spanAll()
          frame.x0 = all.first
          frame.x1 = all.second
        }
        if (frame.kind != "rect") {
          frame.tabW = measurer.width(frame.kind, tiny, Style.Bold) + 14
          // A block grows to the right to give its texts up to FRAME_TEXT of width before they wrap.
          for (k in 0 until frame.dividers.size) {
            val raw = messages[frame.dividers[k]].message
            val tab = if (k == 0) frame.tabW + 8 else 0.0
            val room = frame.x1 - frame.x0 - 16 - tab
            val label = if (raw is String && raw != "") {
              text("[$raw]", small, true, maxOf(room, if (tight) minOf(FRAME_TEXT, wrapW) else FRAME_TEXT))
            } else {
              NO_LABEL
            }
            if (label.width > room) frame.x1 += label.width - room
            if (k == 0) frame.title = label else frame.dividerLabels.add(label)
          }
        }
        extend(frame.x0, frame.x1)
        val parent = if (open.isEmpty()) null else open[open.size - 1]
        if (parent != null) {
          parent.x0 = minOf(parent.x0, frame.x0 - frameNest)
          parent.x1 = maxOf(parent.x1, frame.x1 + frameNest)
        }
      } else if (view != null) {
        val a = cols[view.a]
        val b = cols[view.b]
        val x0: Double
        val x1: Double
        if (view.kind == V.Note) {
          if (m.placement == PLACEMENT.LEFTOF) {
            view.x = a.x - NOTE_GAP - view.w
          } else if (m.placement == PLACEMENT.RIGHTOF) {
            view.x = a.x + NOTE_GAP
          } else if (view.a == view.b) {
            view.x = a.x - view.w / 2
          } else {
            val lo = minOf(a.x, b.x) - noteOver
            val hi = maxOf(a.x, b.x) + noteOver
            if (tight || m.wrap) view.label = text(m.message as String, small, true, maxOf(wrapW, hi - lo - 2 * notePad))
            view.w = maxOf(hi - lo, view.label.width + 2 * notePad)
            view.x = (lo + hi - view.w) / 2
          }
          x0 = view.x
          x1 = view.x + view.w
        } else if (view.kind == V.Self) {
          x0 = a.x
          x1 = a.x + ACT_W + SELF_W + 8 + view.label.width + badgeWidth(i)
        } else {
          var lx0 = minOf(a.x, b.x)
          var lx1 = maxOf(a.x, b.x)
          if (tight || m.wrap) view.label = sizedText(i, maxOf(wrapW, lx1 - lx0 - 2 * msgPad - ACT_W))
          if (b.createAt == i) {
            if (b.x > a.x) lx1 += b.size.w / 2 else lx0 -= b.size.w / 2
          }
          x0 = lx0
          x1 = lx1
        }
        extend(x0, x1)
        val frame = if (open.isEmpty()) null else open[open.size - 1]
        if (frame != null) {
          frame.x0 = minOf(frame.x0, x0 - framePad)
          frame.x1 = maxOf(frame.x1, x1 + framePad)
        }
      }
    }
  }
  layout()

  // Widen gaps only if it reduces message wrapping.
  var grown = false
  var uneven = false
  val maxWidthOpt = options.maxWidth
  if (tight && maxWidthOpt != null) {
    val spare = floor(maxWidthOpt) - ceil(maxX - minX + 2 * pad) - hold
    val stretch = IntArray(cols.size)
    val want = DoubleArray(gaps.size)
    val wrapped = ArrayList<Int>()
    var lines = 0
    var i = 0
    while (spare > 0 && i < views.size) {
      val view = views[i]
      if (view?.kind != V.Line) {
        i++
        continue
      }
      val natural = messageText(messages[i].message as String, false, Double.POSITIVE_INFINITY)
      if (view.label.lines.size <= natural.lines.size) {
        i++
        continue
      }
      wrapped.add(i)
      lines += view.label.lines.size
      val lo = minOf(view.a, view.b)
      val hi = maxOf(view.a, view.b)
      stretch[lo]++
      stretch[hi]--
      val short = (natural.width - (cols[hi].x - cols[lo].x - 2 * msgPad - ACT_W)) / (hi - lo)
      for (g in lo until hi) want[g] = maxOf(want[g], short)
      i++
    }
    var active = 0
    var crossed = 0
    for (k in gaps.indices) {
      active += stretch[k]
      stretch[k] = if (active > 0) 1 else 0
      crossed += stretch[k]
    }
    if (crossed > 0) {
      val before = gaps.copyOf()
      fun spread(share: (Int) -> Double): List<Int> {
        for (g in gaps.indices) gaps[g] = before[g] + share(g)
        layout()
        return wrapped.map { views[it]!!.label.lines.size }
      }
      fun sum(counts: List<Int>): Int = counts.fold(0) { a, b -> a + b }
      val even = { g: Int -> (stretch[g] * spare) / crossed }
      var counts = spread(even)
      val missing = want.fold(0.0) { a, b -> a + b }
      // Allow for rounding error.
      val needsFor = { g: Int -> (want[g] * (spare - 0.01)) / missing }
      // Reducing wrapping requires at least one gap wider than the even split.
      if (byNeed && crossed > 1 && missing > 0 && gaps.indices.any { g -> needsFor(g) > even(g) }) {
        val first = counts
        counts = spread(needsFor)
        uneven = sum(counts) < sum(first) && counts.withIndex().all { (k, n) -> n <= first[k] }
        if (!uneven) counts = spread(even)
      }
      grown = sum(counts) < lines
      if (!grown) {
        for (g in gaps.indices) gaps[g] = before[g]
        layout()
      }
    }
  }

  // Vertical placement, top to bottom, drawing as it goes.
  var boxTitleH = 0.0
  for (run in runs) boxTitleH = maxOf(boxTitleH, run.title.height)
  val boxTop = if (runs.size > 0) boxPad + (if (boxTitleH > 0) boxTitleH + 4 else 0.0) else 0.0
  var rowH = 0.0
  for (col in cols) if (col.createAt < 0) rowH = maxOf(rowH, col.size.h)
  val headBottom = boxTop + rowH
  for (col in cols) {
    col.top = headBottom - col.size.h
    col.lifeStart = headBottom
  }

  val xs = DoubleArray(cols.size) { cols[it].x }
  // Whether a lifeline runs between two x positions, where text would be struck through.
  fun crosses(x0: Double, x1: Double): Boolean {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
      val mid = (lo + hi) shr 1
      if (xs[mid] <= x0) lo = mid + 1 else hi = mid
    }
    return lo < xs.size && xs[lo] < x1
  }
  val stack = ArrayList<Frame>()
  val inks = LinkedHashMap<String, String?>()
  fun inkOf(fill: String?): String? {
    if (fill.isNullOrEmpty()) return null
    if (!inks.containsKey(fill)) inks[fill] = ink(resolveStyle(listOf("fill:$fill")).shape)
    return inks[fill]
  }
  fun inkOn(fill: String?): String {
    val color = inkOf(fill)
    return if (!color.isNullOrEmpty()) " fill=\"$color\"" else ""
  }
  // Prefer the innermost rect's fill, then a containing box's.
  fun fillUnder(l: Double, r: Double): String? {
    for (k in stack.size - 1 downTo 0) if (stack[k].kind == "rect") return stack[k].fill
    for (run in runs) {
      val edges = boxEdges(run)
      if (l >= edges.first && r <= edges.second) return run.box.fill
    }
    return null
  }
  fun under(l: Double, r: Double): String = inkOn(fillUnder(l, r))
  fun backed(label: Label, cx: Double, cy: Double, frame: Boolean = false): String {
    if (label.width == 0.0) return ""
    val l = cx - label.width / 2
    val plate = crosses(l, l + label.width)
    val back = if (plate) {
      "<rect x=\"${num(l - 3)}\" y=\"${num(cy - label.height / 2 + 2)}\" width=\"${num(label.width + 6)}\" height=\"${num(label.height - 4)}\" rx=\"3\" fill=\"var(--_bg)\" stroke=\"none\"/>"
    } else {
      ""
    }
    val fill = if (plate) "" else under(l, l + label.width)
    return back + labelSvg(
      label,
      cx,
      cy,
      if (frame) " class=\"pele-frame-label\"${fill.ifEmpty { " fill=\"var(--_m)\"" }} stroke=\"none\"" else fill,
      icons,
    )
  }
  fun leftEdge(col: Column, extra: Int = 0): Double = if (col.activations.size + extra > 0) col.x - ACT_W / 2 else col.x
  fun rightEdge(col: Column, extra: Int = 0): Double {
    val depth = col.activations.size + extra
    return if (depth > 0) col.x + ACT_W / 2 + (depth - 1) * ACT_STEP else col.x
  }

  // Shaded blocks and activations close from the inside out. They are written in reverse, so that
  // the inner ones end up on top.
  val rectsOut = ArrayList<String>()
  val activationsOut = ArrayList<String>()
  var framesOut = ""
  var edgesOut = ""
  var labelsOut = ""
  var notesOut = ""
  var actorsOut = ""
  var crossesOut = ""
  fun endActivation(col: Column, end: Double): Double {
    val start = col.activations.removeAt(col.activations.size - 1)
    val bottom = maxOf(end, start + ACT_MIN)
    val x = col.x - ACT_W / 2 + col.activations.size * ACT_STEP
    extend(x, x + ACT_W)
    activationsOut.add(
      "<rect class=\"pele-activation\" data-id=\"${escText(col.id)}\" x=\"${num(x)}\" y=\"${num(start)}\" width=\"${jsStr(ACT_W)}\" height=\"${num(
        bottom - start,
      )}\" rx=\"2\"/>",
    )
    return bottom
  }
  // The lifeline of a destroyed participant ends in a cross a little below the message that ends it.
  fun destroy(col: Column, lineY: Double): Double {
    val cx = col.x
    val cy = lineY + 16
    col.lifeEnd = cy
    crossesOut += "<path class=\"pele-destroy\" data-id=\"${escText(col.id)}\" d=\"M${num(cx - 6)},${num(cy - 6)}L${num(cx + 6)},${num(cy + 6)}M${num(
      cx + 6,
    )},${num(cy - 6)}L${num(cx - 6)},${num(cy + 6)}\"/>"
    return cy + 6
  }
  fun actorSvg(col: Column, top: Double, footer: Boolean): String {
    val cls = col.actor.properties["class"]
    return "<g class=\"pele-node pele-actor pele-actor-${col.kind}${if (footer) " pele-actor-footer" else ""}${
      if (cls is String) classNames(cls) else ""
    }\" data-id=\"${escText(col.id)}\">" +
      drawActor(col.kind, col.x, top, col.size, col.label, icons, if (isFigure(col.kind)) inkOn(col.actor.box?.fill) else "") +
      "</g>"
  }
  fun badge(i: Int, cx: Double, cy: Double, w: Double): String =
    "<g class=\"pele-sequence-number\"><rect x=\"${num(cx - w / 2)}\" y=\"${num(cy - BADGE_H / 2)}\" width=\"${num(w)}\" height=\"${jsStr(BADGE_H)}\" rx=\"${
      jsStr(BADGE_H / 2)
    }\" fill=\"var(--_l)\" stroke=\"none\"/><text x=\"${num(cx)}\" y=\"${num(cy + tiny * 0.35)}\" text-anchor=\"middle\" font-size=\"${jsStr(tiny)}\" fill=\"var(--_bg)\" stroke=\"none\">${esc(
      jsStr(numbering.index[i]),
    )}</text></g>"

  var y = headBottom + 6
  for (i in 0 until count) {
    val m = messages[i]
    val type = m.type
    val view = views[i]
    val frame = frames[i]

    if (type == LINETYPE.ACTIVE_START) {
      val col = if (m.from == null) null else colOf[m.from]
      if (col != null) cols[col].activations.add(y)
    } else if (type == LINETYPE.ACTIVE_END) {
      val col = if (m.from == null) null else colOf[m.from]
      if (col != null && cols[col].activations.size > 0) y = maxOf(y, endActivation(cols[col], y))
    } else if (frame != null && frame.dividers[0] == i) {
      stack.add(frame)
      if (frame.kind == "rect") {
        frame.top = y + 8
        y = frame.top
      } else {
        frame.top = y + 12
        y = frame.top + maxOf(TAB_H, frame.title.height + 2)
      }
    } else if (frame != null) {
      stack.removeAt(stack.size - 1)
      val bottom = y + 12
      val x = num(frame.x0)
      val w = num(frame.x1 - frame.x0)
      if (frame.kind == "rect") {
        val fill = if (frame.fill != "") resolveStyle(listOf("fill:${frame.fill}")).shape else ""
        rectsOut.add(
          "<rect class=\"pele-rect\" x=\"$x\" y=\"${num(frame.top)}\" width=\"$w\" height=\"${num(bottom - frame.top)}\"${rounded((if (fill != "") "" else " fill-opacity=\"0.5\"") + fill)}/>",
        )
      } else {
        var inner = "<rect x=\"$x\" y=\"${num(frame.top)}\" width=\"$w\" height=\"${num(bottom - frame.top)}\" fill=\"none\"$ROUNDED/>"
        for (k in 0 until frame.dividerY.size) {
          val dy = frame.dividerY[k]
          val label = frame.dividerLabels[k]
          inner +=
            "<path d=\"M$x,${num(dy)}H${num(frame.x1)}\"$DASH/>" +
            backed(label, frame.x0 + 8 + label.width / 2, dy + 2 + label.height / 2, true)
        }
        inner +=
          "<rect x=\"$x\" y=\"${num(frame.top)}\" width=\"${num(frame.tabW)}\" height=\"${jsStr(TAB_H)}\" fill=\"var(--_s)\"$ROUNDED/>" +
          "<text class=\"pele-frame-kind\" x=\"${num(frame.x0 + frame.tabW / 2)}\" y=\"${num(
            frame.top + TAB_H / 2 + tiny * 0.35,
          )}\" text-anchor=\"middle\" font-size=\"${jsStr(tiny)}\" font-weight=\"var(--_hw)\" fill=\"var(--_m)\" stroke=\"none\">${frame.kind}</text>" +
          backed(
            frame.title,
            frame.x0 + frame.tabW + 8 + frame.title.width / 2,
            frame.top + maxOf(TAB_H, frame.title.height) / 2,
            true,
          )
        framesOut += "<g class=\"pele-frame pele-frame-${frame.kind}\">$inner</g>"
      }
      y = bottom
    } else if (type == LINETYPE.ALT_ELSE || type == LINETYPE.PAR_AND || type == LINETYPE.CRITICAL_OPTION) {
      val current = if (stack.isEmpty()) null else stack[stack.size - 1]
      if (current != null) {
        val label = current.dividerLabels[current.dividerY.size]
        current.dividerY.add(y + 12)
        y += 12 + (if (label.height > 0) label.height else 0.0)
      }
    } else if (view != null && view.kind == V.Note) {
      val a = cols[view.a]
      val h = view.label.height + 2 * maxOf(4.0, notePad - 4)
      val top = y + 10
      var x = view.x
      if (m.placement == PLACEMENT.RIGHTOF) x += rightEdge(a) - a.x
      else if (m.placement == PLACEMENT.LEFTOF) x += leftEdge(a) - a.x
      extend(x, x + view.w)
      notesOut +=
        "<g class=\"pele-note\" data-id=\"i${escText(m.id)}\">" +
        "<rect x=\"${num(x)}\" y=\"${num(top)}\" width=\"${num(view.w)}\" height=\"${num(h)}\"$ROUNDED/>" +
        labelSvg(view.label, x + view.w / 2, top + h / 2, " class=\"pele-label\" stroke=\"none\" fill=\"var(--_fg)\"", icons) +
        "</g>"
      y = top + h
    } else if (view != null) {
      val st = STYLES[type!!]!!
      val a = cols[view.a]
      val b = cols[view.b]
      val label = view.label
      val bw = badgeWidth(i)
      // The activation a `+` starts belongs to this message, so the arrow stops at its edge.
      var starting = 0
      for (k in i + 1 until count) {
        val t = messages[k].type
        if (t == LINETYPE.ACTIVE_START) {
          if (messages[k].from == m.to) starting++
        } else if (t != LINETYPE.ACTIVE_END && t != LINETYPE.CENTRAL_CONNECTION && t != LINETYPE.CENTRAL_CONNECTION_REVERSE) {
          break
        }
      }
      val up = if (st.bottom) 1.0 else -1.0
      val attrs = if (st.dotted) DASH else ""
      val cc = m.centralConnection
      val circleTo = cc == LINETYPE.CENTRAL_CONNECTION || cc == LINETYPE.CENTRAL_CONNECTION_DUAL
      val circleFrom = cc == LINETYPE.CENTRAL_CONNECTION_REVERSE || cc == LINETYPE.CENTRAL_CONNECTION_DUAL
      var line: String?
      var color = "var(--_l)"
      fun circle(cx: Double, cy: Double): String =
        "<circle class=\"pele-central\" cx=\"${num(cx)}\" cy=\"${num(cy)}\" r=\"${jsStr(CENTRAL_R)}\" fill=\"$color\" stroke=\"none\"/>"
      val tailAtEnd = st.atStart && !st.atEnd
      val headTrim = trim(st.head)
      var inner = ""
      var lineY: Double

      if (view.kind == V.Self) {
        val top = y + msgGap + maxOf(0.0, (label.height - SELF_H) / 2)
        val bottom = top + SELF_H
        var x0 = if (circleFrom) a.x + CENTRAL_R else rightEdge(a)
        var x1 = if (circleTo) a.x + CENTRAL_R else rightEdge(a, starting)
        val out = maxOf(x0, x1) + SELF_W + bw / 2
        line = inkOf(fillUnder(a.x, out))
        color = line ?: color
        if (circleFrom) inner += circle(a.x, top)
        if (circleTo) inner += circle(a.x, bottom)
        if (bw > 0) {
          inner += badge(i, if (tailAtEnd) x1 else x0, if (tailAtEnd) bottom else top, bw)
          if (tailAtEnd) x1 += bw / 2 else x0 += bw / 2
        }
        // The loop leaves to the right and comes back heading left, which turns a half head over.
        val heads = (if (st.atStart) head(st.head, x0, top, -1.0, up, color) else "") +
          (if (st.atEnd) head(st.head, x1, bottom, -1.0, -up, color) else "")
        val from = num(x0 + (if (st.atStart) headTrim else 0.0))
        val to = num(x1 + (if (st.atEnd) headTrim else 0.0))
        val d = if (rightAngles) {
          "M$from,${num(top)}H${num(out)}V${num(bottom)}H$to"
        } else {
          "M$from,${num(top)}H${num(out - 6)}A6,6 0 0 1 ${num(out)},${num(top + 6)}V${num(bottom - 6)}A6,6 0 0 1 ${num(out - 6)},${num(
            bottom,
          )}H$to"
        }
        inner = "<path d=\"$d\"$attrs/>" + heads + inner
        extend(a.x, out + 8 + label.width)
        if (label.width > 0) {
          labelsOut += "<g class=\"pele-edge-label\" data-id=\"i${escText(m.id)}\">${labelSvg(
            label,
            out + 8 + label.width / 2,
            (top + bottom) / 2,
            under(out + 8, out + 8 + label.width),
            icons,
          )}</g>"
        }
        // A label taller than the loop reaches below it, so the next row starts under the label.
        lineY = maxOf(bottom, (top + bottom) / 2 + label.height / 2)
      } else {
        val dir = if (b.x > a.x) 1.0 else -1.0
        val creating = b.createAt == i
        line = inkOf(fillUnder(minOf(a.x, b.x), maxOf(a.x, b.x)))
        color = line ?: color
        lineY = y + msgGap + maxOf(if (label.height > 0) label.height + 2 else 6.0, if (creating) b.size.h / 2 - msgGap + 6 else 0.0)
        var x0 = if (dir > 0) rightEdge(a) else leftEdge(a)
        var x1 = if (dir > 0) leftEdge(b, starting) else rightEdge(b, starting)
        if (creating) {
          b.top = lineY - b.size.h / 2
          b.lifeStart = b.top + b.size.h
          x1 = b.x - dir * (if (isFigure(b.kind)) 18.0 else b.size.w / 2 + 1)
        }
        if (circleFrom) {
          inner += circle(a.x, lineY)
          x0 = a.x + dir * CENTRAL_R
        }
        if (circleTo) {
          inner += circle(b.x, lineY)
          x1 = b.x - dir * CENTRAL_R
        }
        if (bw > 0) {
          // The number sits on the lifeline at the tail of the arrow, past a connection circle if there is one.
          if (tailAtEnd) {
            val cx = if (circleTo) x1 - dir * (bw / 2 + 2) else x1
            inner += badge(i, cx, lineY, bw)
            x1 = cx - (dir * bw) / 2
          } else {
            val cx = if (circleFrom) x0 + dir * (bw / 2 + 2) else x0
            inner += badge(i, cx, lineY, bw)
            x0 = cx + (dir * bw) / 2
          }
        }
        val heads = (if (st.atStart) head(st.head, x0, lineY, -dir, up * dir, color) else "") +
          (if (st.atEnd) head(st.head, x1, lineY, dir, up * dir, color) else "")
        val from = x0 + (if (st.atStart) dir * headTrim else 0.0)
        val to = x1 - (if (st.atEnd) dir * headTrim else 0.0)
        inner = "<path d=\"M${num(from)},${num(lineY)}H${num(to)}\"$attrs/>" + heads + inner
        if (label.width > 0) {
          val l = minOf(x0, x1)
          val r = maxOf(x0, x1)
          val cx = if (align < 0) l + msgPad + label.width / 2 else if (align > 0) r - msgPad - label.width / 2 else (l + r) / 2
          extend(cx - label.width / 2, cx + label.width / 2)
          labelsOut += "<g class=\"pele-edge-label\" data-id=\"i${escText(m.id)}\">${backed(label, cx, lineY - 4 - label.height / 2)}</g>"
        }
        if (creating) y = lineY + b.size.h / 2 - 6
      }
      edgesOut += "<g class=\"pele-edge pele-message\" data-id=\"i${escText(m.id)}\"${if (!line.isNullOrEmpty()) " stroke=\"$line\"" else ""}>$inner</g>"
      y = maxOf(y, lineY)
      if (a.destroyAt == i) y = maxOf(y, destroy(a, lineY))
      if (b.destroyAt == i && b !== a) y = maxOf(y, destroy(b, lineY))
    }
  }

  y += 20
  for (col in cols) while (col.activations.size > 0) endActivation(col, y)
  var bottom = y
  if (mirror) {
    var footH = 0.0
    for (col in cols) {
      if (col.lifeEnd >= 0) continue
      footH = maxOf(footH, col.size.h)
      actorsOut += actorSvg(col, y, true)
    }
    bottom = y + footH
  }
  var lifelines = ""
  for (col in cols) {
    actorsOut += actorSvg(col, col.top, false)
    val end = if (col.lifeEnd >= 0) col.lifeEnd else y
    if (end > col.lifeStart) {
      lifelines += "<path class=\"pele-lifeline\" data-id=\"${escText(col.id)}\" d=\"M${num(col.x)},${num(col.lifeStart)}V${num(end)}\"/>"
    }
  }

  var boxesOut = ""
  for (run in runs) {
    val edges = boxEdges(run)
    val x0 = edges.first
    val x1 = edges.second
    val fill = if (run.box.fill == "transparent") "" else resolveStyle(listOf("fill:${run.box.fill}")).shape
    boxesOut +=
      "<g class=\"pele-cluster pele-box\"${if (!run.box.name.isNullOrEmpty()) " data-id=\"${escText(run.box.name)}\"" else ""}>" +
      "<rect x=\"${num(x0)}\" y=\"0\" width=\"${num(x1 - x0)}\" height=\"${num(bottom + boxPad)}\" fill=\"var(--_a)\" stroke=\"var(--_b)\"${rounded((if (fill != "") "" else " fill-opacity=\"0.5\"") + fill)}/>" +
      labelSvg(run.title, (x0 + x1) / 2, boxPad / 2 + 2 + run.title.height / 2, " class=\"pele-cluster-label\"${inkOn(run.box.fill).ifEmpty { " fill=\"var(--_m)\"" }}", icons) +
      "</g>"
  }
  if (runs.size > 0) bottom += boxPad

  var minY = 0.0
  var titleOut = ""
  val room = titleRoom(options)
  val plainTitle = titleLabel(db.title, measurer, size, if (tight) room else Double.POSITIVE_INFINITY)
  val title = if (tight) breakLabel(plainTitle, measurer, maxOf(120.0, room)) else plainTitle
  if (minX > maxX) extend(0.0, maxOf(title.width, 40.0))
  if (title.width > 0) {
    val cx = (minX + maxX) / 2
    extend(cx - title.width / 2, cx + title.width / 2)
    minY = -title.height - 12
    titleOut = labelSvg(title, cx, minY + title.height / 2, " class=\"pele-title\" font-weight=\"var(--_tw)\"", icons)
  }

  val width = ceil(maxX - minX + 2 * pad)
  // Retry with equal shares or room for activation notes and rounding.
  if (grown && width > options.maxWidth!!) {
    return draw(
      db,
      config,
      options,
      tight,
      if (uneven) hold else if (hold > 0) Double.POSITIVE_INFINITY else width - floor(options.maxWidth),
      false,
    )
  }
  val height = ceil(bottom - minY + 2 * pad)
  val svg = svgDocument(
    "sequence",
    width,
    height,
    size,
    options,
    db,
    "<g transform=\"translate(${num(pad - minX)},${num(pad - minY)})\">" +
      titleOut +
      (if (boxesOut != "") "<g class=\"pele-boxes\" font-size=\"${jsStr(small)}\">$boxesOut</g>" else "") +
      (if (rectsOut.size > 0) "<g class=\"pele-rects\" fill=\"var(--_a)\">${rectsOut.asReversed().joinToString("")}</g>" else "") +
      (if (lifelines != "") "<g class=\"pele-lifelines\" fill=\"none\" stroke=\"var(--_b)\">$lifelines</g>" else "") +
      (
        if (activationsOut.size > 0) {
          "<g class=\"pele-activations\" fill=\"var(--_s)\" stroke=\"var(--_b)\">${activationsOut.asReversed().joinToString("")}</g>"
        } else {
          ""
        }
        ) +
      (if (framesOut != "") "<g class=\"pele-frames\" font-size=\"${jsStr(small)}\" stroke=\"var(--_b)\">$framesOut</g>" else "") +
      (if (edgesOut != "") "<g class=\"pele-edges\" fill=\"none\" stroke=\"var(--_l)\" stroke-linecap=\"round\">$edgesOut</g>" else "") +
      (
        if (crossesOut != "") {
          "<g class=\"pele-destroys\" fill=\"none\" stroke=\"var(--_l)\" stroke-width=\"1.5\" stroke-linecap=\"round\">$crossesOut</g>"
        } else {
          ""
        }
        ) +
      (
        if (labelsOut != "") {
          "<g class=\"pele-edge-labels\" font-size=\"${jsStr(small)}\"${if (monospaced) " font-family=\"$FONT_MONO\"" else ""}>$labelsOut</g>"
        } else {
          ""
        }
        ) +
      (if (notesOut != "") "<g class=\"pele-notes\" font-size=\"${jsStr(small)}\" fill=\"var(--_a)\" stroke=\"var(--_b)\">$notesOut</g>" else "") +
      (if (actorsOut != "") "<g class=\"pele-nodes\">$actorsOut</g>" else "") +
      "</g>",
  )
  return Rendered(svg, width, height, ArrayList())
}
