package com.company.seabattle.game

import kotlin.random.Random

/**
 * Игровое поле 10x10.
 *
 * Хранит сетку клеток и список кораблей. Поддерживает автоматическую
 * случайную расстановку по классическим правилам (4-1, 3-2, 2-3, 1-4)
 * и обработку выстрелов.
 */
class Board private constructor(
    private val grid: Array<Array<Cell>>,
    private val ships: MutableList<Ship>
) : java.io.Serializable {
    val size: Int get() = SIZE

    /** Клетка поля (только для чтения). */
    fun cellAt(row: Int, col: Int): Cell = grid[row][col]

    /** Список кораблей (копия). */
    fun ships(): List<Ship> = ships.toList()

    /** Создать поле со случайной расстановкой кораблей. */
    fun randomize(rng: Random = Random.Default) {
        for (r in 0 until SIZE) for (c in 0 until SIZE) grid[r][c] = Cell.WATER
        ships.clear()
        for (deckCount in FLEET_DESC) {
            repeat(FLEET_COUNT[deckCount]!!) {
                placeRandomShip(deckCount, rng)
            }
        }
    }

    private fun placeRandomShip(deckCount: Int, rng: Random) {
        var attempts = 0
        while (attempts < 500) {
            attempts++
            val horizontal = rng.nextBoolean()
            val maxRow = if (horizontal) SIZE else SIZE - deckCount + 1
            val maxCol = if (horizontal) SIZE - deckCount + 1 else SIZE
            val row = rng.nextInt(maxRow)
            val col = rng.nextInt(maxCol)
            val cells = if (horizontal) {
                (0 until deckCount).map { Coord(row, col + it) }
            } else {
                (0 until deckCount).map { Coord(row + it, col) }
            }
            if (canPlace(cells)) {
                cells.forEach { grid[it.row][it.col] = Cell.SHIP }
                ships.add(Ship(cells))
                return
            }
        }
        error("Не удалось разместить корабль длиной $deckCount за $attempts попыток")
    }

    private fun canPlace(cells: List<Coord>): Boolean {
        for (c in cells) {
            for (dr in -1..1) for (dc in -1..1) {
                val nr = c.row + dr
                val nc = c.col + dc
                if (nr in 0 until SIZE && nc in 0 until SIZE) {
                    if (grid[nr][nc] == Cell.SHIP) return false
                }
            }
        }
        return true
    }

    /**
     * Результат выстрела по полю.
     *
     * @param coord куда стреляли
     * @param hit попали ли в корабль
     * @param sunk уничтожен ли корабль целиком
     * @param already стреляли ли в эту клетку ранее
     * @param around клетки вокруг уничтоженного корабля, которые нужно пометить как промах
     */
    data class ShotResult(
        val coord: Coord,
        val hit: Boolean,
        val sunk: Boolean,
        val already: Boolean,
        val around: List<Coord> = emptyList()
    )

    /**
     * Обработать выстрел по координате.
     * Возвращает результат выстрела. Если клетка уже обстреляна — [ShotResult.already] = true.
     */
    fun fire(coord: Coord): ShotResult {
        val current = grid[coord.row][coord.col]
        if (current == Cell.HIT || current == Cell.MISS || current == Cell.SUNK) {
            return ShotResult(coord, hit = false, sunk = false, already = true)
        }
        if (current == Cell.WATER) {
            grid[coord.row][coord.col] = Cell.MISS
            return ShotResult(coord, hit = false, sunk = false, already = false)
        }
        // current == SHIP
        grid[coord.row][coord.col] = Cell.HIT
        val ship = ships.first { coord in it.cells }
        val hits = ship.cells.filter { grid[it.row][it.col] == Cell.HIT }
        return if (hits.size == ship.size) {
            // корабль уничтожен — помечаем палубы и ореол
            ship.cells.forEach { grid[it.row][it.col] = Cell.SUNK }
            val around = mutableListOf<Coord>()
            for (c in ship.cells) {
                for (dr in -1..1) for (dc in -1..1) {
                    val nr = c.row + dr
                    val nc = c.col + dc
                    if (nr in 0 until SIZE && nc in 0 until SIZE) {
                        if (grid[nr][nc] == Cell.WATER) {
                            grid[nr][nc] = Cell.MISS
                            around.add(Coord(nr, nc))
                        }
                    }
                }
            }
            ShotResult(coord, hit = true, sunk = true, already = false, around = around)
        } else {
            ShotResult(coord, hit = true, sunk = false, already = false)
        }
    }

    /** Все корабли уничтожены — победа противника. */
    fun allSunk(): Boolean = ships.all { ship ->
        ship.cells.all { grid[it.row][it.col] == Cell.SUNK }
    }

    /** Количество живых (не уничтоженных) кораблей. */
    fun aliveShipsCount(): Int = ships.count { ship ->
        ship.cells.any { grid[it.row][it.col] != Cell.SUNK }
    }

    /** Экспортировать состояние поля для сериализации в БД. */
    fun exportState(): BoardState = BoardState(
        grid = grid.map { row -> row.map { it.name }.toTypedArray() }.toTypedArray(),
        ships = ships.map { ship -> ship.cells.map { c -> intArrayOf(c.row, c.col) } }
    )

    companion object {
        const val SIZE = 10

        /** Описание флота: длина корабля -> количество. */
        val FLEET_DESC = listOf(4, 3, 3, 2, 2, 2, 1, 1, 1, 1)
        val FLEET_COUNT = mapOf(4 to 1, 3 to 2, 2 to 3, 1 to 4)

        /** Создать пустое поле. */
        fun empty(): Board = Board(
            Array(SIZE) { Array(SIZE) { Cell.WATER } },
            mutableListOf()
        )

        /** Создать поле со случайной расстановкой. */
        fun random(rng: Random = Random.Default): Board {
            val board = empty()
            board.randomize(rng)
            return board
        }

        /** Восстановить поле из сохранённого состояния. */
        fun fromState(state: BoardState): Board {
            val grid = state.grid.map { row ->
                row.map { name ->
                    runCatching { Cell.valueOf(name) }.getOrDefault(Cell.WATER)
                }.toTypedArray()
            }.toTypedArray()
            val ships = state.ships.map { cells ->
                Ship(cells.map { arr -> Coord(arr[0], arr[1]) })
            }.toMutableList()
            return Board(grid, ships)
        }
    }
}

/**
 * Сериализуемое состояние поля для хранения в БД.
 *
 * @param grid сетка 10×10, каждая клетка — имя элемента [Cell]
 * @param ships список кораблей; каждый корабль — список пар [row, col]
 */
data class BoardState(
    val grid: Array<Array<String>>,
    val ships: List<List<IntArray>>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardState) return false
        return grid.contentDeepEquals(other.grid) && ships == other.ships
    }

    override fun hashCode(): Int = grid.contentDeepHashCode() * 31 + ships.hashCode()
}
