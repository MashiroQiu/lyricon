package io.github.proify.lyricon.lyric.view.line

import kotlin.math.max
import org.junit.Assert.*
import org.junit.Test

class HighlightFadeTest {
    @Test fun matchesOriginalThreeStopGradientThroughTheWholeLine() {
        for (total in listOf(10f, 100f, 1379f)) {
            for (step in 1..99) {
                val geometry = HighlightFade()
                val highlighted = total * step / 100f
                geometry.update(total, highlighted)
                val edge = max(highlighted / total, 0.9f)
                for (sample in 0..100) {
                    val x = highlighted * sample / 100f
                    val original = ((1f - x / highlighted) / (1f - edge)).coerceIn(0f, 1f)
                    val reusable = (1f - (x - geometry.start) / geometry.width).coerceIn(0f, 1f)
                    assertEquals(original, reusable, 0.0002f)
                }
            }
        }
    }

    @Test fun geometryInvalidatesWhenLineWidthChangesAtTheSameHighlightPosition() {
        val geometry = HighlightFade()
        assertTrue(geometry.update(100f, 95f))
        val oldStart = geometry.start
        assertFalse(geometry.update(100f, 95f))
        assertTrue(geometry.update(200f, 95f))
        assertNotEquals(oldStart, geometry.start)
    }

    @Test fun fullAndEmptyLinesKeepAnInvertibleMatrix() {
        val geometry = HighlightFade()
        for ((total, highlighted) in listOf(100f to 100f, 100f to 0f, 0f to 0f)) {
            geometry.update(total, highlighted)
            assertTrue(geometry.start.isFinite())
            assertTrue(geometry.width.isFinite() && geometry.width > 0f)
        }
    }

    @Test fun retainsTheExistingSubpixelUpdateThreshold() {
        val geometry = HighlightFade()
        assertTrue(geometry.update(100f, 50f))
        assertFalse(geometry.update(100f, 50.05f))
        assertTrue(geometry.update(100f, 50.11f))
    }
}
