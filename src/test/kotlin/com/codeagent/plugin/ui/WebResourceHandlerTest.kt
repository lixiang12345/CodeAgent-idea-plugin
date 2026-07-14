package com.codeagent.plugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebResourceHandlerTest {
    @Test
    fun `maps localhost webview urls to classpath web resources`() {
        assertEquals("/web/index.html", WebResourceHandler.resourcePathFor("http://codeagent.localhost/"))
        assertEquals("/web/index.html", WebResourceHandler.resourcePathFor("http://codeagent.localhost/index.html"))
        assertEquals(
            "/web/assets/index-abc.js",
            WebResourceHandler.resourcePathFor("http://codeagent.localhost/assets/index-abc.js"),
        )
        assertEquals(
            "/web/assets/style.css",
            WebResourceHandler.resourcePathFor("http://codeagent.localhost/assets/style.css?v=1#x"),
        )
    }

    @Test
    fun `rejects traversal and non webview hosts`() {
        assertNull(WebResourceHandler.resourcePathFor("http://evil.example/index.html"))
        assertNull(WebResourceHandler.resourcePathFor("http://codeagent.localhost/../secret"))
        assertNull(WebResourceHandler.resourcePathFor("file:///tmp/index.html"))
    }

    @Test
    fun `guesses common mime types`() {
        assertEquals("text/html", WebResourceHandler.mimeTypeFor("/web/index.html"))
        assertTrue(WebResourceHandler.mimeTypeFor("/web/assets/app.js").contains("javascript"))
        assertEquals("text/css", WebResourceHandler.mimeTypeFor("/web/assets/app.css"))
    }
}
