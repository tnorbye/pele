// Ported from Pele src/diagrams/sequence/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.sequence

object T {
  const val END = 0
  const val ERROR = 1
  const val INVALID = 2
  const val NEWLINE = 3
  const val SD = 4
  const val create = 5
  const val box = 6
  const val restOfLine = 7
  const val end = 8
  const val autonumber = 9
  const val NUM = 10
  const val off = 11
  const val activate = 12
  const val deactivate = 13
  const val title = 14
  const val legacy_title = 15
  const val acc_title = 16
  const val acc_title_value = 17
  const val acc_descr = 18
  const val acc_descr_value = 19
  const val acc_descr_multiline_value = 20
  const val loop = 21
  const val rect = 22
  const val opt = 23
  const val alt = 24
  const val par = 25
  const val par_over = 26
  const val critical = 27
  const val `break` = 28
  const val option = 29
  const val and = 30
  const val `else` = 31
  const val participant = 32
  const val AS = 33
  const val participant_actor = 34
  const val destroy = 35
  const val note = 36
  const val over = 37
  const val links = 38
  const val link = 39
  const val properties = 40
  const val details = 41
  const val COMMA = 42
  const val left_of = 43
  const val right_of = 44
  const val PLUS = 45
  const val MINUS = 46
  const val CENTRAL = 47
  const val ACTOR = 48
  const val CONFIG_START = 49
  const val CONFIG_CONTENT = 50
  const val CONFIG_END = 51
  const val TXT = 52
  const val SOLID_OPEN_ARROW = 53
  const val DOTTED_OPEN_ARROW = 54
  const val SOLID_ARROW = 55
  const val BIDIRECTIONAL_SOLID_ARROW = 56
  const val DOTTED_ARROW = 57
  const val BIDIRECTIONAL_DOTTED_ARROW = 58
  const val SOLID_CROSS = 59
  const val DOTTED_CROSS = 60
  const val SOLID_POINT = 61
  const val DOTTED_POINT = 62
  const val SOLID_ARROW_TOP = 63
  const val SOLID_ARROW_BOTTOM = 64
  const val STICK_ARROW_TOP = 65
  const val STICK_ARROW_BOTTOM = 66
  const val SOLID_ARROW_TOP_DOTTED = 67
  const val SOLID_ARROW_BOTTOM_DOTTED = 68
  const val STICK_ARROW_TOP_DOTTED = 69
  const val STICK_ARROW_BOTTOM_DOTTED = 70
  const val SOLID_ARROW_TOP_REVERSE = 71
  const val SOLID_ARROW_BOTTOM_REVERSE = 72
  const val STICK_ARROW_TOP_REVERSE = 73
  const val STICK_ARROW_BOTTOM_REVERSE = 74
  const val SOLID_ARROW_TOP_REVERSE_DOTTED = 75
  const val SOLID_ARROW_BOTTOM_REVERSE_DOTTED = 76
  const val STICK_ARROW_TOP_REVERSE_DOTTED = 77
  const val STICK_ARROW_BOTTOM_REVERSE_DOTTED = 78
}

// Names as Mermaid's grammar spells them, for error messages and for comparison with its parser.
val TOKEN_NAMES: List<String> =
  listOf(
    "\$end",
    "INVALID",
    "INVALID",
    "NEWLINE",
    "SD",
    "create",
    "box",
    "restOfLine",
    "end",
    "autonumber",
    "NUM",
    "off",
    "activate",
    "deactivate",
    "title",
    "legacy_title",
    "acc_title",
    "acc_title_value",
    "acc_descr",
    "acc_descr_value",
    "acc_descr_multiline_value",
    "loop",
    "rect",
    "opt",
    "alt",
    "par",
    "par_over",
    "critical",
    "break",
    "option",
    "and",
    "else",
    "participant",
    "AS",
    "participant_actor",
    "destroy",
    "note",
    "over",
    "links",
    "link",
    "properties",
    "details",
    ",",
    "left_of",
    "right_of",
    "+",
    "-",
    "()",
    "ACTOR",
    "CONFIG_START",
    "CONFIG_CONTENT",
    "CONFIG_END",
    "TXT",
    "SOLID_OPEN_ARROW",
    "DOTTED_OPEN_ARROW",
    "SOLID_ARROW",
    "BIDIRECTIONAL_SOLID_ARROW",
    "DOTTED_ARROW",
    "BIDIRECTIONAL_DOTTED_ARROW",
    "SOLID_CROSS",
    "DOTTED_CROSS",
    "SOLID_POINT",
    "DOTTED_POINT",
    "SOLID_ARROW_TOP",
    "SOLID_ARROW_BOTTOM",
    "STICK_ARROW_TOP",
    "STICK_ARROW_BOTTOM",
    "SOLID_ARROW_TOP_DOTTED",
    "SOLID_ARROW_BOTTOM_DOTTED",
    "STICK_ARROW_TOP_DOTTED",
    "STICK_ARROW_BOTTOM_DOTTED",
    "SOLID_ARROW_TOP_REVERSE",
    "SOLID_ARROW_BOTTOM_REVERSE",
    "STICK_ARROW_TOP_REVERSE",
    "STICK_ARROW_BOTTOM_REVERSE",
    "SOLID_ARROW_TOP_REVERSE_DOTTED",
    "SOLID_ARROW_BOTTOM_REVERSE_DOTTED",
    "STICK_ARROW_TOP_REVERSE_DOTTED",
    "STICK_ARROW_BOTTOM_REVERSE_DOTTED",
  )
