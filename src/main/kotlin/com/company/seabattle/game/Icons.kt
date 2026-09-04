package com.company.seabattle.game

/**
 * Эмодзи для отрисовки поля.
 *
 * Сейчас используются стандартные эмодзи-заглушки. Позже их можно заменить
 * на кастомные эмодзи (custom emoji / стикеры) — достаточно поменять значения
 * в этом объекте, не трогая логику рендеринга.
 */
object Icons {
    /** Море — пустая клетка, по которой не стреляли. */
    const val SEA = "🌊"

    /** Корабль — целая палуба (видно только на своём поле). */
    const val SHIP = "🟫"

    /** Попадание — подбитая палуба. */
    const val HIT = "🔥"

    /** Промах — стреляли в воду. */
    const val MISS = "💧"

    /** Уничтоженный корабль. */
    const val SUNK = "💀"

    /** Неизвестная клетка поля соперника (ещё не стреляли). */
    const val UNKNOWN = "⬜"

    /** Буквы колонок в шапке поля. */
    val COL_HEADERS = listOf("🅰", "🅱", "🅲", "🅳", "🅴", "🅵", "🅶", "🅷", "🅸", "🅹")

    /** Эмодзи для клетки своего поля по состоянию [Cell]. */
    fun forOwn(cell: Cell): String = when (cell) {
        Cell.WATER -> SEA
        Cell.SHIP -> SHIP
        Cell.HIT -> HIT
        Cell.MISS -> MISS
        Cell.SUNK -> SUNK
    }

    /**
     * Эмодзи для клетки поля соперника.
     *
     * Целые корабли соперника не показываются — вместо них [UNKNOWN],
     * пока по клетке не стреляли.
     */
    fun forEnemy(cell: Cell): String = when (cell) {
        Cell.WATER, Cell.SHIP -> UNKNOWN
        Cell.HIT -> HIT
        Cell.MISS -> MISS
        Cell.SUNK -> SUNK
    }
}
