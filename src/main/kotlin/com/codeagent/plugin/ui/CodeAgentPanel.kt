package com.codeagent.plugin.ui

import com.codeagent.plugin.CodeAgentBundle
import com.codeagent.plugin.bridge.IdeBridge
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JComponent

class CodeAgentPanel(private val project: Project) : Disposable {
    private var browser: JBCefBrowser? = null
    private var bridge: IdeBridge? = null

    val component: JComponent = if (JBCefApp.isSupported()) {
        createBrowserComponent()
    } else {
        createFallbackComponent()
    }

    private fun createBrowserComponent(): JComponent {
        val jcefBrowser = JBCefBrowser()
        val ideBridge = IdeBridge(project, jcefBrowser)
        browser = jcefBrowser
        bridge = ideBridge

        val html = requireNotNull(javaClass.getResource("/web/index.html")) {
            "Missing bundled CodeAgent frontend"
        }.readText()
        jcefBrowser.loadHTML(html.replace("</body>", "${ideBridge.injectBridgeScript()}</body>"))
        return jcefBrowser.component
    }

    private fun createFallbackComponent(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
        add(
            JBLabel("<html><b>${CodeAgentBundle["jcef.unavailable.title"]}</b><br><br>${CodeAgentBundle["jcef.unavailable.body"]}</html>"),
            BorderLayout.NORTH,
        )
    }

    override fun dispose() {
        bridge?.dispose()
        browser?.dispose()
        bridge = null
        browser = null
    }
}
