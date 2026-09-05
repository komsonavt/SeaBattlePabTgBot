package com.company.seabattle.state

import com.company.seabattle.db.Database
import com.company.seabattle.game.escapeHtml

data class PlayerProfile(val id: Long, val firstName: String, val lastName: String? = null, val username: String? = null) {
    val displayName: String get() = listOfNotNull(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        .ifBlank { "Игрок $id" }
}
data class RankingEntry(val place: Long, val profile: PlayerProfile, val games: Long, val wins: Long)

class CommunityStore(private val db: Database) {
    fun saveProfile(p: PlayerProfile) {
        db.connection().use { conn ->
            conn.prepareStatement("""INSERT INTO user_profiles(user_id, first_name, last_name, username) VALUES (?,?,?,?)
                ON CONFLICT(user_id) DO UPDATE SET first_name=EXCLUDED.first_name, last_name=EXCLUDED.last_name,
                username=EXCLUDED.username, updated_at=NOW()""").use { ps ->
                ps.setLong(1,p.id); ps.setString(2,p.firstName); ps.setString(3,p.lastName); ps.setString(4,p.username)
                ps.executeUpdate()
            }
        }
    }
    fun name(id: Long): String = if (id == 0L) "Компьютер" else db.connection().use { conn ->
        conn.prepareStatement("SELECT first_name,last_name FROM user_profiles WHERE user_id=?").use { ps ->
            ps.setLong(1,id)
            ps.executeQuery().use { rs -> if (rs.next()) PlayerProfile(id,rs.getString(1),rs.getString(2)).displayName else "Игрок $id" }
        }
    }
    fun pendingJoin(userId: Long): String? = db.connection().use { conn ->
        conn.prepareStatement("SELECT payload FROM pending_joins WHERE user_id=?").use { ps ->
            ps.setLong(1,userId); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
    fun rememberJoin(userId: Long, payload: String) {
        require(payload.length <= 64)
        db.connection().use { conn ->
            conn.prepareStatement("INSERT INTO pending_joins(user_id,payload) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET payload=EXCLUDED.payload").use { ps ->
                ps.setLong(1,userId); ps.setString(2,payload); ps.executeUpdate()
            }
        }
    }
    fun forgetJoin(userId: Long) = executeForUser("DELETE FROM pending_joins WHERE user_id=?",userId)
    fun cancelRegistration(userId: Long) = executeForUser("DELETE FROM tournament_preregistrations WHERE user_id=?",userId)
    private fun executeForUser(sql: String, userId: Long) {
        db.connection().use { conn -> conn.prepareStatement(sql).use { ps -> ps.setLong(1,userId); ps.executeUpdate() } }
    }
    fun register(userId: Long) {
        executeForUser("""INSERT INTO tournament_preregistrations(user_id,first_name,last_name,username)
            SELECT user_id,first_name,last_name,username FROM user_profiles WHERE user_id=?
            ON CONFLICT(user_id) DO NOTHING""",userId)
    }
    fun isRegistered(userId: Long): Boolean = db.connection().use { conn ->
        conn.prepareStatement("SELECT 1 FROM tournament_preregistrations WHERE user_id=?").use { ps ->
            ps.setLong(1,userId); ps.executeQuery().use { it.next() }
        }
    }
    fun registrationCount(): Long = db.connection().use { conn ->
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM tournament_preregistrations").use { it.next(); it.getLong(1) } }
    }
    fun registrationsCsv(): String = db.connection().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT user_id,first_name,last_name,username,registered_at FROM tournament_preregistrations ORDER BY registered_at,user_id").use { rs ->
                buildString {
                    append('\uFEFF').append("id,first_name,last_name,username,registered_at\r\n")
                    while (rs.next()) append((1..5).joinToString(",") { csvCell(rs.getString(it).orEmpty()) }).append("\r\n")
                }
            }
        }
    }

    /** Statistics are derived from committed games, so duplicate completion cannot increment counters. */
    fun leaderboard(userId: Long): List<RankingEntry> = db.connection().use { conn ->
        conn.prepareStatement("""
            WITH results AS (
                SELECT player1_id AS user_id, winner_id FROM games
                WHERE finished AND mode='VS_COLLEAGUE' AND player1_id>0 AND player2_id>0
                  AND winner_id IN (player1_id,player2_id)
                UNION ALL
                SELECT player2_id AS user_id, winner_id FROM games
                WHERE finished AND mode='VS_COLLEAGUE' AND player1_id>0 AND player2_id>0
                  AND winner_id IN (player1_id,player2_id)
            ), totals AS (
                SELECT user_id, COUNT(*) games, COUNT(*) FILTER(WHERE winner_id=user_id) wins
                FROM results GROUP BY user_id
            ), ranked AS (
                SELECT *, ROW_NUMBER() OVER(ORDER BY wins DESC, wins::numeric/games DESC,user_id) place FROM totals
            )
            SELECT r.*,p.first_name,p.last_name,p.username FROM ranked r LEFT JOIN user_profiles p USING(user_id)
            WHERE place<=15 OR user_id=? ORDER BY place
        """.trimIndent()).use { ps ->
            ps.setLong(1,userId)
            ps.executeQuery().use { rs -> buildList {
                while(rs.next()) add(RankingEntry(rs.getLong("place"),PlayerProfile(rs.getLong("user_id"),
                    rs.getString("first_name").orEmpty(),rs.getString("last_name"),rs.getString("username")),rs.getLong("games"),rs.getLong("wins")))
            } }
        }
    }
    companion object {
        fun csvCell(value: String): String {
            val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
            return "\"${safe.replace("\"","\"\"")}\""
        }
        fun renderLeaderboard(rows: List<RankingEntry>, userId: Long): String = buildString {
            append("<h3>Таблица лидеров NMH Team</h3><p>Здесь считаются только игры с коллегами. Сначала победы, затем процент побед.</p>")
            if (rows.isNotEmpty()) {
                append("<table compact bordered><tr><th>Место</th><th>Игрок</th><th>Игры</th><th>Победы</th></tr>")
                for (row in rows) {
                    if(row.place>15) append("<tr><td>…</td><td>Твоя позиция</td><td></td><td></td></tr>")
                    val name = escapeHtml(row.profile.displayName.take(80))
                    append("<tr><td>${row.place}</td><td>${if(row.profile.id==userId) "<b>$name — ты</b>" else name}</td><td>${row.games}</td><td>${row.wins}</td></tr>")
                }
                append("</table>")
            } else append("<p>Первые победы ещё впереди!</p>")
            if(rows.none { it.profile.id==userId }) append("<p>Твоё место: пока нет места. Заверши первую игру с коллегой.</p>")
            append("<tg-button-row><tg-button type=\"callback_data\" data=\"menu\">В меню</tg-button></tg-button-row>")
        }
    }
}
