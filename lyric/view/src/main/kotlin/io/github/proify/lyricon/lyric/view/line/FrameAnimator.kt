/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view.line

import android.view.Choreographer

/**
 * 基于 [Choreographer] 的帧调度器：与 [LyricLineView] 解耦。
 *
 * 每帧回调 [onFrame]（返回本帧是否有内容变化）；有变化时调用 [requestInvalidate] 请求重绘；
 * 当 [shouldRun] 不再满足（例如 View 脱离窗口）时自动停止。
 */
internal class FrameAnimator(
    private val onFrame: (deltaNanos: Long) -> Boolean,
    private val requestInvalidate: () -> Unit,
    private val shouldRun: () -> Boolean,
    private val post: (Runnable) -> Unit,
    private val removePost: (Runnable) -> Unit,
    private val scheduleFrame: (Choreographer.FrameCallback, Long) -> Unit = { callback, delay ->
        Choreographer.getInstance().postFrameCallbackDelayed(callback, delay)
    },
    private val cancelFrame: (Choreographer.FrameCallback) -> Unit = { callback ->
        Choreographer.getInstance().removeFrameCallback(callback)
    },
) : Choreographer.FrameCallback {

    private companion object {
        // Tiny status-bar text does not need to redraw at the display's 90/120 Hz rate.
        const val FRAME_INTERVAL_NANOS = 33_333_333L
        const val VSYNC_TOLERANCE_NANOS = 500_000L
    }

    private var generation = 0L
    private var running = false
    private var lastFrameNanos = 0L

    val isRunning: Boolean get() = running
    private val postFrame = Runnable {
        if (running && shouldRun()) scheduleFrame(this, 0L) else stop()
    }

    fun startIfNeeded() {
        if (!running && shouldRun()) {
            generation++
            running = true
            lastFrameNanos = 0L
            post(postFrame)
        }
    }

    fun stop() {
        generation++
        running = false
        removePost(postFrame)
        cancelFrame(this)
        lastFrameNanos = 0L
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !shouldRun()) {
            stop()
            return
        }

        val elapsed = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
        if (lastFrameNanos != 0L && elapsed < FRAME_INTERVAL_NANOS - VSYNC_TOLERANCE_NANOS) {
            scheduleFrame(this, ((FRAME_INTERVAL_NANOS - elapsed) / 1_000_000L - 1).coerceAtLeast(0))
            return
        }
        lastFrameNanos = frameTimeNanos
        val frameGeneration = generation
        val changed = onFrame(elapsed)
        // A listener may replace the lyric/stop and restart while the renderer steps.
        if (frameGeneration != generation) return
        if (changed) requestInvalidate()

        if (running && shouldRun()) {
            // Leave the next vsync to Android; preserve real elapsed time for scroll/highlight.
            scheduleFrame(this, FRAME_INTERVAL_NANOS / 1_000_000L - 1)
        } else {
            stop()
        }
    }
}
