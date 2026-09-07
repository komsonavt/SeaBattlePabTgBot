package com.company.seabattle.bot

import com.company.seabattle.config.BotConfig
import com.company.seabattle.copy.Copy
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
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** MVP bot: state is committed before asynchronous Rich Message synchronization. */
class SeaBattleBot(private val config: BotConfig, private val store: GameStore) :
    LongPollingSingleThreadUpdateConsumer, AutoCloseable {
    val client: TelegramClient = OkHttpTelegramClient(config.botToken)
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
        if (message.chatId == config.moderationChatId) {
            if (user.id in config.adminIds && config.broadcastsTopicId != null && message.messageThreadId == config.broadcastsTopicId && !message.text.startsWith("/")) {
                broadcast(user.id, message.text)
            }
            return
        }
        if (message.chatId != user.id) return
        remember(user)
        val text = message.text.trim()
        if (text.startsWith("/start")) {
            val payload = text.removePrefix("/start").trim().takeIf { it.isNotEmpty() }
            if (payload?.startsWith("join_") == true) store.community.rememberJoin(user.id, payload)
            enter(user.id, payload)
            return
        }
        if (handleAccessFlow(user.id, text)) return
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
        val data = query.data ?: return
        if (message.chatId == config.moderationChatId) {
            BotHelper.answerCallback(client, query.id)
            if (user.id in config.adminIds && data.startsWith("mod:")) moderate(user.id, data)
            return
        }
        if (message.chatId != user.id) return
        remember(user); BotHelper.answerCallback(client, query.id)
        if (data == "request_access") { beginAccessRequest(user.id); return }
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
    private fun enter(userId: Long, payload: String?) {
        if (store.community.isApproved(userId) || userId in config.adminIds) {
            val join = payload ?: store.community.pendingJoin(userId)
            if (join != null) {
                val session = store.acceptInvite(join.removePrefix("join_"), userId)
                store.community.forgetJoin(userId)
                if (session != null) {
                    cards.request(session, true)
                    BotHelper.sendText(client, userId, Copy.text("invite_started"))
                } else BotHelper.sendText(client, userId, Copy.text("invite_invalid"))
            } else {
                BotHelper.sendText(client, userId, Copy.text("welcome"))
                menu(userId)
            }
        } else accessDenied(userId)
    }
    private fun requireAccess(userId: Long): Boolean {
        if (store.community.isApproved(userId) || userId in config.adminIds) return true
        accessDenied(userId); return false
    }
    private fun accessDenied(userId: Long) {
        rich.sync(userId, 0, Copy.text("access_denied"))
    }
    private fun beginAccessRequest(userId: Long) {
        val request = store.community.request(userId, store.community.pendingJoin(userId))
        when (request.status) {
            com.company.seabattle.state.AccessStatus.DRAFT -> BotHelper.sendText(client, userId, Copy.text("access_name"))
            com.company.seabattle.state.AccessStatus.PENDING -> BotHelper.sendText(client, userId, Copy.text("access_waiting"))
            else -> accessDenied(userId)
        }
    }
    /** Returns true when a private text message was consumed by the access questionnaire. */
    private fun handleAccessFlow(userId: Long, text: String): Boolean {
        val request = store.community.accessRequest(userId) ?: return false
        if (request.status != com.company.seabattle.state.AccessStatus.DRAFT) return false
        if (request.name.isNullOrBlank()) {
            if (text.length !in 2..120) { BotHelper.sendText(client,userId,Copy.text("access_name_invalid")); return true }
            store.community.setRequestName(userId,text)
            BotHelper.sendText(client,userId,Copy.text("access_activity"))
            return true
        }
        if (text.length !in 2..120) { BotHelper.sendText(client,userId,Copy.text("access_activity_invalid")); return true }
        val submitted = store.community.submitRequest(userId,text) ?: return true
        val body = Copy.text("moderation_request", "name" to submitted.name, "activity" to submitted.activity, "id" to submitted.userId)
        val keyboard = BotHelper.keyboard(listOf(listOf(Copy.text("moderation_approve") to "mod:approve:${submitted.id}", Copy.text("moderation_decline") to "mod:decline:${submitted.id}")))
        BotHelper.sendText(client,config.moderationChatId,body,replyMarkup=keyboard,threadId=config.moderationTopicId)
        BotHelper.sendText(client,userId,Copy.text("access_sent"))
        return true
    }
    private fun moderate(moderatorId: Long, data: String) {
        val parts=data.split(":")
        if(parts.size!=3) return
        val approved=parts[1]=="approve"
        val request=store.community.decideRequest(parts[2],approved,moderatorId) ?: return
        BotHelper.sendText(client,request.userId,if(approved) Copy.text("access_approved") else Copy.text("access_declined"))
        if(approved) enter(request.userId,request.payload)
    }
    private fun broadcast(authorId: Long, text: String) {
        val recipients=store.community.approvedUsers().filter { it !in config.adminIds }
        recipients.forEach { id -> runCatching { BotHelper.sendText(client,id,text,parseMode=null) } }
        BotHelper.sendText(client,config.moderationChatId,Copy.text("broadcast_done", "count" to recipients.size),threadId=config.broadcastsTopicId)
    }

    private fun menu(userId: Long) {
        val rows = mutableListOf(
            listOf(Copy.text("menu_cpu") to "mode_cpu"), listOf(Copy.text("menu_friend") to "mode_friend"),
            listOf(Copy.text("menu_leaderboard") to "leaderboard"), listOf(Copy.text("menu_tournament") to "tournament")
        )
        if (store.getSessionByPlayer(userId) != null) rows.add(0, listOf(Copy.text("menu_resume") to "resume"))
        BotHelper.sendText(client, userId, Copy.text("menu_title"), replyMarkup = BotHelper.keyboard(rows))
    }
    private fun startCpu(userId: Long) {
        if (store.getComputerSession(userId) != null || store.getColleagueSession(userId) != null) { busy(userId); return }
        cards.request(store.createVsComputerSession(userId), true)
    }
    private fun invite(userId: Long) {
        if (store.getColleagueSession(userId) != null) { busy(userId); return }
        val link = "https://t.me/${config.botUsername}?start=join_${store.createInvite(userId)}"
        BotHelper.sendText(client, userId, Copy.text("invite", "link" to link), replyMarkup = BotHelper.keyboard(listOf(listOf(Copy.text("invite_cancel") to "cancel_invite"))))
    }
    private fun leaderboard(userId: Long) { rich.sync(userId, 0, CommunityStore.renderLeaderboard(store.community.leaderboard(userId), userId)) }
    private fun preregistration(userId: Long) {
        val registered = store.community.isRegistered(userId)
        val action = if (registered) "cancel_preregister" else "preregister"
        val label = if (registered) Copy.text("tournament_cancel") else Copy.text("tournament_register")
        val admin = if (userId in config.adminIds) "<tg-button type=\"callback_data\" data=\"download_registrations\">Заявки: ${store.community.registrationCount()}</tg-button>" else ""
        rich.sync(userId, 0, "<h3>${Copy.text("tournament_title")}</h3><p>${Copy.text("tournament_text", "date" to config.tournamentDate)}</p><p>${config.tournamentPrizes}</p><p>${if (registered) Copy.text("tournament_registered") else Copy.text("tournament_not_registered")}</p><tg-button-row><tg-button type=\"callback_data\" data=\"$action\">$label</tg-button>$admin<tg-button type=\"callback_data\" data=\"menu\">${Copy.text("to_menu")}</tg-button></tg-button-row>")
    }
    private fun registrationExport(userId: Long) = BotHelper.sendCsv(client, config.moderationChatId, "nmh-tournament-registrations.csv", store.community.registrationsCsv(), "Заявок: ${store.community.registrationCount()}", config.exportsTopicId)
    private fun resume(userId: Long) { store.getSessionByPlayer(userId)?.let { cards.reopen(it, userId) } ?: BotHelper.sendText(client, userId, Copy.text("no_active_game")) }
    private fun busy(userId: Long): Boolean {
        if (store.getSessionByPlayer(userId) == null) return false
        BotHelper.sendText(client, userId, Copy.text("busy")); return true
    }
    private fun help(userId: Long) = BotHelper.sendText(client, userId, Copy.text("help"))

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
