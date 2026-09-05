package com.company.seabattle.access

import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import java.util.concurrent.ConcurrentHashMap
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted

enum class AccessResult { ALLOWED, NOT_MEMBER, UNAVAILABLE }

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
    private data class CacheEntry(val result: AccessResult, val timestamp: Long)

    private val cache = ConcurrentHashMap<Long, CacheEntry>()

    /**
     * Проверить, имеет ли пользователь доступ.
     *
     * Администраторы бота (из [adminIds]) всегда проходят проверку.
     * Остальные — только если состоят в корпоративном чате.
     */
    fun isAllowed(userId: Long): Boolean {
        return check(userId) == AccessResult.ALLOWED
    }

    fun check(userId: Long, fresh: Boolean = false): AccessResult {
        if (userId in adminIds) return AccessResult.ALLOWED
        val entry = cache[userId]
        val now = System.currentTimeMillis()
        if (!fresh && entry != null && now - entry.timestamp < CACHE_TTL_MS) {
            return entry.result
        }
        val result = checkMembership(userId)
        if(result != AccessResult.UNAVAILABLE) cache[userId] = CacheEntry(result, now)
        return result
    }

    private fun checkMembership(userId: Long): AccessResult {
        return try {
            val member = client.execute(
                GetChatMember(corporateChatId.toString(), userId)
            )
            membership(member.status, (member as? ChatMemberRestricted)?.isMember ?: false)
        } catch (e: Exception) {
            // Если не удалось проверить (бот не админ, нет сети и т.п.) — закрываем доступ.
            AccessResult.UNAVAILABLE
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
        fun membership(status: String, restrictedMember: Boolean = false): AccessResult = when(status) {
            "creator", "administrator", "member" -> AccessResult.ALLOWED
            "restricted" -> if(restrictedMember) AccessResult.ALLOWED else AccessResult.NOT_MEMBER
            "left", "kicked" -> AccessResult.NOT_MEMBER
            else -> AccessResult.UNAVAILABLE
        }
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 минут
    }
}
