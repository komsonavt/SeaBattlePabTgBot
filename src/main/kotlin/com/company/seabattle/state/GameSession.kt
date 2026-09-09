package com.company.seabattle.state

import com.company.seabattle.copy.Copy

import com.company.seabattle.game.AiState
import com.company.seabattle.game.Board
import com.company.seabattle.game.BoardState
import com.company.seabattle.game.SeaBattleAI

/**
 * Одна партия морского боя.
 *
 * Хранит поля обоих игроков, чей сейчас ход и (для режима с компьютером) ИИ.
 * Используется во всех трёх режимах: против компьютера, против коллеги и в турнире.
 *
 * @param id уникальный идентификатор партии
 * @param player1Id id первого игрока (того, кто ходит первым)
 * @param player2Id id второго игрока (или 0 для компьютера)
 * @param vsComputer true — второй игрок это компьютер
 * @param mode режим игры (см. [GameMode])
 * @param tournamentMatchId id матча в турнире (если игра турнирная)
 */
class GameSession(
    val id: String,
    val player1Id: Long,
    val player2Id: Long,
    val vsComputer: Boolean,
    val mode: GameMode,
    val tournamentMatchId: String? = null
) {
    var board1: Board = Board.random()
        private set
    var board2: Board = Board.random()
        private set

    /** ИИ для режима против компьютера (стреляет по полю player1). */
    var ai: SeaBattleAI? = if (vsComputer) SeaBattleAI() else null
        private set

    /** Чей сейчас ход: true — player1, false — player2 (или ИИ). */
    var turnIsPlayer1: Boolean = true
        private set

    /** Завершена ли партия. */
    var finished: Boolean = false
        private set

    /** Id победителя (0, пока не завершена). */
    var winnerId: Long = 0L
        private set

    /** Id сообщения с полем соперника (для редактирования inline-клавиатуры). */
    var enemyKeyboardMessageId1: Long = 0L
    var enemyKeyboardMessageId2: Long = 0L

    /** Дедлайн текущего хода (epoch millis). 0 — таймер не установлен. */
    var turnDeadline: Long = 0L

    var ui: GameUi = GameUi()
    var rules: GameRules = GameRules()

    fun accepts(action: com.company.seabattle.game.GameAction, playerId: Long, messageId: Long): Boolean {
        if (finished || action.gameId != id || playerId != player1Id && playerId != player2Id) return false
        val view = uiFor(playerId)
        return action.revision == view.revision && messageId == view.enemyMessageId && messageId > 0
    }

    fun uiFor(playerId: Long): PlayerUi = if (playerId == player1Id) ui.player1 else ui.player2

    fun invalidateViews() {
        ui.player1.revision++
        ui.player2.revision++
    }

    /** One human action and the complete computer response, without network effects. */
    fun fire(playerId: Long, coord: com.company.seabattle.game.Coord): Boolean {
        if (finished || playerId != currentTurnPlayerId || playerId == 0L) return false
        val enemy = if (playerId == player1Id) board2 else board1
        val result = enemy.fire(coord)
        if (result.already) return false
        val ownNotice = when {
            result.sunk -> Copy.text("shot_sunk", "cell" to coord.label())
            result.hit -> Copy.text("shot_hit", "cell" to coord.label())
            else -> Copy.text("shot_miss", "cell" to coord.label())
        }
        val enemyNotice = when {
            result.sunk -> Copy.text("enemy_sunk", "cell" to coord.label())
            result.hit -> Copy.text("enemy_hit", "cell" to coord.label())
            else -> Copy.text("enemy_miss", "cell" to coord.label())
        }
        if (playerId == player1Id) { rules.notice1 = ownNotice; rules.notice2 = enemyNotice }
        else { rules.notice2 = ownNotice; rules.notice1 = enemyNotice }
        if (enemy.allSunk()) setWinner(playerId)
        else if (!result.hit) switchTurn()
        if (vsComputer && !finished && !turnIsPlayer1) resumeComputerTurn()
        invalidateViews()
        return true
    }

    fun resumeComputerTurn() {
        if (!vsComputer || finished || turnIsPlayer1) return
        val computer = requireNotNull(ai)
        while (!finished && !turnIsPlayer1) {
            val result = board1.fire(computer.chooseTarget(board1))
            computer.onShotResult(result)
            if (board1.allSunk()) setWinner(0L)
            else if (!result.hit) switchTurn()
        }
    }

    fun expire(now: Long): Boolean {
        if (vsComputer || finished || turnDeadline <= 0 || now < turnDeadline) return false
        val skips = if (turnIsPlayer1) ++rules.skips1 else ++rules.skips2
        val notice = if (skips <= 3) Copy.text("timeout_skip", "count" to skips)
            else Copy.text("timeout_loss")
        if (turnIsPlayer1) { rules.notice1 = notice; rules.notice2 = "Соперник пропустил ход ($skips)." }
        else { rules.notice2 = notice; rules.notice1 = "Соперник пропустил ход ($skips)." }
        if (skips >= 4) setWinner(if (turnIsPlayer1) player2Id else player1Id, "TIMEOUT", now)
        else { switchTurn(); turnDeadline = now + TURN_MILLIS }
        invalidateViews()
        return true
    }

    /** Used once after a restart: at most one overdue turn is counted, then a new full turn begins. */
    fun recoverDeadline(now: Long) {
        if (vsComputer || finished) { turnDeadline = 0; return }
        if (turnDeadline <= 0) turnDeadline = now + TURN_MILLIS
        else if (now >= turnDeadline) expire(now)
    }

    fun setWinner(winner: Long, reason: String = "FLEET_DESTROYED", now: Long = System.currentTimeMillis()) {
        winnerId = winner
        finished = true
        turnDeadline = 0L
        rules.finishReason = reason
        rules.finishedAt = now
    }

    fun switchTurn() {
        turnIsPlayer1 = !turnIsPlayer1
    }

    /** Установить новый дедлайн хода. */
    fun updateTurnDeadline(deadlineMillis: Long) {
        turnDeadline = if (vsComputer || finished) 0 else deadlineMillis
    }

    /** Очистить дедлайн (например, при завершении игры). */
    fun clearTurnDeadline() {
        turnDeadline = 0L
    }

    /** Текущий ходящий игрок. */
    val currentTurnPlayerId: Long
        get() = if (turnIsPlayer1) player1Id else player2Id

    // ---- Сериализация для БД ----

    fun exportBoard1(): BoardState = board1.exportState()
    fun exportBoard2(): BoardState = board2.exportState()
    fun exportAiState(): AiState? = ai?.exportState()

    /** Восстановить состояние из БД (вызывается при загрузке сессии). */
    fun restoreState(
        board1State: BoardState,
        board2State: BoardState,
        aiState: AiState?,
        turnIsPlayer1: Boolean,
        finished: Boolean,
        winnerId: Long,
        enemyKeyboardMessageId1: Long,
        enemyKeyboardMessageId2: Long,
        turnDeadline: Long
    ) {
        this.board1 = Board.fromState(board1State)
        this.board2 = Board.fromState(board2State)
        if (vsComputer) {
            if (this.ai == null) this.ai = SeaBattleAI()
            val currentAi = this.ai
            if (currentAi != null && aiState != null) {
                currentAi.restoreState(aiState)
            }
        }
        this.turnIsPlayer1 = turnIsPlayer1
        this.finished = finished
        this.winnerId = winnerId
        this.enemyKeyboardMessageId1 = enemyKeyboardMessageId1
        this.enemyKeyboardMessageId2 = enemyKeyboardMessageId2
        this.turnDeadline = turnDeadline
    }

    companion object { const val TURN_MILLIS = 180_000L }
}

data class GameRules(var skips1: Int = 0, var skips2: Int = 0, var notice1: String = "Выбери клетку для первого выстрела.",
    var notice2: String = "Соперник ходит первым.", var finishReason: String? = null, var finishedAt: Long? = null)

data class PlayerUi(var ownMessageId: Long = 0, var enemyMessageId: Long = 0, var half: Int = 0, var revision: Long = 0,
    var confirmingSurrender: Boolean = false, var opponentMapMessageId: Long = 0)
data class GameUi(var player1: PlayerUi = PlayerUi(), var player2: PlayerUi = PlayerUi(), var needsSync: Boolean = true)

/** Persistent Rich-message slots for one player's game screen. */
enum class GameCardSlot { OWN, OPPONENT_MAP, CONTROLS }

/** Режим игры. */
enum class GameMode {
    /** Игра против компьютера. */
    VS_COMPUTER,

    /** Игра против коллеги по пригласительной ссылке. */
    VS_COLLEAGUE,

    /** Турнирный матч. */
    TOURNAMENT;

    companion object {
        fun fromString(value: String): GameMode =
            entries.firstOrNull { it.name == value } ?: VS_COMPUTER
    }
}
