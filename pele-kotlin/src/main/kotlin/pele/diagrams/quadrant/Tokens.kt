// Ported from Pele src/diagrams/quadrant/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.quadrant

object T {
  const val END = 0
  const val ERROR = 1
  const val NEWLINE = 2
  const val title = 3
  const val title_value = 4
  const val acc_title = 5
  const val acc_title_value = 6
  const val acc_descr = 7
  const val acc_descr_value = 8
  const val acc_descr_multiline_value = 9
  const val X_AXIS = 10
  const val Y_AXIS = 11
  const val DELIMITER = 12
  const val QUADRANT_1 = 13
  const val QUADRANT_2 = 14
  const val QUADRANT_3 = 15
  const val QUADRANT_4 = 16
  const val CLASSDEF = 17
  const val MD_STR = 18
  const val STR = 19
  const val class_name = 20
  const val point_start = 21
  const val point_x = 22
  const val point_y = 23
  const val QUADRANT = 24
  const val ALPHA = 25
  const val UNICODE_TEXT = 26
  const val COLON = 27
  const val PLUS = 28
  const val COMMA = 29
  const val EQUALS = 30
  const val MULT = 31
  const val BRKT = 32
  const val UNDERSCORE = 33
  const val DOT = 34
  const val AMP = 35
  const val MINUS = 36
  const val NUM = 37
  const val SPACE = 38
  const val SEMI = 39
  const val PUNCTUATION = 40
  const val EOF = 41
}

val TOKEN_NAMES: List<String> =
  listOf(
    "\$end",
    "INVALID",
    "NEWLINE",
    "title",
    "title_value",
    "acc_title",
    "acc_title_value",
    "acc_descr",
    "acc_descr_value",
    "acc_descr_multiline_value",
    "X-AXIS",
    "Y-AXIS",
    "AXIS-TEXT-DELIMITER",
    "QUADRANT_1",
    "QUADRANT_2",
    "QUADRANT_3",
    "QUADRANT_4",
    "CLASSDEF",
    "MD_STR",
    "STR",
    "class_name",
    "point_start",
    "point_x",
    "point_y",
    "QUADRANT",
    "ALPHA",
    "UNICODE_TEXT",
    "COLON",
    "PLUS",
    "COMMA",
    "EQUALS",
    "MULT",
    "BRKT",
    "UNDERSCORE",
    "DOT",
    "AMP",
    "MINUS",
    "NUM",
    "SPACE",
    "SEMI",
    "PUNCTUATION",
    "EOF",
  )
