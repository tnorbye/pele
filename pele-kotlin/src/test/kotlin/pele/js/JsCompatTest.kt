// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Expected values come from Node 24.
class JsCompatTest {
  @Test
  fun jdkVersion() {
    assertTrue(Runtime.version().feature() >= 17)
  }

  @Test
  fun numberToString() {
    val nums = listOf(
      12345678.5, 1e21, 1e-7, 123e-20, -0.0, 0.1 + 0.2, 1.0 / 3, -1e-7, 5e-324, 1.7976931348623157e308, 100.0,
      0.000001, 1234.5678e10, 9007199254740994.0, 4503599627370497.0, 12345678901234567890.0,
    )
    val expected = listOf(
      "12345678.5", "1e+21", "1e-7", "1.23e-18", "0", "0.30000000000000004", "0.3333333333333333", "-1e-7", "5e-324",
      "1.7976931348623157e+308", "100", "0.000001", "12345678000000", "9007199254740994", "4503599627370497",
      "12345678901234567000",
    )
    assertEquals(expected, nums.map(::jsStr))
    assertEquals(expected, nums.map(::jsStrFallback))
  }

  @Test
  fun round() {
    val input = listOf(0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.4, 4503599627370497.0)
    assertEquals(listOf("1", "0", "2", "-1", "3", "-2", "0", "0", "4503599627370497"), input.map { jsStr(jsRound(it)) })
    assertEquals(-0.0, jsRound(-0.4))
    assertTrue(jsRound(Double.NaN).isNaN())
  }

  @Test
  fun comparators() {
    // A comparator that returns NaN counts as 0, and `NaN || x` and `-0 || x` are x.
    assertEquals(listOf(1, -1, 0, 0), listOf(2.0, -0.5, 0.0, Double.NaN).map(::cmp))
    assertEquals(5.0, Double.NaN.jsOr { 5.0 })
    assertEquals(5.0, (-0.0).jsOr { 5.0 })
    assertEquals(-2.0, (-2.0).jsOr { 5.0 })
  }

  @Test
  fun hypot() {
    // Math.hypot in Node 24.
    assertEquals(5.0, jsHypot(3.0, 4.0))
    assertEquals(123.45600000405001, jsHypot(123.456, 0.001))
    assertEquals(2.9154759474226504, jsHypot(1.1, 2.7))
    assertEquals(3.3333001500014965, jsHypot(1e-3, 3.3333))
    assertEquals(0.0, jsHypot(0.0, -0.0))
    assertEquals(Double.POSITIVE_INFINITY, jsHypot(Double.NaN, Double.NEGATIVE_INFINITY))
    assertTrue(jsHypot(Double.NaN, 1.0).isNaN())
  }

  @Test
  fun toFixed() {
    val input = listOf(
      1.005 to 2, -0.001 to 2, -0.0 to 2, 1.45 to 1, 123.456 to 0, 0.5 to 0, 1.5 to 0, 2.5 to 0, 1e21 to 2,
      -1.5 to 0,
    )
    assertEquals(
      listOf("1.00", "-0.00", "0.00", "1.4", "123", "1", "2", "3", "1e+21", "-2"),
      input.map { (x, d) -> x.toFixed(d) },
    )
  }

  @Test
  fun toPrecision() {
    val input = listOf(
      123.456 to 4, 0.000123 to 2, 1234567.0 to 3, 1.5 to 1, 99.99 to 3, 0.1 to 3, 1e-7 to 2, 123.0 to 3,
      123.0 to 5, 5.0 to 1,
    )
    assertEquals(
      listOf("123.5", "0.00012", "1.23e+6", "2", "100", "0.100", "1.0e-7", "123", "123.00", "5"),
      input.map { (x, p) -> x.toPrecision(p) },
    )
  }

  @Test
  fun number() {
    val input = listOf(
      "-0x10", "0x1p3", "1f", "", " ﻿12 ", "0x10", "0b101", "1e3", ".5", "5.", "1_0", "+.5e-2", "Infinity",
      "-Infinity", "infinity", " 12 ",
    )
    assertEquals(
      listOf("NaN", "NaN", "NaN", "0", "12", "16", "5", "1000", "0.5", "5", "NaN", "0.005", "Infinity", "-Infinity", "NaN", "12"),
      input.map { jsStr(jsNumber(it)) },
    )
  }

