// Ported from Pele src/diagrams/sequence/parser.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.js.jsNumber
import pele.js.jsSubstring
import pele.js.jsTrim
import pele.syntaxError

// The calls Mermaid's grammar makes on its `yy` object.
interface SeqBuilder {
  fun apply(doc: Any)
  fun parseMessage(str: String): SeqText
  fun parseBoxData(str: String): SeqBoxData
  fun setDiagramTitle(text: String)
  fun setAccTitle(text: String)
  fun setAccDescription(text: String)
}

private val SIGNAL = IntArray(T.STICK_ARROW_BOTTOM_REVERSE_DOTTED + 1) { -1 }.also { s ->
  s[T.SOLID_OPEN_ARROW] = LINETYPE.SOLID_OPEN
  s[T.DOTTED_OPEN_ARROW] = LINETYPE.DOTTED_OPEN
  s[T.SOLID_ARROW] = LINETYPE.SOLID
  s[T.DOTTED_ARROW] = LINETYPE.DOTTED
  s[T.BIDIRECTIONAL_SOLID_ARROW] = LINETYPE.BIDIRECTIONAL_SOLID
  s[T.BIDIRECTIONAL_DOTTED_ARROW] = LINETYPE.BIDIRECTIONAL_DOTTED
  s[T.SOLID_CROSS] = LINETYPE.SOLID_CROSS
  s[T.DOTTED_CROSS] = LINETYPE.DOTTED_CROSS
  s[T.SOLID_POINT] = LINETYPE.SOLID_POINT
  s[T.DOTTED_POINT] = LINETYPE.DOTTED_POINT
  s[T.SOLID_ARROW_TOP] = LINETYPE.SOLID_TOP
  s[T.SOLID_ARROW_BOTTOM] = LINETYPE.SOLID_BOTTOM
  s[T.STICK_ARROW_TOP] = LINETYPE.STICK_TOP
  s[T.STICK_ARROW_BOTTOM] = LINETYPE.STICK_BOTTOM
  s[T.SOLID_ARROW_TOP_DOTTED] = LINETYPE.SOLID_TOP_DOTTED
  s[T.SOLID_ARROW_BOTTOM_DOTTED] = LINETYPE.SOLID_BOTTOM_DOTTED
  s[T.STICK_ARROW_TOP_DOTTED] = LINETYPE.STICK_TOP_DOTTED
  s[T.STICK_ARROW_BOTTOM_DOTTED] = LINETYPE.STICK_BOTTOM_DOTTED
  s[T.SOLID_ARROW_TOP_REVERSE] = LINETYPE.SOLID_ARROW_TOP_REVERSE
  s[T.SOLID_ARROW_BOTTOM_REVERSE] = LINETYPE.SOLID_ARROW_BOTTOM_REVERSE
  s[T.STICK_ARROW_TOP_REVERSE] = LINETYPE.STICK_ARROW_TOP_REVERSE
  s[T.STICK_ARROW_BOTTOM_REVERSE] = LINETYPE.STICK_ARROW_BOTTOM_REVERSE
  s[T.SOLID_ARROW_TOP_REVERSE_DOTTED] = LINETYPE.SOLID_ARROW_TOP_REVERSE_DOTTED
  s[T.SOLID_ARROW_BOTTOM_REVERSE_DOTTED] = LINETYPE.SOLID_ARROW_BOTTOM_REVERSE_DOTTED
  s[T.STICK_ARROW_TOP_REVERSE_DOTTED] = LINETYPE.STICK_ARROW_TOP_REVERSE_DOTTED
  s[T.STICK_ARROW_BOTTOM_REVERSE_DOTTED] = LINETYPE.STICK_ARROW_BOTTOM_REVERSE_DOTTED
}

private object K {
  const val Top = 0
  const val Box = 1
  const val Loop = 2
  const val Rect = 3
  const val Opt = 4
  const val Break = 5
  const val Alt = 6
  const val Par = 7
  const val Critical = 8
}

private class ParseFrame(
  var kind: Int,
  var text: String,
  var startType: Int,
  // The current section's statements. Slot 0 of a block's first section is kept for its start entry.
  var list: MutableList<Any>,
  // Finished sections, each followed by the divider text that ended it.
  var sections: MutableList<Any>?,
)

private val BLOCK = IntArray(T.STICK_ARROW_BOTTOM_REVERSE_DOTTED + 1) { -1 }.also { b ->
  b[T.loop] = K.Loop
  b[T.rect] = K.Rect
  b[T.opt] = K.Opt
  b[T.`break`] = K.Break
  b[T.alt] = K.Alt
  b[T.par] = K.Par
  b[T.par_over] = K.Par
  b[T.critical] = K.Critical
  b[T.box] = K.Box
}

