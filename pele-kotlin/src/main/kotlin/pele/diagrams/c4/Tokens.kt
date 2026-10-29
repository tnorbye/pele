// Ported from Pele src/diagrams/c4/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.c4

object T {
  const val END = 0
  const val ERROR = 1
  const val direction_tb = 2
  const val direction_bt = 3
  const val direction_rl = 4
  const val direction_lr = 5
  const val title = 6
  const val accDescription = 7
  const val acc_title = 8
  const val acc_title_value = 9
  const val acc_descr = 10
  const val acc_descr_value = 11
  const val acc_descr_multiline_value = 12
  const val NEWLINE = 13
  const val C4_CONTEXT = 14
  const val C4_CONTAINER = 15
  const val C4_COMPONENT = 16
  const val C4_DYNAMIC = 17
  const val C4_DEPLOYMENT = 18
  const val PERSON = 19
  const val PERSON_EXT = 20
  const val SYSTEM = 21
  const val SYSTEM_DB = 22
  const val SYSTEM_QUEUE = 23
  const val SYSTEM_EXT = 24
  const val SYSTEM_EXT_DB = 25
  const val SYSTEM_EXT_QUEUE = 26
  const val CONTAINER = 27
  const val CONTAINER_DB = 28
  const val CONTAINER_QUEUE = 29
  const val CONTAINER_EXT = 30
  const val CONTAINER_EXT_DB = 31
  const val CONTAINER_EXT_QUEUE = 32
  const val COMPONENT = 33
  const val COMPONENT_DB = 34
  const val COMPONENT_QUEUE = 35
  const val COMPONENT_EXT = 36
  const val COMPONENT_EXT_DB = 37
  const val COMPONENT_EXT_QUEUE = 38
  const val ENTERPRISE_BOUNDARY = 39
  const val SYSTEM_BOUNDARY = 40
  const val BOUNDARY = 41
  const val CONTAINER_BOUNDARY = 42
  const val NODE = 43
  const val NODE_L = 44
  const val NODE_R = 45
  const val REL = 46
  const val BIREL = 47
  const val REL_U = 48
  const val REL_D = 49
  const val REL_L = 50
  const val REL_R = 51
  const val REL_B = 52
  const val REL_INDEX = 53
  const val UPDATE_EL_STYLE = 54
  const val UPDATE_REL_STYLE = 55
  const val UPDATE_LAYOUT_CONFIG = 56
  const val EOF_IN_STRUCT = 57
  const val ATTRIBUTE_EMPTY = 58
  const val STR = 59
  const val STR_KEY = 60
  const val STR_VALUE = 61
  const val LBRACE = 62
  const val RBRACE = 63
  const val EOF = 64
}

val TOKEN_NAMES: List<String> =
  listOf(
    "\$end",
    "INVALID",
    "direction_tb",
    "direction_bt",
    "direction_rl",
    "direction_lr",
    "title",
    "accDescription",
    "acc_title",
    "acc_title_value",
    "acc_descr",
    "acc_descr_value",
    "acc_descr_multiline_value",
    "NEWLINE",
    "C4_CONTEXT",
    "C4_CONTAINER",
    "C4_COMPONENT",
    "C4_DYNAMIC",
    "C4_DEPLOYMENT",
    "PERSON",
    "PERSON_EXT",
    "SYSTEM",
    "SYSTEM_DB",
    "SYSTEM_QUEUE",
    "SYSTEM_EXT",
    "SYSTEM_EXT_DB",
    "SYSTEM_EXT_QUEUE",
    "CONTAINER",
    "CONTAINER_DB",
    "CONTAINER_QUEUE",
    "CONTAINER_EXT",
    "CONTAINER_EXT_DB",
    "CONTAINER_EXT_QUEUE",
    "COMPONENT",
    "COMPONENT_DB",
    "COMPONENT_QUEUE",
    "COMPONENT_EXT",
    "COMPONENT_EXT_DB",
    "COMPONENT_EXT_QUEUE",
    "ENTERPRISE_BOUNDARY",
    "SYSTEM_BOUNDARY",
    "BOUNDARY",
    "CONTAINER_BOUNDARY",
    "NODE",
    "NODE_L",
    "NODE_R",
    "REL",
    "BIREL",
    "REL_U",
    "REL_D",
    "REL_L",
    "REL_R",
    "REL_B",
    "REL_INDEX",
    "UPDATE_EL_STYLE",
    "UPDATE_REL_STYLE",
    "UPDATE_LAYOUT_CONFIG",
    "EOF_IN_STRUCT",
    "ATTRIBUTE_EMPTY",
    "STR",
    "STR_KEY",
    "STR_VALUE",
    "LBRACE",
    "RBRACE",
    "EOF",
  )
