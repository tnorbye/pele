// Ported from Pele src/diagrams/agentflow/diagnostics.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

// Mermaid's diagnostic vocabulary for agentflow. Only the ids in EMITTED are raised by a parser;
// the rest are reserved for tools that check a diagram's meaning.
object AgentflowWarning {
  const val SHAPE_UNSUPPORTED = "SHAPE_UNSUPPORTED"
  const val SHAPE_REMOVED = "SHAPE_REMOVED"
  const val EDGE_OPERATOR_UNSUPPORTED = "EDGE_OPERATOR_UNSUPPORTED"
  const val REFERENCE_EDGE_LABEL_REJECTED = "REFERENCE_EDGE_LABEL_REJECTED"
  const val CONNECTOR_REF_UNRESOLVED = "CONNECTOR_REF_UNRESOLVED"
  const val CONNECTOR_REF_NOT_A_CONNECTOR = "CONNECTOR_REF_NOT_A_CONNECTOR"
  const val METADATA_KEY_MISAPPLIED = "METADATA_KEY_MISAPPLIED"
  const val DUPLICATE_ID_NODE = "DUPLICATE_ID_NODE"
  const val RESERVED_SYNTHETIC_ID = "RESERVED_SYNTHETIC_ID"
  const val CONTAINMENT_VIOLATION = "CONTAINMENT_VIOLATION"
  const val EDGE_SEMANTIC_CONTRADICTION = "EDGE_SEMANTIC_CONTRADICTION"
  const val FLOW_NO_INPUT = "FLOW_NO_INPUT"

  // Object.values order.
  val values: List<DiagnosticId> = listOf(
    SHAPE_UNSUPPORTED,
    SHAPE_REMOVED,
    EDGE_OPERATOR_UNSUPPORTED,
    REFERENCE_EDGE_LABEL_REJECTED,
    CONNECTOR_REF_UNRESOLVED,
    CONNECTOR_REF_NOT_A_CONNECTOR,
    METADATA_KEY_MISAPPLIED,
    DUPLICATE_ID_NODE,
    RESERVED_SYNTHETIC_ID,
    CONTAINMENT_VIOLATION,
    EDGE_SEMANTIC_CONTRADICTION,
    FLOW_NO_INPUT,
  )
}

val EMITTED: Set<DiagnosticId> = linkedSetOf(
  "SHAPE_UNSUPPORTED",
  "SHAPE_REMOVED",
  "CONTAINMENT_VIOLATION",
)

val RESERVED: Set<DiagnosticId> = AgentflowWarning.values.filter { id -> !EMITTED.contains(id) }.toCollection(LinkedHashSet())
