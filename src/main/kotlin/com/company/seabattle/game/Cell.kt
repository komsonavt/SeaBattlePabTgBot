package com.company.seabattle.game

/** Состояние одной клетки поля. */
enum class Cell {
    /** Пустая вода — корабля нет, выстрела не было. */
    WATER,

    /** Корабль стоит, выстрела не было. */
    SHIP,

    /** Попадание в корабль (палуба подбита). */
    HIT,

    /** Промах — стреляли в воду. */
    MISS,

    /** Корабль уничтожен полностью (помечается вокруг для отображения ореола). */
    SUNK
}

/** Координата клетки на поле 10x10. */
data class Coord(val row: Int, val col: Int) {
    init {
        require(row in 0 until Board.SIZE) { "row out of range: $row" }
        require(col in 0 until Board.SIZE) { "col out of range: $col" }
    }

    /** Буквенно-цифровое обозначение клетки, например "Б4". */
    fun label(): String = "${COL_LETTERS[col]}${row + 1}"

    companion object {
        val COL_LETTERS = listOf('А', 'Б', 'В', 'Г', 'Д', 'Е', 'Ж', 'З', 'И', 'К')

        fun fromLabel(label: String): Coord? {
            if (label.length < 2) return null
            val letter = label[0].uppercaseChar()
            val col = COL_LETTERS.indexOf(letter)
            if (col < 0) return null
            val row = label.drop(1).trim().toIntOrNull()?.minus(1) ?: return null
            return if (row in 0 until Board.SIZE) Coord(row, col) else null
        }
    }
}
