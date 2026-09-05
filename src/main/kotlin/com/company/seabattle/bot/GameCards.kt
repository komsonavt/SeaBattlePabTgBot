package com.company.seabattle.bot

import com.company.seabattle.game.RichBoardRenderer
import com.company.seabattle.state.GameSession
import com.company.seabattle.state.GameStore
import kotlin.math.max

class GameCards(private val store: GameStore, private val rich: RichMessageClient,
    private val renderer: RichBoardRenderer) : AutoCloseable {
    private val queue = LatestTaskQueue()
    // Only accessed by the single sender for that game.
    private val sent = java.util.concurrent.ConcurrentHashMap<String, String>()
    private data class Card(val playerId: Long, val own: Boolean, val messageId: Long, val html: String)
    fun request(session: GameSession, force: Boolean = false) {
        queue.submit(session.id) { sync(session,force) }
    }
    private fun sync(session: GameSession, force: Boolean) {
        val snapshot = synchronized(store) {
            val cards = listOf(session.player1Id,session.player2Id).filter { it!=0L }.flatMap { pid ->
                val p1=pid==session.player1Id
                val view=session.uiFor(pid)
                val own=if(p1) session.board1 else session.board2
                val enemy=if(p1) session.board2 else session.board1
                listOf(Card(pid,true,view.ownMessageId,renderer.own(own)),
                    Card(pid,false,view.enemyMessageId,renderer.enemy(enemy,session.id,view.revision,view.half,
                        !session.finished && session.currentTurnPlayerId==pid,
                        notice(session,pid,store.community.name(if(p1) session.player2Id else session.player1Id),System.currentTimeMillis()),
                        session.finished,session.vsComputer,view.confirmingSurrender)))
            }
            Triple(cards,session.ui.player1.revision,session.ui.player2.revision)
        }
        var failed=false
        for(card in snapshot.first) {
            val key="${session.id}:${card.playerId}:${card.own}"
            if(!force && card.messageId>0 && sent[key]==card.html) continue
            try {
                val id=rich.sync(card.playerId,card.messageId,card.html)
                store.saveCardId(session,card.playerId,card.own,id)
                sent[key]=card.html
            } catch (_: Exception) { failed=true }
        }
        if(failed) error("Card update failed")
        store.markSyncedIfCurrent(session,snapshot.second,snapshot.third)
        if(session.finished) snapshot.first.forEach { sent.remove("${session.id}:${it.playerId}:${it.own}") }
    }
    override fun close() { queue.close() }
    companion object {
        fun notice(s: GameSession,pid: Long,opponent: String,now: Long): String {
            val p1=pid==s.player1Id
            val own=if(p1) s.board1 else s.board2
            val enemy=if(p1) s.board2 else s.board1
            val status=if(s.finished) {
                val result=if(s.winnerId==pid) "Ты победил!" else "Ты проиграл."
                val reason=when(s.rules.finishReason) { "TIMEOUT" -> "Четвёртый пропуск хода."; "SURRENDER" -> "Партия завершена сдачей."; else -> "Все корабли проигравшего потоплены." }
                "$result $reason"
            } else {
                val turn=if(s.currentTurnPlayerId==pid) "Твой ход" else "Ход соперника"
                if(s.vsComputer) "$turn · без ограничения времени" else {
                    val seconds=max(0,(s.turnDeadline-now+999)/1000)
                    "$turn · осталось %02d:%02d".format(seconds/60,seconds%60)
                }
            }
            val skips=if(s.vsComputer) "" else "\nПропуски: ты ${if(p1)s.rules.skips1 else s.rules.skips2}/3 · соперник ${if(p1)s.rules.skips2 else s.rules.skips1}/3"
            return "Соперник: ${opponent.take(80)}\n$status\nТы потопил: ${10-enemy.aliveShipsCount()}/10 · Соперник: ${10-own.aliveShipsCount()}/10$skips\n" +
                (if(p1)s.rules.notice1 else s.rules.notice2) + if(s.uiFor(pid).confirmingSurrender && !s.finished) "\nСдаться? Сопернику будет засчитана победа." else ""
        }
    }
}
