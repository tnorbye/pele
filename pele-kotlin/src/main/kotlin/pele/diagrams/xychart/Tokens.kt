// Ported from Pele src/diagrams/xychart/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.xychart

object T {
  const val END = 0
  const val ERROR = 1
  const val NEWLINE = 2
  const val title = 3
  const val acc_title = 4
  const val acc_title_value = 5
  const val acc_descr = 6
  const val acc_descr_value = 7
  const val acc_descr_multiline_value = 8
  const val XYCHART = 9
  const val CHART_ORIENTATION = 10
  const val X_AXIS = 11
  const val Y_AXIS = 12
  const val ARROW_DELIMITER = 13
  const val LINE = 14
  const val BAR = 15
  const val NUMBER_WITH_DECIMAL = 16
  const val SQUARE_BRACES_START = 17
  const val SQUARE_BRACES_END = 18
  const val STR = 19
  const val ALPHA = 20
  const val COLON = 21
  const val PLUS = 22
  const val COMMA = 23
  const val EQUALS = 24
  const val MULT = 25
  const val BRKT = 26
  const val UNDERSCORE = 27
  const val DOT = 28
  const val AMP = 29
  const val MINUS = 30
  const val NUM = 31
  const val SEMI = 32
  const val EOF = 33
}

val TOKEN_NAMES: List<String> = listOf(
  "\$end",
  "INVALID",
  "NEWLINE",
  "title",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "XYCHART",
  "CHART_ORIENTATION",
  "X_AXIS",
  "Y_AXIS",
  "ARROW_DELIMITER",
  "LINE",
  "BAR",
  "NUMBER_WITH_DECIMAL",
  "SQUARE_BRACES_START",
  "SQUARE_BRACES_END",
  "STR",
  "ALPHA",
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
  "SEMI",
  "EOF",
)
