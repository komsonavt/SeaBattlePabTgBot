package com.company.seabattle.state

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

    fun setWinner(winner: Long) {
        winnerId = winner
        finished = true
        turnDeadline = 0L
    }

    fun switchTurn() {
        turnIsPlayer1 = !turnIsPlayer1
    }

    /** Установить новый дедлайн хода. */
    fun updateTurnDeadline(deadlineMillis: Long) {
        turnDeadline = deadlineMillis
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
            aiState?.let { this.ai!!.restoreState(it) }
        }
        this.turnIsPlayer1 = turnIsPlayer1
        this.finished = finished
        this.winnerId = winnerId
        this.enemyKeyboardMessageId1 = enemyKeyboardMessageId1
        this.enemyKeyboardMessageId2 = enemyKeyboardMessageId2
        this.turnDeadline = turnDeadline
    }
}

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
