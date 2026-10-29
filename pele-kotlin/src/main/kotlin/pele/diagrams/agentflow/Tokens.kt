// Ported from Pele src/diagrams/agentflow/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.agentflow

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
  const val flow = 21
  const val connector = 22
  const val global = 23
  const val end = 24
  const val LINK_TARGET = 25
  const val NODIR = 26
  const val DIR = 27
  const val direction_tb = 28
  const val direction_bt = 29
  const val direction_rl = 30
  const val direction_lr = 31
  const val direction_td = 32
  const val LINK_ID = 33
  const val NUM = 34
  const val BRKT = 35
  const val STYLE_SEPARATOR = 36
  const val COLON = 37
  const val AMP = 38
  const val SEMI = 39
  const val COMMA = 40
  const val MULT = 41
  const val LINK = 42
  const val START_LINK = 43
  const val EDGE_TEXT = 44
  const val ELLIPSE_END = 45
  const val TEXT = 46
  const val ELLIPSE_START = 47
  const val STADIUMEND = 48
  const val STADIUMSTART = 49
  const val SUBROUTINEEND = 50
  const val SUBROUTINESTART = 51
  const val VERTEX_WITH_PROPS_START = 52
  const val TAGEND = 53
  const val CYLINDEREND = 54
  const val CYLINDERSTART = 55
  const val DOUBLECIRCLEEND = 56
  const val DOUBLECIRCLESTART = 57
  const val TRAPEND = 58
  const val INVTRAPEND = 59
  const val TRAPSTART = 60
  const val INVTRAPSTART = 61
  const val TAGSTART = 62
  const val UP = 63
  const val SEP = 64
  const val DOWN = 65
  const val COMMENT = 66
  const val NODE_STRING = 67
  const val MINUS = 68
  const val UNICODE_TEXT = 69
  const val PIPE = 70
  const val PE = 71
  const val PS = 72
  const val SQE = 73
  const val SQS = 74
  const val DIAMOND_STOP = 75
  const val DIAMOND_START = 76
  const val NEWLINE = 77
  const val SPACE = 78
  const val EOF = 79
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
  "flow",
  "connector",
  "global",
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
  "COMMENT",
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
