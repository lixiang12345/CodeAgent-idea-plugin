package com.codeagent.plugin.context

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class CodeAgentPostStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        if (project.isDefault || project.isDisposed || project.basePath == null) return
        project.service<CodeAgentContextSyncService>()
        val context = project.service<ContextEngineService>()
        context.status().thenAccept { status ->
            if (!status.indexed) context.index { }
        }.whenComplete { _, error ->
            if (error != null) {
                LOG.info("CodeAgent context startup initialization deferred: ${error.message}")
            }
        }
    }

    private companion object {
        val LOG = logger<CodeAgentPostStartupActivity>()
    }
}
