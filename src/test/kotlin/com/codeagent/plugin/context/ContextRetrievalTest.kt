package com.codeagent.plugin.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextRetrievalTest {
    @Test
    fun `planner derives symbol concept and path queries without a model`() {
        val plan = ContextQueryPlanner.plan(
            request = "Trace RemoteAgentClient stream handling in src/main/kotlin/com/codeagent/plugin/agent/RemoteAgentClient.kt",
            strategy = "deep",
            focusPaths = listOf("src/main/kotlin/com/codeagent/plugin/bridge"),
        )

        assertEquals("deep", plan.strategy)
        assertTrue("RemoteAgentClient" in plan.identifiers)
        assertTrue(plan.queries.any { it.purpose == "symbols" && "RemoteAgentClient" in it.text })
        assertTrue(plan.queries.any { it.pathPrefix == "src/main/kotlin/com/codeagent/plugin/bridge" })
        assertTrue(plan.queries.any { it.pathPrefix?.endsWith("RemoteAgentClient.kt") == true })
        assertTrue(plan.queries.size <= 6)
    }

    @Test
    fun `pack builder deduplicates corroborated hits and preserves citations under budget`() {
        val plan = ContextQueryPlanner.plan("Trace RemoteAgentClient stream handling", "balanced")
        val primary = hit(
            id = "client-stream",
            path = "src/main/kotlin/com/codeagent/plugin/agent/RemoteAgentClient.kt",
            startLine = 120,
            content = "fun stream() = Unit\n".repeat(1_000),
            score = 0.81,
        )
        val bridge = hit(
            id = "bridge-send",
            path = "src/main/kotlin/com/codeagent/plugin/bridge/IdeBridge.kt",
            startLine = 560,
            content = "fun sendMessage() = Unit",
            score = 0.72,
        )
        val results = listOf(
            ContextQueryResult(plan.queries[0], listOf(primary, bridge)),
            ContextQueryResult(plan.queries.getOrElse(1) { plan.queries[0] }, listOf(primary.copy(score = 0.76))),
        )

        val pack = ContextPackBuilder.build(plan, results, maxTokens = 500)

        assertEquals(2, pack.availableHitCount)
        assertTrue(pack.hitCount >= 1)
        assertTrue(pack.estimatedTokens <= 500)
        assertTrue(pack.packedText.contains("RemoteAgentClient.kt:120-124"))
        assertTrue(pack.packedText.contains("matched_queries: 1, 2"))
        assertTrue(pack.truncated)
    }

    @Test
    fun `pack builder returns every reranked candidate when no cap is supplied`() {
        val plan = ContextQueryPlanner.plan("Trace retrieval ownership", "balanced")
        val first = hit("one", "src/One.kt", 10, "fun one() = Unit", 0.9)
        val second = hit("two", "src/Two.kt", 20, "fun two() = Unit", 0.8)
        val results = listOf(ContextQueryResult(plan.queries[0], listOf(first, second)))

        val pack = ContextPackBuilder.build(plan, results, maxTokens = null)

        assertEquals(2, pack.availableHitCount)
        assertEquals(2, pack.hitCount)
        assertFalse(pack.truncated)
        assertTrue(pack.packedText.contains("src/One.kt:10-14"))
        assertTrue(pack.packedText.contains("src/Two.kt:20-24"))
    }

    private fun hit(
        id: String,
        path: String,
        startLine: Int,
        content: String,
        score: Double,
    ): ContextSearchHit = ContextSearchHit(
        chunk = ContextSearchChunk(
            id = id,
            path = path,
            language = "kotlin",
            startLine = startLine,
            endLine = startLine + 4,
            content = content,
            symbol = id,
        ),
        score = score,
        source = "bm25",
        channels = mapOf("fts" to score, "symbol" to 0.5),
    )
}
