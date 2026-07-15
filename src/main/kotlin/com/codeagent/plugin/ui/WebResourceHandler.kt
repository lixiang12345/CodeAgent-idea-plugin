package com.codeagent.plugin.ui

import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLConnection
import java.nio.charset.StandardCharsets

// Serves classpath resources under /web for http://codeagent.localhost
// (same multi-asset pattern as the original IDEA plugin).
internal class WebResourceHandler(
    private val requestUrl: String,
    private val htmlFilter: ((String) -> String)? = null,
) : CefResourceHandler {
    private var stream: InputStream? = null
    private var mimeType: String = "application/octet-stream"
    private var closed = false

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        val url = request?.url ?: requestUrl
        val resourcePath = resourcePathFor(url)
        if (resourcePath == null) {
            callback?.cancel()
            return false
        }

        val raw = javaClass.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        if (raw == null) {
            callback?.cancel()
            return false
        }

        mimeType = mimeTypeFor(resourcePath)
        val bytes = if (mimeType.startsWith("text/html") && htmlFilter != null) {
            val text = raw.toString(StandardCharsets.UTF_8)
            htmlFilter.invoke(text).toByteArray(StandardCharsets.UTF_8)
        } else {
            raw
        }
        stream = ByteArrayInputStream(bytes)
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        val input = stream
        if (response == null || input == null) {
            response?.setStatus(404)
            response?.setStatusText("Not Found")
            responseLength?.set(0)
            return
        }

        val available = runCatching { input.available() }.getOrDefault(-1)
        response.setStatus(200)
        response.setStatusText("OK")
        response.setMimeType(mimeType)
        response.setHeaderByName("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0", true)
        response.setHeaderByName("Pragma", "no-cache", true)
        response.setHeaderByName("Expires", "0", true)
        response.setHeaderByName("Access-Control-Allow-Origin", "*", true)
        responseLength?.set(if (available >= 0) available else -1)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        val input = stream
        if (dataOut == null || input == null || closed) {
            bytesRead?.set(0)
            return false
        }

        val read = input.read(dataOut, 0, bytesToRead.coerceAtMost(dataOut.size))
        if (read <= 0) {
            bytesRead?.set(0)
            closeStream()
            return false
        }
        bytesRead?.set(read)
        return true
    }

    override fun cancel() {
        closeStream()
    }

    private fun closeStream() {
        if (closed) return
        closed = true
        runCatching { stream?.close() }
        stream = null
    }

    companion object {
        const val ORIGIN = "http://codeagent.localhost"
        private const val RESOURCE_ROOT = "/web"
        private val LOCAL_ASSET_ATTRIBUTE = Regex("""(src|href)=(["'])(/assets/[^"'?#]+)\2""")

        fun isWebviewUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return url.startsWith("$ORIGIN/") || url == ORIGIN || url == "$ORIGIN/"
        }

        fun versionAssetUrls(html: String, cacheToken: String): String {
            require(cacheToken.matches(Regex("""[A-Za-z0-9._-]+"""))) { "Invalid webview cache token" }
            return LOCAL_ASSET_ATTRIBUTE.replace(html) { match ->
                val attribute = match.groupValues[1]
                val quote = match.groupValues[2]
                val path = match.groupValues[3]
                "$attribute=$quote$path?v=$cacheToken$quote"
            }
        }

        fun resourcePathFor(url: String): String? {
            if (!isWebviewUrl(url)) return null
            val path = url
                .removePrefix(ORIGIN)
                .substringBefore('?')
                .substringBefore('#')
                .ifBlank { "/" }
            val normalized = when {
                path == "/" || path.isEmpty() -> "/index.html"
                path.endsWith("/") -> path + "index.html"
                else -> path
            }
            val clean = normalized.replace('\\', '/')
            if (clean.contains("..")) return null
            return RESOURCE_ROOT + if (clean.startsWith("/")) clean else "/$clean"
        }

        fun mimeTypeFor(resourcePath: String): String {
            val name = resourcePath.substringAfterLast('/')
            URLConnection.guessContentTypeFromName(name)?.let { return it }
            return when (resourcePath.substringAfterLast('.').lowercase()) {
                "html", "htm" -> "text/html"
                "js", "mjs" -> "text/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "ttf" -> "font/ttf"
                "map" -> "application/json"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }
}
