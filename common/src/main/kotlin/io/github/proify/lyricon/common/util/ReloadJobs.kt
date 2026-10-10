package io.github.proify.lyricon.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArraySet

/** Tracks only scopes owned by the injected generation, never normal application scopes. */
object ReloadJobs {
    @Volatile var enabled = false
    private val jobs = CopyOnWriteArraySet<Job>()
    fun track(scope: CoroutineScope) {
        if (enabled) scope.coroutineContext[Job]?.let { jobs.add(it) }
    }
    fun retire() {
        val snapshot = jobs.toList()
        snapshot.forEach { it.cancel() }
        runBlocking { withTimeout(8000) { snapshot.forEach { it.join() } } }
        jobs.clear()
        enabled = false
    }
}
