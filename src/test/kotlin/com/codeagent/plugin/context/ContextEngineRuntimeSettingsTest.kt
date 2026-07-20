package com.codeagent.plugin.context

import com.codeagent.plugin.settings.CodeAgentSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextEngineRuntimeSettingsTest {
    @Test
    fun `remote HTTP mode maps deployment endpoint and token`() {
        val runtime = contextEngineRuntimeSettings(
            settings(
                contextMode = "remote-http",
                contextEmbeddingApiKey = null,
                contextHttpApiKey = "http-secret",
            ),
            resolvedNodePath = "/opt/node",
            processEnvironment = mapOf("CONTEXTENGINE_HTTP_API_KEY" to "environment-secret"),
        )

        assertEquals("http://127.0.0.1:8790", runtime.environment["CONTEXTENGINE_HTTP_URL"])
        assertEquals("http-secret", runtime.environment["CONTEXTENGINE_HTTP_API_KEY"])
        assertFalse(runtime.environment.containsKey("CONTEXTENGINE_EMBEDDING_API_KEY"))
    }

    @Test
    fun `remote HTTP mode falls back to the process token`() {
        val runtime = contextEngineRuntimeSettings(
            settings(
                contextMode = "remote-http",
                contextEmbeddingApiKey = null,
                contextHttpApiKey = null,
            ),
            resolvedNodePath = "/opt/node",
            processEnvironment = mapOf("CONTEXTENGINE_HTTP_API_KEY" to "environment-secret"),
        )

        assertEquals("environment-secret", runtime.environment["CONTEXTENGINE_HTTP_API_KEY"])
    }

    @Test
    fun `configured remote token takes precedence over the process token`() {
        val current = settings(
            contextMode = "remote-http",
            contextEmbeddingApiKey = null,
            contextHttpApiKey = "configured-secret",
        )

        assertEquals(
            "configured-secret",
            resolvedContextHttpApiKey(
                current,
                processEnvironment = mapOf("CONTEXTENGINE_HTTP_API_KEY" to "environment-secret"),
            ),
        )
    }

    @Test
    fun `lexical mode cannot inherit semantic endpoint configuration`() {
        val runtime = contextEngineRuntimeSettings(
            settings(contextMode = "lexical", contextEmbeddingApiKey = "secret"),
            resolvedNodePath = "/opt/node",
        )

        assertEquals("/opt/node", runtime.nodePath)
        assertTrue(runtime.environment.isEmpty())
    }

    @Test
    fun `json lines remains an explicit sidecar fallback`() {
        val runtime = contextEngineRuntimeSettings(
            settings(contextMode = "lexical", contextEmbeddingApiKey = null),
            resolvedNodePath = "/opt/node",
            processEnvironment = mapOf("CODEAGENT_CONTEXT_RPC" to "jsonl"),
        )

        assertEquals("jsonl", runtime.rpcMode)
    }

    @Test
    fun `private semantic mode maps embedding endpoint settings`() {
        val runtime = contextEngineRuntimeSettings(
            settings(
                contextMode = "private-semantic",
                contextEmbeddingApiKey = "private-token",
            ),
            resolvedNodePath = "/opt/node",
        )

        assertEquals("private-token", runtime.environment["CONTEXTENGINE_EMBEDDING_API_KEY"])
        assertEquals("https://context.example/v1", runtime.environment["CONTEXTENGINE_EMBEDDING_BASE_URL"])
        assertEquals("Qwen/custom-embedding", runtime.environment["CONTEXTENGINE_EMBEDDING_MODEL"])
        assertFalse(runtime.environment.containsKey("CONTEXTENGINE_NEURAL_RERANK"))
    }

    @Test
    fun `rerank uses embedding endpoint and local placeholder token by default`() {
        val runtime = contextEngineRuntimeSettings(
            settings(
                contextMode = "private-semantic",
                contextEmbeddingApiKey = "",
                contextNeuralRerank = true,
            ),
            resolvedNodePath = "/opt/node",
        )

        assertEquals("codeagent-local-endpoint", runtime.environment["CONTEXTENGINE_EMBEDDING_API_KEY"])
        assertEquals("1", runtime.environment["CONTEXTENGINE_NEURAL_RERANK"])
        assertEquals("codeagent-local-endpoint", runtime.environment["CONTEXTENGINE_RERANK_API_KEY"])
        assertEquals("https://context.example/v1", runtime.environment["CONTEXTENGINE_RERANK_BASE_URL"])
        assertEquals("Qwen/custom-reranker", runtime.environment["CONTEXTENGINE_RERANK_MODEL"])
    }

    private fun settings(
        contextMode: String,
        contextEmbeddingApiKey: String?,
        contextHttpApiKey: String? = null,
        contextNeuralRerank: Boolean = false,
    ) = CodeAgentSettings(
        backendUrl = "https://agent.example",
        nodePath = "node",
        autoApproveReadOnly = true,
        backendToken = null,
        contextMode = contextMode,
        contextHttpBaseUrl = "http://127.0.0.1:8790",
        contextHttpApiKey = contextHttpApiKey,
        contextEmbeddingBaseUrl = "https://context.example/v1",
        contextEmbeddingModel = "Qwen/custom-embedding",
        contextEmbeddingApiKey = contextEmbeddingApiKey,
        contextNeuralRerank = contextNeuralRerank,
        contextRerankBaseUrl = "",
        contextRerankModel = "Qwen/custom-reranker",
    )
}
