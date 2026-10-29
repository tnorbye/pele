// Ported from Pele src/diagrams/state/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.state

object T {
  const val END = 0
  const val ERROR = 1
  const val NL = 2
  const val SD = 3
  const val DESCR = 4
  const val ARROW = 5
  const val HIDE_EMPTY = 6
  const val scale = 7
  const val WIDTH = 8
  const val COMPOSIT_STATE = 9
  const val STRUCT_START = 10
  const val STRUCT_STOP = 11
  const val STATE_DESCR = 12
  const val AS = 13
  const val ID = 14
  const val FORK = 15
  const val JOIN = 16
  const val CHOICE = 17
  const val CONCURRENT = 18
  const val note = 19
  const val NOTE_TEXT = 20
  const val acc_title = 21
  const val acc_title_value = 22
  const val acc_descr = 23
  const val acc_descr_value = 24
  const val acc_descr_multiline_value = 25
  const val CLICK = 26
  const val STRING = 27
  const val HREF = 28
  const val classDef = 29
  const val CLASSDEF_ID = 30
  const val DEFAULT_CLASSDEF_ID = 31
  const val CLASSDEF_STYLEOPTS = 32
  const val DEFAULT = 33
  const val style = 34
  const val STYLE_IDS = 35
  const val STYLEDEF_STYLEOPTS = 36
  const val `class` = 37
  const val CLASSENTITY_IDS = 38
  const val STYLECLASS = 39
  const val direction_tb = 40
  const val direction_bt = 41
  const val direction_rl = 42
  const val direction_lr = 43
  const val EDGE_STATE = 44
  const val STYLE_SEPARATOR = 45
  const val left_of = 46
  const val right_of = 47
  const val INVALID = 48
}

val TOKEN_NAMES: List<String> = listOf(
  "\$end",
  "INVALID",
  "NL",
  "SD",
  "DESCR",
  "-->",
  "HIDE_EMPTY",
  "scale",
  "WIDTH",
  "COMPOSIT_STATE",
  "STRUCT_START",
  "STRUCT_STOP",
  "STATE_DESCR",
  "AS",
  "ID",
  "FORK",
  "JOIN",
  "CHOICE",
  "CONCURRENT",
  "note",
  "NOTE_TEXT",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "CLICK",
  "STRING",
  "HREF",
  "classDef",
  "CLASSDEF_ID",
  "DEFAULT_CLASSDEF_ID",
  "CLASSDEF_STYLEOPTS",
  "DEFAULT",
  "style",
  "STYLE_IDS",
  "STYLEDEF_STYLEOPTS",
  "class",
  "CLASSENTITY_IDS",
  "STYLECLASS",
  "direction_tb",
  "direction_bt",
  "direction_rl",
  "direction_lr",
  "EDGE_STATE",
  "STYLE_SEPARATOR",
  "left_of",
  "right_of",
  "INVALID",
)
