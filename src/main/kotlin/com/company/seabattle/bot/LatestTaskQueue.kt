package com.company.seabattle.bot

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One sender per game. New requests replace pending work, never the task currently in flight. */
class LatestTaskQueue(private val executor: ExecutorService = Executors.newFixedThreadPool(4)) : AutoCloseable {
    private val pending = mutableMapOf<String, () -> Unit>()
    private val running = mutableSetOf<String>()
    @Synchronized fun submit(key: String, task: () -> Unit) {
        if(executor.isShutdown) return
        pending[key]=task
        if(running.add(key)) executor.execute { drain(key) }
    }
    private fun drain(key: String) {
        while(true) {
            val task = synchronized(this) {
                pending.remove(key) ?: run { running.remove(key); return }
            }
            try { task() } catch (_: Exception) { println("Не удалось обновить карточку; запланирован повтор.") }
        }
    }
    override fun close() { executor.shutdownNow() }
}
