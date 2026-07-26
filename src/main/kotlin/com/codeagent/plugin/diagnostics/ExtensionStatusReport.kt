package com.codeagent.plugin.diagnostics

import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.InlineCompletionTelemetryService
import com.codeagent.plugin.context.ContextEngineService
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URI
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object ExtensionStatusReport {
    private const val PLUGIN_ID = "com.codeagent.workspace.idea"
    private const val REMOTE_TIMEOUT_SECONDS = 10L

    /** Collects the structured status report off the EDT; every line reflects a real probe. */
    fun collect(project: Project): CompletableFuture<String> =
        CompletableFuture.supplyAsync({ collectLines(project) }, AppExecutorUtil.getAppExecutorService())
            .thenCompose { it }

    private fun collectLines(project: Project): CompletableFuture<String> {
        val backendLines = project.service<AgentOrchestrator>().health()
            .handle { value, error ->
                if (error != null || value == null) {
                    listOf(
                        "Backend health: unavailable (${reason(error)})",
                        "Active model: unknown (backend unreachable)",
                    )
                } else {
                    listOf(
                        (if (value.ok) "Backend health: online" else "Backend health: unhealthy") +
                            " (service=${value.service}, protocol=${value.protocolVersion})",
                        "Active model: ${value.defaultModel ?: "unknown"} (provider: ${value.provider ?: "unknown"})",
                    )
                }
            }
            .completeOnTimeout(
                listOf(
                    "Backend health: unavailable (timed out after ${REMOTE_TIMEOUT_SECONDS}s)",
                    "Active model: unknown (backend unreachable)",
                ),
                REMOTE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        val sidecarLine = project.service<ContextEngineService>()
            .sidecarRequest("health")
            .handle { _, error -> if (error == null) "Sidecar: healthy" else "Sidecar: unavailable (${reason(error)})" }
            .completeOnTimeout("Sidecar: unavailable (timed out after ${REMOTE_TIMEOUT_SECONDS}s)", REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val contextLine = project.service<ContextEngineService>().status()
            .handle { value, error ->
                when {
                    error != null || value == null -> "ContextEngine index: unavailable (${reason(error)})"
                    value.indexed ->
                        "ContextEngine index: indexed (${value.fileCount} files, ${value.chunkCount} chunks, watching=${value.watching})"
                    else -> "ContextEngine index: not indexed"
                }
            }
            .completeOnTimeout(
                "ContextEngine index: unavailable (timed out after ${REMOTE_TIMEOUT_SECONDS}s)",
                REMOTE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        return CompletableFuture.allOf(backendLines, sidecarLine, contextLine).thenApplyAsync(
            { buildReport(backendLines.join(), sidecarLine.join(), contextLine.join()) },
            AppExecutorUtil.getAppExecutorService(),
        )
    }

    private fun buildReport(backendLines: List<String>, sidecarLine: String, contextLine: String): String {
        val application = ApplicationInfo.getInstance()
        val settings = service<CodeAgentSettingsService>()
        val completions = service<InlineCompletionTelemetryService>().snapshot()
        val jcefSupported = runCatching { JBCefApp.isSupported().toString() }
            .getOrDefault("unavailable (JCEF support query failed)")
        val outOfProcessJcef = runCatching { Registry.`is`("ide.browser.jcef.out-of-process.enabled").toString() }
            .getOrDefault("unavailable (registry key missing)")
        return buildString {
            appendLine("CodeAgent extension status")
            appendLine("Generated: ${Instant.now()}")
            appendLine("Plugin version: ${pluginVersion()}")
            appendLine("IDE: ${application.fullApplicationName} (build ${application.build})")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("Backend host: ${redactedEndpoint(settings.state.backendUrl)}")
            appendLine("Signed in: ${if (settings.isSignedIn()) "yes" else "no"}")
            backendLines.forEach(::appendLine)
            appendLine(sidecarLine)
            appendLine("ContextEngine mode: ${settings.state.contextMode}")
            appendLine(contextLine)
            appendLine("JCEF supported: $jcefSupported")
            appendLine("JCEF out-of-process registry: $outOfProcessJcef")
            append(
                "Inline completions: ${completions.suggestions} suggestions, " +
                    "${completions.cacheHits} cache hits, ${completions.failures} failures",
            )
        }
    }

    /** Keeps only scheme, host, and port so credentials, paths, and query tokens never leak. */
    internal fun redactedEndpoint(url: String): String = runCatching {
        val uri = URI.create(url.trim())
        val host = uri.host ?: return@runCatching "unknown"
        buildString {
            append(uri.scheme ?: "http").append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
        }
    }.getOrDefault("unknown")

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"

    private fun reason(error: Throwable?): String {
        var current = error ?: return "unknown failure"
        while (current.cause != null) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }
}
