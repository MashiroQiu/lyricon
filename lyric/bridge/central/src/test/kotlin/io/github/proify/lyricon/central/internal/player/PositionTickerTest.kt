package io.github.proify.lyricon.central.internal.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class PositionTickerTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun stopCancelsStartBeforeItsFirstDispatch() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var reads = 0
        val ticker = PositionTicker(scope, { ++reads; 0L }, {}, { 0L })
        try {
            ticker.start(16)
            ticker.stop()
            dispatcher.drain()
            assertEquals(0, reads)
        } finally { scope.cancel() }
    }

    @Test fun queuedRestartOnlyRunsTheReplacement() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var reads = 0
        lateinit var ticker: PositionTicker
        ticker = PositionTicker(scope, { ++reads; 42L }, { ticker.stop() }, { 0L })
        try {
            ticker.start(16)
            ticker.stop()
            ticker.start(16)
            dispatcher.drain()
            assertEquals(1, reads)
        } finally { scope.cancel() }
    }

    @Test fun repeatedStartCannotCreateParallelTickers() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var reads = 0
        lateinit var ticker: PositionTicker
        ticker = PositionTicker(scope, { ++reads; 42L }, { ticker.stop() }, { 0L })
        try {
            repeat(10) { ticker.start(16) }
            assertEquals(1, dispatcher.tasks.size)
            dispatcher.drain()
            assertEquals(1, reads)
        } finally { scope.cancel() }
    }
}
