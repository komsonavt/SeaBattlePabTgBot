package com.company.seabattle.copy

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** User-facing copy. An external COPY_FILE overrides the bundled defaults. */
object Copy {
    private val values: Map<String, String> by lazy {
        val external = System.getenv("COPY_FILE")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val stream = if (external != null && Files.isRegularFile(external)) Files.newInputStream(external)
        else requireNotNull(Copy::class.java.getResourceAsStream("/copy.xml")) { "copy.xml not found" }
        stream.use { input ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input).documentElement
            buildMap {
                val nodes = root.childNodes
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    if (node is Element) put(node.tagName, node.textContent.trim())
                }
            }
        }
    }
    fun text(key: String, vararg args: Pair<String, Any?>): String {
        var result = values[key] ?: error("Copy key is missing: $key")
        args.forEach { (name, value) -> result = result.replace("{$name}", value?.toString().orEmpty()) }
        return result
    }
}
