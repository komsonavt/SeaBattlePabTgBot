package com.company.seabattle.copy

import java.nio.file.Files
import java.nio.file.Path

/** User-facing copy loaded from the editor-friendly Markdown file. */
object Copy {
    private val external = System.getenv("COPY_FILE")?.takeIf { it.isNotBlank() }?.let(Path::of)
    @Volatile private var loadedAt = Long.MIN_VALUE
    @Volatile private var values: Map<String, String> = emptyMap()

    fun text(key: String, vararg args: Pair<String, Any?>): String {
        val current = loadIfChanged()
        var result = current[key] ?: error("Copy key is missing: $key")
        args.forEach { (name, value) -> result = result.replace("{$name}", value?.toString().orEmpty()) }
        return result
    }

    private fun loadIfChanged(): Map<String, String> {
        val file = external?.takeIf(Files::isRegularFile)
        val stamp = file?.let { Files.getLastModifiedTime(it).toMillis() } ?: -1L
        if (stamp == loadedAt && values.isNotEmpty()) return values
        synchronized(this) {
            if (stamp == loadedAt && values.isNotEmpty()) return values
            val markdown = if (file != null) Files.readString(file)
            else requireNotNull(Copy::class.java.getResourceAsStream("/copy.md")) { "copy.md not found" }
                .bufferedReader().use { it.readText() }
            values = parse(markdown)
            loadedAt = stamp
            return values
        }
    }

    private fun parse(markdown: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var key: String? = null
        val body = StringBuilder()
        fun save() {
            val currentKey = key ?: return
            check(result.put(currentKey, body.toString().trim()) == null) { "Duplicate copy key: $currentKey" }
        }
        markdown.lineSequence().forEach { line ->
            val match = Regex("^## ([a-z][a-z0-9_]*)\\s*$").matchEntire(line)
            if (match != null) {
                save()
                key = match.groupValues[1]
                body.clear()
            } else if (line.startsWith("# ")) {
                // Editor-only headings (for example "Новые записи") never belong to a copy value.
                save()
                key = null
                body.clear()
            } else if (key != null) {
                body.appendLine(line)
            }
        }
        save()
        check(result.isNotEmpty()) { "No copy entries found in Markdown" }
        return result
    }
}
