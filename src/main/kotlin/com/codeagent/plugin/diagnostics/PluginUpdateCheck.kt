package com.codeagent.plugin.diagnostics

import com.codeagent.plugin.context.RuntimeManifest
import com.codeagent.plugin.settings.CodeAgentConfigurable
import com.codeagent.plugin.settings.CodeAgentSettings
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

object PluginUpdateCheck {
    fun shouldNotify(currentVersion: String?, latestVersion: String?, alreadyNotified: String?): Boolean {
        val current = currentVersion?.trim().takeUnless { it.isNullOrEmpty() } ?: return false
        val latest = latestVersion?.trim().takeUnless { it.isNullOrEmpty() } ?: return false
        if (latest == alreadyNotified?.trim()) return false
        return compareVersions(latest, current) > 0
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = parse(left)
        val b = parse(right)
        for (index in 0 until maxOf(a.numbers.size, b.numbers.size)) {
            val comparison = a.numbers.getOrElse(index) { 0L }.compareTo(b.numbers.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        if (a.preRelease.isEmpty() && b.preRelease.isEmpty()) return 0
        if (a.preRelease.isEmpty()) return 1
        if (b.preRelease.isEmpty()) return -1
        for (index in 0 until maxOf(a.preRelease.size, b.preRelease.size)) {
            val leftId = a.preRelease.getOrNull(index) ?: return -1
            val rightId = b.preRelease.getOrNull(index) ?: return 1
            val leftNumber = leftId.toLongOrNull()
            val rightNumber = rightId.toLongOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftId.compareTo(rightId)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class ParsedVersion(val numbers: List<Long>, val preRelease: List<String>)

    /** Semantic-version parse: build metadata after `+` is ignored, pre-release follows the first `-`. */
    private fun parse(version: String): ParsedVersion {
        val normalized = version.trim().removePrefix("v").substringBefore('+')
        return ParsedVersion(
            numbers = normalized.substringBefore('-').split('.')
                .map { segment -> segment.takeWhile(Char::isDigit).toLongOrNull() ?: 0L },
            preRelease = normalized.substringAfter('-', "").split('.').filter(String::isNotEmpty),
        )
    }
}

class CodeAgentUpdateStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        if (project.isDefault || project.isDisposed) return
        val now = System.currentTimeMillis()
        val previous = LAST_CHECK_AT_MILLIS.get()
        if (now - previous < CHECK_INTERVAL_MILLIS || !LAST_CHECK_AT_MILLIS.compareAndSet(previous, now)) return
        AppExecutorUtil.getAppExecutorService().execute { check(project) }
    }

    private fun check(project: Project) {
        val settings = service<CodeAgentSettingsService>()
        val latest = runCatching { latestPluginVersion(settings.snapshot()) }
            .onFailure { error -> LOG.info("CodeAgent update check skipped: ${error.message}") }
            .getOrNull()
            ?: return
        val current = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
        if (!PluginUpdateCheck.shouldNotify(current, latest, settings.updateNotifiedVersion())) return
        settings.markUpdateVersionNotified(latest)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeAgent.Updates")
                .createNotification(
                    "CodeAgent update available",
                    "Version $latest is available; ${current ?: "an unknown version"} is installed.",
                    NotificationType.INFORMATION,
                )
                .addAction(
                    NotificationAction.createSimpleExpiring("Open plugin settings") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, CodeAgentConfigurable::class.java)
                    },
                )
                .notify(project)
        }
    }

    private fun latestPluginVersion(settings: CodeAgentSettings): String? {
        val manifestUri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/runtime/manifest")
        val loopback = manifestUri.host in setOf("127.0.0.1", "localhost", "::1")
        require(manifestUri.scheme == "https" || (manifestUri.scheme == "http" && loopback)) {
            "Runtime manifest URL must use HTTPS unless loopback"
        }
        val request = HttpRequest.newBuilder(manifestUri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .apply { settings.backendToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") } }
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299) { "Runtime manifest returned HTTP ${response.statusCode()}" }
        require(response.body().size <= MAX_MANIFEST_BYTES) { "Runtime manifest is too large" }
        return json.decodeFromString<RuntimeManifest>(response.body().decodeToString())
            .latestPluginVersion
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private companion object {
        const val PLUGIN_ID = "com.codeagent.workspace.idea"
        const val CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
        const val MAX_MANIFEST_BYTES = 1_000_000
        val LAST_CHECK_AT_MILLIS = AtomicLong()
        val LOG = logger<CodeAgentUpdateStartupActivity>()
        val json = Json { ignoreUnknownKeys = true }
        val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }
}