  @Test
  fun parseFloatAndInt() {
    val floats = listOf("1.5.2px", ".", "-.5e3x", "  12abc", "Infinityx", "+", "1e", "1e+", "0x10", "  3")
    assertEquals(
      listOf("1.5", "NaN", "-500", "12", "Infinity", "NaN", "1", "1", "0", "3"),
      floats.map { jsStr(jsParseFloat(it)) },
    )
    val ints = listOf("12px", "0x1F", "-0x1F", "  -12.9", "abc", "99999999999999999999", "08", "1e3", " 7")
    assertEquals(
      listOf("12", "0", "0", "-12", "NaN", "100000000000000000000", "8", "1", "7"),
      ints.map { jsStr(jsParseInt(it, 10)) },
    )
    assertEquals(listOf("31", "12", "-31"), listOf("0x1F", "12", "-0x1F").map { jsStr(jsParseInt(it)) })
  }

  @Test
  fun trim() {
    assertEquals(listOf("a\u001c", "\u001cb\u001f", "c"), listOf("﻿a\u001C ", "\u001Cb\u001F", " c ").map { it.jsTrim() })
  }

  @Test
  fun split() {
    assertEquals(listOf("a", " ", "b"), "a b".jsSplit(JsRegExp("(\\s)")))
    assertEquals(listOf("a", "b", "c"), "abc".jsSplit(JsRegExp("x*")))
    assertEquals(listOf("a", "b", "c"), "abc".jsSplit(""))
    assertEquals(listOf(""), "".jsSplit(JsRegExp("x")))
    assertEquals(emptyList(), "".jsSplit(JsRegExp("x*")))
    assertEquals(listOf("a", "b", ""), "a,b,".jsSplit(JsRegExp(",")))
    assertEquals(listOf("A", "1", "B", "2", ""), "A1B2".jsSplit(JsRegExp("(\\d)?")))
  }

  @Test
  fun replace() {
    assertEquals("a[b$\$1acabc]ca[b$\$1abcac]c", "abcabc".jsReplace(JsRegExp("b", "g"), "[$&$$$1$`$']"))
    assertEquals("ba$3a0ac", "abc".jsReplace(JsRegExp("(a)(b)"), "$2$1$3$10$01"))
    assertEquals("xx", "aaa".jsReplace(JsRegExp("a*", "g"), "x"))
    assertEquals("abbc", "abc".jsReplace("b", "$&$&"))
  }

  @Test
  fun regexTranslation() {
    val cases = listOf(
      Triple("\\s", "", " ") to true,
      Triple("\\s", "", "\u0085") to false,
      Triple("beta\\b", "", "beta中") to true,
      Triple("x$", "", "x\n") to false,
      Triple("^b", "m", "a\r\nb") to true,
      Triple("a$", "m", "a\u0085b") to false,
      Triple(".", "", "\u0085") to true,
      Triple(".", "", " ") to false,
      Triple("[[]", "", "[") to true,
      Triple("a{", "", "a{") to true,
      Triple("[\\b]", "", "\b") to true,
      Triple("\\cJ", "", "\n") to true,
      Triple("\\v", "", "\u000b") to true,
      Triple("\\a", "", "a") to true,
      Triple("é", "i", "É") to true,
      Triple("[a&&b]", "", "&") to true,
      Triple("^$", "m", "a\n") to true,
      Triple("\\0", "", "\u0000") to true,
    )
    for ((c, expected) in cases) {
      val (source, flags, input) = c
      assertEquals(expected, JsRegExp(source, flags).test(input), "/$source/$flags on ${jsonQuote(input)}")
    }
  }

