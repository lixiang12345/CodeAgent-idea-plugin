package com.codeagent.plugin.ui

import com.codeagent.plugin.CodeAgentBundle
import com.codeagent.plugin.bridge.IdeBridge
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JComponent

// Tool-window host for the CodeAgent web UI.
// Multi-asset frontend under classpath /web, loaded via
// loadURL("http://codeagent.localhost/index.html") and a CEF resource handler.
class CodeAgentPanel(private val project: Project) : Disposable {
    private var browser: JBCefBrowser? = null
    private var bridge: IdeBridge? = null
    private var requestHandler: CefRequestHandlerAdapter? = null
    private var loadHandler: CefLoadHandlerAdapter? = null

    val component: JComponent = if (JBCefApp.isSupported()) {
        createBrowserComponent()
    } else {
        createFallbackComponent()
    }

    private fun createBrowserComponent(): JComponent {
        val jcefBrowser = JBCefBrowser()
        jcefBrowser.jbCefClient.setProperty("JBCefClient.JSQuery.poolSize", 20)
        val ideBridge = IdeBridge(project, jcefBrowser)
        browser = jcefBrowser
        bridge = ideBridge

        val cefBrowser = jcefBrowser.cefBrowser
        val client = jcefBrowser.jbCefClient
        val bridgeScript = ideBridge.injectBridgeScript()
        val resourceVersion = System.nanoTime().toString()

        val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
            ) = if (WebResourceHandler.isWebviewUrl(request?.url)) {
                WebResourceHandler(request?.url ?: WebResourceHandler.ORIGIN) { html ->
                    val versioned = WebResourceHandler.versionAssetUrls(html, resourceVersion)
                    if (versioned.contains("</head>")) {
                        versioned.replaceFirst("</head>", "$bridgeScript</head>")
                    } else if (versioned.contains("</body>")) {
                        versioned.replaceFirst("</body>", "$bridgeScript</body>")
                    } else {
                        versioned + bridgeScript
                    }
                }
            } else {
                null
            }
        }

        val reqHandler = object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String?,
                disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler {
                if (WebResourceHandler.isWebviewUrl(request?.url)) {
                    disableDefaultHandling?.set(true)
                }
                return resourceRequestHandler
            }
        }
        requestHandler = reqHandler
        client.addRequestHandler(reqHandler, cefBrowser)

        val onLoad = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame == null || !frame.isMain) return
                // Safety re-inject if HTML filter was skipped for any reason.
                browser?.executeJavaScript(ideBridge.injectBridgeJavaScript(), frame.url, 0)
                LOG.info("CodeAgent webview loaded status=$httpStatusCode url=${frame.url}")
            }

            override fun onLoadError(
                browser: CefBrowser?,
                frame: CefFrame?,
                errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                if (frame == null || !frame.isMain) return
                LOG.warn("CodeAgent webview failed url=$failedUrl code=$errorCode text=$errorText")
            }
        }
        loadHandler = onLoad
        client.addLoadHandler(onLoad, cefBrowser)

        jcefBrowser.loadURL("${WebResourceHandler.ORIGIN}/index.html?v=$resourceVersion")
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
        val current = browser
        if (current != null) {
            // Best-effort cleanup; JBCefClient owns handler registration lifecycle with the browser.
            runCatching { requestHandler?.let { current.jbCefClient.removeRequestHandler(it, current.cefBrowser) } }
            runCatching { loadHandler?.let { current.jbCefClient.removeLoadHandler(it, current.cefBrowser) } }
        }
        bridge?.dispose()
        current?.dispose()
        bridge = null
        browser = null
        requestHandler = null
        loadHandler = null
    }

    companion object {
        private val LOG = logger<CodeAgentPanel>()
    }
}
