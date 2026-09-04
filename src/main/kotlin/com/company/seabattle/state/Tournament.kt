package com.company.seabattle.state

import com.company.seabattle.db.Database
import java.sql.Connection
import java.sql.Types

/** Фазы турнира. */
enum class TournamentPhase {
    /** Сбор участников (лидов). */
    REGISTRATION,

    /** Участники распределены по группам, идут матчи. */
    GROUP_STAGE,

    /** Плей-офф на выбывание. */
    PLAYOFF,

    /** Турнир завершён. */
    FINISHED;

    companion object {
        fun fromString(value: String): TournamentPhase =
            entries.firstOrNull { it.name == value } ?: REGISTRATION
    }
}

/**
 * Турнирный матч (в группе или плей-офф).
 *
 * @param id id матча
 * @param player1Id id первого игрока
 * @param player2Id id второго игрока
 * @param winnerId id победителя (0, пока не сыгран)
 * @param played сыгран ли матч
 * @param round раунд (для плей-офф: 1 = финал, 2 = полуфинал и т.д.)
 * @param groupId id группы (для группового этапа)
 * @param stage GROUP или PLAYOFF
 */
data class TournamentMatch(
    val id: String,
    val player1Id: Long,
    val player2Id: Long,
    var winnerId: Long = 0L,
    var played: Boolean = false,
    val round: Int = 1,
    val groupId: String? = null,
    val stage: MatchStage
)

enum class MatchStage { GROUP, PLAYOFF }

/**
 * Группа в групповом этапе.
 */
data class TournamentGroup(
    val id: String,
    val name: String,
    val playerIds: MutableList<Long> = mutableListOf(),
    val matches: MutableList<TournamentMatch> = mutableListOf()
) {
    /** Очки игрока: победа = 1, поражение = 0. */
    fun scores(): Map<Long, Int> {
        val s = mutableMapOf<Long, Int>()
        playerIds.forEach { s[it] = 0 }
        matches.filter { it.played }.forEach { m ->
            s[m.winnerId] = (s[m.winnerId] ?: 0) + 1
        }
        return s
    }

    /** Рейтинг игроков группы (по убыванию очков). */
    fun ranking(): List<Pair<Long, Int>> =
        scores().toList().sortedByDescending { it.second }
}

/**
 * Турнир: регистрация → группы → плей-офф.
 *
 * Все данные хранятся в PostgreSQL. Кэш в памяти синхронизируется с БД
 * при каждом изменении.
 *
 * @param id идентификатор турнира
 * @param groupSize размер группы
 * @param playersPerGroupAdvance сколько игроков из группы проходит в плей-офф
 * @param db подключение к БД
 */
