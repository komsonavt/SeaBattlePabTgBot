package com.company.seabattle.access

import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import java.util.concurrent.ConcurrentHashMap

/**
 * Контроль доступа: проверяет, состоит ли пользователь в корпоративном чате.
 *
 * Бот должен быть добавлен в корпоративный чат с правами администратора,
 * иначе Telegram API не позволит проверить членство произвольного пользователя.
 *
 * Результаты кэшируются на [CACHE_TTL_MS], чтобы не дёргать API на каждое сообщение.
 */
class AccessGuard(
    private val client: TelegramClient,
    private val corporateChatId: Long,
    private val adminIds: Set<Long> = emptySet()
) {
    private data class CacheEntry(val allowed: Boolean, val timestamp: Long)

    private val cache = ConcurrentHashMap<Long, CacheEntry>()

    /**
     * Проверить, имеет ли пользователь доступ.
     *
     * Администраторы бота (из [adminIds]) всегда проходят проверку.
     * Остальные — только если состоят в корпоративном чате.
     */
    fun isAllowed(userId: Long): Boolean {
        if (userId in adminIds) return true
        val entry = cache[userId]
        val now = System.currentTimeMillis()
        if (entry != null && now - entry.timestamp < CACHE_TTL_MS) {
            return entry.allowed
        }
        val allowed = checkMembership(userId)
        cache[userId] = CacheEntry(allowed, now)
        return allowed
    }

    private fun checkMembership(userId: Long): Boolean {
        return try {
            val member = client.execute(
                GetChatMember(corporateChatId.toString(), userId)
            )
            val status = member.status
            // "left" и "kicked" означают, что пользователь не в чате.
            // Все остальные статусы (member, creator, administrator, restricted) — в чате.
            status != "left" && status != "kicked"
        } catch (e: Exception) {
            // Если не удалось проверить (бот не админ, нет сети и т.п.) — закрываем доступ.
            false
        }
    }

    /** Сбросить кэш для пользователя (например, после изменения состава чата). */
    fun invalidate(userId: Long) {
        cache.remove(userId)
    }

    /** Полная очистка кэша. */
    fun clearCache() {
        cache.clear()
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 минут
    }
}
