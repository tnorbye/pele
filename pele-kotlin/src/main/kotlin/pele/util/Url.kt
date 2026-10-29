// Ported from Pele src/util/url.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.util

import pele.LinkInfo
import pele.RenderOptions
import pele.js.JsRegExp
import pele.js.fromCharCode
import pele.js.jsDecodeURIComponent
import pele.js.jsMatchGlobal
import pele.js.jsNumber
import pele.js.jsReplace
import pele.js.jsTrim
import pele.js.jsTrimStart
import pele.js.toInt32
import pele.js.whatwgHref
import pele.svg.RE_INVALID_XML
import pele.svg.esc
import pele.text.decodeEntities

private const val BLANK = "about:blank"
private val RE_INVALID_PROTOCOL = JsRegExp("""^([^\w]*)(javascript|data|vbscript)""", "im")
private val RE_HTML_ENTITIES = JsRegExp("""&#(\w+)(^\w|;)?""", "g")
private val RE_HTML_CTRL_ENTITY = JsRegExp("""&(newline|tab);""", "gi")
private val RE_CTRL = JsRegExp("[\\u0000-\\u001F\\u007F-\\u009F -‍﻿\\uD800-\\uDFFF\\uFFFE\\uFFFF]", "gimu")
private val RE_SCHEME = JsRegExp("""^.+(:|&colon;)""", "gim")
private val RE_WS_ESCAPE = JsRegExp("""(\\|%5[cC])((%(6[eE]|72|74))|[nrt])""", "g")

private fun decode(uri: String): String =
  try {
    jsDecodeURIComponent(uri)
  } catch (_: Exception) {
    uri
  }

// String.fromCharCode(dec), where dec is the matched text.
private fun charFromCode(dec: String): String {
  val n = jsNumber(dec)
  return fromCharCode(toInt32(n) and 0xFFFF)
}

// Removes the control characters, entity codes and escapes that could hide a scheme, and with
// `percent`, percent escapes too. Undefined for a URL wrapped too many times to be a real one.
private fun unwrap(url: String, percent: Boolean): String? {
  var out = if (percent) decode(url.jsTrim()) else url.jsTrim()
  var rounds = 0
  do {
    if (++rounds > 32) return null
    out = out
      .jsReplace(RE_CTRL, "")
      .jsReplace(RE_HTML_ENTITIES) { m -> charFromCode(m[1]!!) }
      .jsReplace(RE_HTML_CTRL_ENTITY, "")
      .jsReplace(RE_CTRL, "")
      .jsReplace(RE_WS_ESCAPE, "")
      .jsTrim()
    if (percent) out = decode(out)
    val more = out.jsMatchGlobal(RE_CTRL) ?: out.jsMatchGlobal(RE_HTML_ENTITIES) ?: out.jsMatchGlobal(RE_HTML_CTRL_ENTITY)
      ?: out.jsMatchGlobal(RE_WS_ESCAPE)
  } while (more != null && more.isNotEmpty())
  return out
}

private val RE_BACKSLASH = JsRegExp("""\\""", "g")

// Refuses what @braintree/sanitize-url refuses, the check Mermaid applies to click targets.
// Unlike it, keeps percent escapes, so `file=A%26B` still names the note `A&B`.
fun sanitizeUrl(url: String): String {
  if (url.isEmpty()) return BLANK
  val decoded = unwrap(url, true)
  val kept = unwrap(url, false)
  if (decoded.isNullOrEmpty() || kept.isNullOrEmpty()) return BLANK
  if (decoded[0] == '.' || decoded[0] == '/') return kept

  val scheme = decoded.jsTrimStart().jsMatchGlobal(RE_SCHEME) ?: return kept

  val protocol = scheme[0].lowercase().jsTrim()
  if (RE_INVALID_PROTOCOL.test(protocol)) return BLANK

  val normalized = kept.jsTrimStart().jsReplace(RE_BACKSLASH, "/")
  if (protocol == "mailto:" || protocol.contains("://")) return normalized

  if (protocol == "http:" || protocol == "https:") {
    // PORT: WHATWG URL parsing and serialization; the hostname is already lower case.
    return whatwgHref(normalized) ?: BLANK
  }
  return normalized
}

// A URL as written in a diagram: entity codes are read first, so they cannot hide a scheme.
fun safeUrl(url: String): String = sanitizeUrl(decodeEntities(url))

private val LINK_SCHEMES = listOf("http", "https", "mailto", "tel")
private val IMAGE_SCHEMES = listOf("http", "https")
private val RE_SCHEME_NAME = JsRegExp("""^([a-z][a-z0-9+.-]*):""", "i")
private val RE_NETWORK_PATH = JsRegExp("""^[\\/]{2}""")

// Stricter than Mermaid, which lets any scheme but three through: a diagram in a shared note
// could otherwise open a local file, a network share, or another application.
private fun allowed(url: String, schemes: List<String>): Boolean {
  if (RE_NETWORK_PATH.test(url)) return false
  val scheme = RE_SCHEME_NAME.exec(url)
  return scheme == null || scheme[1]!!.lowercase() in schemes
}

// The decoded URL is checked too, for an app that decodes the href before opening it.
private fun allow(url: String, schemes: List<String>): String? {
  val decoded = unwrap(url, true)
  return if (decoded != null && allowed(url, schemes) && allowed(decoded, schemes)) url else null
}

// Both take a URL that sanitizeUrl has passed, and keep it only when it is relative or its
// scheme is one the host allows. One that is refused is left out of the drawing: written as
// `about:blank`, a link would still take the page it is in somewhere when clicked.
fun linkUrl(url: String, options: RenderOptions): String? =
  if (options.links == false) null else allow(url, options.linkSchemes ?: LINK_SCHEMES)

fun imageUrl(url: String, options: RenderOptions): String? =
  if (options.images == false) null else allow(url, options.imageSchemes ?: IMAGE_SCHEMES)

private val RE_DROPPED = JsRegExp(RE_INVALID_XML.source, "gu")
private val RE_TAB_BREAK = JsRegExp("""[\t\n\r]""", "g")
private val RE_LEADING_CTRL = JsRegExp("""^[\x00-\x20]+""")

// The href keeps the original name, so sanitizing alone is not enough.
fun internalLink(name: String, options: RenderOptions): Boolean {
  val url = sanitizeUrl(name)
  if (url == BLANK || linkUrl(url, options) == null) return false
  // Match character removal by esc() and the browser.
  val seen = name.jsReplace(RE_DROPPED, "").jsReplace(RE_TAB_BREAK, "").jsReplace(RE_LEADING_CTRL, "")
  return linkUrl(seen, options) != null
}

fun linked(body: String, url: String, id: String, links: MutableList<LinkInfo>, options: RenderOptions, attrs: String = ""): String {
  val href = linkUrl(url, options) ?: return body
  links.add(LinkInfo(id, href, false))
  return "<a href=\"${esc(href)}\"$attrs${relAttr(options)}>$body</a>"
}

fun relAttr(options: RenderOptions): String {
  val rel = options.linkRel ?: "noopener"
  return if (rel.isNotEmpty()) " rel=\"${esc(rel)}\"" else ""
}
