package io.github.proify.lyricon.lyric.view

import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test

class TranslationSelectionTest {
    @Test fun translationWinsOverSuperLyricRomajiSecondary() {
        val source = RichLyricLine(begin = 1000, end = 4000, text = "君が好き",
            translation = "我喜欢你", secondary = "kimi ga suki", roma = "kimi ga suki")
        val result = LyricLineAssembler(displayRoma = false).buildSecondary(source)
        assertEquals("我喜欢你", result.line.text)
        assertTrue(result.line.metadata?.getBoolean("translation") == true)
        assertTrue(result.alwaysShow)
    }
    @Test fun translationWinsEvenWhenRomaEnabled() {
        val source = RichLyricLine(begin = 1000, end = 4000, translation = "你好",
            secondary = "konnichiwa", roma = "konnichiwa")
        assertEquals("你好", LyricLineAssembler(displayRoma = true).buildSecondary(source).line.text)
    }
    @Test fun genuineSecondaryStillWorksWithoutTranslation() {
        val source = RichLyricLine(begin = 1000, end = 4000, secondary = "second singer")
        assertEquals("second singer", LyricLineAssembler().buildSecondary(source).line.text)
    }
    @Test fun explicitRomaRespectsDisplayFlag() {
        val source = RichLyricLine(begin = 1000, end = 4000, roma = "kimi ga suki")
        assertEquals("kimi ga suki", LyricLineAssembler(displayRoma = true).buildSecondary(source).line.text)
        assertTrue(LyricLineAssembler(displayRoma = false).buildSecondary(source).line.text.isNullOrEmpty())
    }
    @Test fun disablingTranslationPreservesSecondary() {
        val source = RichLyricLine(begin = 1000, end = 4000, translation = "你好", secondary = "second singer")
        assertEquals("second singer", LyricLineAssembler(displayTranslation = false).buildSecondary(source).line.text)
    }
}
