package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HookRuntimeServiceTest {
    @Test
    fun `runs only automatic hooks and records audit output`() {
        val executed = mutableListOf<String>()
        val runtime = HookRuntime(HookCommandExecutor { definition, context ->
            executed += "${definition.id}:${context.event}"
            HookProcessOutput(exitCode = 0, output = "checked ${context.runId}")
        })
        runtime.reconcile(
            listOf(
                hook("automatic", "before-run", "automatic"),
                hook("manual", "before-run", "manual"),
            ),
        )

        runtime.runLifecycle(
            "before-run",
            HookExecutionContext(runId = "run-1", event = "before-run"),
        )

        assertEquals(listOf("automatic:before-run"), executed)
        assertEquals(2, runtime.snapshot().configured)
        assertEquals(1, runtime.snapshot().automatic)
        assertEquals("completed", runtime.snapshot().recent.single().status)
        assertTrue(runtime.snapshot().recent.single().detail.orEmpty().contains("run-1"))
    }

    @Test
    fun `blocking hook failure aborts the lifecycle`() {
        val runtime = HookRuntime(HookCommandExecutor { _, _ ->
            HookProcessOutput(exitCode = 7, output = "policy failed")
        })
        runtime.reconcile(
            listOf(hook("blocking", "before-tool", "automatic", failurePolicy = "fail-run")),
        )

        assertFailsWith<HookExecutionException> {
            runtime.runLifecycle(
                "before-tool",
                HookExecutionContext(runId = "run-2", event = "before-tool", toolName = "write_file"),
            )
        }
        assertEquals("failed", runtime.snapshot().recent.single().status)
        assertEquals(7, runtime.snapshot().recent.single().exitCode)
    }

    @Test
    fun `manual hook can be tested explicitly`() {
        val runtime = HookRuntime(HookCommandExecutor { definition, _ ->
            HookProcessOutput(exitCode = 0, output = definition.command)
        })
        runtime.reconcile(listOf(hook("manual", "after-run", "manual")))

        val result = runtime.test("manual")

        assertEquals("completed", result.status)
        assertEquals("after-run", result.event)
    }

    private fun hook(
        id: String,
        event: String,
        runPolicy: String,
        failurePolicy: String = "continue",
    ) = RemoteConfiguration(
        id = id,
        kind = "hooks",
        value = buildJsonObject {
            put("name", id)
            put("enabled", true)
            put("event", event)
            put("command", "printf test")
            put("timeoutSeconds", 30)
            put("runPolicy", runPolicy)
            put("failurePolicy", failurePolicy)
            put("requiredEnvironment", buildJsonArray { add(JsonPrimitive("PATH")) })
        },
    )
}
