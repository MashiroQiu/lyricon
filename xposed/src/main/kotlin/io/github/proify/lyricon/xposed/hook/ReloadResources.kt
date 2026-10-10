package io.github.proify.lyricon.xposed.hook

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

object ReloadResources {
    @Volatile var retiring = false

    fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        val handler = Handler(Looper.getMainLooper())
        check(handler.post(task)) { "Main looper is stopping" }
        try { return task.get(8, TimeUnit.SECONDS) }
        catch (error: Exception) {
            handler.removeCallbacks(task)
            task.cancel(false)
            throw IllegalStateException("Hot reload main-thread operation failed", error)
        }
    }

    /** Call off the main thread if the scope can have tasks waiting for Dispatchers.Main. */
    fun stop(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job]
        scope.cancel()
        runBlocking { withTimeout(8000) { job?.join() } }
    }
}
