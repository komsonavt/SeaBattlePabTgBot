package com.company.seabattle.bot

import com.company.seabattle.access.AccessGuard
import com.company.seabattle.access.AccessResult
import com.company.seabattle.config.BotConfig
import com.company.seabattle.game.Coord
import com.company.seabattle.game.GameAction
import com.company.seabattle.game.RichBoardRenderer
import com.company.seabattle.state.CommunityStore
import com.company.seabattle.state.GameMode
import com.company.seabattle.state.GameSession
import com.company.seabattle.state.GameStore
import com.company.seabattle.state.PlayerProfile
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** MVP bot: state is committed before asynchronous Rich Message synchronization. */
class SeaBattleBot(private val config: BotConfig, private val store: GameStore) :
    LongPollingSingleThreadUpdateConsumer, AutoCloseable {
    val client: TelegramClient = OkHttpTelegramClient(config.botToken)
    private val access = AccessGuard(client, config.corporateChatId, config.adminIds)
    private val cards = GameCards(store, RichMessageClient(config.botToken), RichBoardRenderer())
    private val rich = RichMessageClient(config.botToken)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GameDeadlineScanner").apply { isDaemon = true }
    }

    init {
        store.sessionsToSync().forEach { cards.request(it, true) }
        scheduler.scheduleWithFixedDelay(::tick, 1, 5, TimeUnit.SECONDS)
    }
    override fun close() { scheduler.shutdownNow(); cards.close() }

    override fun consume(update: Update) = try {
        when {
            update.hasMessage() && update.message.hasText() -> onMessage(update)
            update.hasCallbackQuery() -> onCallback(update)
            else -> Unit
        }
    } catch (e: Exception) { println("Ошибка обработки обновления: ${e.javaClass.simpleName}") }

    private fun onMessage(update: Update) {
        val message = update.message
        val user = message.from
        if (message.chatId != user.id) return
        remember(user)
        val text = message.text.trim()
        if (text.startsWith("/start")) {
            val payload = text.removePrefix("/start").trim().takeIf { it.isNotEmpty() }
            if (payload?.startsWith("join_") == true) store.community.rememberJoin(user.id, payload)
            enter(user.id, payload)
            return
        }
        if (!requireAccess(user.id)) return
        when (text.lowercase()) {
            "/menu", "меню" -> menu(user.id)
            "/play_cpu", "1" -> startCpu(user.id)
            "/play_friend", "2" -> invite(user.id)
            "/leaderboard", "4" -> leaderboard(user.id)
            "/tournament", "3" -> preregistration(user.id)
            "/mygames" -> resume(user.id)
            "/surrender", "сдаться" -> askSurrender(user.id)
            "/help" -> help(user.id)
            else -> menu(user.id)
        }
    }

    private fun onCallback(update: Update) {
        val query = update.callbackQuery
        val message = query.message ?: return
        val user = query.from
        if (message.chatId != user.id) return
        remember(user); BotHelper.answerCallback(client, query.id)
        val data = query.data ?: return
        if (data == "access_check") { enter(user.id, store.community.pendingJoin(user.id)); return }
        if (!requireAccess(user.id)) return
        when {
            data == "menu" -> if (store.getColleagueSession(user.id) != null) resume(user.id) else menu(user.id)
            data == "mode_cpu" -> startCpu(user.id)
            data == "mode_friend" -> invite(user.id)
            data == "leaderboard" -> leaderboard(user.id)
            data == "tournament" -> preregistration(user.id)
            data == "preregister" -> { store.community.register(user.id); preregistration(user.id) }
            data == "cancel_preregister" -> { store.community.cancelRegistration(user.id); preregistration(user.id) }
            data == "cancel_invite" -> { store.cancelInvite(user.id); menu(user.id) }
            data == "resume" -> resume(user.id)
            data == "download_registrations" && user.id in config.adminIds -> registrationExport(user.id)
            data.startsWith("game:") -> gameAction(user.id, message.messageId.toLong(), data)
        }
    }

    private fun remember(user: User) = store.community.saveProfile(PlayerProfile(user.id, user.firstName, user.lastName, user.userName))
    private fun enter(userId: Long, payload: String?) = when (val result = access.check(userId)) {
        AccessResult.ALLOWED -> {
            val join = payload ?: store.community.pendingJoin(userId)
            if (join != null) {
                val session = store.acceptInvite(join.removePrefix("join_"), userId)
                store.community.forgetJoin(userId)
                if (session != null) {
                    cards.request(session, true)
                    BotHelper.sendText(client, userId, "⚓ Игра с коллегой началась. На каждый ход есть 3 минуты.")
                } else BotHelper.sendText(client, userId, "Приглашение уже недействительно или один из игроков занят.")
            } else {
                BotHelper.sendText(client, userId, "⚓ Добро пожаловать в Морской бой NMH Team!\nЗдесь можно потренироваться, сыграть с коллегой и записаться на турнир.")
                menu(userId)
            }
        }
        else -> accessDenied(userId, result)
    }
    private fun requireAccess(userId: Long): Boolean {
        val result = access.check(userId)
        if (result == AccessResult.ALLOWED) return true
        accessDenied(userId, result); return false
    }
    private fun accessDenied(userId: Long, result: AccessResult) {
        val text = if (result == AccessResult.NOT_MEMBER) "🔒 Бот доступен участникам NMH Team. Подпишись на канал, затем нажми «Проверить подписку»."
        else "⚠️ Не удалось проверить подписку. Проверь права администратора у бота в канале и попробуй снова."
        val row = InlineKeyboardRow()
        if (config.corporateChatUrl.isNotBlank()) row.add(InlineKeyboardButton("Открыть канал").apply { url = config.corporateChatUrl })
        row.add(InlineKeyboardButton("Проверить подписку").apply { callbackData = "access_check" })
        BotHelper.sendText(client, userId, text, replyMarkup = InlineKeyboardMarkup(listOf(row)))
    }

    private fun menu(userId: Long) {
        val rows = mutableListOf(
            listOf("🤖 Сыграть против компьютера" to "mode_cpu"), listOf("👥 Поиграть с коллегой" to "mode_friend"),
            listOf("🏅 Таблица лидеров" to "leaderboard"), listOf("🏆 Турнир" to "tournament")
        )
        if (store.getSessionByPlayer(userId) != null) rows.add(0, listOf("⚓ Продолжить игру" to "resume"))
        BotHelper.sendText(client, userId, "*⚓ Морской бой NMH Team*\n\nВыбирай режим — поле и корабли бот расставит сам.", replyMarkup = BotHelper.keyboard(rows))
    }
    private fun startCpu(userId: Long) {
        if (store.getComputerSession(userId) != null || store.getColleagueSession(userId) != null) { busy(userId); return }
        cards.request(store.createVsComputerSession(userId), true)
    }
    private fun invite(userId: Long) {
        if (store.getColleagueSession(userId) != null) { busy(userId); return }
        val link = "https://t.me/${config.botUsername}?start=join_${store.createInvite(userId)}"
        BotHelper.sendText(client, userId, "*👥 Игра с коллегой*\n\nОтправь эту одноразовую ссылку:\n`$link`\n\nНа ход — 3 минуты. Три пропуска допустимы.", replyMarkup = BotHelper.keyboard(listOf(listOf("Отменить приглашение" to "cancel_invite"))))
    }
    private fun leaderboard(userId: Long) { rich.sync(userId, 0, CommunityStore.renderLeaderboard(store.community.leaderboard(userId), userId)) }
    private fun preregistration(userId: Long) {
        val registered = store.community.isRegistered(userId)
        val action = if (registered) "cancel_preregister" else "preregister"
        val label = if (registered) "Отменить запись" else "Предварительно записаться"
        val admin = if (userId in config.adminIds) "<tg-button type=\"callback_data\" data=\"download_registrations\">Заявки: ${store.community.registrationCount()}</tg-button>" else ""
        rich.sync(userId, 0, "<h3>🏆 Турнир NMH Team</h3><p>Предварительный старт — ${config.tournamentDate}. Дату подтвердим отдельно.</p><p>${config.tournamentPrizes}</p><p>${if (registered) "Ты уже в предварительном списке ✅" else "Оставь заявку — мы сохраним имя, username и Telegram ID."}</p><tg-button-row><tg-button type=\"callback_data\" data=\"$action\">$label</tg-button>$admin<tg-button type=\"callback_data\" data=\"menu\">В меню</tg-button></tg-button-row>")
    }
    private fun registrationExport(userId: Long) = BotHelper.sendCsv(client, userId, "nmh-tournament-registrations.csv", store.community.registrationsCsv(), "Заявок: ${store.community.registrationCount()}")
    private fun resume(userId: Long) { store.getSessionByPlayer(userId)?.let { cards.request(it, true) } ?: BotHelper.sendText(client, userId, "Активной игры нет.") }
    private fun busy(userId: Long): Boolean {
        if (store.getSessionByPlayer(userId) == null) return false
        BotHelper.sendText(client, userId, "Сначала заверши текущую игру или открой её командой /mygames."); return true
    }
    private fun help(userId: Long) = BotHelper.sendText(client, userId, "Корабли расставляются автоматически. Попадание даёт ещё выстрел. В PvP на ход есть 3 минуты; после трёх пропусков четвёртый означает поражение. У ИИ таймера нет.")

    private fun askSurrender(userId: Long) {
        val session = store.getSessionByPlayer(userId) ?: return
        store.update(session) { session.uiFor(userId).confirmingSurrender = true; session.uiFor(userId).revision++ }
        cards.request(session)
    }
    private fun gameAction(userId: Long, messageId: Long, data: String) {
        val action = GameAction.parse(data) ?: return
        val session = store.getSession(action.gameId, userId) ?: return
        if (!session.accepts(action, userId, messageId)) { cards.request(session); return }
        when (action.type) {
            "half" -> store.update(session) { session.uiFor(userId).half = action.value; session.uiFor(userId).revision++ }
            "surrender" -> store.update(session) { session.uiFor(userId).confirmingSurrender = true; session.uiFor(userId).revision++ }
            "cancel" -> store.update(session) { session.uiFor(userId).confirmingSurrender = false; session.uiFor(userId).revision++ }
            "confirm" -> if (session.uiFor(userId).confirmingSurrender) store.update(session) {
                session.setWinner(if (userId == session.player1Id) session.player2Id else session.player1Id, "SURRENDER"); session.invalidateViews()
            }
            "fire" -> store.update(session) {
                val changed = session.fire(userId, Coord(action.value / 10, action.value % 10))
                if (changed && !session.finished && !session.vsComputer) session.updateTurnDeadline(System.currentTimeMillis() + GameSession.TURN_MILLIS)
            }
        }
        if (session.finished) announceTournament(session)
        cards.request(session)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        store.activeSessions().forEach { session -> runCatching {
            if (!session.vsComputer && session.turnDeadline > 0 && now >= session.turnDeadline) {
                var changed = false
                store.update(session) { changed = session.expire(now) }
                if (changed) {
                    if (session.finished) announceTournament(session)
                    cards.request(session)
                }
            } else if (!session.vsComputer && now / 15_000 != (now - 5_000) / 15_000) cards.request(session)
        } }
        store.sessionsToSync().filter { it.ui.needsSync }.forEach { cards.request(it) }
    }
    private fun announceTournament(session: GameSession) {
        if (session.mode != GameMode.TOURNAMENT) return
        val tournament = store.tournamentForMatch(session.tournamentMatchId ?: return) ?: return
        val winner = store.community.name(session.winnerId)
        tournament.participants.keys.forEach { id -> runCatching { BotHelper.sendText(client, id, "🏆 Результат матча: $winner победил.") } }
    }
}
