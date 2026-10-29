// Ported from Pele src/diagrams/block/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.block

object T {
  const val END = 0
  const val ERROR = 1
  const val KEY = 2
  const val ID_BLOCK = 3
  const val COLUMNS = 4
  const val MD_STR = 5
  const val STR = 6
  const val SPACE_BLOCK = 7
  const val DEFAULT = 8
  const val LINKSTYLE = 9
  const val INTERPOLATE = 10
  const val CLASSDEF = 11
  const val DEFAULT_CLASSDEF_ID = 12
  const val CLASSDEF_ID = 13
  const val CLASSDEF_STYLEOPTS = 14
  const val CLASS = 15
  const val CLASSENTITY_IDS = 16
  const val STYLECLASS = 17
  const val STYLE = 18
  const val STYLE_ENTITY_IDS = 19
  const val STYLE_DEFINITION_DATA = 20
  const val acc_title = 21
  const val acc_title_value = 22
  const val acc_descr = 23
  const val acc_descr_value = 24
  const val acc_descr_multiline_value = 25
  const val end = 26
  const val NODE_DSTART = 27
  const val NODE_DEND = 28
  const val BLOCK_ARROW_START = 29
  const val BLOCK_ARROW_END = 30
  const val DIR = 31
  const val NODE_ID = 32
  const val LINK = 33
  const val START_LINK = 34
  const val LINK_LABEL = 35
  const val SIZE = 36
  const val EOF = 37
}

val TOKEN_NAMES: List<String> = listOf(
  "\$end",
  "INVALID",
  "BLOCK_DIAGRAM_KEY",
  "id-block",
  "COLUMNS",
  "MD_STR",
  "STR",
  "SPACE_BLOCK",
  "DEFAULT",
  "LINKSTYLE",
  "INTERPOLATE",
  "classDef",
  "DEFAULT_CLASSDEF_ID",
  "CLASSDEF_ID",
  "CLASSDEF_STYLEOPTS",
  "class",
  "CLASSENTITY_IDS",
  "STYLECLASS",
  "style",
  "STYLE_ENTITY_IDS",
  "STYLE_DEFINITION_DATA",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "end",
  "NODE_DSTART",
  "NODE_DEND",
  "BLOCK_ARROW_START",
  "BLOCK_ARROW_END",
  "DIR",
  "NODE_ID",
  "LINK",
  "START_LINK",
  "LINK_LABEL",
  "SIZE",
  "EOF",
)
