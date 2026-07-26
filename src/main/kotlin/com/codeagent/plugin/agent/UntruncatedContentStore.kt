package com.codeagent.plugin.agent

import com.intellij.openapi.components.Service
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the full text of tool outputs that were truncated before being handed
 * to the model, so the agent can re-read the remainder by reference instead of
 * losing it. Mirrors the original plugin's untruncated-content manager and its
 * `view-range-untruncated` / `search-untruncated` recovery tools.
 */
@Service(Service.Level.PROJECT)
class UntruncatedContentStore {
    private val sequence = AtomicLong()
    private val entries = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_ENTRIES
    }

    fun store(toolName: String, content: String): String {
        val id = "${toolName.replace(Regex("[^a-zA-Z0-9_-]"), "-")}-${sequence.incrementAndGet()}"
        synchronized(entries) { entries[id] = content.take(MAX_CONTENT_CHARS) }
        return id
    }

    fun get(referenceId: String): String? = synchronized(entries) { entries[referenceId] }

    fun ids(): List<String> = synchronized(entries) { entries.keys.toList() }

    fun clear() = synchronized(entries) { entries.clear() }

    /** Returns 1-based [startLine]..[endLine] of the stored content, numbered. */
    fun viewRange(referenceId: String, startLine: Int, endLine: Int): String {
        val content = requireNotNull(get(referenceId)) { "Unknown reference_id: $referenceId" }
        val lines = content.split('\n')
        require(startLine >= 1) { "start_line is 1-based" }
        require(endLine >= startLine) { "end_line must be greater than or equal to start_line" }
        val from = (startLine - 1).coerceAtMost(lines.size)
        val to = endLine.coerceAtMost(lines.size)
        if (from >= to) return "No lines in range; the stored content has ${lines.size} lines"
        return lines.subList(from, to)
            .mapIndexed { index, line -> "${from + index + 1}: $line" }
            .joinToString("\n")
    }

    /** Returns matching lines with surrounding context, numbered like [viewRange]. */
    fun search(referenceId: String, term: String, contextLines: Int): String {
        val content = requireNotNull(get(referenceId)) { "Unknown reference_id: $referenceId" }
        require(term.isNotBlank()) { "search_term must not be blank" }
        val lines = content.split('\n')
        val context = contextLines.coerceIn(0, 10)
        val rendered = mutableListOf<String>()
        var matches = 0
        lines.forEachIndexed { index, line ->
            if (matches < MAX_MATCHES && line.contains(term, ignoreCase = true)) {
                matches += 1
                for (position in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(lines.lastIndex)) {
                    val marker = if (position == index) ":" else "-"
                    rendered += "${position + 1}$marker ${lines[position]}"
                }
            }
        }
        if (matches == 0) return "No matches for \"$term\" in $referenceId"
        return rendered.joinToString("\n")
    }

    private companion object {
        const val MAX_ENTRIES = 40
        const val MAX_CONTENT_CHARS = 4_000_000
        const val MAX_MATCHES = 60
    }
}
