package com.company.seabattle.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Конфигурация бота, читаемая из переменных окружения.
 *
 * Переменные:
 * - BOT_TOKEN          — токен бота от @BotFather
 * - BOT_USERNAME       — username бота без @
 * - MODERATION_CHAT_ID — id супергруппы-форума, где бот создаёт рабочие темы
 * - ADMIN_IDS          — список id администраторов через запятую (опционально)
 * - GROUP_SIZE         — размер группы в турнире (по умолчанию 4)
 * - PLAYERS_PER_GROUP_ADVANCE — сколько игроков из группы проходит в плей-офф (по умолчанию 2)
 * - DB_URL             — JDBC URL PostgreSQL (jdbc:postgresql://host:port/dbname)
 * - DB_USER            — пользователь БД
 * - DB_PASSWORD        — пароль БД
 * - TURN_TIMEOUT_SECONDS — таймаут хода в секундах (фиксированно 180 в PvP)
 */
data class BotConfig(
    val botToken: String,
    val botUsername: String,
    val moderationChatId: Long?,
    val moderationTopicId: Int?,
    val exportsTopicId: Int?,
    val broadcastsTopicId: Int?,
    val adminIds: Set<Long>,
    val groupSize: Int,
    val playersPerGroupAdvance: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val turnTimeoutSeconds: Long,
    val tournamentDate: String = "20 сентября",
    val tournamentPrizes: String = "Победителя и призёров ждут призы.",
    val tournamentBracketEnabled: Boolean = false
) {
    init {
        require(groupSize >= 2) { "GROUP_SIZE must be at least 2" }
        require(playersPerGroupAdvance in 1..groupSize) { "PLAYERS_PER_GROUP_ADVANCE must be between 1 and GROUP_SIZE" }
        require(turnTimeoutSeconds in 1..604800) { "TURN_TIMEOUT_SECONDS must be between 1 and 604800" }
    }
    companion object {
        private val fileConfig: Properties by lazy {
            Properties().apply {
                val path = Path.of(System.getenv("BOT_CONFIG_FILE") ?: "bot.properties")
                if (Files.exists(path)) {
                    require(Files.isRegularFile(path)) {
                        "BOT_CONFIG_FILE должен указывать на файл настроек, а не каталог: $path"
                    }
                    Files.newBufferedReader(path, Charsets.UTF_8).use { load(it) }
                }
            }
        }
        fun fromEnv(): BotConfig {
            val token = env("BOT_TOKEN")
            val username = env("BOT_USERNAME")
            val moderationChatId = envOrNull("MODERATION_CHAT_ID")?.toLongOrNull()
            fun topic(name: String) = envOrNull(name)?.toIntOrNull()
            val admins = envOrNull("ADMIN_IDS")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { it.toLongOrNull() ?: error("Некорректный ID администратора в ADMIN_IDS: '$it'") }
                ?.toSet()
                ?: emptySet()
            val groupSize = envOrNull("GROUP_SIZE")?.toIntOrNull() ?: 4
            val advance = envOrNull("PLAYERS_PER_GROUP_ADVANCE")?.toIntOrNull() ?: 2
            val dbUrl = envOrNull("DB_URL") ?: "jdbc:postgresql://localhost:5432/seabattle"
            val dbUser = envOrNull("DB_USER") ?: "seabattle"
            val dbPassword = envOrNull("DB_PASSWORD") ?: "seabattle"
            val turnTimeout = 180L
            return BotConfig(
                token, username, moderationChatId, topic("MODERATION_TOPIC_ID"), topic("EXPORTS_TOPIC_ID"), topic("BROADCASTS_TOPIC_ID"), admins, groupSize, advance,
                dbUrl, dbUser, dbPassword, turnTimeout,
                envOrNull("TOURNAMENT_DATE") ?: "20 сентября",
                envOrNull("TOURNAMENT_PRIZES") ?: "Победителя и призёров ждут призы.",
                envOrNull("TOURNAMENT_BRACKET_ENABLED")?.toBooleanStrictOrNull() ?: false
            )
        }

        /**
         * Возвращает значение переменной окружения.
         * Пустая строка считается как «не задано» (Docker Compose передаёт пустую строку,
         * если переменная не определена в .env).
         */
        private fun env(name: String): String {
            val value = envOrNull(name)
            if (value.isNullOrEmpty()) {
                error("Не задана переменная окружения $name")
            }
            return value
        }

        /** Возвращает значение переменной окружения или null, если не задана/пустая. */
        private fun envOrNull(name: String): String? =
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
                ?: fileConfig.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
