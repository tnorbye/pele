// Ported from Pele src/diagrams/usecase/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.usecase

import pele.js.jsSplit

// Mermaid's token types, in an order that makes its token categories ranges:
// WORD is USECASE to IDENTIFIER, and LABEL_TEXT is USECASE to LABEL_SYMBOL.
object T {
  const val EOF = 0
  const val USECASE = 1
  const val ACTOR = 2
  const val SYSTEM_BOUNDARY = 3
  const val END = 4
  const val DIRECTION = 5
  const val TD = 6
  const val TB = 7
  const val BT = 8
  const val LR = 9
  const val RL = 10
  const val NOTE = 11
  const val FOR = 12
  const val JSON = 13
  const val CLASS_DEF = 14
  const val CLASS = 15
  const val STYLE = 16
  const val INCLUDE = 17
  const val EXTEND = 18
  const val TRUE = 19
  const val FALSE = 20
  const val IDENTIFIER = 21
  const val NUMBER = 22
  const val AT = 23
  const val COMMA = 24
  const val COLON = 25
  const val HASH_COLOR = 26
  const val CSS_IDENTIFIER = 27
  const val CSS_ESCAPED_COMMA = 28
  const val DASH = 29
  const val DOT = 30
  const val PERCENT = 31
  const val CSS_PUNCTUATION = 32
  const val LABEL_PUNCTUATION = 33
  const val LABEL_SYMBOL = 34
  const val PLAIN_STRING = 35
  const val MARKDOWN_STRING = 36
  const val UNCLOSED_MARKDOWN_STRING = 37
  const val COMMENT = 38
  const val NEWLINE = 39
  const val ACC_DESCR_BLOCK = 40
  const val ACC_TITLE_LINE = 41
  const val ACC_DESCR_LINE = 42
  const val JSON_DECLARATION_START = 43
  const val JSON_OBJECT_LITERAL = 44
  const val UNCLOSED_JSON_OBJECT_LITERAL = 45
  const val GENERALIZATION = 46
  const val DEPENDENCY_ARROW = 47
  const val STEREOTYPE_START = 48
  const val STEREOTYPE_END = 49
  const val STEREOTYPE_TEXT = 50
  const val UNCLOSED_STEREOTYPE_TEXT = 51
  const val CLASS_SEPARATOR = 52
  const val FORWARD_SOLID = 53
  const val BACKWARD_SOLID = 54
  const val FORWARD_CIRCLE = 55
  const val BACKWARD_CIRCLE = 56
  const val FORWARD_CROSS = 57
  const val BACKWARD_CROSS = 58
  const val MARKERLESS_SOLID = 59
  const val METADATA_START = 60
  const val LBRACE = 61
  const val RBRACE = 62
  const val LBRACKET = 63
  const val RBRACKET = 64
  const val LPAREN = 65
  const val RPAREN = 66
}

val TOKEN_NAMES: List<String> =
  "EOF USECASE ACTOR SYSTEM_BOUNDARY END DIRECTION TD TB BT LR RL NOTE FOR JSON CLASS_DEF CLASS STYLE INCLUDE EXTEND TRUE FALSE IDENTIFIER NUMBER AT COMMA COLON HASH_COLOR CSS_IDENTIFIER CSS_ESCAPED_COMMA DASH DOT PERCENT CSS_PUNCTUATION LABEL_PUNCTUATION LABEL_SYMBOL PLAIN_STRING MARKDOWN_STRING UNCLOSED_MARKDOWN_STRING COMMENT NEWLINE ACC_DESCR_BLOCK ACC_TITLE_LINE ACC_DESCR_LINE JSON_DECLARATION_START JSON_OBJECT_LITERAL UNCLOSED_JSON_OBJECT_LITERAL GENERALIZATION DEPENDENCY_ARROW STEREOTYPE_START STEREOTYPE_END STEREOTYPE_TEXT UNCLOSED_STEREOTYPE_TEXT CLASS_SEPARATOR FORWARD_SOLID BACKWARD_SOLID FORWARD_CIRCLE BACKWARD_CIRCLE FORWARD_CROSS BACKWARD_CROSS MARKERLESS_SOLID METADATA_START LBRACE RBRACE LBRACKET RBRACKET LPAREN RPAREN"
    .jsSplit(" ")
