package com.codeagent.plugin.ui

/**
 * Prioritized status-bar states, mirroring the original plugin's state machine:
 * the highest-priority active state owns the widget presentation instead of a
 * single concatenated status string.
 */
enum class CodeAgentStatusState(val priority: Int, val tooltip: String) {
    INITIALIZING(60, "CodeAgent is starting up"),
    BACKEND_UNAVAILABLE(50, "CodeAgent backend is unavailable"),
    GENERATING_COMPLETION(40, "CodeAgent is generating a completion"),
    NO_COMPLETIONS(30, "CodeAgent returned no completion for the last request"),
    COMPLETIONS_DISABLED(20, "CodeAgent automatic completions are disabled"),
    READY(10, "CodeAgent is ready"),
    ;

    companion object {
        /**
         * Resolves the visible state from independent signals. `backendOnline`
         * is null while the first health check is still outstanding.
         */
        fun resolve(
            initializing: Boolean,
            backendOnline: Boolean?,
            generating: Boolean,
            lastRequestSuggested: Boolean?,
            completionsEnabled: Boolean,
        ): CodeAgentStatusState {
            val candidates = buildList {
                if (initializing || backendOnline == null) add(INITIALIZING)
                if (backendOnline == false) add(BACKEND_UNAVAILABLE)
                if (generating) add(GENERATING_COMPLETION)
                if (!completionsEnabled) add(COMPLETIONS_DISABLED)
                if (completionsEnabled && lastRequestSuggested == false) add(NO_COMPLETIONS)
                add(READY)
            }
            return candidates.maxBy { it.priority }
        }
    }
}
