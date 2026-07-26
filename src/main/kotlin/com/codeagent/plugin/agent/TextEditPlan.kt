package com.codeagent.plugin.agent

/**
 * One requested edit: a text replacement, or a line insertion when [oldText] is
 * absent. [oldTextStartLine] disambiguates repeated text by anchoring the match
 * to a 1-based line; [insertLine] is the 1-based line after which text is
 * inserted (0 inserts at the top of the file).
 */
data class TextEdit(
    val oldText: String?,
    val newText: String,
    val oldTextStartLine: Int? = null,
    val insertLine: Int? = null,
    val replaceAll: Boolean = false,
)

/** A resolved edit expressed as an absolute character range in the source. */
data class ResolvedEdit(val start: Int, val end: Int, val replacement: String)

/**
 * Resolves and applies batched edits against one file's content, mirroring the
 * original plugin's multi-edit str-replace contract: every edit is located
 * independently against the original text, overlaps are rejected, and the
 * edits are applied from the end so earlier offsets stay valid.
 */
object TextEditPlan {
    fun apply(path: String, content: String, edits: List<TextEdit>): String {
        require(edits.isNotEmpty()) { "At least one edit is required" }
        val resolved = edits.flatMap { resolve(path, content, it) }.sortedBy { it.start }
        for (index in 1 until resolved.size) {
            val previous = resolved[index - 1]
            val current = resolved[index]
            require(current.start >= previous.end) {
                "Edits overlap in $path near offset ${current.start}; split them into separate calls"
            }
        }
        val builder = StringBuilder(content)
        for (edit in resolved.sortedByDescending { it.start }) {
            builder.replace(edit.start, edit.end, edit.replacement)
        }
        return builder.toString()
    }

    private fun resolve(path: String, content: String, edit: TextEdit): List<ResolvedEdit> {
        val oldText = edit.oldText
        if (oldText.isNullOrEmpty()) {
            val line = requireNotNull(edit.insertLine) {
                "Each edit needs either old_text to replace or insert_line to insert at"
            }
            val offset = insertionOffset(path, content, line)
            val prefixNewline = if (offset > 0 && content.getOrNull(offset - 1) != '\n') "\n" else ""
            val suffixNewline = if (edit.newText.endsWith("\n")) "" else "\n"
            return listOf(ResolvedEdit(offset, offset, prefixNewline + edit.newText + suffixNewline))
        }

        if (edit.replaceAll) {
            val matches = allOccurrences(content, oldText)
            require(matches.isNotEmpty()) { "old_text was not found in $path" }
            return matches.map { ResolvedEdit(it, it + oldText.length, edit.newText) }
        }

        val anchor = edit.oldTextStartLine
        if (anchor != null) {
            val lineOffset = lineStartOffset(path, content, anchor)
            val index = content.indexOf(oldText, startIndex = lineOffset)
            require(index >= 0) { "old_text was not found in $path at or after line $anchor" }
            require(content.substring(lineOffset, index).none { it == '\n' }) {
                "old_text does not start on line $anchor in $path"
            }
            return listOf(ResolvedEdit(index, index + oldText.length, edit.newText))
        }

        val matches = allOccurrences(content, oldText)
        require(matches.isNotEmpty()) { "old_text was not found in $path" }
        require(matches.size == 1) {
            "old_text occurs ${matches.size} times in $path; set replace_all=true, " +
                "add old_text_start_line, or provide a more specific match"
        }
        return listOf(ResolvedEdit(matches.first(), matches.first() + oldText.length, edit.newText))
    }

    private fun allOccurrences(content: String, needle: String): List<Int> {
        val result = mutableListOf<Int>()
        var index = content.indexOf(needle)
        while (index >= 0) {
            result += index
            index = content.indexOf(needle, index + needle.length)
        }
        return result
    }

    private fun lineStartOffset(path: String, content: String, line: Int): Int {
        require(line >= 1) { "Line numbers are 1-based" }
        var offset = 0
        repeat(line - 1) {
            val next = content.indexOf('\n', offset)
            require(next >= 0) { "$path has fewer than $line lines" }
            offset = next + 1
        }
        return offset
    }

    private fun insertionOffset(path: String, content: String, afterLine: Int): Int {
        if (afterLine <= 0) return 0
        var offset = 0
        repeat(afterLine) {
            val next = content.indexOf('\n', offset)
            if (next < 0) {
                // Inserting after the final line appends at the end of the file.
                offset = content.length
                return@repeat
            }
            offset = next + 1
        }
        return offset.coerceAtMost(content.length)
    }
}
