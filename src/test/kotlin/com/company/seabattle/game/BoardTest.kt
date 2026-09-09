package com.company.seabattle.game

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты автоматической расстановки кораблей.
 *
 * Воспроизводит баг, который был в продакшене: при случайной расстановке
 * алгоритм не мог разместить корабль за 500 попыток и падал с
 * IllegalStateException. После исправления (перебор всех валидных позиций
 * вместо случайных попыток) расстановка должна работать всегда.
 */
class BoardTest {

    @Test
    fun `случайная расстановка создаёт ровно 10 кораблей`() {
        val board = Board.random()
        assertEquals(10, board.ships().size, "Должно быть 10 кораблей (1x4 + 2x3 + 3x2 + 4x1)")
    }

    @Test
    fun `флот соответствует стандартным правилам морского боя`() {
        val board = Board.random()
        val bySize = board.ships().groupBy { it.size }
        assertEquals(1, bySize[4]?.size, "Должен быть 1 четырёхпалубник")
        assertEquals(2, bySize[3]?.size, "Должно быть 2 трёхпалубника")
        assertEquals(3, bySize[2]?.size, "Должно быть 3 двухпалубника")
        assertEquals(4, bySize[1]?.size, "Должно быть 4 однопалубника")
    }

    @Test
    fun `корабли не соприкасаются друг с другом`() {
        val board = Board.random()
        val allCells = board.ships().flatMap { it.cells }.toSet()

        for (ship in board.ships()) {
            for (cell in ship.cells) {
                // Проверяем все 8 соседних клеток
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = cell.row + dr
                        val nc = cell.col + dc
                        if (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE) {
                            val neighbor = Coord(nr, nc)
                            // Соседняя клетка не должна принадлежать другому кораблю
                            if (neighbor in allCells) {
                                assertTrue(
                                    ship.cells.contains(neighbor),
                                    "Корабли соприкасаются: клетка $neighbor рядом с $cell принадлежит другому кораблю"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `все корабли находятся в пределах поля`() {
        val board = Board.random()
        for (ship in board.ships()) {
            for (cell in ship.cells) {
                assertTrue(cell.row in 0 until Board.SIZE, "Координата row вне поля: ${cell.row}")
                assertTrue(cell.col in 0 until Board.SIZE, "Координата col вне поля: ${cell.col}")
            }
        }
    }

    @Test
    fun `расстановка работает 1000 раз подряд без ошибок`() {
        // Это регрессионный тест на баг из продакшена:
        // раньше placeRandomShip падал с "Не удалось разместить корабль за 500 попыток"
        repeat(1000) { i ->
            val rng = Random(i.toLong()) // детерминированный seed для воспроизводимости
            val board = Board.random(rng)
            assertEquals(10, board.ships().size, "Итерация $i: должно быть 10 кораблей")
        }
    }

    @Test
    fun `расстановка с разными seed даёт разные результаты`() {
        val board1 = Board.random(Random(1))
        val board2 = Board.random(Random(2))
        // Вероятность совпадения расстановок ничтожно мала
        val cells1 = board1.ships().flatMap { it.cells }.toSet()
        val cells2 = board2.ships().flatMap { it.cells }.toSet()
        assertFalse(cells1 == cells2, "Расстановки с разными seed не должны совпадать")
    }

    @Test
    fun `выстрел по воде возвращает промах`() {
        val board = Board.random()
        // Ищем клетку с водой
        var target: Coord? = null
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.cellAt(r, c) == Cell.WATER) {
                    target = Coord(r, c)
                    break
                }
            }
            if (target != null) break
        }
        val result = board.fire(target!!)
        assertFalse(result.hit, "Выстрел по воде не должен быть попаданием")
        assertFalse(result.sunk, "Выстрел по воде не должен топить корабль")
        assertFalse(result.already, "Первый выстрел в клетку не должен быть 'already'")
    }

    @Test
    fun `выстрел по кораблю возвращает попадание`() {
        val board = Board.random()
        // Ищем клетку с кораблём
        var target: Coord? = null
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.cellAt(r, c) == Cell.SHIP) {
                    target = Coord(r, c)
                    break
                }
            }
            if (target != null) break
        }
        val result = board.fire(target!!)
        assertTrue(result.hit, "Выстрел по кораблю должен быть попаданием")
    }

    @Test
    fun `повторный выстрел в ту же клетку возвращает already`() {
        val board = Board.random()
        var target: Coord? = null
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.cellAt(r, c) == Cell.WATER) {
                    target = Coord(r, c)
                    break
                }
            }
            if (target != null) break
        }
        board.fire(target!!)
        val result = board.fire(target!!)
        assertTrue(result.already, "Повторный выстрел должен вернуть already=true")
    }

    @Test
    fun `уничтожение однопалубника помечает ореол как промахи`() {
        val board = Board.random()
        // Находим однопалубник
        val singleShip = board.ships().first { it.size == 1 }
        val target = singleShip.cells.first()
        val result = board.fire(target)
        assertTrue(result.hit, "Должно быть попадание")
        assertTrue(result.sunk, "Однопалубник должен быть уничтожен одним выстрелом")
        // Все клетки вокруг должны стать MISS
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = target.row + dr
                val nc = target.col + dc
                if (nr in 0 until Board.SIZE && nc in 0 until Board.SIZE) {
                    val cell = board.cellAt(nr, nc)
                    assertTrue(
                        cell == Cell.MISS || cell == Cell.SHIP || cell == Cell.SUNK,
                        "Клетка ($nr,$nc) вокруг уничтоженного корабля должна быть MISS/SHIP/SUNK, а не WATER. Было: $cell"
                    )
                }
            }
        }
    }

    @Test
    fun `allSunk возвращает false для нового поля`() {
        val board = Board.random()
        assertFalse(board.allSunk(), "Новое поле не должно быть полностью уничтожено")
    }

    @Test
    fun `aliveShipsCount возвращает 10 для нового поля`() {
        val board = Board.random()
        assertEquals(10, board.aliveShipsCount(), "Новое поле должно иметь 10 живых кораблей")
    }

    @Test
    fun `sunk ships are grouped by deck count`() {
        val board = Board.random()
        val twoDeckShip = board.ships().first { it.size == 2 }
        twoDeckShip.cells.forEach { board.fire(it) }
        assertEquals(mapOf(2 to 1), board.sunkShipsBySize())
    }
}
