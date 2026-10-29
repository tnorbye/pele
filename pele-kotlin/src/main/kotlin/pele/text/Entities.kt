// Ported from Pele src/text/entities.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.text

import pele.js.JsRegExp
import pele.js.fromCodePoint
import pele.js.jsNumber
import pele.js.jsParseInt
import pele.js.jsReplace
import pele.js.jsSplit

private const val NAMED =
  "amp:38,lt:60,gt:62,quot:34,apos:39,nbsp:160,shy:173,iexcl:161,cent:162,pound:163,curren:164,yen:165,sect:167," +
    "copy:169,laquo:171,not:172,reg:174,deg:176,plusmn:177,sup2:178,sup3:179,micro:181,para:182,middot:183," +
    "sup1:185,raquo:187,frac14:188,frac12:189,frac34:190,iquest:191,times:215,divide:247,ndash:8211,mdash:8212," +
    "lsquo:8216,rsquo:8217,sbquo:8218,ldquo:8220,rdquo:8221,bdquo:8222,dagger:8224,Dagger:8225,bull:8226," +
    "hellip:8230,permil:8240,prime:8242,Prime:8243,lsaquo:8249,rsaquo:8250,euro:8364,trade:8482,larr:8592," +
    "uarr:8593,rarr:8594,darr:8595,harr:8596,lArr:8656,uArr:8657,rArr:8658,dArr:8659,hArr:8660,forall:8704," +
    "part:8706,exist:8707,empty:8709,nabla:8711,isin:8712,notin:8713,ni:8715,prod:8719,sum:8721,minus:8722," +
    "radic:8730,infin:8734,and:8743,or:8744,cap:8745,cup:8746,int:8747,there4:8756,sim:8764,cong:8773," +
    "asymp:8776,ne:8800,equiv:8801,le:8804,ge:8805,sub:8834,sup:8835,sube:8838,supe:8839,oplus:8853,otimes:8855," +
    "perp:8869,sdot:8901,loz:9674,spades:9824,clubs:9827,hearts:9829,diams:9830,check:10003,cross:10007," +
    "AMP:38,LT:60,GT:62,QUOT:34,lsqb:91,rsqb:93,lcub:123,rcub:125,verbar:124,VerticalLine:124,midast:42," +
    "DiacriticalGrave:96,Hat:94,UnderBar:95," +
    "equals:61,colon:58,semi:59,num:35,percnt:37,lpar:40,rpar:41,lbrack:91,rbrack:93,lbrace:123,rbrace:125," +
    "vert:124,sol:47,bsol:92,quest:63,excl:33,ast:42,plus:43,comma:44,period:46,dollar:36,commat:64,grave:96," +
    "hat:94,lowbar:95,tilde:126,Alpha:913,Beta:914,Gamma:915,Delta:916,Epsilon:917,Zeta:918,Eta:919,Theta:920," +
    "Iota:921,Kappa:922,Lambda:923,Mu:924,Nu:925,Xi:926,Omicron:927,Pi:928,Rho:929,Sigma:931,Tau:932," +
    "Upsilon:933,Phi:934,Chi:935,Psi:936,Omega:937,alpha:945,beta:946,gamma:947,delta:948,epsilon:949,zeta:950," +
    "eta:951,theta:952,iota:953,kappa:954,lambda:955,mu:956,nu:957,xi:958,omicron:959,pi:960,rho:961,sigmaf:962," +
    "sigma:963,tau:964,upsilon:965,phi:966,chi:967,psi:968,omega:969"

private val named: Map<String, String> by lazy {
  val map = HashMap<String, String>()
  for (pair in NAMED.jsSplit(",")) {
    val at = pair.indexOf(':')
    map[pair.substring(0, at)] = fromCodePoint(jsNumber(pair.substring(at + 1)).toInt())
  }
  map
}

private fun lookup(name: String): String? = named[name]

private fun codePoint(n: Double, fallback: String): String =
  if (n > 0 && n <= 0x10ffff && (n < 0xd800 || n > 0xdfff)) fromCodePoint(n.toInt()) else fallback

private val RE_ENTITY = JsRegExp("""&(?:#(\d+)|#[xX]([0-9a-fA-F]+)|(\w+));""", "g")
private const val OPEN = "ﬂ°"
private const val CLOSE = "¶ß"

// Mermaid's placeholders run from `ﬂ°` to `¶ß`, with a second `°` marking a numeric code. Like Mermaid, turn them
// back into entities, so that each code is decoded once.
private fun restorePlaceholders(text: String): String {
  val out = StringBuilder()
  var last = 0
  var from = text.indexOf(OPEN)
  while (from != -1) {
    val stop = text.indexOf('¶', from + 2)
    if (stop == -1) break
    if (stop + 1 < text.length && text[stop + 1] == 'ß') {
      val numeric = from + 2 < text.length && text[from + 2] == '°'
      out.append(text, last, from).append(if (numeric) "&#" else "&")
      val start = from + (if (numeric) 3 else 2)
      if (start < stop) out.append(text, start, stop)
      out.append(';')
      last = stop + 2
    }
    from = text.indexOf(OPEN, stop + 1)
  }
  return if (last == 0) text else out.toString() + text.substring(last)
}

private val RE_PLACEHOLDER = JsRegExp("""ﬂ°°?(\w+)¶ß""", "g")

fun writtenCodes(text: String): String = if (text.contains(CLOSE)) text.jsReplace(RE_PLACEHOLDER, "#$1;") else text

// Turns Mermaid's `#35;` / `#quot;` codes (as hidden by encodeEntities) and HTML entities into characters.
fun decodeEntities(textIn: String): String {
  var text = textIn
  if (text.contains(CLOSE)) text = restorePlaceholders(text)
  if (text.contains('&')) {
    text = text.jsReplace(RE_ENTITY) { m ->
      val dec = m[1]
      val hex = m[2]
      val name = m[3]
      if (!dec.isNullOrEmpty()) {
        codePoint(jsNumber(dec), m.value)
      } else if (!hex.isNullOrEmpty()) {
        codePoint(jsParseInt(hex, 16), m.value)
      } else {
        lookup(name!!) ?: m.value
      }
    }
  }
  return text
}
