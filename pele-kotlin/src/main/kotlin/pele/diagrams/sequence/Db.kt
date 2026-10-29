// Ported from Pele src/diagrams/sequence/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.isTruthy
import pele.js.jsEntries
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.jsString
import pele.js.jsTrim
import pele.js.jsonParse
import pele.js.toCodePointStrings
import pele.util.YamlValue
import pele.util.parseYaml

class SeqDbOptions(
  // The top-level `wrap` setting, which the `%%{wrap}%%` directive also sets.
  val wrap: Boolean? = null,
  // `sequence.wrap`.
  val sequenceWrap: Boolean? = null,
)

private val RE_WRAP = JsRegExp("""^:?wrap:""")
private val RE_NOWRAP = JsRegExp("""^:?nowrap:""")
private val RE_WRAP_PREFIX = JsRegExp("""^:?(?:no)?wrap:""")
private val RE_BOX_FUNCTION = JsRegExp("""^(?:rgba?|hsla?)\s*\(""")
private val RE_LINE_TERMINATOR = JsRegExp("""[\r  ]""")
private val RE_WORD = JsRegExp("""^\w*""")
private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

private fun fail(message: String): Nothing {
  throw PeleError(message, PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "sequence"))
}

private fun extractWrap(raw: String): SeqText {
  val text = raw.jsTrim()
  val wrap = if (RE_WRAP.test(text)) true else if (RE_NOWRAP.test(text)) false else null
  return SeqText((if (wrap == null) text else text.jsReplace(RE_WRAP_PREFIX, "")).jsTrim(), wrap)
}

// Splits a box line into a leading color and the rest, as Mermaid's
// /^((?:rgba?|hsla?)\s*\(.*\)|\w*)(.*)$/ does. `.` stops at a line terminator, so a line that
// holds one outside the gap before the parenthesis does not match at all.
private fun splitBox(str: String): Pair<String, String>? {
  val fn = RE_BOX_FUNCTION.exec(str)
  if (fn != null) {
    val close = str.lastIndexOf(')')
    if (close >= fn.value.length) {
      if (RE_LINE_TERMINATOR.test(str.jsSlice(fn.value.length))) return null
      return Pair(str.jsSlice(0, close + 1), str.jsSlice(close + 1))
    }
  }
  if (RE_LINE_TERMINATOR.test(str)) return null
  val word = RE_WORD.exec(str)!!.value
  return Pair(word, str.jsSlice(word.length))
}

private fun entries(value: Any?): List<Pair<String, Any?>> {
  if (value is String)
    return value.toCodePointStrings().mapIndexed { i, ch -> Pair(i.toString(), ch) }
  if (value is Map<*, *>) {
    @Suppress("UNCHECKED_CAST")
    return jsEntries(value as Map<String, Any?>)
  }
  if (value is List<*>) return value.mapIndexed { i, v -> Pair(i.toString(), v) }
  return emptyList()
}

class SeqDb(options: SeqDbOptions = SeqDbOptions()) : SequenceModel, SeqBuilder {
  override val type = "sequence"
  override val actors = LinkedHashMap<String, SeqActor>()
  override val createdActors = LinkedHashMap<String, Int>()
  override val destroyedActors = LinkedHashMap<String, Int>()
  override val boxes = ArrayList<SeqBox>()
  override val messages = ArrayList<SeqMessage>()
  override val notes = ArrayList<SeqNote>()
  override var title: String? = null
  override var accTitle: String? = null
    private set

  override var accDescr: String? = null
    private set

  private var prevActor: String? = null
  private var currentBox: SeqBox? = null
  private var lastCreated: String? = null
  private var lastDestroyed: String? = null
  private var wrapEnabled: Boolean? = options.wrap
  private var sequenceWrap: Boolean = options.sequenceWrap ?: false
  private val active = LinkedHashMap<String, Int>()

  fun autoWrap(): Boolean = wrapEnabled ?: sequenceWrap

  override fun setAccTitle(text: String) {
    accTitle = text.jsReplace(RE_LEADING, "")
  }

  override fun setAccDescription(text: String) {
    accDescr = text.jsReplace(RE_INDENT, "\n")
  }

  override fun setDiagramTitle(text: String) {
    title = text
  }

  override fun parseMessage(str: String): SeqText = extractWrap(str)

  override fun parseBoxData(str: String): SeqBoxData {
    val match = splitBox(str)
    var color = if (match != null && match.first != "") match.first.jsTrim() else "transparent"
    var title: String? = if (match != null && match.second != "") match.second.jsTrim() else null
    if (!isCssColor(color)) {
      color = "transparent"
      title = str.jsTrim()
    }
    if (title == null) return SeqBoxData(null, color, null)
    val parsed = extractWrap(title)
    return SeqBoxData(if (parsed.text == "") null else parsed.text, color, parsed.wrap)
  }