private const val SIGNAL_TOKENS =
  "'SOLID_OPEN_ARROW', 'DOTTED_OPEN_ARROW', 'SOLID_ARROW', 'DOTTED_ARROW', 'SOLID_CROSS', 'DOTTED_CROSS', 'SOLID_POINT', 'DOTTED_POINT'"

fun parseSequence(src: String, db: SeqBuilder) {
  val tokens = tokenize(src)
  val types = tokens.types
  val texts = tokens.texts
  val starts = tokens.starts
  var i = 0

  fun fail(expected: String): Nothing {
    val t = types[i]
    if (t == T.ERROR) throw syntaxError("sequence", src, starts[i], "", true)
    throw syntaxError("sequence", src, starts[i], "Expecting $expected, got '${TOKEN_NAMES[t]}'")
  }
  fun expect(type: Int): String {
    if (types[i] != type) fail("'${TOKEN_NAMES[type]}'")
    return texts[i++]
  }
  fun actor(): SeqStatement {
    val t = types[i]
    if (t != T.ACTOR && t != T.link) fail("'ACTOR'")
    return SeqStatement(type = "addParticipant", actor = texts[i++])
  }
  fun text2(): SeqText = db.parseMessage(expect(T.TXT).jsTrim().jsSubstring(1))

  fun participant(): SeqStatement {
    val t = types[i++]
    if (t == T.destroy) {
      val node = actor()
      expect(T.NEWLINE)
      node.type = "destroyParticipant"
      return node
    }
    val node = actor()
    if (types[i] == T.CONFIG_START && types[i - 1] == T.ACTOR) {
      i++
      val content = expect(T.CONFIG_CONTENT)
      expect(T.CONFIG_END)
      node.config = content.jsTrim()
    }
    node.draw = if (t == T.participant) "participant" else "actor"
    if (types[i] == T.AS) {
      i++
      val alias = expect(T.restOfLine)
      expect(T.NEWLINE)
      node.description = db.parseMessage(alias)
    } else if (types[i] == T.NEWLINE) {
      i++
    } else {
      fail("'AS', 'NEWLINE'")
    }
    return node
  }

  fun signal(from: SeqStatement): Any {
    var centralFrom = false
    if (types[i] == T.CENTRAL) {
      centralFrom = true
      i++
    }
    val signalType = SIGNAL[types[i]]
    if (signalType < 0) fail(SIGNAL_TOKENS)
    i++
    val mark = types[i]
    if (mark == T.CENTRAL || (!centralFrom && (mark == T.PLUS || mark == T.MINUS))) i++
    val to = actor()
    val msg = text2()
    val message = SeqStatement(type = "addMessage", from = from.actor as String, to = to.actor as String, signalType = signalType, msg = msg)
    val out = ArrayList<Any>()
    out.add(from)
    out.add(to)
    out.add(message)
    if (centralFrom) {
      val dual = mark == T.CENTRAL
      message.activate = dual
      message.centralConnection = if (dual) LINETYPE.CENTRAL_CONNECTION_DUAL else LINETYPE.CENTRAL_CONNECTION_REVERSE
      if (dual) out.add(SeqStatement(type = "centralConnection", signalType = LINETYPE.CENTRAL_CONNECTION, actor = to.actor))
      out.add(SeqStatement(type = "centralConnectionReverse", signalType = LINETYPE.CENTRAL_CONNECTION_REVERSE, actor = from.actor))
    } else if (mark == T.PLUS) {
      message.activate = true
      out.add(SeqStatement(type = "activeStart", signalType = LINETYPE.ACTIVE_START, actor = to.actor))
    } else if (mark == T.MINUS) {
      out.add(SeqStatement(type = "activeEnd", signalType = LINETYPE.ACTIVE_END, actor = from.actor))
    } else if (mark == T.CENTRAL) {
      message.activate = true
      message.centralConnection = LINETYPE.CENTRAL_CONNECTION
      out.add(SeqStatement(type = "centralConnection", signalType = LINETYPE.CENTRAL_CONNECTION, actor = to.actor))
    }
    return out
  }

  fun menu(type: String): Any {
    i++
    val who = actor()
    return arrayListOf<Any>(who, SeqStatement(type = type, actor = who.actor, text = text2()))
  }

  fun note(): Any {
    i++
    val t = types[i]
    if (t == T.over) {
      i++
      val first = actor()
      var who: Any = first
      var ids = listOf(first.actor as String, first.actor as String)
      if (types[i] == T.COMMA) {
        i++
        val second = actor()
        who = arrayListOf<Any>(first, second)
        ids = listOf(first.actor as String, second.actor as String)
      }
      return arrayListOf<Any>(who, SeqStatement(type = "addNote", placement = PLACEMENT.OVER, actor = ids, text = text2()))
    }
    if (t != T.left_of && t != T.right_of) fail("'over', 'left_of', 'right_of'")
    i++
    val who = actor()
    val placement = if (t == T.left_of) PLACEMENT.LEFTOF else PLACEMENT.RIGHTOF
    return arrayListOf<Any>(who, SeqStatement(type = "addNote", placement = placement, actor = who.actor, text = text2()))
  }

  fun autonumber(): SeqStatement {
    i++
    val node = SeqStatement(type = "sequenceIndex", sequenceVisible = true, signalType = LINETYPE.AUTONUMBER)
    if (types[i] == T.NUM) {
      node.sequenceIndex = jsNumber(texts[i++])
      node.sequenceIndexStep = if (types[i] == T.NUM) jsNumber(texts[i++]) else 1.0
    } else if (types[i] == T.off) {
      i++
      node.sequenceVisible = false
    }
    if (types[i] != T.NEWLINE) fail("'NEWLINE', 'NUM', 'off'")
    i++
    return node
  }

  // Folds the sections of an alt, par, or critical block from the last one back, as the grammar's
  // right recursion does, so the texts reach the model builder in the same order.
  fun fold(frame: ParseFrame, divider: String, key: String, signalType: Int): MutableList<Any> {
    var list = frame.list
    val sections = frame.sections
    if (sections != null) {
      var k = sections.size - 2
      while (k >= 0) {
        val head = SeqStatement(type = divider, signalType = signalType)
        val text = db.parseMessage(sections[k + 1] as String)
        when (key) {
          "altText" -> head.altText = text
          "parText" -> head.parText = text
          else -> head.optionText = text
        }
        @Suppress("UNCHECKED_CAST")
        val section = sections[k] as MutableList<Any>
        val merged = ArrayList<Any>(section)
        merged.add(head)
        merged.add(list)
        list = merged
        k -= 2
      }
    }
    return list
  }

  fun close(frame: ParseFrame): MutableList<Any> {
    val text = frame.text
    var list = frame.list
    when (frame.kind) {
      K.Box -> {
        list[0] = SeqStatement(type = "boxStart", boxData = db.parseBoxData(text))
        list.add(SeqStatement(type = "boxEnd", boxText = text))
      }
      K.Loop -> {
        list[0] = SeqStatement(type = "loopStart", loopText = db.parseMessage(text), signalType = LINETYPE.LOOP_START)
        list.add(SeqStatement(type = "loopEnd", loopText = text, signalType = LINETYPE.LOOP_END))
      }
      K.Rect -> {
        list[0] = SeqStatement(type = "rectStart", color = db.parseMessage(text), signalType = LINETYPE.RECT_START)
        list.add(SeqStatement(type = "rectEnd", color = db.parseMessage(text), signalType = LINETYPE.RECT_END))
      }
      K.Opt -> {
        list[0] = SeqStatement(type = "optStart", optText = db.parseMessage(text), signalType = LINETYPE.OPT_START)
        list.add(SeqStatement(type = "optEnd", optText = db.parseMessage(text), signalType = LINETYPE.OPT_END))
      }
      K.Break -> {
        list[0] = SeqStatement(type = "breakStart", breakText = db.parseMessage(text), signalType = LINETYPE.BREAK_START)
        list.add(SeqStatement(type = "breakEnd", optText = db.parseMessage(text), signalType = LINETYPE.BREAK_END))
      }
      K.Alt -> {
        list = fold(frame, "else", "altText", LINETYPE.ALT_ELSE)
        list[0] = SeqStatement(type = "altStart", altText = db.parseMessage(text), signalType = LINETYPE.ALT_START)
        list.add(SeqStatement(type = "altEnd", signalType = LINETYPE.ALT_END))
      }
      K.Par -> {
        list = fold(frame, "and", "parText", LINETYPE.PAR_AND)
        list[0] = SeqStatement(type = "parStart", parText = db.parseMessage(text), signalType = frame.startType)
        list.add(SeqStatement(type = "parEnd", signalType = LINETYPE.PAR_END))
      }
      else -> {
        list = fold(frame, "option", "optionText", LINETYPE.CRITICAL_OPTION)
        list[0] = SeqStatement(type = "criticalStart", criticalText = db.parseMessage(text), signalType = LINETYPE.CRITICAL_START)
        list.add(SeqStatement(type = "criticalEnd", signalType = LINETYPE.CRITICAL_END))
      }
    }
    return list
  }

  while (types[i] == T.NEWLINE) i++
  expect(T.SD)

  val stack = ArrayList<ParseFrame>()
  var frame = ParseFrame(K.Top, "", 0, ArrayList(), null)

  while (true) {
    val t = types[i]
    val list = frame.list
    if (frame.kind == K.Box) {
      if (t == T.participant || t == T.participant_actor || t == T.destroy) {
        list.add(participant())
      } else if (t == T.NEWLINE) {
        list.add(ArrayList<Any>())
        i++
      } else if (t == T.end) {
        i++
        val done = close(frame)
        frame = stack.removeAt(stack.size - 1)
        frame.list.add(done)
      } else {
        fail("'end', 'NEWLINE', 'participant', 'participant_actor', 'destroy'")
      }
      continue
    }
    when (t) {
      T.NEWLINE, T.INVALID -> {
        list.add(ArrayList<Any>())
        i++
      }
      T.participant, T.participant_actor, T.destroy -> list.add(participant())
      T.create -> {
        i++
        val next = types[i]
        if (next != T.participant && next != T.participant_actor && next != T.destroy) {
          fail("'participant', 'participant_actor', 'destroy'")
        }
        val node = participant()
        node.type = "createParticipant"
        list.add(node)
      }
      T.ACTOR -> {
        list.add(signal(actor()))
        expect(T.NEWLINE)
      }
      T.link -> {
        val next = types[i + 1]
        list.add(if (next == T.ACTOR || next == T.link) menu("addALink") else signal(actor()))
        expect(T.NEWLINE)
      }
      T.links -> {
        list.add(menu("addLinks"))
        expect(T.NEWLINE)
      }
      T.properties -> {
        list.add(menu("addProperties"))
        expect(T.NEWLINE)
      }
      T.details -> {
        list.add(menu("addDetails"))
        expect(T.NEWLINE)
      }
      T.note -> {
        list.add(note())
        expect(T.NEWLINE)
      }
      T.autonumber -> list.add(autonumber())
      T.activate, T.deactivate -> {
        i++
        val who = actor()
        expect(T.NEWLINE)
        list.add(
          if (t == T.activate) {
            SeqStatement(type = "activeStart", signalType = LINETYPE.ACTIVE_START, actor = who.actor)
          } else {
            SeqStatement(type = "activeEnd", signalType = LINETYPE.ACTIVE_END, actor = who.actor)
          },
        )
      }
      T.title, T.legacy_title -> {
        val title = texts[i++].jsSubstring(if (t == T.title) 6 else 7)
        db.setDiagramTitle(title)
        list.add(title)
      }
      T.acc_title -> {
        i++
        val value = expect(T.acc_title_value).jsTrim()
        db.setAccTitle(value)
        list.add(value)
      }
      T.acc_descr -> {
        i++
        val value = expect(T.acc_descr_value).jsTrim()
        db.setAccDescription(value)
        list.add(value)
      }
      T.acc_descr_multiline_value -> {
        val value = texts[i++].jsTrim()
        db.setAccDescription(value)
        list.add(value)
      }
      T.loop, T.rect, T.opt, T.`break`, T.alt, T.par, T.par_over, T.critical, T.box -> {
        i++
        val text = expect(T.restOfLine)
        stack.add(frame)
        frame = ParseFrame(
          BLOCK[t],
          text,
          if (t == T.par_over) LINETYPE.PAR_OVER_START else LINETYPE.PAR_START,
          arrayListOf<Any>(ArrayList<Any>()),
          null,
        )
      }
      T.`else`, T.and, T.option -> {
        val kind = frame.kind
        if (kind != (if (t == T.`else`) K.Alt else if (t == T.and) K.Par else K.Critical)) fail("'end'")
        i++
        val text = expect(T.restOfLine)
        val sections = frame.sections ?: ArrayList<Any>().also { frame.sections = it }
        sections.add(list)
        sections.add(text)
        frame.list = ArrayList()
      }
      T.end -> {
        if (frame.kind == K.Top) fail("'NEWLINE', 'EOF'")
        i++
        val done = close(frame)
        frame = stack.removeAt(stack.size - 1)
        frame.list.add(done)
      }
      T.END -> {
        if (frame.kind != K.Top) fail("'end'")
        db.apply(list)
        return
      }
      else -> fail("'NEWLINE', 'ACTOR', 'participant', 'note', 'end'")
    }
  }
}
