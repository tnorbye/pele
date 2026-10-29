// Ported from Pele src/diagrams/requirement/tokens.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.requirement

object T {
  const val END = 0
  const val ERROR = 1
  const val title = 2
  const val acc_title = 3
  const val acc_title_value = 4
  const val acc_descr = 5
  const val acc_descr_value = 6
  const val acc_descr_multiline_value = 7
  const val direction_tb = 8
  const val direction_bt = 9
  const val direction_rl = 10
  const val direction_lr = 11
  const val NEWLINE = 12
  const val EOF = 13
  const val RD = 14
  const val STRUCT_START = 15
  const val STRUCT_STOP = 16
  const val STYLE_SEPARATOR = 17
  const val COLONSEP = 18
  const val ID = 19
  const val TEXT = 20
  const val RISK = 21
  const val VERIFYMTHD = 22
  const val REQUIREMENT = 23
  const val FUNCTIONAL_REQUIREMENT = 24
  const val INTERFACE_REQUIREMENT = 25
  const val PERFORMANCE_REQUIREMENT = 26
  const val PHYSICAL_REQUIREMENT = 27
  const val DESIGN_CONSTRAINT = 28
  const val LOW_RISK = 29
  const val MED_RISK = 30
  const val HIGH_RISK = 31
  const val VERIFY_ANALYSIS = 32
  const val VERIFY_DEMONSTRATION = 33
  const val VERIFY_INSPECTION = 34
  const val VERIFY_TEST = 35
  const val ELEMENT = 36
  const val CONTAINS = 37
  const val COPIES = 38
  const val DERIVES = 39
  const val SATISFIES = 40
  const val VERIFIES = 41
  const val REFINES = 42
  const val TRACES = 43
  const val TYPE = 44
  const val DOCREF = 45
  const val STYLE = 46
  const val CLASSDEF = 47
  const val CLASS = 48
  const val ALPHA = 49
  const val COLON = 50
  const val SEMICOLON = 51
  const val PERCENT = 52
  const val MINUS = 53
  const val BRKT = 54
  const val END_ARROW_L = 55
  const val END_ARROW_R = 56
  const val LINE = 57
  const val qString = 58
  const val unqString = 59
  const val COMMA = 60
}

val TOKEN_NAMES: List<String> = listOf(
  "\$end",
  "INVALID",
  "title",
  "acc_title",
  "acc_title_value",
  "acc_descr",
  "acc_descr_value",
  "acc_descr_multiline_value",
  "direction_tb",
  "direction_bt",
  "direction_rl",
  "direction_lr",
  "NEWLINE",
  "EOF",
  "RD",
  "STRUCT_START",
  "STRUCT_STOP",
  "STYLE_SEPARATOR",
  "COLONSEP",
  "ID",
  "TEXT",
  "RISK",
  "VERIFYMTHD",
  "REQUIREMENT",
  "FUNCTIONAL_REQUIREMENT",
  "INTERFACE_REQUIREMENT",
  "PERFORMANCE_REQUIREMENT",
  "PHYSICAL_REQUIREMENT",
  "DESIGN_CONSTRAINT",
  "LOW_RISK",
  "MED_RISK",
  "HIGH_RISK",
  "VERIFY_ANALYSIS",
  "VERIFY_DEMONSTRATION",
  "VERIFY_INSPECTION",
  "VERIFY_TEST",
  "ELEMENT",
  "CONTAINS",
  "COPIES",
  "DERIVES",
  "SATISFIES",
  "VERIFIES",
  "REFINES",
  "TRACES",
  "TYPE",
  "DOCREF",
  "STYLE",
  "CLASSDEF",
  "CLASS",
  "ALPHA",
  "COLON",
  "SEMICOLON",
  "PERCENT",
  "MINUS",
  "BRKT",
  "END_ARROW_L",
  "END_ARROW_R",
  "LINE",
  "qString",
  "unqString",
  "COMMA",
)

// Every keyword is matched without regard to case and must end at a word boundary.
val KEYWORDS: Map<String, Int> = mapOf(
  "requirementdiagram" to T.RD,
  "id" to T.ID,
  "text" to T.TEXT,
  "risk" to T.RISK,
  "verifymethod" to T.VERIFYMTHD,
  "requirement" to T.REQUIREMENT,
  "functionalrequirement" to T.FUNCTIONAL_REQUIREMENT,
  "interfacerequirement" to T.INTERFACE_REQUIREMENT,
  "performancerequirement" to T.PERFORMANCE_REQUIREMENT,
  "physicalrequirement" to T.PHYSICAL_REQUIREMENT,
  "designconstraint" to T.DESIGN_CONSTRAINT,
  "low" to T.LOW_RISK,
  "medium" to T.MED_RISK,
  "high" to T.HIGH_RISK,
  "analysis" to T.VERIFY_ANALYSIS,
  "demonstration" to T.VERIFY_DEMONSTRATION,
  "inspection" to T.VERIFY_INSPECTION,
  "test" to T.VERIFY_TEST,
  "element" to T.ELEMENT,
  "contains" to T.CONTAINS,
  "copies" to T.COPIES,
  "derives" to T.DERIVES,
  "satisfies" to T.SATISFIES,
  "verifies" to T.VERIFIES,
  "refines" to T.REFINES,
  "traces" to T.TRACES,
  "type" to T.TYPE,
  "docref" to T.DOCREF,
  "style" to T.STYLE,
  "classdef" to T.CLASSDEF,
  "class" to T.CLASS,
)