  @Test
  fun sticky() {
    val re = JsRegExp("[a-z]+", "y")
    re.lastIndex = 2
    assertEquals(null, re.exec("ab12cd"))
    assertEquals(0, re.lastIndex)
    re.lastIndex = 4
    assertEquals("cd", re.exec("ab12cd")?.value)
    assertEquals(6, re.lastIndex)
    // Lookbehind sees text before lastIndex.
    val lb = JsRegExp("(?<=a)b", "y")
    lb.lastIndex = 1
    assertEquals("b", lb.exec("ab")?.value)
    // `^` without /m only matches at the start of input, even with a region.
    val caret = JsRegExp("^b", "y")
    caret.lastIndex = 1
    assertEquals(null, caret.exec("ab"))
  }

  @Test
  fun json() {
    assertEquals(listOf("\"x y\"", "\"a\\\"b\\\\\\n\\u0001\\ud800\""), listOf("x y", "a\"b\\\n\u0001\ud800").map(::jsonQuote))
    val parsed = jsonParse("""{"b":1,"2":true,"a":[null,"x"],"1":{}}""") as Map<*, *>
    assertEquals(listOf("1", "2", "b", "a"), parsed.keys.toList())
  }

  @Test
  fun jsonErrorMessages() {
    // Messages and positions as V8 words them (Node 24), which usecase reads positions from.
    val cases = listOf(
      "" to "Unexpected end of JSON input",
      " " to "Unexpected end of JSON input",
      "{" to "Expected property name or '}' in JSON at position 1 (line 1 column 2)",
      "{\"a\"" to "Expected ':' after property name in JSON at position 4 (line 1 column 5)",
      "{\"a\":1" to "Expected ',' or '}' after property value in JSON at position 6 (line 1 column 7)",
      "{\"a\":1," to "Expected double-quoted property name in JSON at position 7 (line 1 column 8)",
      "{\"a\":1,,\"b\":2}" to "Expected double-quoted property name in JSON at position 7 (line 1 column 8)",
      "{a:1}" to "Expected property name or '}' in JSON at position 1 (line 1 column 2)",
      "[1,]" to "Unexpected token ']', \"[1,]\" is not valid JSON",
      "[1 2]" to "Expected ',' or ']' after array element in JSON at position 3 (line 1 column 4)",
      "[1" to "Expected ',' or ']' after array element in JSON at position 2 (line 1 column 3)",
      "{\"a\":01}" to "Unexpected number in JSON at position 6 (line 1 column 7)",
      "{\"a\":n1}" to "Unexpected number in JSON at position 6 (line 1 column 7)",
      "{\"a\":nul}" to "Unexpected token '}', \"{\"a\":nul}\" is not valid JSON",
      "-" to "No number after minus sign in JSON at position 1 (line 1 column 2)",
      "-x" to "No number after minus sign in JSON at position 1 (line 1 column 2)",
      "1." to "Unterminated fractional number in JSON at position 2 (line 1 column 3)",
      "1e" to "Exponent part is missing a number in JSON at position 2 (line 1 column 3)",
      "1e+" to "Exponent part is missing a number in JSON at position 3 (line 1 column 4)",
      "\"abc" to "Unterminated string in JSON at position 4 (line 1 column 5)",
      "\"a\nb\"" to "Bad control character in string literal in JSON at position 2 (line 1 column 3)",
      "\"\\x\"" to "Bad escaped character in JSON at position 2 (line 1 column 3)",
      "\"\\u12G4\"" to "Bad Unicode escape in JSON at position 5 (line 1 column 6)",
      "\"\\" to "Unexpected end of JSON input",
      "{\"a\":1}}" to "Unexpected non-whitespace character after JSON at position 7 (line 1 column 8)",
      "1 2" to "Unexpected non-whitespace character after JSON at position 2 (line 1 column 3)",
      "undefined" to "\"undefined\" is not valid JSON",
      "NaN" to "\"NaN\" is not valid JSON",
      "{\"a\":😀}" to "Unexpected token '\ud83d', \"{\"a\":😀}\" is not valid JSON",
      "{\"abcdefghijklmnopqrstuvwxyz\":x}" to "Unexpected token 'x', ...\"stuvwxyz\":x}\" is not valid JSON",
      "{\"a\":1,\"bcdefghijklmnopq\":x}" to "Unexpected token 'x', ...\"jklmnopq\":x}\" is not valid JSON",
      "[1,2,3,4,5,6,7,8,9,10,11,x]" to "Unexpected token 'x', ...\"8,9,10,11,x]\" is not valid JSON",
      "{\r\n\"a\":\r\n}" to "Unexpected token '}', \"{\r\n\"a\":\r\n}\" is not valid JSON",
      "{\"a\":\n\n}" to "Unexpected token '}', \"{\"a\":\n\n}\" is not valid JSON",
    )
    for ((input, expected) in cases) {
      val actual = try {
        jsonParse(input)
        "no error"
      } catch (e: JsonSyntaxError) {
        e.message
      }
      assertEquals(expected, actual, jsonQuote(input))
    }
  }

