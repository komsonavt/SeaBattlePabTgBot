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
     * Каждая кнопка — клетка 10x10 (без шапки и номеров строк, чтобы
     * уложиться в лимит ширины inline-клавиатуры Telegram).
     * Столбцы: А-К слева направо, строки: 1-10 сверху вниз.
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

        for (r in 0 until Board.SIZE) {
            val row = InlineKeyboardRow()
            for (c in 0 until Board.SIZE) {
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
        val markup = InlineKeyboardMarkup(rows)
        // Лог отрисовки клавиатуры: сколько строк и кнопок в каждой
        val buttonsPerRow = rows.joinToString(",") { it.size.toString() }
        println("[RENDER] renderEnemyKeyboard: rows=${rows.size}, buttonsPerRow=[$buttonsPerRow], totalButtons=${rows.sumOf { it.size }}")
        return markup
    }
}
