package com.company.seabattle.bot

import com.company.seabattle.game.RichBoardRenderer
import com.company.seabattle.game.Board
import com.company.seabattle.copy.Copy
import com.company.seabattle.state.GameSession
import com.company.seabattle.state.GameStore
import com.company.seabattle.state.GameCardSlot
import kotlin.math.max

class GameCards(private val store: GameStore, private val rich: RichMessageClient,
    private val renderer: RichBoardRenderer) : AutoCloseable {
    private val queue = LatestTaskQueue()
    // Only accessed by the single sender for that game.
    private val sent = java.util.concurrent.ConcurrentHashMap<String, String>()
    /**
     * One game screen is always three cards: fleet, reconnaissance map and
     * controls. They are rendered from one snapshot and updated together.
     */
    private data class Card(val playerId: Long, val slot: GameCardSlot, val messageId: Long, val html: String)
    fun request(session: GameSession, force: Boolean = false) {
        queue.submit(session.id) { sync(session,force) }
    }
    /** Sends a fresh three-card screen to the bottom of one player's chat. */
    fun reopen(session: GameSession, playerId: Long) {
        queue.submit(session.id) {
            store.clearCardIds(session, playerId)
            sync(session, true, playerId)
        }
    }
    private fun sync(session: GameSession, force: Boolean, onlyPlayerId: Long? = null) {
        // Existing games created before the reconnaissance card have two message
        // IDs. Editing those in place would put the newly sent map below the
        // controls, so reopen the complete three-card screen once instead.
        val playersToRepair = synchronized(store) {
            listOf(session.player1Id, session.player2Id).filter { playerId ->
                playerId != 0L && (onlyPlayerId == null || playerId == onlyPlayerId)
            }.filter { playerId ->
                val view = session.uiFor(playerId)
                view.opponentMapMessageId == 0L && (view.ownMessageId != 0L || view.enemyMessageId != 0L)
            }
        }
        playersToRepair.forEach { store.clearCardIds(session, it) }
        val snapshot = synchronized(store) {
            val cards = listOf(session.player1Id,session.player2Id).filter { it!=0L && (onlyPlayerId == null || it == onlyPlayerId) }.flatMap { pid ->
                val p1=pid==session.player1Id
                val view=session.uiFor(pid)
                val own=if(p1) session.board1 else session.board2
                val enemy=if(p1) session.board2 else session.board1
                // Keep the action buttons below both maps in every game mode.
                listOf(Card(pid,GameCardSlot.OWN,view.ownMessageId,renderer.own(own)),
                    Card(pid,GameCardSlot.OPPONENT_MAP,view.opponentMapMessageId,renderer.opponent(enemy)),
                    Card(pid,GameCardSlot.CONTROLS,view.enemyMessageId,renderer.enemy(enemy,session.id,view.revision,view.half,
                        !session.finished && session.currentTurnPlayerId==pid,
                        notice(session,pid,store.community.name(if(p1) session.player2Id else session.player1Id),System.currentTimeMillis()),
                        session.finished,session.vsComputer,view.confirmingSurrender)))
            }
            Triple(cards,session.ui.player1.revision,session.ui.player2.revision)
        }
        var failed=false
        for(card in snapshot.first) {
            val key="${session.id}:${card.playerId}:${card.slot}"
            if(!force && card.messageId>0 && sent[key]==card.html) continue
            try {
                val id=rich.sync(card.playerId,card.messageId,card.html)
                store.saveCardId(session,card.playerId,card.slot,id)
                sent[key]=card.html
            } catch (_: Exception) { failed=true }
        }
        if(failed) error("Card update failed")
        store.markSyncedIfCurrent(session,snapshot.second,snapshot.third)
        if(session.finished) snapshot.first.forEach { sent.remove("${session.id}:${it.playerId}:${it.slot}") }
    }
    override fun close() { queue.close() }
    companion object {
        fun notice(s: GameSession,pid: Long,opponent: String,now: Long): String {
            val p1=pid==s.player1Id
            val own=if(p1) s.board1 else s.board2
            val enemy=if(p1) s.board2 else s.board1
            val status=if(s.finished) {
                val result=if(s.winnerId==pid) Copy.text("result_win") else Copy.text("result_loss")
                val reason=when(s.rules.finishReason) { "TIMEOUT" -> Copy.text("reason_timeout"); "SURRENDER" -> Copy.text("reason_surrender"); else -> Copy.text("reason_fleet") }
                "$result $reason"
            } else {
                val turn=if(s.currentTurnPlayerId==pid) Copy.text("turn_yours") else Copy.text("turn_opponent")
                if(s.vsComputer) "$turn · ${Copy.text("turn_cpu_no_limit")}" else {
                    val seconds=max(0,(s.turnDeadline-now+999)/1000)
                    "$turn · ${Copy.text("turn_remaining", "time" to "%02d:%02d".format(seconds/60,seconds%60))}"
                }
            }
            val skips=if(s.vsComputer) "" else "\n${Copy.text("skips", "mine" to if(p1)s.rules.skips1 else s.rules.skips2, "theirs" to if(p1)s.rules.skips2 else s.rules.skips1)}"
            return "$status\n${Copy.text("opponent", "name" to opponent.take(80))}\n${Copy.text("score_detailed", "mine" to 10-enemy.aliveShipsCount(), "mine_fleet" to fleet(enemy), "theirs" to 10-own.aliveShipsCount(), "theirs_fleet" to fleet(own))}$skips\n" +
                (if(p1)s.rules.notice1 else s.rules.notice2) + if(s.uiFor(pid).confirmingSurrender && !s.finished) "\n${Copy.text("surrender_confirm")}" else ""
        }

        private fun fleet(board: Board): String = board.sunkShipsBySize()
            .toSortedMap(compareByDescending { it })
            .map { (decks, count) ->
                Copy.text("fleet_item", "count" to count, "deck" to Copy.text("fleet_deck_$decks"), "ship" to shipWord(count))
            }
            .joinToString(" · ")
            .ifBlank { Copy.text("fleet_none") }

        private fun shipWord(count: Int): String = Copy.text(
            when {
                count % 10 == 1 && count % 100 != 11 -> "fleet_ship_one"
                count % 10 in 2..4 && count % 100 !in 12..14 -> "fleet_ship_few"
                else -> "fleet_ship_many"
            }
        )
    }
}