  fun addBox(data: SeqBoxData) {
    val box = SeqBox(data.text, data.wrap ?: autoWrap(), data.color, ArrayList())
    currentBox = box
    boxes.add(box)
  }

  fun addActor(
    id: String,
    name: String,
    descriptionIn: SeqText?,
    typeIn: String?,
    metadata: String? = null,
  ) {
    var description = descriptionIn
    var type = typeIn
    var assignedBox = currentBox
    var doc: Map<String, Any?>? = null
    if (metadata != null) {
      val parsed: YamlValue
      try {
        parsed =
          parseYaml(if (metadata.contains('\n')) metadata + "\n" else "{\n" + metadata + "\n}")
      } catch (error: Exception) {
        throw PeleError(
          "Config of participant \"$id\" is not valid YAML. ${error.message}",
          PeleErrorCode.SYNTAX,
          PeleErrorInfo(type = "sequence"),
        )
      }
      @Suppress("UNCHECKED_CAST") if (parsed is Map<*, *>) doc = parsed as Map<String, Any?>
    }
    val docType = if (doc != null && doc.containsKey("type")) doc["type"] else null
    val docAlias = if (doc != null && doc.containsKey("alias")) doc["alias"] else null
    if (docType != null) type = jsString(docType)
    if (isTruthy(docAlias) && (description == null || description.text == name)) {
      description = SeqText(jsString(docAlias), description?.wrap)
    }

    val old = actors[id]
    if (old != null) {
      val currentBox = this.currentBox
      val oldBox = old.box
      if (currentBox != null && oldBox != null && currentBox !== oldBox) {
        fail(
          "A same participant should only be defined in one Box: ${old.name} can't be in '${oldBox.name ?: "undefined"}' and in '${currentBox.name ?: "undefined"}' at the same time."
        )
      }
      assignedBox = oldBox ?: currentBox
      old.box = assignedBox
      if (name == old.name && description == null) return
    }
    // A participant that is only mentioned in a message has no kind and takes its name as its
    // label.
    if (description == null || type == null) description = SeqText(name, null)

    actors[id] =
      SeqActor(
        box = assignedBox,
        name = name,
        description = description.text,
        wrap = description.wrap ?: autoWrap(),
        prevActor = prevActor,
        links = LinkedHashMap(),
        properties = LinkedHashMap(),
        type = type ?: "participant",
      )
    val prevId = prevActor
    if (!prevId.isNullOrEmpty()) {
      val prev = actors[prevId]
      if (prev != null) prev.nextActor = id
    }
    currentBox?.actorKeys?.add(id)
    prevActor = id
  }

  fun addMessage(from: String, to: String, message: SeqText, answer: Any?) {
    messages.add(
      SeqMessage(
        id = messages.size.toString(),
        from = from,
        to = to,
        message = message.text,
        wrap = message.wrap ?: autoWrap(),
        answer = answer,
      )
    )
  }

  fun addSignal(
    from: String? = null,
    to: String? = null,
    message: SeqText? = null,
    type: Int? = null,
    activate: Boolean = false,
    centralConnection: Int? = null,
  ): Boolean {
    // Mermaid recounts every earlier message here. A running count per participant says the same.
    if (type == LINETYPE.ACTIVE_END) {
      val count = if (!from.isNullOrEmpty()) (active[from] ?: 0) else 0
      if (count < 1) fail("Trying to inactivate an inactive participant (${from ?: "undefined"})")
      active[from!!] = count - 1
    } else if (type == LINETYPE.ACTIVE_START && !from.isNullOrEmpty()) {
      active[from] = (active[from] ?: 0) + 1
    }
    messages.add(
      SeqMessage(
        id = messages.size.toString(),
        from = from,
        to = to,
        message = message?.text ?: "",
        wrap = message?.wrap ?: autoWrap(),
        type = type,
        activate = activate,
        centralConnection = centralConnection ?: 0,
      )
    )
    return true
  }

  fun addNote(actor: Any, placement: Int, message: SeqText) {
    val wrap = message.wrap ?: autoWrap()
    val note = SeqNote(actor, placement, message.text, wrap)
    val pair: List<*> = if (actor is String) listOf(actor, actor) else actor as List<*>
    notes.add(note)
    messages.add(
      SeqMessage(
        id = messages.size.toString(),
        from = pair[0] as String?,
        to = pair[1] as String?,
        message = message.text,
        wrap = wrap,
        type = LINETYPE.NOTE,
        placement = placement,
      )
    )
  }

  // Mermaid logs and ignores menu text that is not valid JSON. It also undoes the `&amp;` and
  // `&equals;` its sanitizer writes; Pele has no sanitizer, and a `;` cannot occur in the text.
  fun addLinks(actorId: String, text: SeqText) {
    val actor = actors[actorId] ?: return
    try {
      val parsed = jsonParse(text.text)
      for ((key, value) in entries(parsed)) actor.links[key] = value
    } catch (e: Exception) {
      return
    }
  }

