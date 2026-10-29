// Ported from Pele src/diagrams/er/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.er

object T {
  const val END = 0
  const val ERROR = 1
  const val ER_DIAGRAM = 2
  const val EOF = 3
  const val NEWLINE = 4
  const val COLON = 5
  const val STYLE_SEPARATOR = 6
  const val BLOCK_START = 7
  const val BLOCK_STOP = 8
  const val SQS = 9
  const val SQE = 10
  const val acc_title = 11
  const val acc_title_value = 12
  const val acc_descr = 13
  const val acc_descr_value = 14
  const val acc_descr_multiline_value = 15
  const val END_KW = 16
  const val SUBGRAPH = 17
  const val direction_tb = 18
  const val direction_bt = 19
  const val direction_rl = 20
  const val direction_lr = 21
  const val CLASSDEF = 22
  const val UNICODE_TEXT = 23
  const val STYLE_TEXT = 24
  const val COMMA = 25
  const val CLASS = 26
  const val STYLE = 27
  const val SEMI = 28
  const val NUM = 29
  const val BRKT = 30
  const val ENTITY_NAME = 31
  const val DECIMAL_NUM = 32
  const val ENTITY_ONE = 33
  const val ATTRIBUTE_WORD = 34
  const val ATTRIBUTE_KEY = 35
  const val COMMENT = 36
  const val ZERO_OR_ONE = 37
  const val ZERO_OR_MORE = 38
  const val ONE_OR_MORE = 39
  const val ONLY_ONE = 40
  const val MD_PARENT = 41
  const val NON_IDENTIFYING = 42
  const val IDENTIFYING = 43
  const val WORD = 44
  // A character no rule names; the grammar gives it meaning only as `?` and `,` inside a block.
  const val CHAR = 45
}

val TOKEN_NAMES: List<String> =
  listOf(
    "\$end",
    "INVALID",
    "ER_DIAGRAM",
    "EOF",
    "NEWLINE",
    "COLON",
    "STYLE_SEPARATOR",
    "BLOCK_START",
    "BLOCK_STOP",
    "SQS",
    "SQE",
    "acc_title",
    "acc_title_value",
    "acc_descr",
    "acc_descr_value",
    "acc_descr_multiline_value",
    "END",
    "SUBGRAPH",
    "direction_tb",
    "direction_bt",
    "direction_rl",
    "direction_lr",
    "CLASSDEF",
    "UNICODE_TEXT",
    "STYLE_TEXT",
    "COMMA",
    "CLASS",
    "STYLE",
    "SEMI",
    "NUM",
    "BRKT",
    "ENTITY_NAME",
    "DECIMAL_NUM",
    "ENTITY_ONE",
    "ATTRIBUTE_WORD",
    "ATTRIBUTE_KEY",
    "COMMENT",
    "ZERO_OR_ONE",
    "ZERO_OR_MORE",
    "ONE_OR_MORE",
    "ONLY_ONE",
    "MD_PARENT",
    "NON_IDENTIFYING",
    "IDENTIFYING",
    "WORD",
    "CHAR",
  )
