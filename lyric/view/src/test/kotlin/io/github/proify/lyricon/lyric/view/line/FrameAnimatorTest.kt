package io.github.proify.lyricon.lyric.view.line

import org.junit.Assert.*
import org.junit.Test

class FrameAnimatorTest {
    private class Harness {
        var visible = true
        var animating = true
        var draws = 0
        var steps = 0
        var elapsed = 0L
        var schedules = 0
        var cancellations = 0
        var posted: Runnable? = null
        var endDuringStep = false
        var onStep: (() -> Unit)? = null
        var changed = true
        val animator = FrameAnimator(
            onFrame = {
                steps++
                elapsed += it
                if (endDuringStep) animating = false
                onStep?.invoke()
                changed
            },
            requestInvalidate = { draws++ },
            shouldRun = { visible && animating },
            post = { posted = it },
            removePost = { if (posted === it) posted = null },
            frameIntervalNanos = 33_333_333L,
            scheduleFrame = { _, _ -> schedules++ },
            cancelFrame = { cancellations++ },
        )
        fun start() { animator.startIfNeeded(); posted?.run(); posted = null }
    }

    @Test fun boundsRedrawsAt120HzAndPreservesElapsedPlaybackTime() {
        val h = Harness()
        h.start()
        val start = 1_000_000_000L
        for (frame in 0..120) h.animator.doFrame(start + frame * 1_000_000_000L / 120)
        assertEquals(31, h.draws) // Initial draw + 30 elapsed intervals.
        assertEquals(1_000_000_000L, h.elapsed)
    }

    @Test fun stopsOnTheFrameThatFinishesAndCanRestart() {
        val h = Harness()
        h.start()
        h.endDuringStep = true
        h.animator.doFrame(1_000_000_000L)
        assertFalse(h.animator.isRunning)
        assertEquals(1, h.schedules)
        h.animating = true
        h.endDuringStep = false
        h.start()
        h.animator.doFrame(2_000_000_000L)
        assertEquals(0L, h.elapsed) // Hidden/idle time is not applied to the new animation.
        assertTrue(h.animator.isRunning)
    }

    @Test fun doesNotKeepRunningWhenHiddenBeforeFirstPostedCallback() {
        val h = Harness()
        h.animator.startIfNeeded()
        val callback = h.posted!!
        h.visible = false
        callback.run()
        assertFalse(h.animator.isRunning)
        assertEquals(0, h.draws)
        assertEquals(0, h.schedules)
        h.visible = true
        h.start()
        assertTrue(h.animator.isRunning)
    }

    @Test fun stopsWhenAncestorBecomesHiddenAndRemovesCallbacks() {
        val h = Harness()
        h.start()
        h.visible = false
        h.animator.doFrame(1_000_000_000L)
        assertFalse(h.animator.isRunning)
        assertEquals(0, h.steps)
        h.animator.stop()
        assertTrue(h.cancellations > 0)
        assertNull(h.posted)
    }

    @Test fun staticRendererDoesNotScheduleAnyFrame() {
        val h = Harness()
        h.animating = false
        h.start()
        assertFalse(h.animator.isRunning)
        assertEquals(0, h.schedules)
    }
    @Test fun delayCanKeepSchedulingWithoutDrawing() {
        val h = Harness()
        h.changed = false
        h.start()
        h.animator.doFrame(1_000_000_000L)
        assertTrue(h.animator.isRunning)
        assertEquals(0, h.draws)
        assertEquals(2, h.schedules)
    }

    @Test fun rendererRestartDoesNotEnqueueAnExtraOldGenerationFrame() {
        val h = Harness()
        h.start()
        h.onStep = { h.animator.stop(); h.animator.startIfNeeded() }
        h.animator.doFrame(1_000_000_000L)
        assertEquals(1, h.schedules)
        assertEquals(0, h.draws)
        assertNotNull(h.posted)
        assertTrue(h.animator.isRunning)
    }

}