  @Test
  fun int32() {
    assertEquals(listOf(-1, -2147483648, 1), listOf(-1.0, 2147483648.0, 4294967297.0).map(::toInt32))
    assertEquals(4294967295.0, toUint32(-1.0))
    assertEquals(2147483645, jsImul(0x7fffffff, 3))
    assertEquals(1661992960, toInt32(1e20))
  }

  @Test
  fun urlHostSeparators() {
    assertEquals(null, whatwgHref("https://www\u2028.example.com"))
    assertEquals("https://www.example.com/", whatwgHref("https://www.example.com"))
  }

  @Test
  fun urlHostMapping() {
    // Expected values from Node 24's `new URL(x).href` (null where it throws).
    val cases = listOf(
      "https:/\u0301/www.github.com" to null,
      "http://\u0301/" to null,
      "http://a\u0301b/" to "http://xn--b-tfa/",
      "http://\u00ad/" to null,
      "http://x\u200b/" to "http://x/",
      "http://A\u2028/" to null,
      "http://\u0301.com/" to null,
      "http://ab\u0300c.de" to "http://xn--abc-hdc.de/",
      "http://\u0130/" to "http://xn--i-9bb/",
      "http://A\u0130b.com/" to "http://xn--aib-9dc.com/",
      "http://\u212a/" to "http://k/",
      "http://\u017f.com/" to "http://s.com/",
      "http://\u1e9e/" to "http://xn--zca/",
      "http://\u0131/" to "http://xn--cfa/",
    )
    for ((input, expected) in cases) assertEquals(expected, whatwgHref(input), jsonQuote(input))
  }

  @Test
  fun caseInsensitiveFolding() {
    // Expected values from Node 24.
    val cases = listOf(
      Triple("k", "i", "\u212a") to false,
      Triple("i", "i", "\u0131") to false,
      Triple("s", "i", "\u017f") to false,
      Triple("é", "i", "É") to true,
      Triple("k", "iu", "\u212a") to true,
      Triple("i", "iu", "\u0131") to false,
      Triple("s", "iu", "\u017f") to true,
      Triple("ß", "i", "\u1e9e") to false,
      Triple("[a-z]", "i", "\u212a") to false,
      Triple("\u00e5", "i", "\u212b") to false,
      Triple("[^a-z]", "i", "K") to false,
      Triple("section", "i", "SECTION") to true,
      Triple("section", "i", "sect\u0131on") to false,
    )
    for ((c, expected) in cases) {
      val (source, flags, input) = c
      assertEquals(expected, JsRegExp(source, flags).test(input), "/$source/$flags on ${jsonQuote(input)}")
    }
    // Matches report the original text.
    assertEquals("É", JsRegExp("(é)", "i").exec("xÉ")?.get(1))
  }

  @Test
  fun numberToStringFastPathMatchesSearch() {
    // Random bit patterns cover every exponent, subnormals included; the integers cover 2^53 and up.
    val random = java.util.Random(53)
    repeat(100_000) {
      val x = java.lang.Double.longBitsToDouble(random.nextLong())
      assertEquals(jsStrFallback(x), jsStr(x), "bits ${java.lang.Double.doubleToRawLongBits(x)}")
      val y = (random.nextLong() shr random.nextInt(40)).toDouble()
      assertEquals(jsStrFallback(y), jsStr(y), "$y")
    }
  }
}
