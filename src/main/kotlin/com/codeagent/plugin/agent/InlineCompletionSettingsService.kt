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
}

class InlineCompletionSettingsState {
    var enabled: Boolean = true
}
