package com.company.seabattle.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.company.seabattle.copy.Copy
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

data class BrandEmoji(val id: String? = null, val alt: String) {
    init {
        require(id == null || id.matches(Regex("[0-9]+"))) { "custom_emoji_id must be numeric" }
        require(alt.isNotBlank()) { "Emoji alternative text is required" }
    }
    fun html(): String = if (id == null) escapeHtml(alt)
        else "<tg-emoji emoji-id=\"$id\">${escapeHtml(alt)}</tg-emoji>"
}

class BoardTheme(private val roles: Map<String, BrandEmoji>) {
    fun cell(cell: Cell, enemy: Boolean = false): String = roles.getValue(
        if (enemy) when (cell) {
            Cell.WATER, Cell.SHIP -> "sea"
            Cell.MISS -> "miss"
            Cell.HIT, Cell.SUNK -> "ship"
        } else when (cell) {
            Cell.WATER -> "sea"
            Cell.SHIP -> "ship"
            Cell.MISS -> "miss"
            Cell.HIT -> "hit"
            Cell.SUNK -> "sunk"
        }
    ).html()
    companion object {
        private val defaults = mapOf("sea" to "🌊", "ship" to "🚢", "miss" to "💥", "hit" to "💣", "sunk" to "☠️")
        fun load(path: String? = System.getenv("EMOJI_FILE") ?: System.getenv("BRAND_EMOJI_FILE")): BoardTheme {
            if (path.isNullOrBlank()) return BoardTheme(defaults.mapValues { BrandEmoji(alt = it.value) })
            val file = Path.of(path).toFile()
            val configured = if (path.lowercase().endsWith(".xml")) {
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                defaults.keys.associateWith { key ->
                    val node = requireNotNull(root.getElementsByTagName(key).item(0)) { "Missing emoji role: $key" } as org.w3c.dom.Element
                    BrandEmoji(node.getAttribute("id").ifBlank { null }, node.getAttribute("alt").ifBlank { defaults.getValue(key) })
                }
            } else {
                val json = jacksonObjectMapper().readTree(file)
                defaults.keys.associateWith { key ->
                    val item = requireNotNull(json[key]) { "Missing emoji role: $key" }
                    BrandEmoji(item["id"]?.takeUnless { it.isNull }?.asText(), item["alt"]?.asText().orEmpty())
                }
            }
            return BoardTheme(configured)
        }
    }
}

fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** Two Rich Message cards. Hidden ships never enter enemy markup. */
class RichBoardRenderer(private val theme: BoardTheme = BoardTheme.load()) {
    fun own(board: Board): String = buildString {
        append("<h3>${Copy.text("own_board")}</h3><table compact bordered><tr><th></th>")
        Coord.COL_LETTERS.forEach { append("<th>$it</th>") }
        append("</tr>")
        for (r in 0 until Board.SIZE) {
            append("<tr><th>${r + 1}</th>")
            for (c in 0 until Board.SIZE) append("<td>${theme.cell(board.cellAt(r, c))}</td>")
            append("</tr>")
        }
        append("</table>")
    }
    fun enemy(board: Board, gameId: String, revision: Long, half: Int, canFire: Boolean, notice: String,
        finished: Boolean = false, vsComputer: Boolean = false, confirmSurrender: Boolean = false): String = buildString {
        require(half in 0..1)
        append("<p>${escapeHtml(notice)}</p><h3>${Copy.text("board_anchor")}</h3>")
        for (r in 0 until Board.SIZE) {
            append("<tg-button-row>")
            for (c in half * 5 until half * 5 + 5) {
                val cell = board.cellAt(r, c)
                val unknown = cell == Cell.WATER || cell == Cell.SHIP
                // У всех клеток — только эмодзи. Постоянный заголовок выше служит
                // якорем ширины сообщения, поэтому попадания и промахи не сжимают
                // карточку на телефоне.
                val label = theme.cell(if (unknown) Cell.WATER else cell, enemy = true)
                if (unknown && canFire) {
                    val data = GameAction(gameId, revision, "fire", r * 10 + c).encode()
                    append("<tg-button type=\"callback_data\" data=\"$data\">$label</tg-button>")
                } else append("<tg-button type=\"disabled\">$label</tg-button>")
            }
            append("</tg-button-row>")
        }
        append("<tg-button-row>")
        for (page in 0..1) {
            val label = if (page == 0) "← А–Д" else "Е–К →"
            if (page == half) append("<tg-button type=\"disabled\">$label</tg-button>")
            else append("<tg-button type=\"callback_data\" data=\"${GameAction(gameId, revision, "half", page).encode()}\">$label</tg-button>")
        }
        append("</tg-button-row>")
        append("<tg-button-row>")
        if(!finished) {
            if(confirmSurrender) {
                append(button(Copy.text("surrender_yes"),GameAction(gameId,revision,"confirm",0).encode()))
                append(button(Copy.text("fight_on"),GameAction(gameId,revision,"cancel",0).encode()))
            } else append(button(Copy.text("surrender"),GameAction(gameId,revision,"surrender",0).encode()))
        } else {
            append(button(Copy.text("leaderboard_button"),"leaderboard"))
            if(vsComputer) append(button(Copy.text("replay"),"mode_cpu"))
        }
        if (vsComputer || finished) append(button(Copy.text("to_menu"),"menu"))
        append("</tg-button-row>")
    }
    private fun button(text: String, data: String) = "<tg-button type=\"callback_data\" data=\"$data\">$text</tg-button>"
}
