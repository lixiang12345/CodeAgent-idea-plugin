package com.codeagent.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class CodeAgentUiService(private val project: Project) {
    private val listeners = CopyOnWriteArrayList<(CodeAgentUiRequest) -> Unit>()
    private val pending = ConcurrentLinkedQueue<CodeAgentUiRequest>()

    fun request(request: CodeAgentUiRequest) {
        pending.add(request)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CodeAgent") ?: return
        toolWindow.activate { flush() }
    }

    fun subscribe(parent: Disposable, listener: (CodeAgentUiRequest) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
        flush()
    }

    private fun flush() {
        if (listeners.isEmpty()) return
        while (true) {
            val request = pending.poll() ?: break
            listeners.forEach { it(request) }
        }
    }
}

data class CodeAgentUiRequest(
    val action: String,
    val section: String? = null,
)
