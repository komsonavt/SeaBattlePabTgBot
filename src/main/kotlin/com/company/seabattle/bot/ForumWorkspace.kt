package com.company.seabattle.bot

import com.company.seabattle.config.BotConfig
import com.company.seabattle.copy.Copy
import com.company.seabattle.state.CommunityStore
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic
import org.telegram.telegrambots.meta.api.objects.forum.ForumTopic
import org.telegram.telegrambots.meta.generics.TelegramClient

data class ForumTopics(val moderation: Int, val exports: Int, val broadcasts: Int, val created: Set<String>)

/** Creates the bot's working topics once and retains their Telegram IDs in PostgreSQL. */
object ForumWorkspace {
    fun ensure(client: TelegramClient, chatId: Long, store: CommunityStore): ForumTopics {
        val createdKeys = mutableSetOf<String>()
        fun topic(key: String, title: String, fallback: Int?): Int {
            store.forumTopic(key)?.let { return it }
            fallback?.let { store.saveForumTopic(key, it); return it }
            val created: ForumTopic = client.execute(CreateForumTopic(chatId.toString(), title))
            return created.messageThreadId.also { store.saveForumTopic(key, it); createdKeys.add(key) }
        }
        return ForumTopics(
            topic("moderation", "🛟 Заявки в игру", null),
            topic("exports", "📊 Выгрузки и статистика", null),
            topic("broadcasts", "📣 Рассылки", null), createdKeys
        )
    }
}
