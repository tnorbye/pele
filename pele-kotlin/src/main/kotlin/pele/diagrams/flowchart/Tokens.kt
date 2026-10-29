// Ported from Pele src/diagrams/flowchart/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.flowchart

@Suppress("ObjectPropertyName", "ConstPropertyName")
object T {
  const val END = 0
  const val ERROR = 1
  const val acc_title = 2
  const val acc_title_value = 3
  const val acc_descr = 4
  const val acc_descr_value = 5
  const val acc_descr_multiline_value = 6
  const val SHAPE_DATA = 7
  const val CALLBACKNAME = 8
  const val CALLBACKARGS = 9
  const val MD_STR = 10
  const val STR = 11
  const val STYLE = 12
  const val DEFAULT = 13
  const val LINKSTYLE = 14
  const val INTERPOLATE = 15
  const val CLASSDEF = 16
  const val CLASS = 17
  const val HREF = 18
  const val CLICK = 19
  const val GRAPH = 20
  const val subgraph = 21
  const val end = 22
  const val LINK_TARGET = 23
  const val NODIR = 24
  const val DIR = 25
  const val direction_tb = 26
  const val direction_bt = 27
  const val direction_rl = 28
  const val direction_lr = 29
  const val direction_td = 30
  const val LINK_ID = 31
  const val NUM = 32
  const val BRKT = 33
  const val STYLE_SEPARATOR = 34
  const val COLON = 35
  const val AMP = 36
  const val SEMI = 37
  const val COMMA = 38
  const val MULT = 39
  const val LINK = 40
  const val START_LINK = 41
  const val EDGE_TEXT = 42
  const val ELLIPSE_END = 43
  const val TEXT = 44
  const val ELLIPSE_START = 45
  const val STADIUMEND = 46
  const val STADIUMSTART = 47
  const val SUBROUTINEEND = 48
  const val SUBROUTINESTART = 49
  const val VERTEX_WITH_PROPS_START = 50
  const val TAGEND = 51
  const val CYLINDEREND = 52
  const val CYLINDERSTART = 53
  const val DOUBLECIRCLEEND = 54
  const val DOUBLECIRCLESTART = 55
  const val TRAPEND = 56
  const val INVTRAPEND = 57
  const val TRAPSTART = 58
  const val INVTRAPSTART = 59
  const val TAGSTART = 60
  const val UP = 61
  const val SEP = 62
  const val DOWN = 63
  const val NODE_STRING = 64
  const val MINUS = 65
  const val UNICODE_TEXT = 66
  const val PIPE = 67
  const val PE = 68
  const val PS = 69
  const val SQE = 70
  const val SQS = 71
  const val DIAMOND_STOP = 72
  const val DIAMOND_START = 73
  const val NEWLINE = 74
  const val SPACE = 75
  const val EOF = 76
}

val TOKEN_NAMES: List<String> = listOf(
  "\$end",
  "INVALID",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "SHAPE_DATA",
  "CALLBACKNAME",
  "CALLBACKARGS",
  "MD_STR",
  "STR",
  "STYLE",
  "DEFAULT",
  "LINKSTYLE",
  "INTERPOLATE",
  "CLASSDEF",
  "CLASS",
  "HREF",
  "CLICK",
  "GRAPH",
  "subgraph",
  "end",
  "LINK_TARGET",
  "NODIR",
  "DIR",
  "direction_tb",
  "direction_bt",
  "direction_rl",
  "direction_lr",
  "direction_td",
  "LINK_ID",
  "NUM",
  "BRKT",
  "STYLE_SEPARATOR",
  "COLON",
  "AMP",
  "SEMI",
  "COMMA",
  "MULT",
  "LINK",
  "START_LINK",
  "EDGE_TEXT",
  "-)",
  "TEXT",
  "(-",
  "STADIUMEND",
  "STADIUMSTART",
  "SUBROUTINEEND",
  "SUBROUTINESTART",
  "VERTEX_WITH_PROPS_START",
  "TAGEND",
  "CYLINDEREND",
  "CYLINDERSTART",
  "DOUBLECIRCLEEND",
  "DOUBLECIRCLESTART",
  "TRAPEND",
  "INVTRAPEND",
  "TRAPSTART",
  "INVTRAPSTART",
  "TAGSTART",
  "UP",
  "SEP",
  "DOWN",
  "NODE_STRING",
  "MINUS",
  "UNICODE_TEXT",
  "PIPE",
  "PE",
  "PS",
  "SQE",
  "SQS",
  "DIAMOND_STOP",
  "DIAMOND_START",
  "NEWLINE",
  "SPACE",
  "EOF",
)
