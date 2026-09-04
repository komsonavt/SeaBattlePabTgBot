package com.company.seabattle.state

import com.company.seabattle.db.Database
import com.company.seabattle.game.AiState
import com.company.seabattle.game.BoardState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Types
import java.util.UUID

/**
 * Центральное хранилище состояния бота на PostgreSQL.
 *
 * Хранит:
 * - активные игровые сессии (по id и по id игрока)
 * - ожидающие приглашения (id игры -> создатель)
 * - турниры
 *
 * Все данные персистентны — переживают перезапуск бота.
 * Активные сессии кэшируются в памяти для производительности
 * и синхронизируются с БД при каждом изменении.
 */
class GameStore(private val db: Database) {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    /** Кэш активных сессий в памяти (для быстрого доступа). */
    private val sessionsCache = java.util.concurrent.ConcurrentHashMap<String, GameSession>()

    /** Id активной партии для каждого игрока. */
    private val playerToSession = java.util.concurrent.ConcurrentHashMap<Long, String>()

    init {
        loadActiveSessionsIntoCache()
    }

    /** Загрузить все незавершённые сессии из БД в кэш при старте. */
    private fun loadActiveSessionsIntoCache() {
        db.connection().use { conn ->
            conn.createStatement().executeQuery(
                "SELECT id, player1_id, player2_id FROM games WHERE NOT finished"
            ).use { rs ->
                while (rs.next()) {
                    val id = rs.getString("id")
                    val p1 = rs.getLong("player1_id")
                    val p2 = rs.getLong("player2_id")
                    val session = loadSessionFromDb(conn, id, p1, p2)
                    if (session != null) {
                        sessionsCache[id] = session
                        playerToSession[p1] = id
                        if (p2 != 0L) playerToSession[p2] = id
                    }
                }
            }
        }
        if (sessionsCache.isNotEmpty()) {
            println("Загружено ${sessionsCache.size} активных сессий из БД.")
        }
    }

    // ---- Сессии ----

    fun createVsComputerSession(playerId: Long): GameSession {
        cancelActiveSession(playerId)
        val session = GameSession(
            id = newId(),
            player1Id = playerId,
            player2Id = 0L,
            vsComputer = true,
            mode = GameMode.VS_COMPUTER
        )
        saveSession(session)
        sessionsCache[session.id] = session
        playerToSession[playerId] = session.id
        return session
    }

