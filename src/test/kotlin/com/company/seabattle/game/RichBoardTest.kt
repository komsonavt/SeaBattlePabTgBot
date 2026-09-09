package com.company.seabattle.game

import com.company.seabattle.state.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class RichBoardTest {
    private val id = "a".repeat(32)
    private fun session(cpu: Boolean = false) = GameSession(id, 1, if (cpu) 0 else 2, cpu,
        if (cpu) GameMode.VS_COMPUTER else GameMode.VS_COLLEAGUE)

    @Test fun `two halves cover all 100 coordinates without revealing ships`() {
        val renderer = RichBoardRenderer(BoardTheme.load(null))
        val board = Board.random()
        val callbacks = (0..1).flatMap { half ->
            val html = renderer.enemy(board, id, 0, half, true, "<test>")
            assertTrue("&lt;test&gt;" in html)
            assertFalse("🚢" in html)
            Regex("data=\"([^\"]+)\"").findAll(html).mapNotNull { GameAction.parse(it.groupValues[1]) }
                .filter { it.type == "fire" }.map { it.value }.toList()
        }
        assertEquals((0..99).toList(), callbacks.sorted())
    }

    @Test fun `own card has 100 custom emoji and enemy rows have at most five cells`() {
        val theme = BoardTheme(listOf("sea", "ship", "miss", "hit", "sunk").associateWith { BrandEmoji("123", "🌊") })
        val renderer = RichBoardRenderer(theme)
        assertEquals(100, Regex("<tg-emoji ").findAll(renderer.own(Board.random())).count())
        val html = renderer.enemy(Board.random(), id, 1, 0, false, "Wait")
        val rows = Regex("<tg-button-row>(.*?)</tg-button-row>").findAll(html).toList()
        assertEquals(12, rows.size)
        assertTrue(rows.all { Regex("<tg-button ").findAll(it.value).count() <= 5 })
        assertEquals(51, Regex("type=\"disabled\"").findAll(html).count())
    }

    @Test fun `enemy board uses emoji only and has a fixed width anchor`() {
        val renderer = RichBoardRenderer(BoardTheme.load(null))
        val board = Board.random()
        val target = board.ships().first { it.size > 1 }.cells.first()
        board.fire(target)
        val html = renderer.enemy(board, id, 1, target.col / 5, true, "Твой ход")
        assertTrue("⚓ Поле соперника · 10 × 10 · держим курс на победу" in html)
        assertFalse("${target.label()} " in html)
        assertTrue(">🚢</tg-button>" in html)
    }

    @Test fun `a shot refreshes the shooter enemy map and defender own map`() {
        val renderer = RichBoardRenderer(BoardTheme.load(null))
        val game = session()
        val target = game.board2.ships().first().cells.first()
        val shooterEnemyBefore = renderer.enemy(game.board2, id, 0, 0, false, "До выстрела")
        val defenderOwnBefore = renderer.own(game.board2)

        assertTrue(game.fire(1, target))

        val shooterEnemyAfter = renderer.enemy(game.board2, id, 1, 0, false, "Попадание")
        val defenderOwnAfter = renderer.own(game.board2)
        assertNotEquals(shooterEnemyBefore, shooterEnemyAfter)
        assertNotEquals(defenderOwnBefore, defenderOwnAfter)
        assertTrue("⚓ Твоя карта боя · 10 × 10" in defenderOwnAfter)
    }

    @Test fun `opponent map mirrors the own map without exposing untouched ships`() {
        val renderer = RichBoardRenderer(BoardTheme.load(null))
        val board = Board.random()
        val target = board.ships().first { it.size == 1 }.cells.first()
        val hidden = renderer.opponent(board)
        assertTrue("⚓ Карта соперника · 10 × 10" in hidden)
        assertFalse("🚢" in hidden)

        board.fire(target)
        val afterHit = renderer.opponent(board)
        assertNotEquals(hidden, afterHit)
        assertTrue(">☠️</td>" in afterHit)
    }

    @Test fun `only computer game offers a menu exit while active`() {
        val renderer = RichBoardRenderer(BoardTheme.load(null))
        val pvp = renderer.enemy(Board.random(), id, 1, 0, true, "Твой ход", vsComputer = false)
        val cpu = renderer.enemy(Board.random(), id, 1, 0, true, "Твой ход", vsComputer = true)
        assertFalse("data=\"menu\"" in pvp)
        assertTrue("data=\"menu\"" in cpu)
    }

    @Test fun `all active and finished board controls have valid actions`() {
        val renderer = RichBoardRenderer(BoardTheme.load(null))
        val active = renderer.enemy(Board.random(), id, 7, 0, true, "Твой ход")
        val confirmation = renderer.enemy(Board.random(), id, 7, 0, true, "Твой ход", confirmSurrender = true)
        val finishedCpu = renderer.enemy(Board.random(), id, 7, 0, false, "Победа", finished = true, vsComputer = true)
        assertTrue(GameAction.parse("game:$id:7:fire:0") != null)
        assertTrue("game:$id:7:half:1" in active)
        assertTrue("game:$id:7:surrender:0" in active)
        assertTrue("game:$id:7:confirm:0" in confirmation)
        assertTrue("game:$id:7:cancel:0" in confirmation)
        assertTrue("data=\"leaderboard\"" in finishedCpu)
        assertTrue("data=\"mode_cpu\"" in finishedCpu)
        assertTrue("data=\"menu\"" in finishedCpu)
    }

    @Test fun `a human can finish a complete computer game`() {
        val game = session(cpu = true)
        while (!game.finished) {
            if (game.currentTurnPlayerId == 1L) {
                val target = buildList {
                    for (row in 0 until Board.SIZE) for (col in 0 until Board.SIZE) {
                        val cell = game.board2.cellAt(row, col)
                        if (cell == Cell.WATER || cell == Cell.SHIP) add(Coord(row, col))
                    }
                }.first()
                assertTrue(game.fire(1, target))
            } else game.resumeComputerTurn()
        }
        assertTrue(game.finished)
        assertTrue(game.winnerId in setOf(0L, 1L))
        assertEquals(0L, game.turnDeadline)
    }

    @Test fun `callback rejects old game old revision old message and another player`() {
        val s = session()
        s.ui.player1.enemyMessageId = 42
        val action = GameAction(id, 0, "fire", 23)
        assertTrue(s.accepts(action, 1, 42))
        assertFalse(s.accepts(action.copy(gameId = "b".repeat(32)), 1, 42))
        assertFalse(s.accepts(action.copy(revision = 1), 1, 42))
        assertFalse(s.accepts(action, 1, 41))
        assertFalse(s.accepts(action, 3, 42))
        assertNull(GameAction.parse("game:$id:0:fire:100"))
        assertNull(GameAction.parse("game:$id:-1:half:0"))
        assertNull(GameAction.parse("game:$id:0:half:2"))
        assertEquals(action, GameAction.parse(action.encode()))
    }

    @Test fun `duplicate shot and out of turn shot cannot change game`() {
        val s = session()
        val target = s.board2.ships().first().cells.first()
        assertFalse(s.fire(2, target))
        assertTrue(s.fire(1, target))
        val revision = s.ui.player1.revision
        assertFalse(s.fire(1, target))
        assertEquals(revision, s.ui.player1.revision)
        assertTrue(s.turnIsPlayer1)
    }

    @Test fun `restored deadline records a skipped turn and assigns fresh deadline`() {
        val original = session()
        original.turnDeadline = 1000
        val restored = session()
        restored.restoreState(original.exportBoard1(), original.exportBoard2(), null, true, false, 0, 0, 0, original.turnDeadline)
        assertFalse(restored.expire(999))
        assertTrue(restored.expire(1000))
        assertEquals(0, restored.winnerId)
        assertEquals(1, restored.rules.skips1)
        assertEquals(181_000, restored.turnDeadline)
        assertFalse(restored.expire(1001))
    }

    @Test fun `PVP gives three skips then technical loss on fourth`() {
        val s = session()
        repeat(6) { number ->
            s.turnDeadline = 1000L + number
            assertTrue(s.expire(2000L + number))
            assertFalse(s.finished)
        }
        assertEquals(3, s.rules.skips1)
        assertEquals(3, s.rules.skips2)
        s.turnDeadline = 5000
        assertTrue(s.expire(5000))
        assertTrue(s.finished)
        assertEquals("TIMEOUT", s.rules.finishReason)
    }

    @Test fun `computer game has no timeout and expired timer is ignored`() {
        val s = session(cpu = true)
        s.updateTurnDeadline(1)
        assertEquals(0, s.turnDeadline)
        assertFalse(s.expire(Long.MAX_VALUE))
        assertFalse(s.finished)
    }

    @Test fun `restart processes at most one late turn then grants a fresh three minutes`() {
        val s = session()
        s.turnDeadline = 1
        s.recoverDeadline(10_000)
        assertEquals(1, s.rules.skips1)
        assertEquals(190_000, s.turnDeadline)
        s.recoverDeadline(10_001)
        assertEquals(1, s.rules.skips1)
    }

    @Test fun `ui state survives serialization including independent halves`() {
        val mapper = jacksonObjectMapper()
        val ui = GameUi(PlayerUi(10, 11, 1, 7), PlayerUi(20, 21, 0, 9))
        assertEquals(ui, mapper.readValue(mapper.writeValueAsString(ui), GameUi::class.java))
        assertEquals(GameUi(), mapper.readValue("{}", GameUi::class.java))
    }

    @Test fun `changing half does not shoot or extend deadline`() {
        val s = session()
        s.turnDeadline = 1000
        val board = s.exportBoard2().grid
        s.uiFor(1).half = 1
        s.uiFor(1).revision++
        assertTrue(board.contentDeepEquals(s.exportBoard2().grid))
        assertEquals(1000, s.turnDeadline)
        assertTrue(s.turnIsPlayer1)
        assertEquals(0, s.uiFor(2).half)
    }

    @Test fun `old SDK can deserialize a Rich Message callback`() {
        val json = """{"update_id":1,"callback_query":{"id":"x","from":{"id":1,"is_bot":false,"first_name":"Test"},"chat_instance":"x","data":"game:$id:0:fire:23","message":{"message_id":42,"date":1,"chat":{"id":1,"type":"private"},"rich_message":{"blocks":[]}}}}"""
        val update = jacksonObjectMapper().readValue(json, org.telegram.telegrambots.meta.api.objects.Update::class.java)
        assertEquals(42, update.callbackQuery.message.messageId)
        assertEquals(1, update.callbackQuery.from.id)
    }

    @Test fun `AI never wastes turns on known perimeter and finishes the fleet`() {
        repeat(20) { seed ->
            val board = Board.random(kotlin.random.Random(seed))
            val ai = SeaBattleAI(kotlin.random.Random(seed))
            var shots = 0
            while (!board.allSunk()) {
                val target = ai.chooseTarget(board)
                assertTrue(board.cellAt(target.row, target.col) in listOf(Cell.WATER, Cell.SHIP))
                ai.onShotResult(board.fire(target))
                assertTrue(++shots <= 100)
            }
        }
    }
}
