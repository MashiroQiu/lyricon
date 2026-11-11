package io.github.proify.lyricon.lyric.view.line

import kotlin.math.max
import kotlin.math.abs

/** Geometry for a reusable opaque-to-transparent unit gradient. */
internal class HighlightFade {
    var start = 0f
        private set
    var width = 1f
        private set
    private var total = Float.NaN
    private var highlighted = Float.NaN

    fun update(totalWidth: Float, highlightWidth: Float): Boolean {
        if (total == totalWidth && abs(highlighted - highlightWidth) <= 0.1f) return false
        total = totalWidth
        highlighted = highlightWidth
        val edge = if (totalWidth > 0f) max(highlightWidth / totalWidth, 0.9f) else 1f
        start = highlightWidth * edge
        width = (highlightWidth - start).coerceAtLeast(0.0001f)
        return true
    }
}
