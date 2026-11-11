/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 播放位置轮询器：按固定间隔读取位置并回调。
 *
 * 使用 elapsedRealtime 校正漂移；start/stop 可被幂等地重复调用，
 * 内部同步登记任务，防止排队中的 start 越过 stop。
 *
 * @property scope 宿主协程作用域。
 * @property readPosition 位置读取源。
 * @property onPosition 每次读到位置后的回调。
 */
internal class PositionTicker(
    private val scope: CoroutineScope,
    private val readPosition: () -> Long,
    private val onPosition: (Long) -> Unit,
    private val timeSource: () -> Long = SystemClock::elapsedRealtime,
) {

    private val lock = Any()

    @Volatile
    private var job: Job? = null

    /**
     * 开始轮询（若已在运行则忽略）。
     *
     * @param interval 轮询间隔（毫秒），最小 16ms。
     */
    fun start(interval: Long) = synchronized(lock) {
        if (job?.isActive == true) return@synchronized
        job = scope.launch(start = CoroutineStart.LAZY) {
            val safeInterval = interval.coerceAtLeast(MIN_INTERVAL_MS)
            var nextTick = timeSource()
            while (isActive) {
                onPosition(readPosition())
                nextTick += safeInterval
                val remaining = nextTick - timeSource()
                if (remaining > 0) delay(remaining)
                else { nextTick = timeSource(); yield() }
            }
        }.also { it.start() }
    }

    /** Cancels a queued start as well as an already-running ticker. */
    fun stop() = synchronized(lock) {
        job?.cancel()
        job = null
    }

    /** 停止轮询并释放资源。 */
    fun close() {
        stop()
    }

    private companion object {
        private const val MIN_INTERVAL_MS = 16L
    }
}
