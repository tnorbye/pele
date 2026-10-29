// Ported from Pele src/diagrams/treeview/icons.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.treeview

import pele.Config
import pele.js.isTruthy
import pele.js.jsSubstring

// Mermaid's name for its built-in pack. Only `file` and `folder` are built in; every other icon
// name is offered to the host's icon resolver.
const val BUILTIN_PACK = "mermaid-treeview"

// Outlines on a 24 by 24 grid.
val GLYPHS: Map<String, String> =
  mapOf(
    "folder" to
      "M3 6.5A1.5 1.5 0 0 1 4.5 5h4.6l2.4 2.5h8A1.5 1.5 0 0 1 21 9v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18z",
    "file" to
      "M6.5 3h7L19 8.5v11a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-15A1.5 1.5 0 0 1 6.5 3zM13.5 3v5.5H19",
  )

typealias IconMap = Map<*, *>

class IconConfig(
  val showIcons: Boolean,
  val defaultIconPack: String,
  val filenameIcons: IconMap? = null,
  val extensionIcons: IconMap? = null,
)

private fun own(map: IconMap?, key: String): String? {
  if (map == null || !map.containsKey(key)) return null
  val value = map[key]
  return if (value is String) value else null
}

// A file's icon from the configured maps. A file name wins over an extension, and extensions
// match in lower case with or without the leading dot.
fun detectIcon(name: String, config: IconConfig? = null): String? {
  val byName = own(config?.filenameIcons, name)
  if (!byName.isNullOrEmpty()) return byName
  val dot = name.lastIndexOf('.')
  if (dot > 0) {
    val ext = name.jsSubstring(dot).lowercase()
    return own(config?.extensionIcons, ext) ?: own(config?.extensionIcons, ext.substring(1))
  }
  return null
}

private fun qualifyIcon(icon: String, defaultIconPack: String): String {
  if (icon.contains(':')) return icon
  if (GLYPHS.containsKey(icon) || defaultIconPack.isEmpty()) return "$BUILTIN_PACK:$icon"
  return "$defaultIconPack:$icon"
}

// The qualified icon name for a node, or undefined when it shows none. An `icon()` annotation
// always wins; otherwise icons appear only with `showIcons`.
fun getNodeIcon(node: TreeNode, config: IconConfig): String? {
  if (node.icon == "none") return null
  if (!node.icon.isNullOrEmpty()) return qualifyIcon(node.icon!!, config.defaultIconPack)
  if (!config.showIcons) return null
  if (node.nodeType == "file") {
    val detected = detectIcon(node.name, config)
    if (detected == "none") return null
    if (!detected.isNullOrEmpty()) return qualifyIcon(detected, config.defaultIconPack)
  }
  return "$BUILTIN_PACK:${if (node.nodeType == "directory") "folder" else "file"}"
}

fun iconConfig(config: Config): IconConfig {
  val map = { value: Any? -> if (value is Map<*, *>) value else null }
  val c: Map<*, *> = map(config["treeView"]) ?: emptyMap<String, Any?>()
  val pack = c["defaultIconPack"]
  return IconConfig(
    showIcons = isTruthy(c["showIcons"]),
    defaultIconPack = if (pack is String) pack else "",
    filenameIcons = map(c["filenameIcons"]),
    extensionIcons = map(c["extensionIcons"]),
  )
}
