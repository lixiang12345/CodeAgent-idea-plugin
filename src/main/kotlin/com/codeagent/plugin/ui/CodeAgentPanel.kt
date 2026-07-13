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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JComponent

class CodeAgentPanel(private val project: Project) : Disposable {
    private var browser: JBCefBrowser? = null
    private var bridge: IdeBridge? = null
    private var webRoot: Path? = null

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

        val page = materializeWebPage(ideBridge.injectBridgeScript())
        webRoot = page.parent
        // file:// avoids JCEF data-URL size/module failures that render raw JS as text.
        jcefBrowser.loadURL(page.toUri().toString())
        return jcefBrowser.component
    }

    private fun materializeWebPage(bridgeScript: String): Path {
        val html = requireNotNull(javaClass.getResourceAsStream("/web/index.html")) {
            "Missing bundled CodeAgent frontend"
        }.use { stream -> stream.readBytes().toString(StandardCharsets.UTF_8) }

        val dir = Files.createTempDirectory("codeagent-web-")
        dir.toFile().deleteOnExit()
        val page = dir.resolve("index.html")
        val withBridge = if (html.contains("</body>")) {
            html.replaceFirst("</body>", "$bridgeScript</body>")
        } else {
            html + bridgeScript
        }
        Files.writeString(page, withBridge, StandardCharsets.UTF_8)
        page.toFile().deleteOnExit()
        return page
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
        webRoot?.let { root ->
            runCatching {
                Files.walk(root).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
        bridge = null
        browser = null
        webRoot = null
    }
}
