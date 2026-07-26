package com.codeagent.plugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeAgentStatusStateTest {
    @Test
    fun `reports initializing until the first health result arrives`() {
        assertEquals(
            CodeAgentStatusState.INITIALIZING,
            CodeAgentStatusState.resolve(
                initializing = false,
                backendOnline = null,
                generating = false,
                lastRequestSuggested = null,
                completionsEnabled = true,
            ),
        )
    }

    @Test
    fun `backend failure outranks completion activity`() {
        assertEquals(
            CodeAgentStatusState.BACKEND_UNAVAILABLE,
            CodeAgentStatusState.resolve(
                initializing = false,
                backendOnline = false,
                generating = true,
                lastRequestSuggested = false,
                completionsEnabled = true,
            ),
        )
    }

    @Test
    fun `generating outranks no-completions and disabled`() {
        assertEquals(
            CodeAgentStatusState.GENERATING_COMPLETION,
            CodeAgentStatusState.resolve(
                initializing = false,
                backendOnline = true,
                generating = true,
                lastRequestSuggested = false,
                completionsEnabled = false,
            ),
        )
    }

    @Test
    fun `reports no completions only while completions are enabled`() {
        assertEquals(
            CodeAgentStatusState.NO_COMPLETIONS,
            CodeAgentStatusState.resolve(
                initializing = false,
                backendOnline = true,
                generating = false,
                lastRequestSuggested = false,
                completionsEnabled = true,
            ),
        )
        assertEquals(
            CodeAgentStatusState.COMPLETIONS_DISABLED,
            CodeAgentStatusState.resolve(
                initializing = false,
                backendOnline = true,
                generating = false,
                lastRequestSuggested = false,
                completionsEnabled = false,
            ),
        )
    }

    @Test
    fun `reports ready when healthy and idle`() {
        assertEquals(
            CodeAgentStatusState.READY,
            CodeAgentStatusState.resolve(
                initializing = false,
                backendOnline = true,
                generating = false,
                lastRequestSuggested = true,
                completionsEnabled = true,
            ),
        )
    }
}
