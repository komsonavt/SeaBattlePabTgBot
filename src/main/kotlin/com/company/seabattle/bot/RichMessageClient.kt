package com.company.seabattle.bot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Raw API adapter: the pinned SDK predates Rich Messages. Never logs token-bearing URLs. */
class RichMessageClient(private val token: String,
    private val transport: ((String, Map<String, Any>) -> com.fasterxml.jackson.databind.JsonNode)? = null) {
    private val mapper = jacksonObjectMapper()
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    fun sync(chatId: Long, messageId: Long, html: String): Long {
        if (messageId != 0L) {
            val response = request("editMessageText", mapOf("chat_id" to chatId, "message_id" to messageId,
                "rich_message" to mapOf("html" to html)))
            val description = response.path("description").asText()
            if (response.path("ok").asBoolean() || description.contains("message is not modified")) return messageId
            if (!description.contains("message to edit not found")) error("Telegram rich edit failed (${response.path("error_code").asInt()})")
        }
        val response = request("sendRichMessage", mapOf("chat_id" to chatId, "rich_message" to mapOf("html" to html)))
        check(response.path("ok").asBoolean()) { "Telegram rich send failed (${response.path("error_code").asInt()})" }
        return response.path("result").path("message_id").asLong().also { check(it > 0) }
    }
    private fun request(method: String, payload: Map<String, Any>): com.fasterxml.jackson.databind.JsonNode {
        transport?.let { return it(method, payload) }
        try {
            val request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot$token/$method"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build()
            return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            error("Telegram request interrupted")
        } catch (_: Exception) {
            error("Telegram connection or response error")
        }
    }
}
