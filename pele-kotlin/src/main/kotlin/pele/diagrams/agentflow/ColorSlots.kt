// Ported from Pele src/diagrams/agentflow/colorSlots.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

import kotlin.math.max

// Two rules, as in Mermaid: a node takes the color of its kind, from a fixed slot, so editing the
// diagram around it never recolors it; containers count through the slots above the kinds, in the
// order they were declared, so a frame does not take the color of a node inside it.

val KIND_SLOT: Map<String, Int> =
  mapOf(
    "tool" to 0,
    "task" to 1,
    "decision" to 2,
    "input" to 3,
    "refdoc" to 4,
    "connector" to 5,
    "action" to 6,
  )

val KIND_COUNT: Int = KIND_SLOT.size

// At least one, so that a palette shorter than the kinds still gives containers a slot.
fun containerSlotCount(paletteLength: Int): Int = max(1, paletteLength - KIND_COUNT)

fun containerSlot(n: Int, paletteLength: Int): Int {
  val slot = KIND_COUNT + (n % containerSlotCount(paletteLength))
  return if (paletteLength > 0) slot % paletteLength else slot
}

interface SlotNode {
  val id: String
  val shape: String?
  val isGroup: Boolean?
  var colorIndex: Int?
  var kind: String?
}

fun assignColorSlots(
  nodes: List<SlotNode>,
  kindOf: (id: String) -> String?,
  containerOrder: Map<String, Int>,
  paletteLength: Int,
) {
  var fallback = containerOrder.size
  for (node in nodes) {
    // A collapsed container is drawn as a node but keeps its slot, so collapsing one does not
    // recolor the containers after it.
    if (node.isGroup == true || node.shape == "collapsedGroup") {
      node.colorIndex = containerSlot(containerOrder[node.id] ?: fallback++, paletteLength)
      continue
    }
    val kind = kindOf(node.id)
    if (!kind.isNullOrEmpty() && KIND_SLOT.containsKey(kind)) node.kind = kind
  }
}
