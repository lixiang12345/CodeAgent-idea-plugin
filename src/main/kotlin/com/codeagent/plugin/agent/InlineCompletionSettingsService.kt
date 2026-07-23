package com.codeagent.plugin.agent

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "CodeAgentInlineCompletionSettings", storages = [Storage("CodeAgentInlineCompletion.xml")])
class InlineCompletionSettingsService : PersistentStateComponent<InlineCompletionSettingsState> {
    private var state = InlineCompletionSettingsState()

    override fun getState(): InlineCompletionSettingsState = state

    override fun loadState(state: InlineCompletionSettingsState) {
        this.state = state
    }

    fun isEnabled(): Boolean = state.enabled

    fun setEnabled(enabled: Boolean) {
        state.enabled = enabled
    }

    fun disabledLanguages(): String = state.disabledLanguages

    fun setDisabledLanguages(value: String) {
        state.disabledLanguages = normalizeDisabledLanguages(value)
    }

    /**
     * Mirrors the original plugin's "Disable Completion By Language" setting: a
     * comma-separated list of file extensions (for example `*.js, *.ts`) where
     * inline completion must not run. Matching is case-insensitive and tolerates
     * entries with or without a leading `*`/`.`.
     */
    fun isCompletionDisabledForFile(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        val patterns = parseDisabledExtensions(state.disabledLanguages)
        if (patterns.isEmpty()) return false
        val lowerName = fileName.lowercase()
        return patterns.any { lowerName.endsWith(it) }
    }

    companion object {
        fun normalizeDisabledLanguages(value: String): String =
            value.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

        /** Returns lowercase `.ext` suffixes to test against a file name. */
        private fun parseDisabledExtensions(value: String): List<String> =
            value.split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .map { entry ->
                    val bare = entry.removePrefix("*").let { if (it.startsWith(".")) it else ".$it" }
                    bare
                }
                .filter { it.length > 1 }
    }
}

class InlineCompletionSettingsState {
    var enabled: Boolean = true
    var disabledLanguages: String = ""
}
