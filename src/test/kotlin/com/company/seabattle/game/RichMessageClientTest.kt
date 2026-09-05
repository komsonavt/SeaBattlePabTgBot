package com.company.seabattle.game

import com.company.seabattle.bot.RichMessageClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class RichMessageClientTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `deleted message is replaced using rich_message html`() {
        val calls = mutableListOf<String>()
        val client = RichMessageClient("unused") { method, body ->
            calls += method
            assertEquals(mapOf("html" to "<h3>Field</h3>"), body["rich_message"])
            mapper.readTree(if (method == "editMessageText")
                """{"ok":false,"error_code":400,"description":"Bad Request: message to edit not found"}"""
                else """{"ok":true,"result":{"message_id":43}}""")
        }
        assertEquals(43, client.sync(1, 42, "<h3>Field</h3>"))
        assertEquals(listOf("editMessageText", "sendRichMessage"), calls)
    }
    @Test fun `unchanged edit is successful without creating another card`() {
        val client = RichMessageClient("unused") { method, _ ->
            assertEquals("editMessageText", method)
            mapper.readTree("""{"ok":false,"description":"Bad Request: message is not modified"}""")
        }
        assertEquals(42, client.sync(1, 42, "field"))
    }
    @Test fun `rate limiting does not create duplicate messages`() {
        val client = RichMessageClient("unused") { method, _ ->
            assertEquals("editMessageText", method)
            mapper.readTree("""{"ok":false,"error_code":429,"description":"Too Many Requests"}""")
        }
        assertFailsWith<IllegalStateException> { client.sync(1, 42, "field") }
    }
}
