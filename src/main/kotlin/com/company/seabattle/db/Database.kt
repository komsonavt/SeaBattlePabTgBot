package com.company.seabattle.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * Управление пулом соединений PostgreSQL (HikariCP) и инициализация схемы БД.
 *
 * Все данные бота (игры, приглашения, турниры) хранятся в PostgreSQL,
 * что обеспечивает персистентность при перезапуске.
 *
 * @param jdbcUrl URL подключения к БД (jdbc:postgresql://host:port/dbname)
 * @param username имя пользователя БД
 * @param password пароль БД
 */
class Database(
    jdbcUrl: String,
    username: String,
    password: String
) : AutoCloseable {

    val dataSource: DataSource = createPool(jdbcUrl, username, password)

    /** Получить соединение из пула. Вызывающий отвечает за закрытие. */
    fun connection(): Connection = dataSource.connection

    private fun createPool(url: String, user: String, pass: String): HikariDataSource {
        val cfg = HikariConfig()
        cfg.jdbcUrl = url
        cfg.username = user
        cfg.password = pass
        cfg.driverClassName = "org.postgresql.Driver"
        cfg.maximumPoolSize = 10
        cfg.minimumIdle = 2
        cfg.connectionTimeout = 30_000
        cfg.idleTimeout = 600_000
        cfg.maxLifetime = 1_800_000
        cfg.poolName = "SeaBattlePool"
        cfg.validate()
        return HikariDataSource(cfg)
    }

    /** Создать таблицы, если их ещё нет. Безопасно вызывать при каждом старте. */
    fun initSchema() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(SCHEMA_SQL)
            }
        }
        println("Схема БД инициализирована.")
    }

    override fun close() {
        if (dataSource is HikariDataSource) {
            dataSource.close()
        }
    }

    companion object {
        private val SCHEMA_SQL = """
            -- Игровые сессии (все режимы)
            CREATE TABLE IF NOT EXISTS games (
                id                          VARCHAR(64) PRIMARY KEY,
                player1_id                  BIGINT      NOT NULL,
                player2_id                  BIGINT      NOT NULL,
                vs_computer                 BOOLEAN     NOT NULL,
                mode                        VARCHAR(20) NOT NULL,
                tournament_match_id         VARCHAR(64),
                board1                      TEXT        NOT NULL,
                board2                      TEXT        NOT NULL,
                ai_state                    TEXT,
                turn_is_player1             BOOLEAN     NOT NULL DEFAULT TRUE,
                finished                    BOOLEAN     NOT NULL DEFAULT FALSE,
                winner_id                   BIGINT      NOT NULL DEFAULT 0,
                enemy_keyboard_message_id1  BIGINT      NOT NULL DEFAULT 0,
                enemy_keyboard_message_id2  BIGINT      NOT NULL DEFAULT 0,
                turn_deadline               BIGINT,
                created_at                  TIMESTAMP   NOT NULL DEFAULT NOW()
            );

            -- Индекс для быстрого поиска активной игры игрока
            CREATE INDEX IF NOT EXISTS idx_games_player1 ON games(player1_id) WHERE NOT finished;
            CREATE INDEX IF NOT EXISTS idx_games_player2 ON games(player2_id) WHERE NOT finished;

            -- Ожидающие приглашения (режим «против коллеги»)
            CREATE TABLE IF NOT EXISTS invites (
                invite_id   VARCHAR(64) PRIMARY KEY,
                creator_id  BIGINT      NOT NULL,
                created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
            );

            -- Турниры
            CREATE TABLE IF NOT EXISTS tournaments (
                id                          VARCHAR(64) PRIMARY KEY,
                phase                       VARCHAR(20) NOT NULL,
                group_size                  INT         NOT NULL,
                players_per_group_advance   INT         NOT NULL,
                created_at                  TIMESTAMP   NOT NULL DEFAULT NOW()
            );

            -- Участники турниров
            CREATE TABLE IF NOT EXISTS tournament_participants (
                tournament_id   VARCHAR(64) NOT NULL,
                user_id         BIGINT      NOT NULL,
                display_name    VARCHAR(255) NOT NULL,
                PRIMARY KEY (tournament_id, user_id)
            );

            -- Группы турнира
            CREATE TABLE IF NOT EXISTS tournament_groups (
                id              VARCHAR(64) PRIMARY KEY,
                tournament_id   VARCHAR(64) NOT NULL,
                name            VARCHAR(100) NOT NULL
            );

            -- Игроки в группах
            CREATE TABLE IF NOT EXISTS tournament_group_players (
                group_id    VARCHAR(64) NOT NULL,
                user_id     BIGINT      NOT NULL,
                PRIMARY KEY (group_id, user_id)
            );

            -- Матчи турнира (групповые и плей-офф)
            CREATE TABLE IF NOT EXISTS tournament_matches (
                id              VARCHAR(64) PRIMARY KEY,
                tournament_id   VARCHAR(64) NOT NULL,
                player1_id      BIGINT      NOT NULL,
                player2_id      BIGINT      NOT NULL,
                winner_id       BIGINT      NOT NULL DEFAULT 0,
                played          BOOLEAN     NOT NULL DEFAULT FALSE,
                round           INT         NOT NULL DEFAULT 1,
                group_id        VARCHAR(64),
                stage           VARCHAR(10) NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_matches_tournament ON tournament_matches(tournament_id);
            CREATE INDEX IF NOT EXISTS idx_matches_group ON tournament_matches(group_id);
        """.trimIndent()
    }
}
