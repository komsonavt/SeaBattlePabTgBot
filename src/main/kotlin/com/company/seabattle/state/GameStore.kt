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
    val community = CommunityStore(db)

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
                "SELECT id, player1_id, player2_id FROM games WHERE NOT finished OR ui_state::jsonb ->> 'needsSync' = 'true'"
            ).use { rs ->
                while (rs.next()) {
                    val id = rs.getString("id")
                    val p1 = rs.getLong("player1_id")
                    val p2 = rs.getLong("player2_id")
                    val session = loadSessionFromDb(conn, id, p1, p2)
                    if (session != null) {
                        session.recoverDeadline(System.currentTimeMillis())
                        if (!session.finished) saveSession(session, conn)
                        sessionsCache[id] = session
                        if (!session.finished) {
                            check(playerToSession.putIfAbsent(p1, id) == null) { "Multiple active games for player $p1" }
                            if (p2 != 0L) check(playerToSession.putIfAbsent(p2, id) == null) { "Multiple active games for player $p2" }
                        }
                    }
                }
            }
        }
        if (sessionsCache.isNotEmpty()) {
            println("Загружено ${sessionsCache.size} активных сессий из БД.")
        }
    }

    // ---- Сессии ----

    @Synchronized fun createVsComputerSession(playerId: Long): GameSession {
        require(getSessionByPlayer(playerId) == null) { "Finish the active game first" }
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
    @Synchronized fun createInvite(creatorId: Long): String {
        require(getSessionByPlayer(creatorId) == null) { "Finish the active game first" }
        cancelInvite(creatorId)
        val inviteId = newId()
        db.connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO invites (invite_id, creator_id, game_id) VALUES (?, ?, ?)"
            ).use { ps ->
                ps.setString(1, inviteId)
                ps.setLong(2, creatorId)
                ps.setString(3, inviteId)
                ps.executeUpdate()
            }
        }
        return "${creatorId}_$inviteId"
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
    @Synchronized
    fun acceptInvite(inviteId: String, accepterId: Long): GameSession? {
        if (getSessionByPlayer(accepterId) != null) return null
        val key = inviteId.substringAfter('_', inviteId)
        val claimedCreator = if ('_' in inviteId) inviteId.substringBefore('_').toLongOrNull() ?: return null else null
        return db.connection().use { conn ->
            conn.autoCommit = false
            try {
                val invite = conn.prepareStatement("SELECT creator_id, game_id FROM invites WHERE invite_id = ? FOR UPDATE").use { ps ->
                    ps.setString(1, key)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
                }
                val creator = invite?.first
                if (creator == null || creator == accepterId || claimedCreator != null && creator != claimedCreator || getSessionByPlayer(creator) != null) {
                    conn.rollback()
                    return null
                }
                val session = GameSession(invite.second, creator, accepterId, false, GameMode.VS_COLLEAGUE)
                session.updateTurnDeadline(System.currentTimeMillis() + GameSession.TURN_MILLIS)
                saveSession(session, conn)
                conn.prepareStatement("DELETE FROM invites WHERE creator_id IN (?, ?)").use { ps ->
                    ps.setLong(1, creator)
                    ps.setLong(2, accepterId)
                    ps.executeUpdate()
                }
                conn.commit()
                sessionsCache[session.id] = session
                playerToSession[creator] = session.id
                playerToSession[accepterId] = session.id
                session
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
    /** Создать турнирный матч между двумя игроками. */
    @Synchronized fun createTournamentSession(
        player1Id: Long,
        player2Id: Long,
        tournamentMatchId: String
    ): GameSession {
        require(getSessionByPlayer(player1Id) == null)
        require(getSessionByPlayer(player2Id) == null)
        val session = GameSession(
            id = newId(),
            player1Id = player1Id,
            player2Id = player2Id,
            vsComputer = false,
            mode = GameMode.TOURNAMENT,
            tournamentMatchId = tournamentMatchId
        )
        session.updateTurnDeadline(System.currentTimeMillis() + GameSession.TURN_MILLIS)
        saveSession(session)
        sessionsCache[session.id] = session
        playerToSession[player1Id] = session.id
        playerToSession[player2Id] = session.id
        return session
    }


    fun getSessionByPlayer(playerId: Long): GameSession? {
        val sid = playerToSession[playerId] ?: return null
        return sessionsCache[sid]
    }

    /** Сохранить текущее состояние сессии в БД (вызывать после каждого изменения). */
    fun persistSession(session: GameSession) {
        saveSession(session)
    }

    fun activeSessions(): List<GameSession> = sessionsCache.values.filter { !it.finished }

    fun sessionsToSync(): List<GameSession> = sessionsCache.values.toList()

    @Synchronized fun markSynced(session: GameSession) {
        session.ui.needsSync = false
        try { persistSession(session) }
        catch (e: Exception) { session.ui.needsSync = true; throw e }
        if (session.finished) sessionsCache.remove(session.id)
    }

    /** Roll back the cached object if the database transaction fails. */
    @Synchronized fun update(session: GameSession, action: () -> Unit) {
        val b1 = session.exportBoard1()
        val b2 = session.exportBoard2()
        val ai = session.exportAiState()
        val turn = session.turnIsPlayer1
        val finished = session.finished
        val winner = session.winnerId
        val deadline = session.turnDeadline
        val rules = session.rules.copy()
        val ui = session.ui.copy(player1 = session.ui.player1.copy(), player2 = session.ui.player2.copy())
        try {
            action()
            session.ui.needsSync = true
            if (session.finished) completeSession(session) else persistSession(session)
        } catch (e: Exception) {
            session.restoreState(b1, b2, ai, turn, finished, winner,
                session.enemyKeyboardMessageId1, session.enemyKeyboardMessageId2, deadline)
            session.ui = ui
            session.rules = rules
            throw e
        }
    }

    /** Commit the game and tournament result together before sending notifications. */
    fun completeSession(session: GameSession) {
        db.connection().use { conn ->
            conn.autoCommit = false
            try {
                if (session.mode == GameMode.TOURNAMENT) {
                    val tournament = tournamentForMatch(requireNotNull(session.tournamentMatchId)) ?: error("Tournament missing")
                    tournament.recordResult(requireNotNull(session.tournamentMatchId), session.winnerId, conn)
                }
                saveSession(session, conn)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
        // Keep the finished session until both result cards have been synchronized.
        // `markSynced` removes it afterwards; this lets a restart repair a partial Telegram failure.
        playerToSession.remove(session.player1Id, session.id)
        if (!session.vsComputer) playerToSession.remove(session.player2Id, session.id)
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

    fun tournamentForMatch(matchId: String): Tournament? {
        val id = db.connection().use { conn ->
            conn.prepareStatement("SELECT tournament_id FROM tournament_matches WHERE id = ?").use { ps ->
                ps.setString(1, matchId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        return id?.let { getTournament(it) }
    }

    private fun saveSession(session: GameSession) {
        db.connection().use { saveSession(session, it) }
    }

    private fun saveSession(session: GameSession, conn: java.sql.Connection) {
        val board1Json = mapper.writeValueAsString(session.exportBoard1())
        val board2Json = mapper.writeValueAsString(session.exportBoard2())
        val aiJson = session.exportAiState()?.let { mapper.writeValueAsString(it) }

        run {
            conn.prepareStatement(
                """
                INSERT INTO games (id, player1_id, player2_id, vs_computer, mode, tournament_match_id,
                                   board1, board2, ai_state, turn_is_player1, finished, winner_id,
                                   enemy_keyboard_message_id1, enemy_keyboard_message_id2, turn_deadline, ui_state, rules_state, finish_reason, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    board1 = EXCLUDED.board1,
                    board2 = EXCLUDED.board2,
                    ai_state = EXCLUDED.ai_state,
                    turn_is_player1 = EXCLUDED.turn_is_player1,
                    finished = EXCLUDED.finished,
                    winner_id = EXCLUDED.winner_id,
                    enemy_keyboard_message_id1 = EXCLUDED.enemy_keyboard_message_id1,
                    enemy_keyboard_message_id2 = EXCLUDED.enemy_keyboard_message_id2,
                    turn_deadline = EXCLUDED.turn_deadline, ui_state = EXCLUDED.ui_state,
                    rules_state=EXCLUDED.rules_state, finish_reason=EXCLUDED.finish_reason, finished_at=EXCLUDED.finished_at
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
                ps.setString(16, mapper.writeValueAsString(session.ui))
                ps.setString(17, mapper.writeValueAsString(session.rules))
                ps.setString(18, session.rules.finishReason)
                if(session.rules.finishedAt == null) ps.setNull(19,Types.BIGINT) else ps.setLong(19,session.rules.finishedAt!!)
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
                   enemy_keyboard_message_id1, enemy_keyboard_message_id2, turn_deadline, ui_state, rules_state
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
                session.ui = mapper.readValue(rs.getString("ui_state"), GameUi::class.java)
                session.rules = mapper.readValue(rs.getString("rules_state"), GameRules::class.java)
                return session
            }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun inviteCreator(payload: String): Long? {
        val key = payload.substringAfter('_',payload)
        val claimed = if('_' in payload) payload.substringBefore('_').toLongOrNull() ?: return null else null
        return db.connection().use { conn -> conn.prepareStatement("SELECT creator_id FROM invites WHERE invite_id=?").use { ps ->
            ps.setString(1,key)
            ps.executeQuery().use { rs -> if(rs.next()) rs.getLong(1).takeIf { claimed==null || it==claimed } else null }
        } }
    }

    /** UI-only write: an HTTP response must never overwrite boards or the turn. Caller holds this store's monitor. */
    @Synchronized fun saveCardId(session: GameSession, playerId: Long, own: Boolean, messageId: Long) {
        val view = session.uiFor(playerId)
        val previous = if(own) view.ownMessageId else view.enemyMessageId
        if(own) view.ownMessageId=messageId else view.enemyMessageId=messageId
        try { saveUi(session) } catch(e: Exception) {
            if(own) view.ownMessageId=previous else view.enemyMessageId=previous
            throw e
        }
    }
    @Synchronized fun markSyncedIfCurrent(session: GameSession, revision1: Long, revision2: Long) {
        if(session.ui.player1.revision!=revision1 || session.ui.player2.revision!=revision2) return
        session.ui.needsSync=false
        try { saveUi(session) } catch(e: Exception) { session.ui.needsSync=true; throw e }
        if(session.finished) sessionsCache.remove(session.id)
    }
    private fun saveUi(session: GameSession) {
        db.connection().use { conn -> conn.prepareStatement("UPDATE games SET ui_state=? WHERE id=?").use { ps ->
            ps.setString(1,mapper.writeValueAsString(session.ui)); ps.setString(2,session.id); ps.executeUpdate()
        } }
    }
}
