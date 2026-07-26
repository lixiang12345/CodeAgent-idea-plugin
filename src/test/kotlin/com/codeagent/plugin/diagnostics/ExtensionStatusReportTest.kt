package com.codeagent.plugin.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensionStatusReportTest {
    @Test
    fun `redactedEndpoint keeps scheme, host, and port only`() {
        assertEquals(
            "https://backend.example.test:8788",
            ExtensionStatusReport.redactedEndpoint("https://backend.example.test:8788/v1/runs?token=secret"),
        )
        assertEquals(
            "http://127.0.0.1:8788",
            ExtensionStatusReport.redactedEndpoint("http://user:pass@127.0.0.1:8788/v1/runs"),
        )
        assertEquals(
            "https://backend.example.test",
            ExtensionStatusReport.redactedEndpoint("https://backend.example.test/health"),
        )
    }

    @Test
    fun `redactedEndpoint returns unknown on parse failure`() {
        assertEquals("unknown", ExtensionStatusReport.redactedEndpoint("not a valid uri"))
        assertEquals("unknown", ExtensionStatusReport.redactedEndpoint(""))
    }
}
