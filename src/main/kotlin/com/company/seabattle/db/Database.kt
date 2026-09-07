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
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute(SCHEMA_SQL)
                    stmt.execute(MVP_SQL)
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
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
        private val MVP_SQL = """
            ALTER TABLE games ADD COLUMN IF NOT EXISTS rules_state TEXT NOT NULL DEFAULT '{}';
            ALTER TABLE games ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(32);
            ALTER TABLE games ADD COLUMN IF NOT EXISTS finished_at BIGINT;
            ALTER TABLE games ADD COLUMN IF NOT EXISTS rules_version INT NOT NULL DEFAULT 0;
            UPDATE games SET turn_deadline = CASE WHEN vs_computer THEN NULL
                ELSE (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::BIGINT + 180000 END,
                rules_state = '{}', ui_state = (ui_state::jsonb || '{"needsSync":true}'::jsonb)::text
                WHERE NOT finished AND rules_version = 0;
            UPDATE games SET rules_version = 1 WHERE rules_version = 0;
            ALTER TABLE games ALTER COLUMN rules_version SET DEFAULT 1;
            ALTER TABLE invites ADD COLUMN IF NOT EXISTS game_id VARCHAR(64);
            UPDATE invites SET game_id = md5(invite_id || random()::text) WHERE game_id IS NULL;
            ALTER TABLE invites ALTER COLUMN game_id SET NOT NULL;
            CREATE UNIQUE INDEX IF NOT EXISTS idx_invites_game ON invites(game_id);
            CREATE TABLE IF NOT EXISTS user_profiles (
                user_id BIGINT PRIMARY KEY, first_name TEXT NOT NULL, last_name TEXT,
                username TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            CREATE TABLE IF NOT EXISTS pending_joins (
                user_id BIGINT PRIMARY KEY, payload VARCHAR(64) NOT NULL
            );
            CREATE TABLE IF NOT EXISTS tournament_preregistrations (
                user_id BIGINT PRIMARY KEY, first_name TEXT NOT NULL, last_name TEXT,
                username TEXT, registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            CREATE TABLE IF NOT EXISTS access_requests (
                id VARCHAR(32) PRIMARY KEY, user_id BIGINT NOT NULL, name TEXT,
                activity TEXT, status VARCHAR(16) NOT NULL, payload VARCHAR(64),
                moderator_id BIGINT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), decided_at TIMESTAMPTZ
            );
            CREATE UNIQUE INDEX IF NOT EXISTS idx_access_request_open ON access_requests(user_id) WHERE status='DRAFT' OR status='PENDING';
            CREATE TABLE IF NOT EXISTS broadcast_drafts (
                id VARCHAR(32) PRIMARY KEY, author_id BIGINT NOT NULL, body TEXT NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            CREATE TABLE IF NOT EXISTS forum_topics (
                topic_key VARCHAR(32) PRIMARY KEY, message_thread_id INT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            CREATE TABLE IF NOT EXISTS bot_admins (
                user_id BIGINT PRIMARY KEY, added_by BIGINT, source VARCHAR(32) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            CREATE TABLE IF NOT EXISTS admin_invites (
                invite_id VARCHAR(32) PRIMARY KEY, creator_id BIGINT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), claimed_by BIGINT, claimed_at TIMESTAMPTZ
            );
        """.trimIndent()
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
            ALTER TABLE games ADD COLUMN IF NOT EXISTS ui_state TEXT NOT NULL DEFAULT '{}';
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
