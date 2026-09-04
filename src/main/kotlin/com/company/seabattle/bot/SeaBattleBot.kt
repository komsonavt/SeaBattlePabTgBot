package com.company.seabattle.bot

import com.company.seabattle.access.AccessGuard
import com.company.seabattle.config.BotConfig
import com.company.seabattle.game.Board
import com.company.seabattle.game.BoardRenderer
import com.company.seabattle.game.Coord
import com.company.seabattle.state.GameMode
import com.company.seabattle.state.GameSession
import com.company.seabattle.state.GameStore
import com.company.seabattle.state.TournamentPhase
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Главный обработчик обновлений Telegram-бота «Морской бой».
 *
 * Реализует три режима:
 * 1. Игра против компьютера.
 * 2. Игра против коллеги (через пригласительную deep-link ссылку).
 * 3. Турнирный режим (регистрация → группы → плей-офф).
 *
 * Доступ ограничен членами корпоративного чата (через [AccessGuard]).
 */
class SeaBattleBot(
    private val config: BotConfig,
    private val store: GameStore
) : LongPollingSingleThreadUpdateConsumer {

    val client: TelegramClient = OkHttpTelegramClient(config.botToken)
    private val access = AccessGuard(client, config.corporateChatId, config.adminIds)

    /** Планировщик для таймеров хода. */
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "TurnTimeoutThread").apply { isDaemon = true }
    }

    /** Активные таймеры по id сессии (для отмены при завершении хода). */
    private val turnTimers = ConcurrentHashMap<String, ScheduledFuture<*>>()

    override fun consume(update: Update) {
        try {
            if (update.hasMessage() && update.message.hasText()) {
                handleMessage(update)
            } else if (update.hasCallbackQuery()) {
                handleCallback(update)
            }
        } catch (e: Exception) {
            // Логируем, но не падаем — бот должен продолжать работать.
            println("Ошибка обработки обновления: ${e.message}")
            e.printStackTrace()
        }
    }

    // ===================== Текстовые команды =====================

    private fun handleMessage(update: Update) {
        val msg = update.message
        val userId = msg.from.id
        val chatId = msg.chatId
        val text = msg.text.trim()

        // /start может содержать payload (deep-link) — обрабатываем отдельно
        if (text.startsWith("/start")) {
            val payload = text.removePrefix("/start").trim()
            if (payload.isNotEmpty()) {
                handleStartWithPayload(userId, chatId, payload)
                return
            }
            handleStart(userId, chatId)
            return
        }

        // Все остальные команды требуют доступа
        if (!checkAccess(userId, chatId)) return

        when {
            text == "/menu" || text == "меню" || text == "Меню" -> showMenu(chatId)
            text == "/play_cpu" || text == "1" -> startVsComputer(userId, chatId)
            text == "/play_friend" || text == "2" -> startVsColleague(userId, chatId)
            text == "/tournament" || text == "3" -> showTournamentMenu(userId, chatId)
            text == "/surrender" || text == "сдаться" -> surrender(userId, chatId)
            text == "/help" -> sendHelp(chatId)
            text == "/mygames" -> showMyGames(userId, chatId)
            else -> {
                // если игрок в активной сессии — подсказываем
                val session = store.getSessionByPlayer(userId)
                if (session != null && !session.finished) {
                    BotHelper.sendText(
                        client, chatId,
                        "У вас идёт игра. Жмите на клетки поля соперника, чтобы стрелять. " +
                            "Команда /surrender — сдаться."
                    )
                } else {
                    showMenu(chatId)
                }
            }
        }
    }

    private fun handleStart(userId: Long, chatId: Long) {
        if (!checkAccess(userId, chatId)) return
        showMenu(chatId)
    }

    /** Обработка /start с payload (приглашение в игру с коллегой). */
    private fun handleStartWithPayload(userId: Long, chatId: Long, payload: String) {
        if (!checkAccess(userId, chatId)) return
        // payload = "join_<inviteId>"
        if (payload.startsWith("join_")) {
            val inviteId = payload.removePrefix("join_")
            val session = store.acceptInvite(inviteId, userId)
            if (session == null) {
                BotHelper.sendText(
                    client, chatId,
                    "Приглашение недействительно или уже принято. " +
                        "Возможно, создатель отменил игру."
                )
                return
            }
            startColleagueGame(session)
        } else {
            showMenu(chatId)
        }
    }

    // ===================== Меню =====================

    private fun showMenu(chatId: Long) {
        val text = """
            *⚓️ Морской бой*

            Выберите режим игры:
        """.trimIndent()
        val kb = BotHelper.keyboard(
            listOf(
                listOf("🤖 Против компьютера" to "mode_cpu"),
                listOf("👥 Против коллеги" to "mode_friend"),
                listOf("🏆 Турнир" to "mode_tournament"),
                listOf("❓ Помощь" to "help")
            )
        )
        BotHelper.sendText(client, chatId, text, replyMarkup = kb)
    }

    private fun sendHelp(chatId: Long) {
        val text = """
            *❓ Как играть*

            *Правила:* классический морской бой 10×10.
            Флот: 1×4, 2×3, 3×2, 4×1 кораблей.

            *Управление:*
            • Поле соперника — это кнопки. Нажимайте на клетку, чтобы выстрелить.
            • Своё поле отображается текстом (markdown) над клавиатурой.
            • Попадание — ход остаётся за вами. Промах — ход переходит сопернику.

            *Команды:*
            /menu — главное меню
            /surrender — сдаться в текущей партии
            /mygames — статус ваших игр
            /help — эта справка

            *Режимы:*
            🤖 Против компьютера — быстрая игра с ИИ.
            👥 Против коллеги — создайте приглашение и отправьте ссылку коллеге.
            🏆 Турнир — регистрация, затем группы и плей-офф на выбывание.
        """.trimIndent()
        BotHelper.sendText(client, chatId, text)
    }

    // ===================== Контроль доступа =====================

    private fun checkAccess(userId: Long, chatId: Long): Boolean {
        if (access.isAllowed(userId)) return true
        BotHelper.sendText(
            client, chatId,
            "⛔️ Доступ закрыт. Этот бот только для сотрудников.\n" +
                "Вы должны состоять в корпоративном чате, чтобы пользоваться ботом."
        )
        return false
    }

    // ===================== Режим: против компьютера =====================

    private fun startVsComputer(userId: Long, chatId: Long) {
        val session = store.createVsComputerSession(userId)
        renderGameForPlayer(session, userId, chatId, firstStart = true)
        scheduleTurnTimeout(session)
    }

    // ===================== Режим: против коллеги =====================

    private fun startVsColleague(userId: Long, chatId: Long) {
        val inviteId = store.createInvite(userId)
        val link = "https://t.me/${config.botUsername}?start=join_$inviteId"
        val text = """
            *👥 Игра с коллегой*

            Отправьте эту ссылку коллеге:

            `$link`

            Когда коллега перейдёт по ней, игра начнётся автоматически.
            Ссылка одноразовая — действует до принятия или отмены.
        """.trimIndent()
        val kb = BotHelper.keyboard(listOf(listOf("❌ Отменить приглашение" to "cancel_invite")))
        BotHelper.sendText(client, chatId, text, replyMarkup = kb)
    }

    private fun startColleagueGame(session: GameSession) {
        // Оповещаем обоих игроков и показываем поля
        renderGameForPlayer(session, session.player1Id, session.player1Id, firstStart = true)
        renderGameForPlayer(session, session.player2Id, session.player2Id, firstStart = true)
        scheduleTurnTimeout(session)
    }

    // ===================== Режим: турнир =====================

    private fun showTournamentMenu(userId: Long, chatId: Long) {
        val tournament = store.activeTournament()
        if (tournament == null) {
            // Нет активного турнира — только админ может создать
            if (userId in config.adminIds) {
                val kb = BotHelper.keyboard(listOf(listOf("🏆 Создать турнир" to "tourn_create")))
                BotHelper.sendText(client, chatId, "Активных турниров нет.", replyMarkup = kb)
            } else {
                BotHelper.sendText(
                    client, chatId,
                    "Сейчас нет активного турнира. Следите за новостями в корпоративном чате!"
                )
            }
            return
        }
        renderTournamentStatus(userId, chatId, tournament)
    }

    private fun renderTournamentStatus(userId: Long, chatId: Long, tournament: com.company.seabattle.state.Tournament) {
        val sb = StringBuilder()
        sb.append("*🏆 Турнир*\n")
        sb.append("Фаза: ").append(when (tournament.phase) {
            TournamentPhase.REGISTRATION -> "📋 Регистрация"
            TournamentPhase.GROUP_STAGE -> "👥 Групповой этап"
            TournamentPhase.PLAYOFF -> "🏅 Плей-офф"
            TournamentPhase.FINISHED -> "🏁 Завершён"
        }).append("\n")
        sb.append("Участников: ").append(tournament.participantCount()).append("\n\n")

        val rows = mutableListOf<List<Pair<String, String>>>()

        when (tournament.phase) {
            TournamentPhase.REGISTRATION -> {
                val registered = tournament.isRegistered(userId)
                sb.append(if (registered) "Вы зарегистрированы ✅" else "Вы ещё не зарегистрированы")
                if (!registered) {
                    rows.add(listOf("✅ Зарегистрироваться" to "tourn_register"))
                }
                if (userId in config.adminIds) {
                    rows.add(listOf("▶️ Начать групповой этап" to "tourn_start_groups"))
                }
            }
            TournamentPhase.GROUP_STAGE -> {
                sb.append(renderGroupsText(tournament))
                val match = tournament.findPendingGroupMatch(userId)
                if (match != null) {
                    sb.append("\nУ вас есть матч в группе. Начните его!")
                    rows.add(listOf("⚔️ Начать матч" to "tourn_start_match:${match.id}"))
                } else {
                    sb.append("\nУ вас нет ожидающих матчей в группе.")
                }
                if (userId in config.adminIds && tournament.groupStageComplete()) {
                    rows.add(listOf("▶️ Начать плей-офф" to "tourn_start_playoff"))
                }
            }
            TournamentPhase.PLAYOFF -> {
                sb.append(renderPlayoffText(tournament))
                val match = tournament.findPendingPlayoffMatch(userId)
                if (match != null && match.player2Id != 0L) {
                    sb.append("\nУ вас есть матч плей-офф. Начните его!")
                    rows.add(listOf("⚔️ Начать матч" to "tourn_start_match:${match.id}"))
                } else if (match != null) {
                    sb.append("\nВы прошли в следующий раунд автоматически (bye).")
                }
            }
            TournamentPhase.FINISHED -> {
                val champ = tournament.champion()
                sb.append("\n🥇 Победитель турнира: ").append(tournament.participants[champ] ?: "—")
            }
        }
        rows.add(listOf("⬅️ В меню" to "menu"))
        val kb = BotHelper.keyboard(rows)
        BotHelper.sendText(client, chatId, sb.toString(), replyMarkup = kb)
    }

    private fun renderGroupsText(t: com.company.seabattle.state.Tournament): String {
        val sb = StringBuilder()
        for (g in t.groups) {
            sb.append("\n*").append(g.name).append("*\n")
            val scores = g.scores()
            for ((pid, score) in g.ranking()) {
                val name = t.participants[pid] ?: pid.toString()
                sb.append("  ").append(name).append(" — ").append(score).append("\n")
            }
        }
        return sb.toString()
    }

    private fun renderPlayoffText(t: com.company.seabattle.state.Tournament): String {
        val sb = StringBuilder("\n*Сетка плей-офф:*\n")
        val maxRound = t.playoffMatches.maxOfOrNull { it.round } ?: 1
        for (round in 1..maxRound) {
            sb.append("Раунд ").append(round).append(":\n")
            t.playoffMatches.filter { it.round == round }.forEach { m ->
                val p1 = t.participants[m.player1Id] ?: "—"
                val p2 = if (m.player2Id != 0L) t.participants[m.player2Id] else "bye"
                val result = when {
                    !m.played -> "vs"
                    m.winnerId == m.player1Id -> "▶"
                    else -> "◀"
                }
                sb.append("  ").append(p1).append(" ").append(result).append(" ").append(p2).append("\n")
            }
        }
        return sb.toString()
    }

    // ===================== Callback-обработка =====================

    private fun handleCallback(update: Update) {
        val cq = update.callbackQuery
        val userId = cq.from.id
        val chatId = cq.message?.chatId ?: return
        val data = cq.data

        println("[CALLBACK] userId=$userId, chatId=$chatId, data=$data")

        BotHelper.answerCallback(client, cq.id)

        // Доступ проверяем для всех callback'ов
        if (!access.isAllowed(userId)) {
            BotHelper.sendText(client, chatId, "⛔️ Доступ закрыт.")
            return
        }

        when {
            data == "menu" -> {
                BotHelper.editText(client, chatId, cq.message.messageId.toLong(), "Главное меню")
                showMenu(chatId)
            }
            data == "help" -> sendHelp(chatId)
            data == "mode_cpu" -> startVsComputer(userId, chatId)
            data == "mode_friend" -> startVsColleague(userId, chatId)
            data == "mode_tournament" -> showTournamentMenu(userId, chatId)
            data == "cancel_invite" -> {
                store.cancelInvite(userId)
                BotHelper.editText(client, chatId, cq.message.messageId.toLong(), "Приглашение отменено.")
            }
            // Выстрел по полю соперника: "shoot:r,c" (для компьютера) или "shoot_p2:r,c" / "shoot_p1:r,c"
            data.startsWith("shoot:") -> handleShoot(userId, chatId, data.removePrefix("shoot:"))
            data.startsWith("shoot_p1:") -> handleShootPvp(userId, chatId, data.removePrefix("shoot_p1:"), isShooterPlayer1 = false)
            data.startsWith("shoot_p2:") -> handleShootPvp(userId, chatId, data.removePrefix("shoot_p2:"), isShooterPlayer1 = true)
            // Турнир
            data == "tourn_create" -> handleTournamentCreate(userId, chatId)
            data == "tourn_register" -> handleTournamentRegister(userId, chatId)
            data == "tourn_start_groups" -> handleTournamentStartGroups(userId, chatId)
            data == "tourn_start_playoff" -> handleTournamentStartPlayoff(userId, chatId)
            data.startsWith("tourn_start_match:") -> handleTournamentStartMatch(userId, chatId, data.removePrefix("tourn_start_match:"))
            data == "noop" -> { /* пустой callback */ }
        }
    }

    // ===================== Логика выстрелов =====================

    /** Выстрел в режиме против компьютера. */
    private fun handleShoot(userId: Long, chatId: Long, coords: String) {
        println("[SHOOT] userId=$userId, coords=$coords (режим: против компьютера)")
        val session = store.getSessionByPlayer(userId) ?: run {
            println("[SHOOT] сессия не найдена для userId=$userId")
            return
        }
        if (session.mode != GameMode.VS_COMPUTER || session.finished) return
        if (!session.turnIsPlayer1) {
            BotHelper.sendText(client, chatId, "Сейчас не ваш ход.")
            return
        }
        val coord = parseCoord(coords) ?: run {
            println("[SHOOT] не удалось распарсить координаты: $coords")
            return
        }
        println("[SHOOT] выстрел по координате: row=${coord.row}, col=${coord.col} (${Coord.COL_LETTERS[coord.col]}${coord.row + 1})")
        // Стреляем по полю компьютера (board2)
        val result = session.board2.fire(coord)
        println("[SHOOT] результат: hit=${result.hit}, sunk=${result.sunk}, already=${result.already}")
        if (result.already) {
            BotHelper.sendText(client, chatId, "Вы уже стреляли в эту клетку.")
            return
        }
        // Обновляем поле игрока
        renderGameForPlayer(session, userId, chatId, firstStart = false)

        if (result.sunk && session.board2.allSunk()) {
            finishGame(session, winnerId = userId, chatId)
            return
        }
        if (!result.hit) {
            // Ход переходит к компьютеру
            session.switchTurn()
            store.persistSession(session)
            computerTurn(session, userId, chatId)
        } else {
            // попадание — ход остаётся у игрока, продлеваем таймер
            scheduleTurnTimeout(session)
        }
    }

    /** Ход компьютера. */
    private fun computerTurn(session: GameSession, playerChatId: Long, chatId: Long) {
        val ai = session.ai ?: return
        BotHelper.sendText(client, chatId, "🤖 Компьютер стреляет...")
        // Компьютер стреляет, пока не промахнётся
        while (true) {
            val target = ai.chooseTarget(session.board1)
            val result = session.board1.fire(target)
            ai.onShotResult(result)

            // Обновляем поле игрока (показываем попадания на своём поле)
            renderGameForPlayer(session, playerChatId, chatId, firstStart = false)

            if (result.sunk && session.board1.allSunk()) {
                finishGame(session, winnerId = 0L, chatId)
                return
            }
            if (!result.hit) {
                // промах — ход обратно игроку
                session.switchTurn()
                store.persistSession(session)
                BotHelper.sendText(client, chatId, "Ваш ход! Стреляйте по полю соперника.")
                scheduleTurnTimeout(session)
                return
            }
            // попадание — компьютер стреляет снова
        }
    }

    /** Выстрел в PvP-режиме (против коллеги или турнир). */
    private fun handleShootPvp(userId: Long, chatId: Long, coords: String, isShooterPlayer1: Boolean) {
        val session = store.getSessionByPlayer(userId) ?: return
        if (session.vsComputer || session.finished) return
        val shooterIsP1 = userId == session.player1Id
        if (shooterIsP1 != session.turnIsPlayer1) {
            BotHelper.sendText(client, chatId, "Сейчас не ваш ход.")
            return
        }
        val coord = parseCoord(coords) ?: return
        // Стреляющий стреляет по полю соперника
        val enemyBoard = if (shooterIsP1) session.board2 else session.board1
        val result = enemyBoard.fire(coord)
        if (result.already) {
            BotHelper.sendText(client, chatId, "Вы уже стреляли в эту клетку.")
            return
        }

        val shooterId = if (shooterIsP1) session.player1Id else session.player2Id
        val enemyId = if (shooterIsP1) session.player2Id else session.player1Id

        // Обновляем клавиатуру стреляющего
        renderGameForPlayer(session, shooterId, chatId, firstStart = false)
        // Оповещаем соперника о выстреле по нему
        notifyEnemyOfIncoming(session, enemyId, coord, result)

        if (result.sunk && enemyBoard.allSunk()) {
            finishGame(session, winnerId = shooterId, chatId)
            return
        }
        if (!result.hit) {
            session.switchTurn()
            store.persistSession(session)
            BotHelper.sendText(client, chatId, "Промах! Ход переходит сопернику.")
            BotHelper.sendText(
                client, enemyId,
                "Ваш ход! Стреляйте по полю соперника."
            )
            renderGameForPlayer(session, enemyId, enemyId, firstStart = false)
            scheduleTurnTimeout(session)
        } else {
            // попадание — ход остаётся у стреляющего, продлеваем таймер
            scheduleTurnTimeout(session)
        }
    }

    /** Оповестить соперника о выстреле по его полю (показать на своём поле). */
    private fun notifyEnemyOfIncoming(
        session: GameSession,
        enemyId: Long,
        coord: Coord,
        result: Board.ShotResult
    ) {
        val msg = when {
            result.sunk -> "💥 Соперник потопил ваш корабль в ${coord.label()}!"
            result.hit -> "🔥 Соперник попал в ваш корабль в ${coord.label()}!"
            else -> "💧 Соперник промахнулся в ${coord.label()}."
        }
        BotHelper.sendText(client, enemyId, msg)
        // Обновляем отображение своего поля соперника
        renderGameForPlayer(session, enemyId, enemyId, firstStart = false)
    }

    // ===================== Рендеринг игры =====================

    /**
     * Отрисовать состояние игры для конкретного игрока.
     *
     * - Своё поле — в markdown-тексте.
     * - Поле соперника — в inline-клавиатуре под сообщением.
     *
     * При первом старте отправляет новое сообщение.
     * При последующих обновлениях — редактирует существующее.
     */
    private fun renderGameForPlayer(session: GameSession, playerId: Long, chatId: Long, firstStart: Boolean) {
        val isP1 = playerId == session.player1Id
        val ownBoard = if (isP1) session.board1 else session.board2
        val enemyBoard = if (isP1) session.board2 else session.board1
        val ownTitle = "🛥 Ваше поле"
        val enemyTitle = if (session.vsComputer) "🤖 Поле компьютера" else "🎯 Поле соперника"

        val ownText = BoardRenderer.renderOwnMarkdown(ownBoard, ownTitle)
        val turnInfo = if (session.finished) {
            val won = session.winnerId == playerId
            if (won) "🎉 Вы победили!" else "😔 Вы проиграли."
        } else {
            val myTurn = session.turnIsPlayer1 == isP1
            if (myTurn) "Ваш ход — стреляйте!" else "Ход соперника..."
        }

        val fullText = "$ownText\n\n*$enemyTitle*\n_${turnInfo}_"

        // Клавиатура поля соперника
        val prefix = when {
            session.vsComputer -> "shoot:"
            isP1 -> "shoot_p2:" // P1 стреляет по полю P2
            else -> "shoot_p1:"  // P2 стреляет по полю P1
        }
        val keyboard = BoardRenderer.renderEnemyKeyboard(enemyBoard, prefix)

        val storedMsgId = if (isP1) session.enemyKeyboardMessageId1 else session.enemyKeyboardMessageId2

        if (firstStart || storedMsgId == 0L) {
            // Отправляем своё поле отдельным сообщением
            BotHelper.sendText(client, chatId, ownText)
            // Отправляем поле соперника с клавиатурой
            val msgId = BotHelper.sendWithKeyboard(
                client, chatId,
                "*$enemyTitle*\n_${turnInfo}_",
                keyboard
            )
            if (isP1) session.enemyKeyboardMessageId1 = msgId else session.enemyKeyboardMessageId2 = msgId
            store.persistSession(session)
        } else {
            // Редактируем сообщение с клавиатурой
            BotHelper.editText(client, chatId, storedMsgId, fullText, replyMarkup = keyboard)
        }
    }

    // ===================== Завершение игры =====================

    private fun finishGame(session: GameSession, winnerId: Long, chatId: Long) {
        session.setWinner(winnerId)
        cancelTurnTimeout(session.id)
        val loserId = if (winnerId == session.player1Id) session.player2Id else session.player1Id

        // Оповещаем игроков
        if (session.vsComputer) {
            val won = winnerId == session.player1Id
            val text = if (won) "🎉 Поздравляем! Вы победили компьютер!" else "😔 Компьютер потопил все ваши корабли."
            BotHelper.sendText(client, chatId, text)
            renderGameForPlayer(session, session.player1Id, chatId, firstStart = false)
        } else {
            BotHelper.sendText(client, winnerId, "🎉 Вы победили!")
            if (loserId != 0L) BotHelper.sendText(client, loserId, "😔 Вы проиграли.")
            renderGameForPlayer(session, session.player1Id, session.player1Id, firstStart = false)
            renderGameForPlayer(session, session.player2Id, session.player2Id, firstStart = false)
        }

        // Сохраняем финальное состояние в БД
        store.persistSession(session)

        // Если это турнирный матч — записываем результат
        if (session.mode == GameMode.TOURNAMENT && session.tournamentMatchId != null) {
            recordTournamentResult(session, winnerId)
        }

        // Удаляем сессию
        store.removeSession(session.id)
    }

    private fun surrender(userId: Long, chatId: Long) {
        val session = store.getSessionByPlayer(userId) ?: return
        val winnerId = if (userId == session.player1Id) session.player2Id else session.player1Id
        finishGame(session, winnerId, chatId)
    }

    private fun showMyGames(userId: Long, chatId: Long) {
        val session = store.getSessionByPlayer(userId)
        if (session == null || session.finished) {
            BotHelper.sendText(client, chatId, "У вас нет активных игр.")
            return
        }
        val mode = when (session.mode) {
            GameMode.VS_COMPUTER -> "против компьютера"
            GameMode.VS_COLLEAGUE -> "против коллеги"
            GameMode.TOURNAMENT -> "турнирный матч"
        }
        val turn = if (session.turnIsPlayer1 == (userId == session.player1Id)) "ваш ход" else "ход соперника"
        BotHelper.sendText(client, chatId, "Активная игра: $mode. Сейчас $turn.")
    }

    // ===================== Турнирные обработчики =====================

    private fun handleTournamentCreate(userId: Long, chatId: Long) {
        if (userId !in config.adminIds) {
            BotHelper.sendText(client, chatId, "Только администратор может создавать турнир.")
            return
        }
        if (store.activeTournament() != null) {
            BotHelper.sendText(client, chatId, "Уже есть активный турнир.")
            return
        }
        val tournId = "tourn_${System.currentTimeMillis()}"
        store.createTournament(tournId, config.groupSize, config.playersPerGroupAdvance)
        BotHelper.sendText(
            client, chatId,
            "🏆 Турнир создан! Фаза регистрации открыта.\n" +
                "Размер группы: ${config.groupSize}, проходят в плей-офф: ${config.playersPerGroupAdvance} из группы.\n" +
                "Объявите коллегам: отправьте им команду /tournament для регистрации."
        )
    }

    private fun handleTournamentRegister(userId: Long, chatId: Long) {
        val tourn = store.activeTournament()
        if (tourn == null || tourn.phase != TournamentPhase.REGISTRATION) {
            BotHelper.sendText(client, chatId, "Регистрация закрыта или нет активного турнира.")
            return
        }
        val name = cqUserName(userId)
        tourn.register(userId, name)
        BotHelper.sendText(
            client, chatId,
            "✅ Вы зарегистрированы на турнир! Участников: ${tourn.participantCount()}."
        )
    }

    private fun handleTournamentStartGroups(userId: Long, chatId: Long) {
        if (userId !in config.adminIds) {
            BotHelper.sendText(client, chatId, "Только администратор может запустить групповой этап.")
            return
        }
        val tourn = store.activeTournament() ?: return
        if (tourn.startGroupStage()) {
            BotHelper.sendText(
                client, chatId,
                "👥 Групповой этап начался! Участники распределены по ${tourn.groups.size} группам.\n" +
                    "Игроки могут начать свои матчи командой /tournament."
            )
            // Оповещаем всех участников
            for ((pid, _) in tourn.participants) {
                try {
                    BotHelper.sendText(
                        client, pid,
                        "🏆 Групповой этап турнира начался! Нажмите /tournament, чтобы увидеть свои матчи."
                    )
                } catch (e: Exception) { /* игнорируем */ }
            }
        } else {
            BotHelper.sendText(client, chatId, "Не удалось начать: нужно минимум 2 участника.")
        }
    }

    private fun handleTournamentStartPlayoff(userId: Long, chatId: Long) {
        if (userId !in config.adminIds) {
            BotHelper.sendText(client, chatId, "Только администратор может запустить плей-офф.")
            return
        }
        val tourn = store.activeTournament() ?: return
        if (tourn.startPlayoff()) {
            BotHelper.sendText(client, chatId, "🏅 Плей-офф начался! Участники могут начать матчи.")
            for ((pid, _) in tourn.participants) {
                try {
                    BotHelper.sendText(
                        client, pid,
                        "🏆 Плей-офф турнира начался! Нажмите /tournament, чтобы увидеть свои матчи."
                    )
                } catch (e: Exception) { /* игнорируем */ }
            }
        } else {
            BotHelper.sendText(client, chatId, "Групповой этап ещё не завершён.")
        }
    }

    private fun handleTournamentStartMatch(userId: Long, chatId: Long, matchId: String) {
        val tourn = store.activeTournament() ?: return
        val match = tourn.playoffMatches.firstOrNull { it.id == matchId }
            ?: tourn.groups.flatMap { it.matches }.firstOrNull { it.id == matchId }
            ?: return
        if (match.played) {
            BotHelper.sendText(client, chatId, "Этот матч уже сыгран.")
            return
        }
        if (match.player1Id != userId && match.player2Id != userId) {
            BotHelper.sendText(client, chatId, "Вы не участвуете в этом матче.")
            return
        }
        if (match.player2Id == 0L) {
            BotHelper.sendText(client, chatId, "Этот матч — технический пропуск (bye).")
            return
        }
        // Проверяем, что оба игрока свободны
        val s1 = store.getSessionByPlayer(match.player1Id)
        val s2 = store.getSessionByPlayer(match.player2Id)
        if ((s1 != null && !s1.finished) || (s2 != null && !s2.finished)) {
            BotHelper.sendText(
                client, chatId,
                "Один из игроков сейчас в другой игре. Завершите её, чтобы начать турнирный матч."
            )
            return
        }
        tourn.playerActiveMatch[match.player1Id] = matchId
        tourn.playerActiveMatch[match.player2Id] = matchId
        val session = store.createTournamentSession(match.player1Id, match.player2Id, matchId)
        BotHelper.sendText(client, match.player1Id, "🏆 Турнирный матч начался!")
        BotHelper.sendText(client, match.player2Id, "🏆 Турнирный матч начался!")
        renderGameForPlayer(session, match.player1Id, match.player1Id, firstStart = true)
        renderGameForPlayer(session, match.player2Id, match.player2Id, firstStart = true)
        scheduleTurnTimeout(session)
    }

    private fun recordTournamentResult(session: GameSession, winnerId: Long) {
        val tourn = store.activeTournament() ?: return
        val matchId = session.tournamentMatchId ?: return
        tourn.recordResult(matchId, winnerId)
        // Оповещаем участников турнира о результате
        val loserId = if (winnerId == session.player1Id) session.player2Id else session.player1Id
        val winnerName = tourn.participants[winnerId] ?: "Игрок"
        val loserName = tourn.participants[loserId] ?: "Игрок"
        for ((pid, _) in tourn.participants) {
            try {
                BotHelper.sendText(
                    client, pid,
                    "🏆 Результат матча: $winnerName победил $loserName."
                )
            } catch (e: Exception) { /* игнорируем */ }
        }
        if (tourn.phase == TournamentPhase.FINISHED) {
            val champ = tourn.champion()
            val champName = tourn.participants[champ] ?: "—"
            for ((pid, _) in tourn.participants) {
                try {
                    BotHelper.sendText(
                        client, pid,
                        "🏁 Турнир завершён! 🥇 Победитель: $champName"
                    )
                } catch (e: Exception) { /* игнорируем */ }
            }
        }
    }

    // ===================== Таймер хода =====================

    /**
     * Установить таймер на ход: если игрок не ходит за [BotConfig.turnTimeoutSeconds],
     * ему засчитывается техническое поражение.
     *
     * Сохраняет дедлайн в сессию и БД, планирует отложенную проверку.
     */
    private fun scheduleTurnTimeout(session: GameSession) {
        // Отменяем предыдущий таймер, если был
        turnTimers.remove(session.id)?.cancel(false)
        val deadline = System.currentTimeMillis() + config.turnTimeoutSeconds * 1000
        session.updateTurnDeadline(deadline)
        store.persistSession(session)
        val future = scheduler.schedule({
            checkTurnTimeout(session.id)
        }, config.turnTimeoutSeconds, TimeUnit.SECONDS)
        turnTimers[session.id] = future
    }

    /** Отменить таймер хода (например, при завершении игры). */
    private fun cancelTurnTimeout(sessionId: String) {
        turnTimers.remove(sessionId)?.cancel(false)
    }

    /**
     * Проверить, истёк ли таймер хода. Если да — техническое поражение.
     * Вызывается планировщиком по расписанию.
     */
    private fun checkTurnTimeout(sessionId: String) {
        try {
            val session = store.getSession(sessionId) ?: return
            if (session.finished) return
            if (session.turnDeadline <= 0L) return
            if (System.currentTimeMillis() < session.turnDeadline) return

            // Время вышло — техническое поражение текущему ходящему
            val loserId = session.currentTurnPlayerId
            val winnerId = if (loserId == session.player1Id) session.player2Id else session.player1Id

            BotHelper.sendText(
                client, loserId,
                "⏱ Время на ход истекло. Вам засчитано техническое поражение."
            )
            if (!session.vsComputer && winnerId != 0L) {
                BotHelper.sendText(
                    client, winnerId,
                    "⏱ Соперник не сделал ход за отведённое время. Вам присуждена победа!"
                )
            }
            finishGame(session, winnerId, loserId)
        } catch (e: Exception) {
            println("Ошибка проверки таймаута хода: ${e.message}")
            e.printStackTrace()
        }
    }

    // ===================== Утилиты =====================

    private fun parseCoord(coords: String): Coord? {
        val parts = coords.split(",")
        if (parts.size != 2) return null
        val row = parts[0].toIntOrNull() ?: return null
        val col = parts[1].toIntOrNull() ?: return null
        return if (row in 0 until Board.SIZE && col in 0 until Board.SIZE) Coord(row, col) else null
    }

    /** Получить отображаемое имя пользователя (заглушка — id). */
    private fun cqUserName(userId: Long): String = "Игрок $userId"
}
