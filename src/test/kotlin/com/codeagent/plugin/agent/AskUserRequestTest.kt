package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AskUserRequestTest {
    @Test
    fun `reads a single question with either option spelling`() {
        val withOptions = parseAskUserRequest("""{"question":"Which database?","options":["Postgres","SQLite"],"default":"Postgres"}""")
        assertEquals(1, withOptions.questions.size)
        assertEquals("Which database?", withOptions.question)
        assertEquals(listOf("Postgres", "SQLite"), withOptions.options)
        assertEquals("Postgres", withOptions.default)
        assertNull(withOptions.context)

        // The original plugin names the same field suggested_responses.
        val original = parseAskUserRequest("""{"question":"Which database?","suggested_responses":["Postgres","SQLite"],"context":"The schema differs."}""")
        assertEquals(listOf("Postgres", "SQLite"), original.options)
        assertEquals("The schema differs.", original.context)
    }

    @Test
    fun `reads several questions in one pause`() {
        val request = parseAskUserRequest(
            """{"questions":[
                 {"question":"Which database?","suggested_responses":["Postgres","SQLite"]},
                 {"question":"Run migrations now?","suggested_responses":["Yes","No"]}
               ],"context":"Both affect the migration plan."}""",
        )

        assertEquals(2, request.questions.size)
        assertEquals("Run migrations now?", request.questions[1].question)
        assertEquals(listOf("Yes", "No"), request.questions[1].options)
        assertEquals("Both affect the migration plan.", request.context)
        // The first question stays addressable through the single-question accessors.
        assertEquals("Which database?", request.question)
    }

    @Test
    fun `rejects ambiguous, empty, and oversized requests`() {
        assertFailsWith<IllegalArgumentException> {
            parseAskUserRequest("""{"question":"One","questions":[{"question":"Two","suggested_responses":["a"]}]}""")
        }
        assertFailsWith<IllegalArgumentException> { parseAskUserRequest("""{}""") }
        assertFailsWith<IllegalArgumentException> { parseAskUserRequest("""{"question":"   "}""") }
        assertFailsWith<IllegalArgumentException> {
            parseAskUserRequest("""{"questions":[{"question":"  ","suggested_responses":["a"]}]}""")
        }
        val tooMany = (1..11).joinToString(",") { """{"question":"Q$it","suggested_responses":["a"]}""" }
        assertFailsWith<IllegalArgumentException> { parseAskUserRequest("""{"questions":[$tooMany]}""") }
    }

    @Test
    fun `keeps a question without suggestions answerable`() {
        val request = parseAskUserRequest("""{"questions":[{"question":"Describe the failure"}]}""")

        assertEquals(1, request.questions.size)
        assertEquals(emptyList(), request.questions.single().options)
    }
}
