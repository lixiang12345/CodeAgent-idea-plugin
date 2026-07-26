package com.codeagent.plugin.bridge

import com.codeagent.plugin.settings.CodeAgentSettingsState
import com.codeagent.plugin.settings.DEFAULT_BACKEND_URL
import com.codeagent.plugin.settings.DEFAULT_CONTEXT_MODE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Mirrors IdeBridge's encoder so these assertions describe what the webview actually receives. */
    private val bridgeJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `decodes a versioned command and ignores future fields`() {
        val command = json.decodeFromString<CommandEnvelope>(
            """{"version":1,"id":"request-1","type":"bootstrap","future":true}""",
        )

        assertEquals(BRIDGE_PROTOCOL_VERSION, command.version)
        assertEquals("bootstrap", command.type)
    }

    @Test
    fun `rejects malformed command`() {
        assertFailsWith<Exception> {
            json.decodeFromString<CommandEnvelope>("""{"version":1}""")
        }
    }

    @Test
    fun `dispatches IDE UI bridge commands to the event thread`() {
        listOf(
            "attachActiveEditor",
            "browseImageDirectory",
            "copyText",
            "copyThread",
            "exportTasks",
            "exportThread",
            "importTasks",
            "importThread",
            "pickContext",
        ).forEach { command ->
            assertTrue(bridgeCommandRequiresEdt(command), command)
        }
        assertFalse(bridgeCommandRequiresEdt("sendMessage"))
        assertFalse(bridgeCommandRequiresEdt("refreshImageCanvas"))
    }

    @Test
    fun `encodes ordered browser event sequence`() {
        val encoded = json.encodeToString(
            EventEnvelope(
                sequence = 42L,
                type = "snapshot",
            ),
        )

        assertEquals("""{"sequence":42,"type":"snapshot"}""", encoded)
    }

    @Test
    fun `encodes a redacted settings save confirmation`() {
        val encoded = json.encodeToString(
            SettingsSavedDto(
                requestId = "save-1",
                backendTokenConfigured = true,
                contextHttpTokenConfigured = false,
                contextEmbeddingTokenConfigured = true,
            ),
        )

        assertEquals(
            """{"requestId":"save-1","backendTokenConfigured":true,"contextHttpTokenConfigured":false,"contextEmbeddingTokenConfigured":true}""",
            encoded,
        )
    }

    @Test
    fun `encodes explicit ContextEngine connection results and incremental sync state`() {
        val checked = json.encodeToString(
            ContextConnectionCheckedDto(
                ok = false,
                label = "ContextEngine token is invalid",
            ),
        )
        val context = json.encodeToString(
            ContextSnapshotDto(
                state = "ready",
                label = "68 files indexed",
                watching = true,
                pendingChanges = 2,
                automaticIndexRuns = 4,
                lastAutomaticIndexAt = "2026-07-16T10:00:00Z",
                watchError = "watcher unavailable",
            ),
        )

        assertEquals("""{"ok":false,"label":"ContextEngine token is invalid"}""", checked)
        assertTrue(context.contains("\"pendingChanges\":2"))
        assertTrue(context.contains("\"automaticIndexRuns\":4"))
        assertTrue(context.contains("\"watchError\":\"watcher unavailable\""))
    }

    @Test
    fun `encodes turn identity on streaming bridge payloads`() {
        val delta = json.encodeToString(MessageDeltaDto("message-1", "Done", 2))
        val tool = json.encodeToString(
            ToolRunDto(
                "tool-1",
                "read_file",
                "Read",
                "completed",
                turnIndex = 2,
                createdAt = 1_000,
                updatedAt = 1_250,
                timelineSequence = 3,
            ),
        )

        assertEquals("""{"id":"message-1","delta":"Done","turnIndex":2}""", delta)
        assertEquals(true, tool.contains("\"turnIndex\":2"))
        assertEquals(true, tool.contains("\"updatedAt\":1250"))
        assertEquals(true, tool.contains("\"timelineSequence\":3"))
    }

    @Test
    fun `encodes context and lazy tool telemetry`() {
        val telemetry = AgentRunTelemetryDto(
            phase = "tools",
            turnIndex = 3,
            estimatedInputTokens = 12_000,
            targetInputTokens = 48_000,
            contextWindowTokens = 64_000,
            reservedOutputTokens = 8_192,
            retrievalBudgetTokens = 8_192,
            toolDefinitionTokens = 800,
            assistantResponseTokens = 2_000,
            compactedToolResults = 1,
            truncatedMessages = 2,
            compactionApplied = true,
            overBudget = false,
            activeToolNames = listOf("read_file", "git_history"),
            activeToolCount = 2,
            catalogToolCount = 14,
            discoverableToolCount = 12,
            activatedToolNames = listOf("git_history"),
            toolBatchTotal = 3,
            toolBatchCompleted = 2,
            toolBatchExecution = "sequential",
            retryAttempt = 2,
            retryMaxAttempts = 3,
            retryMessage = "other side closed",
            verificationState = "verified",
            verificationMessage = "Verified with diagnostics",
            verificationToolName = "diagnostics",
        )

        val encoded = json.encodeToString(telemetry)
        assertEquals(true, encoded.contains("\"estimatedInputTokens\":12000"))
        assertEquals(true, encoded.contains("\"phase\":\"tools\""))
        assertEquals(true, encoded.contains("\"toolBatchTotal\":3"))
        assertEquals(true, encoded.contains("\"retryAttempt\":2"))
        assertEquals(true, encoded.contains("\"retrievalBudgetTokens\":8192"))
        assertEquals(true, encoded.contains("\"assistantResponseTokens\":2000"))
        assertEquals(true, encoded.contains("\"activatedToolNames\":[\"git_history\"]"))
        assertEquals(true, encoded.contains("\"verificationState\":\"verified\""))
    }

    @Test
    fun `encodes paused message queue state in the application snapshot`() {
        val encoded = json.encodeToString(
            AppSnapshotDto(
                projectName = "sample-project",
                threads = emptyList(),
                messageQueue = listOf(QueuedMessageDto("queue-1", "Run tests", "agent")),
                messageQueuePaused = true,
            ),
        )

        assertTrue(encoded.contains("\"messageQueue\":[{\"id\":\"queue-1\""))
        assertTrue(encoded.contains("\"messageQueuePaused\":true"))
    }

    @Test
    fun `encodes the cloud surfaces the webview reads by name`() {
        val encoded = bridgeJson.encodeToString(
            AppSnapshotDto(
                projectName = "sample-project",
                threads = emptyList(),
                account = AccountSnapshotDto(
                    state = "signed_in",
                    subscription = SubscriptionSnapshotDto(
                        state = "approaching",
                        plan = "team",
                        label = "Team",
                        manageUrl = "https://billing.example.com/codeagent",
                        quotas = listOf(SubscriptionQuotaDto("agent_run", 4200, 5000, 800, 0.84, "approaching")),
                        warning = SubscriptionWarningDto("warning", "agent_run", "You have used 4200 of 5000 agent_run units."),
                    ),
                ),
                notifications = listOf(
                    NotificationDto(
                        id = "maintenance-window",
                        level = "warning",
                        message = "Scheduled backend maintenance tonight.",
                        actionItems = listOf(NotificationActionItemDto("Maintenance notice", "https://status.example.com/codeagent")),
                    ),
                ),
                sharing = SharingSnapshotDto(state = "shared", tokenPrefix = "abcd1234", expiresAt = 1_800_000_000_000, viewCount = 3),
            ),
        )

        assertTrue(encoded.contains(""""state":"approaching""""))
        assertTrue(encoded.contains(""""manageUrl":"https://billing.example.com/codeagent""""))
        assertTrue(encoded.contains(""""remaining":800"""))
        assertTrue(encoded.contains(""""notifications":[{"id":"maintenance-window""""))
        assertTrue(encoded.contains(""""actionItems":[{"title":"Maintenance notice""""))
        assertTrue(encoded.contains(""""sharing":{"state":"shared""""))
        assertTrue(encoded.contains(""""tokenPrefix":"abcd1234""""))
        // A share URL is a bearer secret; the sharing block carries none unless the host just created one.
        assertTrue(encoded.contains(""""sharing":{"state":"shared","reason":null,"url":null"""))
    }

    @Test
    fun `reports an absent subscription as null rather than inventing a plan`() {
        val encoded = bridgeJson.encodeToString(AppSnapshotDto(projectName = "sample-project", threads = emptyList()))

        assertTrue(encoded.contains(""""subscription":null"""))
        // The webview types both fields as required, so the host always sends them.
        assertTrue(encoded.contains(""""notifications":[]"""))
        assertTrue(encoded.contains(""""sharing":{"state":"unavailable","reason":null"""))
    }

    @Test
    fun `encodes backend tool discovery separately from reported capabilities`() {
        val encoded = json.encodeToString(
            AppSnapshotDto(
                projectName = "sample-project",
                threads = emptyList(),
                backendToolDiscovery = BackendToolDiscoveryDto(
                    state = "error",
                    label = "HTTP 503 from tool discovery",
                ),
                backendTools = listOf(
                    BackendToolDto(
                        name = "notion_search",
                        catalogId = "notion",
                        available = true,
                    ),
                ),
            ),
        )

        assertTrue(encoded.contains("\"backendToolDiscovery\":{\"state\":\"error\""))
        assertTrue(encoded.contains("HTTP 503 from tool discovery"))
        assertTrue(encoded.contains("\"name\":\"notion_search\""))
    }

    @Test
    fun `fresh install targets the local Docker backend`() {
        assertEquals("http://127.0.0.1:8788", DEFAULT_BACKEND_URL)
        assertEquals(DEFAULT_BACKEND_URL, CodeAgentSettingsState().backendUrl)
        assertEquals(DEFAULT_BACKEND_URL, SettingsSnapshotDto().backendUrl)
        assertEquals("remote-http", DEFAULT_CONTEXT_MODE)
        assertEquals(DEFAULT_CONTEXT_MODE, CodeAgentSettingsState().contextMode)
        assertEquals(DEFAULT_CONTEXT_MODE, SettingsSnapshotDto().contextMode)
        assertEquals(true, SettingsSnapshotDto().inlineCompletionsEnabled)
        assertEquals(100, SettingsSnapshotDto().chatZoom)
        assertEquals(true, SettingsSnapshotDto().showTimestamps)
        assertEquals(false, SettingsSnapshotDto().desktopNotifications)
    }

    @Test
    fun `encodes typed product configurations in the application snapshot`() {
        val snapshot = ConfigurationSnapshotDto(
            state = "ready",
            label = "1 configurations",
            items = mapOf(
                "agents" to listOf(
                    ProductConfigurationDto(
                        id = "review-agent",
                        kind = "agents",
                        value = buildJsonObject {
                            put("name", "Review Agent")
                            put("systemPrompt", "Review twice")
                        },
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(snapshot)
        assertEquals(true, encoded.contains("\"review-agent\""))
        assertEquals(true, encoded.contains("\"systemPrompt\":\"Review twice\""))
    }

    @Test
    fun `encodes durable product jobs in the application snapshot`() {
        val jobs = ProductJobSnapshotDto(
            state = "ready",
            label = "1 durable job",
            items = listOf(
                ProductJobDto(
                    id = "job-1",
                    type = "subagent",
                    status = "completed",
                    prompt = "Review the authentication change",
                    role = "review",
                    expectedOutput = "Prioritized findings",
                    maxOutputTokens = 4096,
                    model = "gpt-5.6-sol",
                    output = "Add an expired-token regression test.",
                ),
            ),
        )

        val encoded = json.encodeToString(jobs)
        assertEquals(true, encoded.contains("\"status\":\"completed\""))
        assertEquals(true, encoded.contains("\"role\":\"review\""))
        assertEquals(true, encoded.contains("\"output\":\"Add an expired-token regression test.\""))
    }

    @Test
    fun `encodes declarative plugin runtime and command contributions`() {
        val plugins = PluginRuntimeSnapshotDto(
            state = "ready",
            label = "1 configured · 1 installed · 1 active",
            items = listOf(
                PluginRuntimeItemDto(
                    id = "review-pack",
                    name = "Review pack",
                    source = "https://plugins.example.test/review.json",
                    state = "ready",
                    label = "Installed and active",
                    installedVersion = "1.0.0",
                    grantedCapabilities = listOf("commands"),
                    declaredCapabilities = listOf("commands"),
                    commandCount = 1,
                    promptCount = 1,
                    ruleCount = 1,
                    skillCount = 1,
                ),
            ),
            commands = listOf(
                PluginCommandDto(
                    id = "review-pack.review",
                    pluginId = "review-pack",
                    pluginVersion = "1.0.0",
                    name = "Plugin review",
                    mode = "ask",
                ),
            ),
            prompts = listOf(
                PluginPromptDto(
                    id = "review-pack.security-review",
                    pluginId = "review-pack",
                    pluginVersion = "1.0.0",
                    name = "Security review",
                ),
            ),
            agents = emptyList(),
            hooks = emptyList(),
            mcpServers = emptyList(),
            tools = emptyList(),
        )

        val encoded = json.encodeToString(plugins)

        assertEquals(true, encoded.contains("\"installedVersion\":\"1.0.0\""))

        assertEquals(true, encoded.contains("\"id\":\"review-pack.review\""))
        assertEquals(true, encoded.contains("\"id\":\"review-pack.security-review\""))
        assertEquals(true, encoded.contains("\"promptCount\":1"))
    }

}
