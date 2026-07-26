package com.codeagent.plugin.diagnostics

import com.codeagent.plugin.context.ContextEngineService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.registry.Registry
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class CodeAgentDiagnosticsStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        if (project.isDefault || project.isDisposed) return
        service<CrashDetectionService>().markStarted()
        service<ClientMetricsReporter>().projectOpened()
        project.service<OnboardingSessionEventReporter>()
        warnAboutOutOfProcessJcef(project)
    }

    private fun warnAboutOutOfProcessJcef(project: Project) {
        val outOfProcess = runCatching { Registry.`is`("ide.browser.jcef.out-of-process.enabled") }.getOrDefault(false)
        if (!outOfProcess || !JCEF_WARNING_SHOWN.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeAgent")
            .createNotification(
                "Out-of-process JCEF is enabled",
                "The registry key ide.browser.jcef.out-of-process.enabled is on. " +
                    "This setting is known to cause blank or broken CodeAgent panels; " +
                    "disable it in Registry if the tool window fails to render.",
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private companion object {
        val JCEF_WARNING_SHOWN = AtomicBoolean(false)
    }
}

class CodeAgentSidecarStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        if (project.isDefault || project.isDisposed || project.basePath == null) return
        val startedAt = System.nanoTime()
        project.service<ContextEngineService>()
            .sidecarRequest("health", timeout = Duration.ofSeconds(20))
            .whenComplete { _, error ->
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                service<PerformanceWatcherReporter>().recordProjectStartup(project.name, durationMs)
                if (error != null) LOG.info("CodeAgent sidecar warmup deferred: ${error.message}")
            }
    }

    private companion object {
        val LOG = logger<CodeAgentSidecarStartupActivity>()
    }
}

class CodeAgentIntegrityStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val loader = javaClass.classLoader
        val missing = listOf("web/index.html", "sidecar/server.mjs").filter { loader.getResource(it) == null }
        if (missing.isNotEmpty()) LOG.warn("CodeAgent packaged resources are missing: ${missing.joinToString()}")
    }

    private companion object {
        val LOG = logger<CodeAgentIntegrityStartupActivity>()
    }
}
