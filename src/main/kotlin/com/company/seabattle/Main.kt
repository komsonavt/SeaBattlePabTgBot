package com.company.seabattle

import com.company.seabattle.bot.SeaBattleBot
import com.company.seabattle.config.BotConfig
import com.company.seabattle.db.Database
import com.company.seabattle.state.GameStore
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication

/**
 * Точка входа бота «Морской бой».
 *
 * Читает конфигурацию из переменных окружения, инициализирует PostgreSQL
 * и запускает long-polling бота.
 *
 * Требуемые переменные окружения:
 * - BOT_TOKEN          — токен бота от @BotFather
 * - BOT_USERNAME       — username бота без @
 * - ALLOWED_CHANNEL_ID — единственный приватный канал сотрудников для проверки доступа
 * - GROUP_SIZE         — размер группы в турнире (опционально, по умолчанию 4)
 * - PLAYERS_PER_GROUP_ADVANCE — сколько проходит в плей-офф (опционально, по умолчанию 2)
 * - DB_URL             — JDBC URL PostgreSQL (опционально, по умолчанию localhost:5432/seabattle)
 * - DB_USER            — пользователь БД (опционально, по умолчанию seabattle)
 * - DB_PASSWORD        — пароль БД (опционально, по умолчанию seabattle)
 * - TURN_TIMEOUT_SECONDS — таймаут хода в секундах (фиксированно 180 в PvP)
 */
fun main() {
    val config = BotConfig.fromEnv()

    // Инициализация БД
    val database = Database(config.dbUrl, config.dbUser, config.dbPassword)
    database.use { db ->
        db.initSchema()

        val store = GameStore(db)
        SeaBattleBot(config, store).use { bot ->
            println("Запуск бота @${config.botUsername}...")
            println("БД: ${config.dbUrl}")
            println("Таймаут хода: ${config.turnTimeoutSeconds} сек")
            TelegramBotsLongPollingApplication().use { app ->
                app.registerBot(config.botToken, bot)
                println("Бот запущен. Нажмите Ctrl+C для остановки.")
                Thread.currentThread().join()
            }
        }
    }
}
