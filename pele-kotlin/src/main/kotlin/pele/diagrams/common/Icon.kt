// Ported from Pele src/diagrams/common/icon.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.common

import pele.IconResolver
import pele.svg.escText
import pele.svg.num

// A square for a named icon, in the form labels use. The host's resolver supplies the drawing, if any.
fun iconSvg(name: String, x: Double, y: Double, side: Double, icons: IconResolver?): String =
  "<svg class=\"pele-icon\" data-icon=\"${escText(name)}\" x=\"${num(x)}\" y=\"${num(y)}\" width=\"${num(side)}\" height=\"${num(
    side,
  )}\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">${
    icons?.invoke(name) ?: ""
  }</svg>"
