package com.company.seabattle.bot

import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import java.io.ByteArrayInputStream

/**
 * Вспомогательные функции для работы с Telegram API.
 * Упрощают отправку и редактирование сообщений.
 */
object BotHelper {

    private const val PARSE_MODE = "Markdown"

    /** Отправить текстовое сообщение. */
    fun sendText(
        client: TelegramClient,
        chatId: Long,
        text: String,
        parseMode: String? = PARSE_MODE,
        replyMarkup: InlineKeyboardMarkup? = null,
        threadId: Int? = null
    ): Message {
        val msg = SendMessage(chatId.toString(), text)
        if (parseMode != null) msg.parseMode = parseMode
        if (replyMarkup != null) msg.replyMarkup = replyMarkup
        if (threadId != null) msg.messageThreadId = threadId
        val kbInfo = replyMarkup?.let { kb ->
            val rows = kb.keyboard
            "keyboard(rows=${rows.size}, buttonsPerRow=[${rows.joinToString(",") { it.size.toString() }}])"
        } ?: "no-keyboard"
        println("[API] sendText: chatId=$chatId, textLen=${text.length}, $kbInfo")
        val result = client.execute(msg)
        println("[API] sendText -> ok, messageId=${result.messageId}")
        return result
    }

    /** Отправить сообщение с inline-клавиатурой. Возвращает id отправленного сообщения. */
    fun sendWithKeyboard(
        client: TelegramClient,
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup,
        parseMode: String? = PARSE_MODE
    ): Long {
        return sendText(client, chatId, text, parseMode, keyboard).messageId.toLong()
    }

    fun sendCsv(client: TelegramClient, chatId: Long, filename: String, content: String, caption: String, threadId: Int? = null) {
        val file = InputFile(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), filename)
        val request = SendDocument(chatId.toString(), file)
        request.caption = caption
        if (threadId != null) request.messageThreadId = threadId
        client.execute(request)
    }

    /** Sends a bundled visual with a caption; callers can fall back to text if Telegram rejects it. */
    fun sendPhotoResource(client: TelegramClient, chatId: Long, resource: String, caption: String) {
        val bytes = requireNotNull(BotHelper::class.java.getResourceAsStream(resource)) { "Resource not found: $resource" }.use { it.readBytes() }
        val photo = SendPhoto(chatId.toString(), InputFile(ByteArrayInputStream(bytes), resource.substringAfterLast('/')))
        photo.caption = caption
        photo.parseMode = PARSE_MODE
        client.execute(photo)
    }

    /** Редактировать текст сообщения (и опционально клавиатуру). */
    fun editText(
        client: TelegramClient,
        chatId: Long,
        messageId: Long,
        text: String,
        parseMode: String? = PARSE_MODE,
        replyMarkup: InlineKeyboardMarkup? = null
    ) {
        val edit = EditMessageText(text)
        edit.chatId = chatId.toString()
        edit.messageId = messageId.toInt()
        if (parseMode != null) edit.parseMode = parseMode
        if (replyMarkup != null) edit.replyMarkup = replyMarkup
        val kbInfo = replyMarkup?.let { kb ->
            val rows = kb.keyboard
            "keyboard(rows=${rows.size}, buttonsPerRow=[${rows.joinToString(",") { it.size.toString() }}])"
        } ?: "no-keyboard"
        println("[API] editText: chatId=$chatId, messageId=$messageId, textLen=${text.length}, $kbInfo")
        try {
            client.execute(edit)
            println("[API] editText -> ok")
        } catch (e: Exception) {
            println("[API] editText -> ERROR: ${e.message}")
            // игнорируем ошибки редактирования (сообщение не изменилось и т.п.)
        }
    }

    /** Редактировать только клавиатуру сообщения. */
    fun editKeyboard(
        client: TelegramClient,
        chatId: Long,
        messageId: Long,
        keyboard: InlineKeyboardMarkup
    ) {
        val edit = EditMessageReplyMarkup()
        edit.chatId = chatId.toString()
        edit.messageId = messageId.toInt()
        edit.replyMarkup = keyboard
        val rows = keyboard.keyboard
        println("[API] editKeyboard: chatId=$chatId, messageId=$messageId, keyboard(rows=${rows.size}, buttonsPerRow=[${rows.joinToString(",") { it.size.toString() }}])")
        try {
            client.execute(edit)
            println("[API] editKeyboard -> ok")
        } catch (e: Exception) {
            println("[API] editKeyboard -> ERROR: ${e.message}")
            // игнорируем
        }
    }

    /** Ответить на callback query (убрать "часики" с кнопки). */
    fun answerCallback(client: TelegramClient, callbackQueryId: String, text: String? = null) {
        val answer = AnswerCallbackQuery(callbackQueryId)
        if (text != null) answer.text = text
        try {
            client.execute(answer)
        } catch (e: Exception) {
            // игнорируем
        }
    }

    /** Registers descriptions shown by Telegram next to the bot commands. */
    fun registerCommands(client: TelegramClient, commands: List<Pair<String, String>>) {
        client.execute(SetMyCommands(commands.map { (command, description) -> BotCommand(command, description) }))
    }

    /** Построить простую inline-клавиатуру из списка кнопок (по строкам). */
    fun keyboard(rows: List<List<Pair<String, String>>>): InlineKeyboardMarkup {
        val keyboardRows = rows.map { row ->
            InlineKeyboardRow(row.map { (label, callback) ->
                InlineKeyboardButton(label).apply { callbackData = callback }
            })
        }
        return InlineKeyboardMarkup(keyboardRows)
    }
}