  fun addALink(actorId: String, text: SeqText) {
    val actor = actors[actorId] ?: return
    val sep = text.text.indexOf('@')
    actor.links[text.text.jsSlice(0, sep - 1).jsTrim()] = text.text.jsSlice(sep + 1).jsTrim()
  }

  fun addProperties(actorId: String, text: SeqText) {
    val actor = actors[actorId] ?: return
    try {
      val parsed = jsonParse(text.text)
      for ((key, value) in entries(parsed)) actor.properties[key] = value
    } catch (e: Exception) {
      return
    }
  }

  // Mermaid reads the properties and links from a page element with this id. Pele keeps the id.
  fun addDetails(actorId: String, text: SeqText) {
    val actor = actors[actorId]
    if (actor != null) actor.details = text.text
  }

  // Mermaid walks the nested statement lists by recursion; an explicit stack takes any depth.
  override fun apply(doc: Any) {
    val lists = ArrayList<List<*>>()
    val at = ArrayList<Int>()
    var item: Any? = doc
    while (true) {
      if (item != null) {
        if (item is List<*>) {
          lists.add(item)
          at.add(0)
        } else if (item !is String) {
          applyOne(item as SeqStatement)
        }
      }
      val depth = lists.size - 1
      if (depth < 0) return
      val list = lists[depth]
      if (at[depth] < list.size) {
        item = list[at[depth]]
        at[depth] = at[depth] + 1
      } else {
        lists.removeAt(lists.size - 1)
        at.removeAt(at.size - 1)
        item = null
      }
    }
  }

  private fun applyOne(param: SeqStatement) {
    val actor = param.actor as? String
    when (param.type) {
      "sequenceIndex" ->
        messages.add(
          SeqMessage(
            id = messages.size.toString(),
            from = null,
            to = null,
            message =
              SeqNumbering(
                param.sequenceIndex,
                param.sequenceIndexStep,
                param.sequenceVisible == true,
              ),
            wrap = false,
            type = param.signalType,
          )
        )
      "addParticipant" -> addActor(actor!!, actor, param.description, param.draw, param.config)
      "createParticipant" -> {
        if (actors.containsKey(actor!!)) {
          fail(
            "It is not possible to have actors with the same id, even if one is destroyed before the next is created. Use 'AS' aliases to simulate the behavior"
          )
        }
        lastCreated = actor
        addActor(actor, actor, param.description, param.draw, param.config)
        createdActors[actor] = messages.size
      }
      "destroyParticipant" -> {
        lastDestroyed = actor
        destroyedActors[actor!!] = messages.size
      }
      "activeStart",
      "activeEnd",
      "centralConnection",
      "centralConnectionReverse" -> addSignal(actor, null, null, param.signalType)
      "addNote" -> addNote(param.actor!!, param.placement!!, param.text!!)
      "addLinks" -> addLinks(actor!!, param.text!!)
      "addALink" -> addALink(actor!!, param.text!!)
      "addProperties" -> addProperties(actor!!, param.text!!)
      "addDetails" -> addDetails(actor!!, param.text!!)
      "addMessage" -> {
        val created = lastCreated
        val destroyed = lastDestroyed
        if (!created.isNullOrEmpty()) {
          if (param.to != created) {
            fail(
              "The created participant $created does not have an associated creating message after its declaration. Please check the sequence diagram."
            )
          }
          lastCreated = null
        } else if (!destroyed.isNullOrEmpty()) {
          if (param.to != destroyed && param.from != destroyed) {
            fail(
              "The destroyed participant $destroyed does not have an associated destroying message after its declaration. Please check the sequence diagram."
            )
          }
          lastDestroyed = null
        }
        addSignal(
          param.from,
          param.to,
          param.msg,
          param.signalType,
          param.activate ?: false,
          param.centralConnection,
        )
      }
      "boxStart" -> addBox(param.boxData!!)
      "boxEnd" -> currentBox = null
      "loopStart" -> addSignal(null, null, param.loopText as SeqText, param.signalType)
      "rectStart" -> addSignal(null, null, param.color, param.signalType)
      "optStart" -> addSignal(null, null, param.optText, param.signalType)
      "altStart",
      "else" -> addSignal(null, null, param.altText, param.signalType)
      "parStart",
      "and" -> addSignal(null, null, param.parText, param.signalType)
      "criticalStart" -> addSignal(null, null, param.criticalText, param.signalType)
      "option" -> addSignal(null, null, param.optionText, param.signalType)
      "breakStart" -> addSignal(null, null, param.breakText, param.signalType)
      "loopEnd",
      "rectEnd",
      "optEnd",
      "altEnd",
      "parEnd",
      "criticalEnd",
      "breakEnd" -> addSignal(null, null, null, param.signalType)
    }
  }
}
