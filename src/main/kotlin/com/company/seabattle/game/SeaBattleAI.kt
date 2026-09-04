package com.company.seabattle.game

import kotlin.random.Random
import java.io.Serializable

/**
 * ИИ-противник для режима «игра против компьютера».
 *
 * Стратегия:
 * 1. Режим HUNT — стреляет по случайной неисследованной клетке.
 *    Использует «шахматный» паттерн, чтобы эффективнее находить корабли.
 * 2. При попадании переходит в режим TARGET — добивает корабль,
 *    стреляя по соседним клеткам от известных попаданий.
 */
class SeaBattleAI(private val rng: Random = Random.Default) : Serializable {

    /** Клетки, по которым ИИ уже стрелял. */
    private val shots = mutableSetOf<Coord>()

    /** Попадания, по которым ещё добиваем корабль. */
    private val pendingHits = mutableListOf<Coord>()

    /** Режим работы. */
    private var mode = Mode.HUNT

    private enum class Mode { HUNT, TARGET }

    /** Экспортировать состояние ИИ для сохранения в БД. */
    fun exportState(): AiState = AiState(
        shots = shots.map { intArrayOf(it.row, it.col) },
        pendingHits = pendingHits.map { intArrayOf(it.row, it.col) },
        mode = mode.name
    )

    /** Восстановить состояние ИИ из БД. */
    fun restoreState(state: AiState) {
        shots.clear()
        shots.addAll(state.shots.map { Coord(it[0], it[1]) })
        pendingHits.clear()
        pendingHits.addAll(state.pendingHits.map { Coord(it[0], it[1]) })
        mode = runCatching { Mode.valueOf(state.mode) }.getOrDefault(Mode.HUNT)
    }

    /**
     * Выбрать клетку для следующего выстрела по полю соперника.
     *
     * @param enemyBoard поле соперника (с точки зрения ИИ)
     * @return координата выстрела
     */
    fun chooseTarget(enemyBoard: Board): Coord {
        val target = when (mode) {
            Mode.TARGET -> chooseTargetMode(enemyBoard)
            Mode.HUNT -> chooseHuntMode(enemyBoard)
        }
        return target
    }

    private fun chooseHuntMode(board: Board): Coord {
        // Шахматный паттерн: стреляем по клеткам, где (row+col) чётное,
        // чтобы покрыть поле минимальным числом выстрелов (корабли >= 2 палуб).
        val candidates = mutableListOf<Coord>()
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if ((r + c) % 2 == 0) {
                    val coord = Coord(r, c)
                    if (coord !in shots) candidates.add(coord)
                }
            }
        }
        if (candidates.isEmpty()) {
            // fallback — любая неисследованная
            for (r in 0 until Board.SIZE) for (c in 0 until Board.SIZE) {
                val coord = Coord(r, c)
                if (coord !in shots) candidates.add(coord)
            }
        }
        return candidates.random(rng)
    }

    private fun chooseTargetMode(board: Board): Coord {
        // Если есть попадание, из которого можно построить линию — стреляем по линии.
        if (pendingHits.size >= 2) {
            // Сортируем, чтобы определить направление
            val sorted = pendingHits.sortedWith(compareBy({ it.row }, { it.col }))
            val first = sorted.first()
            val last = sorted.last()
            val sameRow = first.row == last.row
            // Используем Coord.safe(), т.к. координаты могут быть вне поля (край).
            val candidates = if (sameRow) {
                listOfNotNull(
                    Coord.safe(first.row, first.col - 1),
                    Coord.safe(first.row, last.col + 1)
                )
            } else {
                listOfNotNull(
                    Coord.safe(first.row - 1, first.col),
                    Coord.safe(last.row + 1, first.col)
                )
            }
            for (c in candidates) {
                if (c !in shots) {
                    return c
                }
            }
        }
        // Одно попадание — стреляем по четырём соседям
        val origin = pendingHits.first()
        val neighbors = listOfNotNull(
            Coord.safe(origin.row - 1, origin.col),
            Coord.safe(origin.row + 1, origin.col),
            Coord.safe(origin.row, origin.col - 1),
            Coord.safe(origin.row, origin.col + 1)
        )
        for (n in neighbors) {
            if (n !in shots) {
                return n
            }
        }
        // Соседей не осталось — возвращаемся в режим охоты
        mode = Mode.HUNT
        pendingHits.clear()
        return chooseHuntMode(board)
    }

    /**
     * Сообщить ИИ результат его выстрела, чтобы он скорректировал стратегию.
     */
    fun onShotResult(result: Board.ShotResult) {
        shots.add(result.coord)
        when {
            result.sunk -> {
                // Корабль уничтожен — добивать больше не нужно
                pendingHits.clear()
                mode = Mode.HUNT
            }
            result.hit -> {
                pendingHits.add(result.coord)
                mode = Mode.TARGET
            }
            else -> {
                // промах — режим не меняем
            }
        }
    }

    /** Сбросить состояние ИИ для новой игры. */
    fun reset() {
        shots.clear()
        pendingHits.clear()
        mode = Mode.HUNT
    }
}

/**
 * Сериализуемое состояние ИИ для хранения в БД.
 *
 * @param shots клетки, по которым ИИ уже стрелял (пары [row, col])
 * @param pendingHits попадания, по которым добивается корабль
 * @param mode режим работы ("HUNT" или "TARGET")
 */
data class AiState(
    val shots: List<IntArray>,
    val pendingHits: List<IntArray>,
    val mode: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiState) return false
        return shots == other.shots && pendingHits == other.pendingHits && mode == other.mode
    }

    override fun hashCode(): Int = shots.hashCode() * 31 + pendingHits.hashCode() * 31 + mode.hashCode()
}
