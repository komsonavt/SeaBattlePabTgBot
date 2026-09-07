package com.company.seabattle.state

import com.company.seabattle.db.Database
import com.company.seabattle.game.escapeHtml
import java.util.UUID

enum class AccessStatus { DRAFT, PENDING, APPROVED, DECLINED, BLOCKED }
data class AccessRequest(val id: String, val userId: Long, val name: String?, val activity: String?, val status: AccessStatus, val payload: String?)

data class PlayerProfile(val id: Long, val firstName: String, val lastName: String? = null, val username: String? = null) {
    val displayName: String get() = listOfNotNull(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        .ifBlank { "Игрок $id" }
}
data class RankingEntry(val place: Long, val profile: PlayerProfile, val games: Long, val wins: Long)

class CommunityStore(private val db: Database) {
    fun isApproved(userId: Long): Boolean = db.connection().use { conn -> conn.prepareStatement("SELECT status FROM access_requests WHERE user_id=? ORDER BY created_at DESC LIMIT 1").use { ps -> ps.setLong(1,userId); ps.executeQuery().use { it.next() && it.getString(1)=="APPROVED" } } }
    fun request(userId: Long, payload: String?): AccessRequest {
        val existing = accessRequest(userId)
        if (existing != null && existing.status in setOf(AccessStatus.DRAFT, AccessStatus.PENDING, AccessStatus.BLOCKED)) return existing
        val id = UUID.randomUUID().toString().replace("-", "")
        db.connection().use { conn -> conn.prepareStatement("INSERT INTO access_requests(id,user_id,status,payload) VALUES (?,?,'DRAFT',?)").use { ps -> ps.setString(1,id);ps.setLong(2,userId);ps.setString(3,payload);ps.executeUpdate() } }
        return AccessRequest(id,userId,null,null,AccessStatus.DRAFT,payload)
    }
    fun accessRequest(userId: Long): AccessRequest? = db.connection().use { conn -> conn.prepareStatement("SELECT id,user_id,name,activity,status,payload FROM access_requests WHERE user_id=? ORDER BY created_at DESC LIMIT 1").use { ps -> ps.setLong(1,userId);ps.executeQuery().use { rs -> if(rs.next()) AccessRequest(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),AccessStatus.valueOf(rs.getString(5)),rs.getString(6)) else null } } }
    fun setRequestName(userId: Long, name: String) = db.connection().use { conn -> conn.prepareStatement("UPDATE access_requests SET name=? WHERE user_id=? AND status='DRAFT'").use { ps -> ps.setString(1,name);ps.setLong(2,userId);ps.executeUpdate() } }
    fun submitRequest(userId: Long, activity: String): AccessRequest? {
        db.connection().use { conn -> conn.prepareStatement("UPDATE access_requests SET activity=?,status='PENDING' WHERE user_id=? AND status='DRAFT'").use { ps -> ps.setString(1,activity);ps.setLong(2,userId);ps.executeUpdate() } }
        return accessRequest(userId)
    }
    fun decideRequest(id: String, approved: Boolean, moderatorId: Long): AccessRequest? = db.connection().use { conn ->
        conn.prepareStatement("UPDATE access_requests SET status=?,moderator_id=?,decided_at=NOW() WHERE id=? AND status='PENDING' RETURNING id,user_id,name,activity,status,payload").use { ps -> ps.setString(1,if(approved) "APPROVED" else "DECLINED");ps.setLong(2,moderatorId);ps.setString(3,id);ps.executeQuery().use { rs -> if(rs.next()) AccessRequest(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),AccessStatus.valueOf(rs.getString(5)),rs.getString(6)) else null } }
    }
    fun blockRequest(id: String, moderatorId: Long): AccessRequest? = db.connection().use { conn ->
        conn.prepareStatement("UPDATE access_requests SET status='BLOCKED',moderator_id=?,decided_at=NOW() WHERE id=? AND status IN ('DRAFT','PENDING','APPROVED','DECLINED') RETURNING id,user_id,name,activity,status,payload").use { ps ->
            ps.setLong(1,moderatorId); ps.setString(2,id)
            ps.executeQuery().use { rs -> if(rs.next()) AccessRequest(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),AccessStatus.valueOf(rs.getString(5)),rs.getString(6)) else null }
        }
    }
    fun forumTopic(key: String): Int? = db.connection().use { conn -> conn.prepareStatement("SELECT message_thread_id FROM forum_topics WHERE topic_key=?").use { ps ->
        ps.setString(1,key); ps.executeQuery().use { rs -> if(rs.next()) rs.getInt(1) else null }
    } }
    fun saveForumTopic(key: String, threadId: Int) = db.connection().use { conn -> conn.prepareStatement("INSERT INTO forum_topics(topic_key,message_thread_id) VALUES (?,?) ON CONFLICT(topic_key) DO UPDATE SET message_thread_id=EXCLUDED.message_thread_id").use { ps ->
        ps.setString(1,key); ps.setInt(2,threadId); ps.executeUpdate()
    } }
    fun workspaceChatId(): Long? = db.connection().use { conn -> conn.prepareStatement("SELECT chat_id FROM bot_workspace WHERE workspace_key='moderation'").use { ps -> ps.executeQuery().use { rs -> if(rs.next()) rs.getLong(1) else null } } }
    fun activateWorkspace(chatId: Long) = db.connection().use { conn ->
        conn.autoCommit=false
        try {
            val old=workspaceChatId()
            if(old != null && old != chatId) conn.createStatement().executeUpdate("DELETE FROM forum_topics")
            conn.prepareStatement("INSERT INTO bot_workspace(workspace_key,chat_id) VALUES ('moderation',?) ON CONFLICT(workspace_key) DO UPDATE SET chat_id=EXCLUDED.chat_id,activated_at=NOW()").use { ps -> ps.setLong(1,chatId);ps.executeUpdate() }
            conn.commit()
        } catch(e: Exception) { conn.rollback();throw e }
    }
    fun bootstrapAdmins(ids: Set<Long>) = ids.forEach { addAdmin(it, null, "config") }
    fun addAdmin(userId: Long, addedBy: Long?, source: String = "invite") = db.connection().use { conn -> conn.prepareStatement("INSERT INTO bot_admins(user_id,added_by,source) VALUES (?,?,?) ON CONFLICT(user_id) DO NOTHING").use { ps ->
        ps.setLong(1,userId); if(addedBy == null) ps.setNull(2,java.sql.Types.BIGINT) else ps.setLong(2,addedBy); ps.setString(3,source); ps.executeUpdate()
    } }
    fun isAdmin(userId: Long): Boolean = db.connection().use { conn -> conn.prepareStatement("SELECT 1 FROM bot_admins WHERE user_id=?").use { ps -> ps.setLong(1,userId); ps.executeQuery().use { it.next() } } }
    fun adminIds(): Set<Long> = db.connection().use { conn -> conn.createStatement().use { st -> st.executeQuery("SELECT user_id FROM bot_admins").use { rs -> buildSet { while(rs.next()) add(rs.getLong(1)) } } } }
    fun createAdminInvite(creatorId: Long): String {
        val id=UUID.randomUUID().toString().replace("-","")
        db.connection().use { conn -> conn.prepareStatement("INSERT INTO admin_invites(invite_id,creator_id) VALUES (?,?)").use { ps -> ps.setString(1,id); ps.setLong(2,creatorId); ps.executeUpdate() } }
        return id
    }
    fun claimAdminInvite(inviteId: String, userId: Long): Boolean = db.connection().use { conn ->
        conn.autoCommit=false
        try {
            val claimed=conn.prepareStatement("UPDATE admin_invites SET claimed_by=?,claimed_at=NOW() WHERE invite_id=? AND claimed_by IS NULL RETURNING creator_id").use { ps -> ps.setLong(1,userId);ps.setString(2,inviteId);ps.executeQuery().use { it.next() } }
            if(claimed) addAdmin(userId,null,"invite")
            conn.commit(); claimed
        } catch(e: Exception) { conn.rollback(); throw e }
    }
    fun audit(actorId: Long, action: String) = db.connection().use { conn -> conn.prepareStatement("INSERT INTO admin_audit(actor_id,action) VALUES (?,?)").use { ps -> ps.setLong(1,actorId);ps.setString(2,action);ps.executeUpdate() } }
    fun adminAuditCsv(): String = db.connection().use { conn -> conn.createStatement().use { st ->
        st.executeQuery("SELECT a.created_at,a.action,a.actor_id,p.first_name,p.last_name,p.username FROM admin_audit a LEFT JOIN user_profiles p ON p.user_id=a.actor_id ORDER BY a.created_at DESC").use { rs -> buildString {
            append('\uFEFF').append("created_at,action,actor_id,first_name,last_name,username\r\n")
            while(rs.next()) append((1..6).joinToString(",") { csvCell(rs.getString(it).orEmpty()) }).append("\r\n")
        } }
    } }
    /** Removes editable profile data and enrolments; completed games keep anonymous technical IDs for result integrity. */
    fun deleteProfile(userId: Long) = db.connection().use { conn ->
        conn.autoCommit=false
        try {
            listOf("DELETE FROM tournament_preregistrations WHERE user_id=?", "DELETE FROM pending_joins WHERE user_id=?", "DELETE FROM bot_admins WHERE user_id=?", "DELETE FROM user_profiles WHERE user_id=?").forEach { sql ->
                conn.prepareStatement(sql).use { ps -> ps.setLong(1,userId);ps.executeUpdate() }
            }
            conn.prepareStatement("UPDATE access_requests SET name=NULL,activity=NULL,payload=NULL WHERE user_id=?").use { ps -> ps.setLong(1,userId);ps.executeUpdate() }
            conn.commit()
        } catch(e: Exception) { conn.rollback();throw e }
    }
    data class BroadcastDraft(val id: String, val authorId: Long, val body: String)
    fun createBroadcastDraft(authorId: Long, body: String): BroadcastDraft {
        val id=UUID.randomUUID().toString().replace("-","")
        db.connection().use { conn -> conn.prepareStatement("INSERT INTO broadcast_drafts(id,author_id,body) VALUES (?,?,?)").use { ps -> ps.setString(1,id);ps.setLong(2,authorId);ps.setString(3,body);ps.executeUpdate() } }
        return BroadcastDraft(id,authorId,body)
    }
    /** Claims a draft once, so simultaneous confirmation buttons cannot send it twice. */
    fun claimBroadcastDraft(id: String): BroadcastDraft? = db.connection().use { conn -> conn.prepareStatement("UPDATE broadcast_drafts SET status='SENDING' WHERE id=? AND status='DRAFT' RETURNING id,author_id,body").use { ps ->
        ps.setString(1,id);ps.executeQuery().use { rs -> if(rs.next()) BroadcastDraft(rs.getString(1),rs.getLong(2),rs.getString(3)) else null }
    } }
    fun finishBroadcastDraft(id: String, sent: Boolean) = db.connection().use { conn -> conn.prepareStatement("UPDATE broadcast_drafts SET status=? WHERE id=? AND status='SENDING'").use { ps -> ps.setString(1,if(sent) "SENT" else "FAILED");ps.setString(2,id);ps.executeUpdate() } }
    fun cancelBroadcastDraft(id: String): Boolean = db.connection().use { conn -> conn.prepareStatement("UPDATE broadcast_drafts SET status='CANCELLED' WHERE id=? AND status='DRAFT'").use { ps -> ps.setString(1,id);ps.executeUpdate()==1 } }
    /** Everyone known to the bot, including people who played before the access workflow existed. */
    fun broadcastRecipients(): List<Long> = db.connection().use { conn -> conn.createStatement().use { st -> st.executeQuery("""
        WITH known AS (
            SELECT user_id FROM user_profiles
            UNION SELECT user_id FROM access_requests
            UNION SELECT user_id FROM tournament_preregistrations
            UNION SELECT player1_id FROM games WHERE player1_id>0
            UNION SELECT player2_id FROM games WHERE player2_id>0
        ), latest_access AS (
            SELECT DISTINCT ON (user_id) user_id,status FROM access_requests ORDER BY user_id,created_at DESC
        ) SELECT k.user_id FROM known k LEFT JOIN latest_access a USING(user_id)
          WHERE COALESCE(a.status,'') <> 'BLOCKED' ORDER BY k.user_id
    """.trimIndent()).use { rs -> buildList { while(rs.next()) add(rs.getLong(1)) } } } }
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
    fun audienceStatisticsCsv(): String = db.connection().use { conn ->
        conn.createStatement().use { st -> st.executeQuery("""
            WITH known AS (
                SELECT user_id FROM user_profiles UNION SELECT user_id FROM access_requests
                UNION SELECT player1_id FROM games WHERE player1_id>0 UNION SELECT player2_id FROM games WHERE player2_id>0
            ), access AS (SELECT DISTINCT ON (user_id) user_id,status,name,activity,created_at,decided_at FROM access_requests ORDER BY user_id,created_at DESC),
            game_stats AS (SELECT user_id,
                COUNT(*) FILTER (WHERE vs_computer) cpu_games,
                COUNT(*) FILTER (WHERE NOT vs_computer AND player1_id=user_id) human_created,
                COUNT(*) FILTER (WHERE NOT vs_computer) human_played,
                COUNT(*) FILTER (WHERE NOT vs_computer AND finished) human_finished,
                COUNT(*) FILTER (WHERE NOT vs_computer AND finished AND winner_id=user_id) human_wins
                FROM (SELECT player1_id user_id,player1_id,vs_computer,finished,winner_id FROM games WHERE player1_id>0
                      UNION ALL SELECT player2_id,player1_id,vs_computer,finished,winner_id FROM games WHERE player2_id>0) g GROUP BY user_id)
            SELECT k.user_id,p.first_name,p.last_name,p.username,a.status,a.name,a.activity,a.created_at,a.decided_at,
                COALESCE(g.cpu_games,0),COALESCE(g.human_created,0),COALESCE(g.human_played,0),COALESCE(g.human_finished,0),COALESCE(g.human_wins,0),
                CASE WHEN t.user_id IS NULL THEN 'нет' ELSE 'да' END,t.registered_at,p.updated_at
            FROM known k LEFT JOIN user_profiles p ON p.user_id=k.user_id LEFT JOIN access a ON a.user_id=k.user_id
            LEFT JOIN game_stats g ON g.user_id=k.user_id LEFT JOIN tournament_preregistrations t ON t.user_id=k.user_id ORDER BY k.user_id
        """.trimIndent()).use { rs ->
            buildString {
                append('\uFEFF').append("id,telegram_name,first_name,last_name,username,access_status,application_name,activity,application_created_at,moderated_at,cpu_games,human_games_created,human_games_played,human_games_finished,human_wins,tournament_registered,tournament_registered_at,profile_updated_at\r\n")
                while(rs.next()) {
                    val fullName=listOfNotNull(rs.getString(2),rs.getString(3)).filter { it.isNotBlank() }.joinToString(" ")
                    val values=listOf(rs.getString(1),fullName,rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),rs.getString(17))
                    append(values.joinToString(",") { csvCell(it.orEmpty()) }).append("\r\n")
                }
            }
        } }
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
