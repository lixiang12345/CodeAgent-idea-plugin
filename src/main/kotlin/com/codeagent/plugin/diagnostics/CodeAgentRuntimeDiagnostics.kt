package com.codeagent.plugin.diagnostics

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@State(name = "CodeAgentCrashDetection", storages = [Storage("CodeAgentDiagnostics.xml")])
class CrashDetectionService : PersistentStateComponent<CrashDetectionState> {
    private var state = CrashDetectionState()
    private val started = AtomicBoolean()

    override fun getState(): CrashDetectionState = state

    override fun loadState(state: CrashDetectionState) {
        this.state = state
    }

    fun markStarted() {
        if (!started.compareAndSet(false, true)) return
        state.previousSessionUnclean = !state.cleanShutdown
        state.cleanShutdown = false
        state.lastStartedAt = System.currentTimeMillis()
    }

    fun markCleanShutdown() {
        state.cleanShutdown = true
    }

    fun previousSessionWasUnclean(): Boolean = state.previousSessionUnclean
}

class CrashDetectionState {
    var cleanShutdown: Boolean = true
    var previousSessionUnclean: Boolean = false
    var lastStartedAt: Long = 0
}

class PerformanceWatcherReporter {
    private val startupTimes = ConcurrentHashMap<String, Long>()
    private val slowOperations = AtomicLong()

    fun recordProjectStartup(projectName: String, durationMs: Long) {
        startupTimes[projectName] = durationMs.coerceAtLeast(0)
        if (durationMs >= 5_000) slowOperations.incrementAndGet()
    }

    fun snapshot(): PerformanceSnapshot = PerformanceSnapshot(startupTimes.toMap(), slowOperations.get())
}

data class PerformanceSnapshot(val projectStartupMs: Map<String, Long>, val slowOperations: Long)

class ClientMetricsReporter {
    private val openProjects = AtomicInteger()
    private val openEditors = AtomicInteger()

    fun projectOpened() = openProjects.incrementAndGet()
    fun projectClosed() = openProjects.updateAndGet { (it - 1).coerceAtLeast(0) }
    fun editorOpened() = openEditors.incrementAndGet()
    fun editorClosed() = openEditors.updateAndGet { (it - 1).coerceAtLeast(0) }

    fun snapshot(): ClientMetricsSnapshot = ClientMetricsSnapshot(openProjects.get(), openEditors.get())
}

data class ClientMetricsSnapshot(val openProjects: Int, val openEditors: Int)

class OnboardingSessionEventReporter {
    private val editorSelections = AtomicLong()
    private val startedAt = System.currentTimeMillis()

    fun editorSelected() = editorSelections.incrementAndGet()

    fun snapshot(): OnboardingSessionSnapshot = OnboardingSessionSnapshot(startedAt, editorSelections.get())
}

data class OnboardingSessionSnapshot(val startedAt: Long, val editorSelections: Long)
