// Ported from Pele src/util/chars.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.util

// Tests on UTF-16 code units, as the lexers read them with charCodeAt. Past the end of a string
// the code is NaN, which is none of these.
// PORT: charCodeAt() returns -1 past the end, which is none of these either.

// Whitespace as `\s` sees it.
fun isSpace(c: Int): Boolean {
  if (c < 128) return c == 32 || (c >= 9 && c <= 13)
  return c == 0xa0 ||
    c == 0x1680 ||
    (c >= 0x2000 && c <= 0x200a) ||
    c == 0x2028 ||
    c == 0x2029 ||
    c == 0x202f ||
    c == 0x205f ||
    c == 0x3000 ||
    c == 0xfeff
}

fun isBlank(c: Int): Boolean = c == 32 || c == 9

// A line terminator: what `.` does not match.
fun isLineEnd(c: Int): Boolean = c == 10 || c == 13 || c == 0x2028 || c == 0x2029

fun isDigit(c: Int): Boolean = c >= 48 && c <= 57

fun isLetter(c: Int): Boolean = (c >= 65 && c <= 90) || (c >= 97 && c <= 122)

fun isHex(c: Int): Boolean = (c >= 48 && c <= 57) || (c >= 65 && c <= 70) || (c >= 97 && c <= 102)

// A character of `\w`.
fun isWord(c: Int): Boolean =
  (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c == 95
