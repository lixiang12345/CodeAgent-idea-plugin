package com.codeagent.plugin.diagnostics

import com.codeagent.plugin.context.CodeAgentContextSyncService
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class CodeAgentProjectMetricsListener : ProjectManagerListener {
    override fun projectClosed(project: Project) {
        service<ClientMetricsReporter>().projectClosed()
    }
}

class CodeAgentShutdownListener : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        service<CrashDetectionService>().markCleanShutdown()
    }
}

class CodeAgentEditorFocusListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        service<ClientMetricsReporter>().editorOpened()
        event.editor.project?.service<CodeAgentContextSyncService>()?.editorStateChanged()
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        service<ClientMetricsReporter>().editorClosed()
    }
}

class CodeAgentChatSelectionListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        event.editor.project?.service<OnboardingSessionEventReporter>()?.editorSelected()
    }
}

class CodeAgentVfsListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.isEmpty()) return
        val paths = events.map(VFileEvent::getPath)
        ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) project.service<CodeAgentContextSyncService>().externalFilesChanged(paths)
        }
    }
}