    /**
     * Создать приглашение на игру с коллегой.
     * Возвращает inviteId, который кодируется в deep-link.
     */
    fun createInvite(creatorId: Long): String {
        cancelActiveSession(creatorId)
        val inviteId = newId().take(8)
        db.connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO invites (invite_id, creator_id) VALUES (?, ?)"
            ).use { ps ->
                ps.setString(1, inviteId)
                ps.setLong(2, creatorId)
                ps.executeUpdate()
            }
        }
        return inviteId
    }

    /** Отменить приглашение (если есть). */
    fun cancelInvite(creatorId: Long) {
        db.connection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM invites WHERE creator_id = ?"
            ).use { ps ->
                ps.setLong(1, creatorId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Принять приглашение. Создаёт партию между создателем и принявшим.
     * Возвращает сессию или null, если приглашение не найдено / создатель занят.
     */
    fun acceptInvite(inviteId: String, accepterId: Long): GameSession? {
        var creatorId: Long? = null
        db.connection().use { conn ->
            // Атомарно удаляем приглашение и получаем creator_id
            conn.prepareStatement(
                "DELETE FROM invites WHERE invite_id = ? RETURNING creator_id"
            ).use { ps ->
                ps.setString(1, inviteId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        creatorId = rs.getLong("creator_id")
                    }
                }
            }
        }
        val creator = creatorId ?: return null
        if (creator == accepterId) return null
        cancelActiveSession(accepterId)
        val session = GameSession(
            id = newId(),
            player1Id = creator,
            player2Id = accepterId,
            vsComputer = false,
            mode = GameMode.VS_COLLEAGUE
        )
        saveSession(session)
        sessionsCache[session.id] = session
        playerToSession[creator] = session.id
        playerToSession[accepterId] = session.id
        return session
    }

    /** Создать турнирный матч между двумя игроками. */
    fun createTournamentSession(
        player1Id: Long,
        player2Id: Long,
        tournamentMatchId: String
    ): GameSession {
        cancelActiveSession(player1Id)
        cancelActiveSession(player2Id)
        val session = GameSession(
            id = newId(),
            player1Id = player1Id,
            player2Id = player2Id,
            vsComputer = false,
            mode = GameMode.TOURNAMENT,
            tournamentMatchId = tournamentMatchId
        )
        saveSession(session)
        sessionsCache[session.id] = session
        playerToSession[player1Id] = session.id
        playerToSession[player2Id] = session.id
        return session
    }

    fun getSession(id: String): GameSession? = sessionsCache[id]

    fun getSessionByPlayer(playerId: Long): GameSession? {
        val sid = playerToSession[playerId] ?: return null
        return sessionsCache[sid]
    }

    /** Сохранить текущее состояние сессии в БД (вызывать после каждого изменения). */
    fun persistSession(session: GameSession) {
        saveSession(session)
    }

    fun removeSession(id: String) {
        val session = sessionsCache.remove(id) ?: return
        playerToSession.remove(session.player1Id)
        if (!session.vsComputer) playerToSession.remove(session.player2Id)
        // Помечаем как завершённую в БД (оставляем для истории)
        db.connection().use { conn ->
            conn.prepareStatement(
                "UPDATE games SET finished = TRUE, turn_deadline = NULL WHERE id = ?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    fun cancelActiveSession(playerId: Long) {
        val session = getSessionByPlayer(playerId) ?: return
        removeSession(session.id)
    }

    // ---- Турниры ----

    fun createTournament(id: String, groupSize: Int, advance: Int): Tournament {
        db.connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO tournaments (id, phase, group_size, players_per_group_advance) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, TournamentPhase.REGISTRATION.name)
                ps.setInt(3, groupSize)
                ps.setInt(4, advance)
                ps.executeUpdate()
            }
        }
        return Tournament(id, groupSize, advance, db)
    }

    fun getTournament(id: String): Tournament? {
        var tournament: Tournament? = null
        db.connection().use { conn ->
            conn.prepareStatement(
                "SELECT id, phase, group_size, players_per_group_advance FROM tournaments WHERE id = ?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val tid = rs.getString("id")
                        val phase = TournamentPhase.valueOf(rs.getString("phase"))
                        val gs = rs.getInt("group_size")
                        val adv = rs.getInt("players_per_group_advance")
                        tournament = Tournament(tid, gs, adv, db)
                        tournament!!.setPhase(phase)
                        tournament!!.loadFromDb(conn)
                    }
                }
            }
        }
        return tournament
    }

    fun activeTournament(): Tournament? {
        var activeId: String? = null
        db.connection().use { conn ->
            conn.createStatement().executeQuery(
                "SELECT id FROM tournaments WHERE phase != 'FINISHED' ORDER BY created_at DESC LIMIT 1"
            ).use { rs ->
                if (rs.next()) {
                    activeId = rs.getString("id")
                }
            }
        }
        return activeId?.let { getTournament(it) }
    }

    // ---- Внутренние методы БД ----

    private fun saveSession(session: GameSession) {
        val board1Json = mapper.writeValueAsString(session.exportBoard1())
        val board2Json = mapper.writeValueAsString(session.exportBoard2())
        val aiJson = session.exportAiState()?.let { mapper.writeValueAsString(it) }

        db.connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO games (id, player1_id, player2_id, vs_computer, mode, tournament_match_id,
                                   board1, board2, ai_state, turn_is_player1, finished, winner_id,
                                   enemy_keyboard_message_id1, enemy_keyboard_message_id2, turn_deadline)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    board1 = EXCLUDED.board1,
                    board2 = EXCLUDED.board2,
                    ai_state = EXCLUDED.ai_state,
                    turn_is_player1 = EXCLUDED.turn_is_player1,
                    finished = EXCLUDED.finished,
                    winner_id = EXCLUDED.winner_id,
                    enemy_keyboard_message_id1 = EXCLUDED.enemy_keyboard_message_id1,
                    enemy_keyboard_message_id2 = EXCLUDED.enemy_keyboard_message_id2,
                    turn_deadline = EXCLUDED.turn_deadline
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, session.id)
                ps.setLong(2, session.player1Id)
                ps.setLong(3, session.player2Id)
                ps.setBoolean(4, session.vsComputer)
                ps.setString(5, session.mode.name)
                if (session.tournamentMatchId != null) {
                    ps.setString(6, session.tournamentMatchId)
                } else {
                    ps.setNull(6, Types.VARCHAR)
                }
                ps.setString(7, board1Json)
                ps.setString(8, board2Json)
                if (aiJson != null) ps.setString(9, aiJson) else ps.setNull(9, Types.VARCHAR)
                ps.setBoolean(10, session.turnIsPlayer1)
                ps.setBoolean(11, session.finished)
                ps.setLong(12, session.winnerId)
                ps.setLong(13, session.enemyKeyboardMessageId1)
                ps.setLong(14, session.enemyKeyboardMessageId2)
                if (session.turnDeadline > 0) {
                    ps.setLong(15, session.turnDeadline)
                } else {
                    ps.setNull(15, Types.BIGINT)
                }
                ps.executeUpdate()
            }
        }
    }

    private fun loadSessionFromDb(
        conn: java.sql.Connection,
        id: String,
        player1Id: Long,
        player2Id: Long
    ): GameSession? {
        conn.prepareStatement(
            """
            SELECT vs_computer, mode, tournament_match_id, board1, board2, ai_state,
                   turn_is_player1, finished, winner_id,
                   enemy_keyboard_message_id1, enemy_keyboard_message_id2, turn_deadline
            FROM games WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val vsComputer = rs.getBoolean("vs_computer")
                val mode = GameMode.fromString(rs.getString("mode"))
                val tournamentMatchId = rs.getString("tournament_match_id")
                val board1Json = rs.getString("board1")
                val board2Json = rs.getString("board2")
                val aiJson = rs.getString("ai_state")
                val turnIsPlayer1 = rs.getBoolean("turn_is_player1")
                val finished = rs.getBoolean("finished")
                val winnerId = rs.getLong("winner_id")
                val msgId1 = rs.getLong("enemy_keyboard_message_id1")
                val msgId2 = rs.getLong("enemy_keyboard_message_id2")
                val turnDeadline = rs.getLong("turn_deadline")
                val realDeadline = if (rs.wasNull()) 0L else turnDeadline

                val session = GameSession(id, player1Id, player2Id, vsComputer, mode, tournamentMatchId)
                val board1State = mapper.readValue(board1Json, BoardState::class.java)
                val board2State = mapper.readValue(board2Json, BoardState::class.java)
                val aiState = aiJson?.let { mapper.readValue(it, AiState::class.java) }
                session.restoreState(
                    board1State, board2State, aiState,
                    turnIsPlayer1, finished, winnerId,
                    msgId1, msgId2, realDeadline
                )
                return session
            }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
