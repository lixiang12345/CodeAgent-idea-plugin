package com.codeagent.plugin.context

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextEngineConnectionTest {
    @Test
    fun `connection check validates the supplied token against a protected endpoint`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestedPath = AtomicReference("")
        server.createContext("/v1/workspaces") { exchange ->
            requestedPath.set(exchange.requestURI.path)
            val authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer dashboard-test-key"
            val body = if (authorized) """{"workspaces":[]}""" else """{"message":"Unauthorized"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (authorized) 200 else 401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val baseUrl = "http://127.0.0.1:${server.address.port}"

            val rejected = validateContextEngineConnection(client, baseUrl, "wrong-token").join()
            val accepted = validateContextEngineConnection(client, baseUrl, "dashboard-test-key").join()

            assertFalse(rejected.ok)
            assertEquals("ContextEngine token is invalid", rejected.label)
            assertTrue(accepted.ok)
            assertEquals("/v1/workspaces", requestedPath.get())
        } finally {
            server.stop(0)
        }
    }
}
