package com.codeagent.plugin.agent

internal sealed interface PatchEnvelopeSection {
    val path: String

    data class AddFile(override val path: String, val content: String) : PatchEnvelopeSection
    data class DeleteFile(override val path: String) : PatchEnvelopeSection
    data class UpdateFile(
        override val path: String,
        val movePath: String?,
        val chunks: List<List<String>>,
    ) : PatchEnvelopeSection
}

/**
 * Parser and applier for the "*** Begin Patch" envelope format used by the
 * original plugin's apply_patch tool: Add/Update/Delete File sections whose
 * update chunks are located by context match instead of line numbers.
 */
internal object PatchEnvelope {
    fun isEnvelope(patch: String): Boolean = patch.lineSequence().any { it.trim() == "*** Begin Patch" }

    fun parse(patch: String): List<PatchEnvelopeSection> {
        val lines = patch.replace("\r\n", "\n").split('\n')
        val begin = lines.indexOfFirst { it.trim() == "*** Begin Patch" }
        val end = lines.indexOfLast { it.trim() == "*** End Patch" }
        require(begin >= 0 && end > begin) { "patch envelope must contain *** Begin Patch and *** End Patch" }
        val sections = mutableListOf<PatchEnvelopeSection>()
        var index = begin + 1
        while (index < end) {
            val line = lines[index]
            val addPath = line.afterMarker("*** Add File:")
            val updatePath = line.afterMarker("*** Update File:")
            val deletePath = line.afterMarker("*** Delete File:")
            when {
                addPath != null -> {
                    val content = mutableListOf<String>()
                    index += 1
                    while (index < end && !lines[index].startsWith("*** ")) {
                        val added = lines[index]
                        require(added.startsWith("+")) { "Add File sections only accept + lines: $added" }
                        content += added.drop(1)
                        index += 1
                    }
                    sections += PatchEnvelopeSection.AddFile(addPath, content.joinToString("\n"))
                }
                deletePath != null -> {
                    sections += PatchEnvelopeSection.DeleteFile(deletePath)
                    index += 1
                }
                updatePath != null -> {
                    index += 1
                    val movePath = lines.getOrNull(index)?.afterMarker("*** Move to:")?.also { index += 1 }
                    val chunks = mutableListOf<List<String>>()
                    var current = mutableListOf<String>()
                    fun flush() {
                        if (current.isNotEmpty()) {
                            chunks += current.toList()
                            current = mutableListOf()
                        }
                    }
                    while (index < end && !lines[index].startsWith("*** ")) {
                        val body = lines[index]
                        when {
                            body.startsWith("@@") -> flush()
                            body.startsWith(" ") || body.startsWith("+") || body.startsWith("-") -> current += body
                            body.isEmpty() -> current += " "
                            else -> error("Unsupported patch line: $body")
                        }
                        index += 1
                    }
                    flush()
                    require(chunks.isNotEmpty()) { "Update File section for $updatePath has no chunks" }
                    sections += PatchEnvelopeSection.UpdateFile(updatePath, movePath, chunks)
                }
                line.isBlank() -> index += 1
                else -> error("Unsupported patch envelope line: $line")
            }
        }
        return sections
    }

    fun applyChunks(path: String, original: String, chunks: List<List<String>>): String {
        val source = original.split('\n').toMutableList()
        var searchFrom = 0
        for (chunk in chunks) {
            val oldLines = chunk.filter { it.startsWith(" ") || it.startsWith("-") }.map { it.drop(1) }
            val newLines = chunk.filter { it.startsWith(" ") || it.startsWith("+") }.map { it.drop(1) }
            if (oldLines.isEmpty()) {
                // Pure insertion with no anchor: append at the end of the file.
                source.addAll(newLines)
                continue
            }
            val start = findLines(source, oldLines, searchFrom)
            require(start >= 0) { "Patch context not found in $path near: ${oldLines.first().take(120)}" }
            repeat(oldLines.size) { source.removeAt(start) }
            source.addAll(start, newLines)
            searchFrom = start + newLines.size
        }
        return source.joinToString("\n")
    }

    private fun findLines(source: List<String>, target: List<String>, from: Int): Int {
        outer@ for (start in from..source.size - target.size) {
            for (offset in target.indices) {
                if (source[start + offset].trimEnd() != target[offset].trimEnd()) continue@outer
            }
            return start
        }
        return -1
    }

    private fun String.afterMarker(marker: String): String? =
        if (startsWith(marker)) substringAfter(marker).trim().takeIf(String::isNotEmpty) else null
}
