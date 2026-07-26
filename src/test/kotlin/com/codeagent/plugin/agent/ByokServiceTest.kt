package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ByokServiceTest {
    @Test
    fun `builds transient provider headers only for a secure backend`() {
        val headers = ByokRequestCredentials.OpenAi("sk-secret").headersFor("https://codeagent.example.test")
        assertEquals("openai", headers["X-CodeAgent-BYOK-Provider"])
        assertEquals("sk-secret", headers["X-CodeAgent-BYOK-API-Key"])
        assertFalse(headers.values.any { it.contains("Authorization") })
        assertFailsWith<IllegalArgumentException> {
            ByokRequestCredentials.OpenAi("sk-secret").headersFor("http://codeagent.example.test")
        }
    }

    @Test
    fun `uses structured AWS credentials instead of a fake Bedrock API key`() {
        val headers = ByokRequestCredentials.Bedrock(
            accessKeyId = "AKID",
            secretAccessKey = "secret",
            sessionToken = "session",
            region = "us-east-1",
            model = "anthropic.claude-3-5-sonnet:0",
        ).headersFor("http://127.0.0.1:8788")
        assertEquals("aws-bedrock", headers["X-CodeAgent-BYOK-Provider"])
        assertEquals("AKID", headers["X-CodeAgent-BYOK-AWS-Access-Key-ID"])
        assertEquals("secret", headers["X-CodeAgent-BYOK-AWS-Secret-Access-Key"])
        assertEquals("session", headers["X-CodeAgent-BYOK-AWS-Session-Token"])
        assertEquals("us-east-1", headers["X-CodeAgent-BYOK-AWS-Region"])
        assertEquals("anthropic.claude-3-5-sonnet:0", headers["X-CodeAgent-BYOK-Model"])
        assertEquals(null, headers["X-CodeAgent-BYOK-API-Key"])
    }

    @Test
    fun `builds isolated headers for multiple configured providers`() {
        val headers = ByokRequestCredentials.Combined(
            listOf(
                ByokRequestCredentials.OpenAi("sk-openai", "https://openai.example.test"),
                ByokRequestCredentials.Anthropic("sk-anthropic", "https://anthropic.example.test"),
            ),
        ).headersFor("https://codeagent.example.test")

        assertEquals("openai,anthropic", headers["X-CodeAgent-BYOK-Providers"])
        assertEquals("sk-openai", headers["X-CodeAgent-BYOK-OpenAI-API-Key"])
        assertEquals("https://openai.example.test", headers["X-CodeAgent-BYOK-OpenAI-Base-URL"])
        assertEquals("sk-anthropic", headers["X-CodeAgent-BYOK-Anthropic-API-Key"])
        assertEquals("https://anthropic.example.test", headers["X-CodeAgent-BYOK-Anthropic-Base-URL"])
        assertEquals(null, headers["X-CodeAgent-BYOK-API-Key"])
    }

    @Test
    fun `excludes incomplete Bedrock credentials from request routing`() {
        assertFalse(hasCompleteBedrockConfig("AKID", "secret", "us-east-1", ""))
        assertFalse(hasCompleteBedrockConfig("AKID", "", "us-east-1", "model-id"))
        assertEquals(true, hasCompleteBedrockConfig("AKID", "secret", "us-east-1", "model-id"))
    }
}
