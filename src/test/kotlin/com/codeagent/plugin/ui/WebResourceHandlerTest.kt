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
    fun `adds a shared cache token to local frontend assets`() {
        val html = """
            <script type="module" src="/assets/app.js"></script>
            <link rel='stylesheet' href='/assets/app.css'>
            <img src="https://cdn.example.test/logo.png">
        """.trimIndent()

        assertEquals(
            """
                <script type="module" src="/assets/app.js?v=build-42"></script>
                <link rel='stylesheet' href='/assets/app.css?v=build-42'>
                <img src="https://cdn.example.test/logo.png">
            """.trimIndent(),
            WebResourceHandler.versionAssetUrls(html, "build-42"),
        )
    }

    @Test
    fun `guesses common mime types`() {
        assertEquals("text/html", WebResourceHandler.mimeTypeFor("/web/index.html"))
        assertTrue(WebResourceHandler.mimeTypeFor("/web/assets/app.js").contains("javascript"))
        assertEquals("text/css", WebResourceHandler.mimeTypeFor("/web/assets/app.css"))
    }
}
