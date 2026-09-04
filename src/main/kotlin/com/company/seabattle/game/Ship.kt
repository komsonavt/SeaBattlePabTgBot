package com.company.seabattle.game

/** Корабль: набор клеток, которые он занимает. */
data class Ship(val cells: List<Coord>) {
    val size: Int get() = cells.size

    /** Все ли палубы подбиты. */
    fun isSunk(hits: Set<Coord>): Boolean = cells.all { it in hits }
}
