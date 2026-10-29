// Ported from Pele src/diagrams/radar/model.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.radar

import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsReplace
import pele.svg.Accessible

class RadarAxis(
  val name: String,
  val label: String,
)

class RadarCurve(
  val name: String,
  val label: String,
  val entries: List<Double>,
)

class RadarOptions(
  var showLegend: Boolean,
  var ticks: Double,
  var max: Double?,
  var min: Double,
  // 'circle' | 'polygon'
  var graticule: String,
)

class RadarModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  val axes: List<RadarAxis>,
  val curves: List<RadarCurve>,
  val options: RadarOptions,
) : Accessible

private const val MAX_TICKS = 32.0

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

private fun invalid(message: String): PeleError =
  PeleError(message, PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "radar"))

fun buildRadar(ast: RadarAst, title: String?): RadarModel {
  val axes = ast.axes.map { axis -> RadarAxis(axis.name, axis.label ?: axis.name) }

  val curves =
    ast.curves.map { curve ->
      val entries: List<Double>
      if (curve.entries[0].axis == null) {
        entries = curve.entries.map { entry -> entry.value }
      } else {
        if (axes.isEmpty())
          throw invalid("Axes must be populated before curves for reference entries")
        val byAxis = HashMap<String, Double>()
        for (entry in curve.entries) {
          if (entry.axis != null && !byAxis.containsKey(entry.axis))
            byAxis[entry.axis] = entry.value
        }
        entries = axes.map { axis ->
          byAxis[axis.name] ?: throw invalid("Missing entry for axis " + axis.label)
        }
      }
      RadarCurve(curve.name, curve.label ?: curve.name, entries)
    }

  val options =
    RadarOptions(showLegend = true, ticks = 5.0, max = null, min = 0.0, graticule = "circle")
  for (option in ast.options) {
    if (option.name == "showLegend") {
      options.showLegend = option.value as Boolean
    } else if (option.name == "graticule") {
      options.graticule = option.value as String
    } else {
      val value = option.value as Double
      when (option.name) {
        "ticks" -> options.ticks = value
        "max" -> options.max = value
        "min" -> options.min = value
      }
    }
  }
  if (options.ticks > MAX_TICKS) options.ticks = MAX_TICKS

  return RadarModel(
    type = "radar",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle =
      if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr =
      if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    axes = axes,
    curves = curves,
    options = options,
  )
}
