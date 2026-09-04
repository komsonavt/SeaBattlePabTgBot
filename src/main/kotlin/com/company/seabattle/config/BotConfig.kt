package com.company.seabattle.config

/**
 * Конфигурация бота, читаемая из переменных окружения.
 *
 * Переменные:
 * - BOT_TOKEN          — токен бота от @BotFather
 * - BOT_USERNAME       — username бота без @
 * - CORPORATE_CHAT_ID  — id корпоративного чата, членство в котором проверяется
 * - ADMIN_IDS          — список id администраторов через запятую (опционально)
 * - GROUP_SIZE         — размер группы в турнире (по умолчанию 4)
 * - PLAYERS_PER_GROUP_ADVANCE — сколько игроков из группы проходит в плей-офф (по умолчанию 2)
 * - DB_URL             — JDBC URL PostgreSQL (jdbc:postgresql://host:port/dbname)
 * - DB_USER            — пользователь БД
 * - DB_PASSWORD        — пароль БД
 * - TURN_TIMEOUT_SECONDS — таймаут хода в секундах (по умолчанию 300 = 5 минут)
 */
data class BotConfig(
    val botToken: String,
    val botUsername: String,
    val corporateChatId: Long,
    val adminIds: Set<Long>,
    val groupSize: Int,
    val playersPerGroupAdvance: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val turnTimeoutSeconds: Long
) {
    companion object {
        fun fromEnv(): BotConfig {
            val token = env("BOT_TOKEN")
            val username = env("BOT_USERNAME")
            val chatId = env("CORPORATE_CHAT_ID").toLong()
            val admins = envOrNull("ADMIN_IDS")
                ?.split(",")
                ?.map { it.trim().toLong() }
                ?.toSet()
                ?: emptySet()
            val groupSize = envOrNull("GROUP_SIZE")?.toIntOrNull() ?: 4
            val advance = envOrNull("PLAYERS_PER_GROUP_ADVANCE")?.toIntOrNull() ?: 2
            val dbUrl = envOrNull("DB_URL") ?: "jdbc:postgresql://localhost:5432/seabattle"
            val dbUser = envOrNull("DB_USER") ?: "seabattle"
            val dbPassword = envOrNull("DB_PASSWORD") ?: "seabattle"
            val turnTimeout = envOrNull("TURN_TIMEOUT_SECONDS")?.toLongOrNull() ?: 300L
            return BotConfig(
                token, username, chatId, admins, groupSize, advance,
                dbUrl, dbUser, dbPassword, turnTimeout
            )
        }

        private fun env(name: String): String =
            System.getenv(name) ?: error("Не задана переменная окружения $name")

        private fun envOrNull(name: String): String? = System.getenv(name)
    }
}
