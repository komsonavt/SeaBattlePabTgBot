package com.company.seabattle.game

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

/**
 * Рендеринг игровых полей.
 *
 * - Своё поле отрисовывается в markdown-вёрстке (моноширинный блок).
 * - Поле соперника отрисовывается как inline-клавиатура из кнопок,
 *   нажатие на которую = выстрел по координате.
 */
object BoardRenderer {

    /**
     * Построить текстовое (markdown) представление своего поля.
     *
     * @param board своё поле
     * @param title заголовок над полем
     */
    fun renderOwnMarkdown(board: Board, title: String = "🛥 Ваше поле"): String {
        val sb = StringBuilder()
        sb.append("*").append(title).append("*\n")
        sb.append("```\n")
        // шапка с буквами колонок
        sb.append("   ")
        for (c in 0 until Board.SIZE) sb.append(Coord.COL_LETTERS[c]).append(' ')
        sb.append('\n')
        for (r in 0 until Board.SIZE) {
            val rowNum = (r + 1).toString().padStart(2, ' ')
            sb.append(rowNum).append(' ')
            for (c in 0 until Board.SIZE) {
                sb.append(asciiForOwn(board.cellAt(r, c)))
                if (c < Board.SIZE - 1) sb.append(' ')
            }
            sb.append('\n')
        }
        sb.append("```")
        return sb.toString()
    }

    /**
     * ASCII-символ для своего поля в моноширинном блоке.
     * Используем символы, чтобы поле выглядело ровно в code-блоке.
     */
    private fun asciiForOwn(cell: Cell): Char = when (cell) {
        Cell.WATER -> '·'
        Cell.SHIP -> '■'
        Cell.HIT -> '✕'
        Cell.MISS -> '○'
        Cell.SUNK -> '✖'
    }

    /**
     * Построить inline-клавиатуру поля соперника.
     *
     * Telegram ограничивает inline-клавиатуру 8 кнопками в строке,
     * поэтому поле 10×10 разбивается на две половины по 5 столбцов
     * (А-Д и Е-К). Каждая строка = 1 номер + 5 клеток = 6 кнопок.
     *
     * Кнопки, по которым уже стреляли, остаются неактивными (callback "noop").
     * По остальным можно стрелять.
     *
     * @param enemyBoard поле соперника (с точки зрения стреляющего)
     * @param callbackPrefix префикс callback-данных, например "shoot:"
     * @param disabledCells клетки, в которые стрелять нельзя (уже обстреляны)
     */
    fun renderEnemyKeyboard(
        enemyBoard: Board,
        callbackPrefix: String,
        disabledCells: Set<Coord> = emptySet()
    ): InlineKeyboardMarkup {
        val rows = mutableListOf<InlineKeyboardRow>()
        val half = Board.SIZE / 2 // 5

        // Две половины: столбцы 0..4 (А-Д) и 5..9 (Е-К)
        for (startCol in listOf(0, half)) {
            // Шапка с буквами колонок (неактивные кнопки)
            val headerRow = InlineKeyboardRow()
            headerRow.add(InlineKeyboardButton("⬇️").apply { callbackData = "noop" })
            for (c in startCol until startCol + half) {
                headerRow.add(InlineKeyboardButton(Coord.COL_LETTERS[c].toString()).apply { callbackData = "noop" })
            }
            rows.add(headerRow)

            for (r in 0 until Board.SIZE) {
                val row = InlineKeyboardRow()
                // Номер строки слева
                row.add(InlineKeyboardButton((r + 1).toString()).apply { callbackData = "noop" })
                for (c in startCol until startCol + half) {
                    val coord = Coord(r, c)
                    val cell = enemyBoard.cellAt(r, c)
                    val icon = Icons.forEnemy(cell)
                    val alreadyShot = cell == Cell.HIT || cell == Cell.MISS || cell == Cell.SUNK
                    val disabled = coord in disabledCells
                    val callback = if (alreadyShot || disabled) "noop" else "$callbackPrefix${coord.row},${coord.col}"
                    row.add(InlineKeyboardButton(icon).apply { this.callbackData = callback })
                }
                rows.add(row)
            }
        }
        return InlineKeyboardMarkup(rows)
    }
}
