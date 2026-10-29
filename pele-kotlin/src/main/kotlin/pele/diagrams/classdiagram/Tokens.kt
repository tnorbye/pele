// Ported from Pele src/diagrams/class/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.classdiagram

object T {
  const val END = 0
  const val ERROR = 1
  const val CLASS_DIAGRAM = 2
  const val NEWLINE = 3
  const val EOF = 4
  const val SQS = 5
  const val STR = 6
  const val SQE = 7
  const val DOT = 8
  const val GENERICTYPE = 9
  const val LABEL = 10
  const val acc_title = 11
  const val acc_title_value = 12
  const val acc_descr = 13
  const val acc_descr_value = 14
  const val acc_descr_multiline_value = 15
  const val STRUCT_START = 16
  const val STRUCT_STOP = 17
  const val NAMESPACE = 18
  const val STYLE_SEPARATOR = 19
  const val ANNOTATION_START = 20
  const val ANNOTATION_END = 21
  const val CLASS = 22
  const val MEMBER = 23
  const val NOTE_FOR = 24
  const val NOTE = 25
  const val CLASSDEF = 26
  const val ALPHA = 27
  const val COMMA = 28
  const val direction_tb = 29
  const val direction_bt = 30
  const val direction_rl = 31
  const val direction_lr = 32
  const val AGGREGATION = 33
  const val EXTENSION = 34
  const val COMPOSITION = 35
  const val DEPENDENCY = 36
  const val LOLLIPOP = 37
  const val LINE = 38
  const val DOTTED_LINE = 39
  const val CALLBACK = 40
  const val LINK = 41
  const val LINK_TARGET = 42
  const val CLICK = 43
  const val CALLBACK_NAME = 44
  const val CALLBACK_ARGS = 45
  const val HREF = 46
  const val STYLE = 47
  const val CSSCLASS = 48
  const val COLON = 49
  const val BRKT = 50
  const val PCT = 51
  const val MINUS = 52
  const val UNICODE_TEXT = 53
  const val BQUOTE_STR = 54
  const val EDGE_STATE = 55
  const val EOF_IN_STRUCT = 56
  const val OPEN_IN_STRUCT = 57
  const val PLUS = 58
  const val EQUALS = 59
  const val PUNCTUATION = 60
}

val TOKEN_NAMES: List<String> = listOf(
  "\$end",
  "INVALID",
  "CLASS_DIAGRAM",
  "NEWLINE",
  "EOF",
  "SQS",
  "STR",
  "SQE",
  "DOT",
  "GENERICTYPE",
  "LABEL",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "STRUCT_START",
  "STRUCT_STOP",
  "NAMESPACE",
  "STYLE_SEPARATOR",
  "ANNOTATION_START",
  "ANNOTATION_END",
  "CLASS",
  "MEMBER",
  "NOTE_FOR",
  "NOTE",
  "CLASSDEF",
  "ALPHA",
  "COMMA",
  "direction_tb",
  "direction_bt",
  "direction_rl",
  "direction_lr",
  "AGGREGATION",
  "EXTENSION",
  "COMPOSITION",
  "DEPENDENCY",
  "LOLLIPOP",
  "LINE",
  "DOTTED_LINE",
  "CALLBACK",
  "LINK",
  "LINK_TARGET",
  "CLICK",
  "CALLBACK_NAME",
  "CALLBACK_ARGS",
  "HREF",
  "STYLE",
  "CSSCLASS",
  "COLON",
  "BRKT",
  "PCT",
  "MINUS",
  "UNICODE_TEXT",
  "BQUOTE_STR",
  "EDGE_STATE",
  "EOF_IN_STRUCT",
  "OPEN_IN_STRUCT",
  "PLUS",
  "EQUALS",
  "PUNCTUATION",
)