class Tournament(
    val id: String,
    val groupSize: Int,
    val playersPerGroupAdvance: Int,
    private val db: Database
) {
    var phase: TournamentPhase = TournamentPhase.REGISTRATION
        private set

    /** Зарегистрированные участники (id -> отображаемое имя). */
    val participants = mutableMapOf<Long, String>()

    /** Группы группового этапа. */
    val groups = mutableListOf<TournamentGroup>()

    /** Матчи плей-офф. */
    val playoffMatches = mutableListOf<TournamentMatch>()

    /** Id текущего активного матча для игрока (чтобы знать, какой матч стартовать). */
    val playerActiveMatch = mutableMapOf<Long, String>()

    /** Установить фазу (используется при загрузке из БД). */
    internal fun setPhase(p: TournamentPhase) {
        phase = p
    }

    fun register(userId: Long, displayName: String) {
        participants.putIfAbsent(userId, displayName)
        db.connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO tournament_participants (tournament_id, user_id, display_name) VALUES (?, ?, ?) " +
                    "ON CONFLICT (tournament_id, user_id) DO NOTHING"
            ).use { ps ->
                ps.setString(1, id)
                ps.setLong(2, userId)
                ps.setString(3, displayName)
                ps.executeUpdate()
            }
        }
    }

    fun isRegistered(userId: Long): Boolean = participants.containsKey(userId)

    fun participantCount(): Int = participants.size

    /**
     * Распределить участников по группам и сгенерировать матчи группового этапа
     * (каждый с каждым в группе).
     *
     * @return true, если распределение выполнено
     */
    fun startGroupStage(): Boolean {
        if (phase != TournamentPhase.REGISTRATION) return false
        if (participants.size < 2) return false
        val players = participants.keys.shuffled().toMutableList()
        val numGroups = (players.size + groupSize - 1) / groupSize
        groups.clear()
        db.connection().use { conn ->
            conn.autoCommit = false
            try {
                for (g in 0 until numGroups) {
                    val groupId = "${id}_g$g"
                    val groupName = "Группа ${'А' + g}"
                    val group = TournamentGroup(id = groupId, name = groupName)
                    // распределяем по одному в каждую группу (змейкой для баланса)
                    for (i in players.indices) {
                        if (i % numGroups == g) {
                            group.playerIds.add(players[i])
                        }
                    }
                    // сохраняем группу
                    conn.prepareStatement(
                        "INSERT INTO tournament_groups (id, tournament_id, name) VALUES (?, ?, ?)"
                    ).use { ps ->
                        ps.setString(1, groupId)
                        ps.setString(2, id)
                        ps.setString(3, groupName)
                        ps.executeUpdate()
                    }
                    // сохраняем игроков группы
                    for (pid in group.playerIds) {
                        conn.prepareStatement(
                            "INSERT INTO tournament_group_players (group_id, user_id) VALUES (?, ?)"
                        ).use { ps ->
                            ps.setString(1, groupId)
                            ps.setLong(2, pid)
                            ps.executeUpdate()
                        }
                    }
                    // генерируем матчи "каждый с каждым"
                    for (i in group.playerIds.indices) {
                        for (j in (i + 1) until group.playerIds.size) {
                            val match = TournamentMatch(
                                id = "${group.id}_m${group.matches.size}",
                                player1Id = group.playerIds[i],
                                player2Id = group.playerIds[j],
                                stage = MatchStage.GROUP,
                                groupId = group.id
                            )
                            group.matches.add(match)
                            insertMatch(conn, match)
                        }
                    }
                    groups.add(group)
                }
                updatePhase(conn, TournamentPhase.GROUP_STAGE)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        phase = TournamentPhase.GROUP_STAGE
        return true
    }

    /**
     * Все ли матчи группового этапа сыграны.
     */
    fun groupStageComplete(): Boolean =
        groups.all { g -> g.matches.all { it.played } }

    /**
     * Сформировать сетку плей-офф из лучших игроков групп.
     * @return true, если плей-офф стартовал
     */
    fun startPlayoff(): Boolean {
        if (phase != TournamentPhase.GROUP_STAGE) return false
        if (!groupStageComplete()) return false
        val advanced = mutableListOf<Long>()
        for (g in groups) {
            advanced.addAll(g.ranking().take(playersPerGroupAdvance).map { it.first })
        }
        advanced.shuffle()
        playoffMatches.clear()
        db.connection().use { conn ->
            conn.autoCommit = false
            try {
                // первый раунд
                var i = 0
                while (i + 1 < advanced.size) {
                    val match = TournamentMatch(
                        id = "${id}_po_r1_m${playoffMatches.size}",
                        player1Id = advanced[i],
                        player2Id = advanced[i + 1],
                        stage = MatchStage.PLAYOFF,
                        round = 1
                    )
                    playoffMatches.add(match)
                    insertMatch(conn, match)
                    i += 2
                }
                // нечётный участник — проходит автоматически (bye)
                if (i < advanced.size) {
                    val match = TournamentMatch(
                        id = "${id}_po_r1_m${playoffMatches.size}",
                        player1Id = advanced[i],
                        player2Id = 0L,
                        stage = MatchStage.PLAYOFF,
                        round = 1
                    ).apply { played = true; winnerId = advanced[i] }
                    playoffMatches.add(match)
                    insertMatch(conn, match)
                }
                updatePhase(conn, TournamentPhase.PLAYOFF)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        phase = TournamentPhase.PLAYOFF
        return true
    }

    /**
     * Найти матч плей-офф, в котором игрок участвует и который ещё не сыгран.
     */
    fun findPendingPlayoffMatch(playerId: Long): TournamentMatch? =
        playoffMatches.firstOrNull { !it.played && (it.player1Id == playerId || it.player2Id == playerId) }

    /**
     * Найти матч группового этапа, в котором игрок участвует и который ещё не сыгран.
     */
    fun findPendingGroupMatch(playerId: Long): TournamentMatch? =
        groups.flatMap { it.matches }.firstOrNull { !it.played && (it.player1Id == playerId || it.player2Id == playerId) }

    /**
     * Отметить матч сыгранным с указанным победителем.
     */
    fun recordResult(matchId: String, winnerId: Long) {
        val match = findMatch(matchId) ?: return
        match.winnerId = winnerId
        match.played = true
        playerActiveMatch.remove(match.player1Id)
        playerActiveMatch.remove(match.player2Id)
        db.connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "UPDATE tournament_matches SET winner_id = ?, played = TRUE WHERE id = ?"
                ).use { ps ->
                    ps.setLong(1, winnerId)
                    ps.setString(2, matchId)
                    ps.executeUpdate()
                }
                // если это плей-офф — продвигаем победителя в следующий раунд
                if (match.stage == MatchStage.PLAYOFF) {
                    advancePlayoff(conn, match)
                }
                // проверяем завершение плей-офф
                if (phase == TournamentPhase.PLAYOFF && playoffMatches.all { it.played }) {
                    val winners = playoffMatches.filter {
                        it.round == playoffMatches.maxOf { m -> m.round } && it.played
                    }
                    if (winners.isNotEmpty() && playoffMatches.none { !it.played }) {
                        updatePhase(conn, TournamentPhase.FINISHED)
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        // обновляем фазу в памяти
        if (phase == TournamentPhase.PLAYOFF && playoffMatches.all { it.played }) {
            val winners = playoffMatches.filter {
                it.round == playoffMatches.maxOf { m -> m.round } && it.played
            }
            if (winners.isNotEmpty() && playoffMatches.none { !it.played }) {
                phase = TournamentPhase.FINISHED
            }
        }
    }

    private fun advancePlayoff(conn: Connection, match: TournamentMatch) {
        val currentRound = match.round
        val nextRound = currentRound + 1
        val winners = playoffMatches.filter { it.round == currentRound && it.played }.map { it.winnerId }
        val roundMatches = playoffMatches.filter { it.round == currentRound }
        if (roundMatches.all { it.played } && winners.size > 1) {
            var idx = 0
            while (idx + 1 < winners.size) {
                val newMatch = TournamentMatch(
                    id = "${id}_po_r${nextRound}_m${playoffMatches.count { it.round == nextRound }}",
                    player1Id = winners[idx],
                    player2Id = winners[idx + 1],
                    stage = MatchStage.PLAYOFF,
                    round = nextRound
                )
                playoffMatches.add(newMatch)
                insertMatch(conn, newMatch)
                idx += 2
            }
            if (idx < winners.size) {
                val byeMatch = TournamentMatch(
                    id = "${id}_po_r${nextRound}_m${playoffMatches.count { it.round == nextRound }}",
                    player1Id = winners[idx],
                    player2Id = 0L,
                    stage = MatchStage.PLAYOFF,
                    round = nextRound
                ).apply { played = true; winnerId = winners[idx] }
                playoffMatches.add(byeMatch)
                insertMatch(conn, byeMatch)
            }
        }
    }

    private fun findMatch(matchId: String): TournamentMatch? {
        groups.flatMap { it.matches }.firstOrNull { it.id == matchId }?.let { return it }
        return playoffMatches.firstOrNull { it.id == matchId }
    }

    /** Победитель турнира (0, если не завершён). */
    fun champion(): Long {
        if (phase != TournamentPhase.FINISHED) return 0L
        val maxRound = playoffMatches.maxOfOrNull { it.round } ?: return 0L
        return playoffMatches.firstOrNull { it.round == maxRound && it.played }?.winnerId ?: 0L
    }

    // ---- Методы работы с БД ----

    private fun insertMatch(conn: Connection, match: TournamentMatch) {
        conn.prepareStatement(
            "INSERT INTO tournament_matches (id, tournament_id, player1_id, player2_id, winner_id, played, round, group_id, stage) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, match.id)
            ps.setString(2, id)
            ps.setLong(3, match.player1Id)
            ps.setLong(4, match.player2Id)
            ps.setLong(5, match.winnerId)
            ps.setBoolean(6, match.played)
            ps.setInt(7, match.round)
            if (match.groupId != null) {
                ps.setString(8, match.groupId)
            } else {
                ps.setNull(8, Types.VARCHAR)
            }
            ps.setString(9, match.stage.name)
            ps.executeUpdate()
        }
    }

    private fun updatePhase(conn: Connection, newPhase: TournamentPhase) {
        conn.prepareStatement(
            "UPDATE tournaments SET phase = ? WHERE id = ?"
        ).use { ps ->
            ps.setString(1, newPhase.name)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** Загрузить все данные турнира из БД (вызывается из GameStore). */
    internal fun loadFromDb(conn: Connection) {
        // Участники
        conn.prepareStatement(
            "SELECT user_id, display_name FROM tournament_participants WHERE tournament_id = ?"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    participants[rs.getLong("user_id")] = rs.getString("display_name")
                }
            }
        }
        // Группы
        conn.prepareStatement(
            "SELECT id, name FROM tournament_groups WHERE tournament_id = ?"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val groupId = rs.getString("id")
                    val groupName = rs.getString("name")
                    val group = TournamentGroup(id = groupId, name = groupName)
                    // Игроки группы
                    conn.prepareStatement(
                        "SELECT user_id FROM tournament_group_players WHERE group_id = ?"
                    ).use { ps2 ->
                        ps2.setString(1, groupId)
                        ps2.executeQuery().use { rs2 ->
                            while (rs2.next()) {
                                group.playerIds.add(rs2.getLong("user_id"))
                            }
                        }
                    }
                    // Матчи группы
                    conn.prepareStatement(
                        "SELECT id, player1_id, player2_id, winner_id, played, round, stage FROM tournament_matches WHERE group_id = ?"
                    ).use { ps2 ->
                        ps2.setString(1, groupId)
                        ps2.executeQuery().use { rs2 ->
                            while (rs2.next()) {
                                group.matches.add(
                                    TournamentMatch(
                                        id = rs2.getString("id"),
                                        player1Id = rs2.getLong("player1_id"),
                                        player2Id = rs2.getLong("player2_id"),
                                        winnerId = rs2.getLong("winner_id"),
                                        played = rs2.getBoolean("played"),
                                        round = rs2.getInt("round"),
                                        groupId = groupId,
                                        stage = MatchStage.valueOf(rs2.getString("stage"))
                                    )
                                )
                            }
                        }
                    }
                    groups.add(group)
                }
            }
        }
        // Матчи плей-офф
        conn.prepareStatement(
            "SELECT id, player1_id, player2_id, winner_id, played, round FROM tournament_matches WHERE tournament_id = ? AND stage = 'PLAYOFF' ORDER BY round, id"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    playoffMatches.add(
                        TournamentMatch(
                            id = rs.getString("id"),
                            player1Id = rs.getLong("player1_id"),
                            player2Id = rs.getLong("player2_id"),
                            winnerId = rs.getLong("winner_id"),
                            played = rs.getBoolean("played"),
                            round = rs.getInt("round"),
                            stage = MatchStage.PLAYOFF
                        )
                    )
                }
            }
        }
    }
}
