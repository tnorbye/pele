// Ported from Pele src/diagrams/xychart/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.xychart

import pele.js.JsRegExp
import pele.js.jsReplace
import pele.js.jsStr
import pele.js.jsTrim
import pele.svg.Accessible

// `type` is 'text' or 'markdown'.
class XyText(val text: String, val type: String)

class DataPoint(val value: Double, val label: String)

sealed class XAxis {
  abstract var title: String
}

class BandAxis(override var title: String, val categories: List<String>) : XAxis()

class LinearAxis(override var title: String, val min: Double, val max: Double) : XAxis()

// Each value with its place on the x axis: a category, or a number written as text.
// A category with no value has `null`.
typealias PlotData = List<Pair<String, Double?>>

class Plot(
  // 'line' | 'bar'
  val type: String,
  val title: String,
  val data: PlotData,
  var pointLabels: List<String>? = null,
)

interface XyChartModel : Accessible {
  val type: String
  val title: String?
  override val accTitle: String?
  override val accDescr: String?

  // 'vertical' | 'horizontal'
  val orientation: String?
  val xAxis: XAxis
  val yAxis: LinearAxis

  // False while the y range comes from the data and not from a `y-axis` statement.
  val hasSetYAxis: Boolean
  val plots: List<Plot>
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

class XyChartDb : XyChartModel {
  override val type = "xychart"
  override var title: String? = null
  override var accTitle: String? = null
  override var accDescr: String? = null
  override var orientation: String? = null
  override var xAxis: XAxis = BandAxis("", ArrayList())
  override var yAxis: LinearAxis = LinearAxis("", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
  var hasSetXAxis = false
  override var hasSetYAxis = false
  override val plots: MutableList<Plot> = ArrayList()

  @JvmName("setOrientationValue")
  fun setOrientation(orientation: String) {
    this.orientation = if (orientation == "horizontal") "horizontal" else "vertical"
  }

  fun setXAxisTitle(title: XyText) {
    this.xAxis.title = title.text.jsTrim()
  }

  fun setXAxisRangeData(min: Double, max: Double) {
    this.xAxis = LinearAxis(this.xAxis.title, min, max)
    this.hasSetXAxis = true
  }

  fun setXAxisBand(categories: List<XyText>) {
    this.xAxis = BandAxis(this.xAxis.title, categories.map { c -> c.text.jsTrim() })
    this.hasSetXAxis = true
  }

  fun setYAxisTitle(title: XyText) {
    this.yAxis.title = title.text.jsTrim()
  }

  fun setYAxisRangeData(min: Double, max: Double) {
    this.yAxis = LinearAxis(this.yAxis.title, min, max)
    this.hasSetYAxis = true
  }

  private fun plotData(valuesIn: List<Double>): PlotData {
    var values = valuesIn
    if (values.isEmpty()) return emptyList()
    // The first series fixes the x range when no x-axis statement did.
    if (!this.hasSetXAxis) this.setXAxisRangeData(1.0, values.size.toDouble())
    val x = this.xAxis
    if (x is BandAxis && values.size > x.categories.size) values = values.subList(0, x.categories.size)
    if (!this.hasSetYAxis) {
      var min = this.yAxis.min
      var max = this.yAxis.max
      for (value in values) {
        if (value < min) min = value
        if (value > max) max = value
      }
      this.yAxis = LinearAxis(this.yAxis.title, min, max)
    }
    if (x is BandAxis) return x.categories.mapIndexed { i, category -> Pair(category, values.getOrNull(i)) }
    x as LinearAxis
    if (values.size == 1) return listOf(Pair(jsStr(x.min), values[0]))
    val step = (x.max - x.min) / (values.size - 1)
    return values.mapIndexed { i, value -> Pair(jsStr(x.min + i * step), value) }
  }

  private fun addPlot(type: String, title: XyText, data: List<DataPoint>) {
    val plot = Plot(type, title.text.jsTrim(), this.plotData(data.map { d -> d.value }))
    // Mermaid accepts labels on the points of a bar series and ignores them.
    if (type == "line" && data.any { d -> d.label.jsTrim() != "" }) plot.pointLabels = data.map { d -> d.label.jsTrim() }
    this.plots.add(plot)
  }

  fun setLineData(title: XyText, data: List<DataPoint>) {
    this.addPlot("line", title, data)
  }

  fun setBarData(title: XyText, data: List<DataPoint>) {
    this.addPlot("bar", title, data)
  }

  fun setDiagramTitle(text: String) {
    this.title = text
  }

  @JvmName("setAccTitleText")
  fun setAccTitle(text: String) {
    this.accTitle = text.jsReplace(RE_LEADING, "")
  }

  @JvmName("setAccDescriptionText")
  fun setAccDescription(text: String) {
    this.accDescr = text.jsReplace(RE_INDENT, "\n")
  }
}
