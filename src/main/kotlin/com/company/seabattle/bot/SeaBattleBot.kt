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
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
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
    private var topics: ForumTopics? = null
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GameDeadlineScanner").apply { isDaemon = true }
    }

    init {
        store.community.bootstrapAdmins(config.adminIds)
        store.community.workspaceChatId()?.let(::activateForum)
        runCatching { BotHelper.registerCommands(client, listOf("start" to Copy.text("command_start"))) }
            .onFailure { println("Не удалось обновить список команд: ${it.javaClass.simpleName}") }
        store.sessionsToSync().forEach { cards.request(it, true) }
        scheduler.scheduleWithFixedDelay(::tick, 1, 5, TimeUnit.SECONDS)
    }
    override fun close() { scheduler.shutdownNow(); cards.close() }

    override fun consume(update: Update) = try {
        when {
            update.hasMyChatMember() -> onBotAddedToChat(update)
            update.hasMessage() && update.message.hasText() -> onMessage(update)
            update.hasCallbackQuery() -> onCallback(update)
            else -> Unit
        }
    } catch (e: Exception) { println("Ошибка обработки обновления: ${e.javaClass.simpleName}") }

    private fun onBotAddedToChat(update: Update) {
        val change=update.myChatMember
        val chat=change.chat
        if (change.newChatMember.status !in setOf("administrator", "creator", "owner")) return
        when {
            chat.isForum == true -> activateForum(chat.id)
            chat.isChannelChat == true -> {
                store.community.activateAccessChannel(chat.id)
                println("Канал сотрудников подключён: ${chat.id}")
            }
        }
    }
    private fun activateForum(chatId: Long) {
        store.community.activateWorkspace(chatId)
        client.execute(GetChatAdministrators(chatId.toString())).forEach { member ->
            if(member.status in setOf("administrator", "creator", "owner")) store.community.addAdmin(member.user.id, null, "chat_admin")
        }
        val ready=ForumWorkspace.ensure(client,chatId,store.community)
        topics=ready
        if ("moderation" in ready.created) BotHelper.sendText(client,chatId,Copy.text("admin_topic_moderation"),threadId=ready.moderation)
        if ("exports" in ready.created) BotHelper.sendText(client,chatId,Copy.text("admin_topic_exports"),replyMarkup=BotHelper.keyboard(listOf(listOf(Copy.text("admin_export_stats") to "mod:stats",Copy.text("admin_export_tournament") to "mod:registrations"))),threadId=ready.exports)
        if ("broadcasts" in ready.created) BotHelper.sendText(client,chatId,Copy.text("admin_topic_broadcasts"),threadId=ready.broadcasts)
        if ("guide" in ready.created) {
            BotHelper.sendText(client,chatId,Copy.text("admin_topic_guide"),threadId=ready.guide)
            BotHelper.sendDocumentResource(client,chatId,"/README.md",Copy.text("admin_topic_guide_file"),ready.guide)
        }
    }

    private fun onMessage(update: Update) {
        val message = update.message
        val user = message.from
        if (message.chatId == store.community.workspaceChatId()) {
            if (store.community.isAdmin(user.id)) {
                when (message.text.trim().lowercase()) {
                    "/export_stats" -> statisticsExport(user.id)
                    "/export_tournament" -> registrationExport(user.id)
                    "/export_audit" -> auditExport(user.id)
                    else -> if (message.messageThreadId == topics?.broadcasts && !message.text.startsWith("/")) draftBroadcast(user.id, message.text)
                }
            }
            return
        }
        if (message.chatId != user.id) return
        remember(user)
        val text = message.text.trim()
        if (text.startsWith("/start")) {
            val payload = text.removePrefix("/start").trim().takeIf { it.isNotEmpty() }
            if (payload?.startsWith("admin_") == true && store.community.claimAdminInvite(payload.removePrefix("admin_"), user.id)) {
                BotHelper.sendText(client, user.id, Copy.text("admin_invite_accepted"))
            }
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
            "/admin" -> adminPanel(user.id)
            "/delete_me" -> if (store.community.isAdmin(user.id)) deleteProfilePrompt(user.id) else BotHelper.sendText(client, user.id, Copy.text("admin_denied"))
            else -> menu(user.id)
        }
    }

    private fun onCallback(update: Update) {
        val query = update.callbackQuery
        val message = query.message ?: return
        val user = query.from
        val data = query.data ?: return
        if (message.chatId == store.community.workspaceChatId()) {
            BotHelper.answerCallback(client, query.id)
            if (store.community.isAdmin(user.id)) when (data) {
                "mod:stats" -> statisticsExport(user.id)
                "mod:registrations" -> registrationExport(user.id)
                else -> when {
                    data.startsWith("mod:") -> moderate(user.id, data, message.chatId, message.messageId.toLong())
                    data.startsWith("broadcast:send:") -> sendBroadcast(user.id,data.removePrefix("broadcast:send:"))
                    data.startsWith("broadcast:cancel:") -> cancelBroadcast(data.removePrefix("broadcast:cancel:"))
                }
            }
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
            data == "download_registrations" && store.community.isAdmin(user.id) -> registrationExport(user.id)
            data == "download_stats" && store.community.isAdmin(user.id) -> statisticsExport(user.id)
            data == "admin_invite" && store.community.isAdmin(user.id) -> adminInvite(user.id)
            data == "delete_profile_confirm" -> { store.community.deleteProfile(user.id); BotHelper.sendText(client,user.id,Copy.text("profile_deleted")) }
            data == "delete_profile_cancel" -> menu(user.id)
            data.startsWith("game:") -> gameAction(user.id, message.messageId.toLong(), data)
        }
    }

    private fun remember(user: User) = store.community.saveProfile(PlayerProfile(user.id, user.firstName, user.lastName, user.userName))
    private fun enter(userId: Long, payload: String?) {
        if (hasAccess(userId)) {
            val join = payload ?: store.community.pendingJoin(userId)
            if (join != null) {
                val session = store.acceptInvite(join.removePrefix("join_"), userId)
                store.community.forgetJoin(userId)
                if (session != null) {
                    cards.request(session, true)
                    BotHelper.sendText(client, userId, Copy.text("invite_started"))
                } else BotHelper.sendText(client, userId, Copy.text("invite_invalid"))
            } else menu(userId, withImage = true)
        } else accessDenied(userId)
    }
    private fun requireAccess(userId: Long): Boolean {
        if (hasAccess(userId)) return true
        accessDenied(userId); return false
    }
    private fun hasAccess(userId: Long): Boolean {
        if (store.community.isApproved(userId) || store.community.isAdmin(userId)) return true
        val channelId=store.community.accessChannelId() ?: return false
        return runCatching {
            client.execute(GetChatMember(channelId.toString(),userId)).status in setOf("member", "administrator", "creator", "owner")
        }.getOrDefault(false)
    }
    private fun accessDenied(userId: Long) {
        val text = if (store.community.accessRequest(userId)?.status == com.company.seabattle.state.AccessStatus.BLOCKED) Copy.text("access_blocked") else Copy.text("access_denied")
        rich.sync(userId, 0, text)
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
        val keyboard = BotHelper.keyboard(listOf(
            listOf(Copy.text("moderation_approve") to "mod:approve:${submitted.id}"),
            listOf(Copy.text("moderation_decline") to "mod:decline:${submitted.id}"),
            listOf(Copy.text("moderation_block") to "mod:block:${submitted.id}")
        ))
        val chatId=store.community.workspaceChatId() ?: return true
        BotHelper.sendText(client,chatId,body,replyMarkup=keyboard,threadId=topics?.moderation)
        BotHelper.sendText(client,userId,Copy.text("access_sent"))
        return true
    }
    private fun moderate(moderatorId: Long, data: String, chatId: Long, messageId: Long) {
        val parts=data.split(":")
        if(parts.size!=3) return
        when (parts[1]) {
            "approve", "decline" -> {
                val approved=parts[1]=="approve"
                val request=store.community.decideRequest(parts[2],approved,moderatorId) ?: return
                BotHelper.sendText(client,request.userId,if(approved) Copy.text("access_approved") else Copy.text("access_declined"))
                if(approved) enter(request.userId,request.payload)
                val status=if(approved) Copy.text("moderation_status_approved") else Copy.text("moderation_status_declined")
                val keyboard=BotHelper.keyboard(listOf(listOf(Copy.text("moderation_block") to "mod:block:${request.id}")))
                val card=Copy.text("moderation_request", "name" to request.name, "activity" to request.activity, "id" to request.userId)
                BotHelper.editText(client,chatId,messageId,"$card\n\n$status",replyMarkup=keyboard)
            }
            "block" -> {
                val request=store.community.blockRequest(parts[2],moderatorId) ?: return
                store.community.workspaceChatId()?.let { chatId -> runCatching { client.execute(BanChatMember(chatId.toString(), request.userId)) } }
                BotHelper.sendText(client,request.userId,Copy.text("access_blocked"))
                val card=Copy.text("moderation_request", "name" to request.name, "activity" to request.activity, "id" to request.userId)
                BotHelper.editText(client,chatId,messageId,"$card\n\n${Copy.text("moderation_status_blocked")}",replyMarkup=BotHelper.keyboard(emptyList()))
            }
        }
    }
    private fun draftBroadcast(authorId: Long, text: String) {
        val draft=store.community.createBroadcastDraft(authorId,text)
        val chatId=store.community.workspaceChatId() ?: return
        val preview=Copy.text("broadcast_preview", "text" to text)
        val keyboard=BotHelper.keyboard(listOf(listOf(
            Copy.text("broadcast_send") to "broadcast:send:${draft.id}", Copy.text("broadcast_cancel") to "broadcast:cancel:${draft.id}"
        )))
        BotHelper.sendText(client,chatId,preview,parseMode=null,replyMarkup=keyboard,threadId=topics?.broadcasts)
    }
    private fun sendBroadcast(authorId: Long, draftId: String) {
        val draft=store.community.claimBroadcastDraft(draftId) ?: return
        val recipients=store.community.broadcastRecipients()
        var sent=0
        recipients.forEach { id -> if(runCatching { BotHelper.sendText(client,id,draft.body,parseMode=null) }.isSuccess) sent++ }
        store.community.finishBroadcastDraft(draftId,sent==recipients.size)
        store.community.audit(authorId,"broadcast_sent")
        store.community.workspaceChatId()?.let { BotHelper.sendText(client,it,Copy.text("broadcast_done", "count" to sent),threadId=topics?.broadcasts) }
    }
    private fun cancelBroadcast(draftId: String) {
        if(store.community.cancelBroadcastDraft(draftId)) store.community.workspaceChatId()?.let { BotHelper.sendText(client,it,Copy.text("broadcast_cancelled"),threadId=topics?.broadcasts) }
    }

    private fun menu(userId: Long, withImage: Boolean = false) {
        if (withImage) runCatching { BotHelper.sendPhotoResource(client, userId, "/welcome.png", Copy.text("menu_image_caption")) }
        val buttons = buildString {
            if (store.getSessionByPlayer(userId) != null) append("<tg-button-row><tg-button type=\"callback_data\" data=\"resume\">${Copy.text("menu_resume")}</tg-button></tg-button-row>")
            append("<tg-button-row><tg-button type=\"callback_data\" data=\"mode_cpu\">${Copy.text("menu_cpu")}</tg-button></tg-button-row>")
            append("<tg-button-row><tg-button type=\"callback_data\" data=\"mode_friend\">${Copy.text("menu_friend")}</tg-button></tg-button-row>")
            append("<tg-button-row><tg-button type=\"callback_data\" data=\"leaderboard\">${Copy.text("menu_leaderboard")}</tg-button></tg-button-row>")
            append("<tg-button-row><tg-button type=\"callback_data\" data=\"tournament\">${Copy.text("menu_tournament")}</tg-button></tg-button-row>")
        }
        rich.sync(userId, 0, "<h3>${Copy.text("menu_rich_title")}</h3><p>${Copy.text("menu_rich_subtitle")}</p>$buttons")
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
        val admin = if (store.community.isAdmin(userId)) "<tg-button-row><tg-button type=\"callback_data\" data=\"download_registrations\">${Copy.text("admin_export_tournament", "count" to store.community.registrationCount())}</tg-button></tg-button-row>" else ""
        rich.sync(userId, 0, "<h3>${Copy.text("tournament_title")}</h3><p>${Copy.text("tournament_text", "date" to config.tournamentDate)}</p><p>${config.tournamentPrizes}</p><p>${if (registered) Copy.text("tournament_registered") else Copy.text("tournament_not_registered")}</p><tg-button-row><tg-button type=\"callback_data\" data=\"$action\">$label</tg-button></tg-button-row>$admin<tg-button-row><tg-button type=\"callback_data\" data=\"menu\">${Copy.text("to_menu")}</tg-button></tg-button-row>")
    }
    private fun adminPanel(userId: Long) {
        if (!store.community.isAdmin(userId)) { BotHelper.sendText(client, userId, Copy.text("admin_denied")); return }
        rich.sync(userId, 0, Copy.text("admin_panel"))
    }
    private fun adminInvite(userId: Long) {
        val link="https://t.me/${config.botUsername}?start=admin_${store.community.createAdminInvite(userId)}"
        BotHelper.sendText(client,userId,Copy.text("admin_invite_link", "link" to link))
    }
    private fun deleteProfilePrompt(userId: Long) = BotHelper.sendText(client,userId,Copy.text("profile_delete_confirm"),replyMarkup=BotHelper.keyboard(listOf(listOf(Copy.text("profile_delete_yes") to "delete_profile_confirm",Copy.text("profile_delete_no") to "delete_profile_cancel"))))
    private fun registrationExport(userId: Long? = null) { val chatId=store.community.workspaceChatId() ?: return; userId?.let { store.community.audit(it,"export_tournament") }; BotHelper.sendCsv(client, chatId, "nmh-tournament-registrations.csv", store.community.registrationsCsv(), Copy.text("admin_export_tournament_caption", "count" to store.community.registrationCount()), topics?.exports) }
    private fun statisticsExport(userId: Long? = null) { val chatId=store.community.workspaceChatId() ?: return; userId?.let { store.community.audit(it,"export_statistics") }; BotHelper.sendCsv(client, chatId, "nmh-audience-statistics.csv", store.community.audienceStatisticsCsv(), Copy.text("admin_export_stats_caption"), topics?.exports) }
    private fun auditExport(userId: Long) { val chatId=store.community.workspaceChatId() ?: return; store.community.audit(userId,"export_audit"); BotHelper.sendCsv(client,chatId,"nmh-admin-audit.csv",store.community.adminAuditCsv(),Copy.text("admin_export_audit_caption"),topics?.exports) }
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
