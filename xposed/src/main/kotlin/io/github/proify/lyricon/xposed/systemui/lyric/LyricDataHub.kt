/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.util.Log
import android.os.Handler
import android.os.Looper
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.systemui.lyric.processor.LyricDataProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 歌词数据调度中枢
 * 管理歌词生命周期：获取原始数据 -> 后台加工 -> 第一次分发 -> 后台增强 -> 最终分发。
 */
object LyricDataHub : ActivePlayerListener {
    private const val TAG = "LyricDataHub"

    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { io.github.proify.lyricon.common.util.ReloadJobs.track(it) }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var providerInfo: ProviderInfo? = null
    private val processing = SongProcessingSession(
        scope = scope,
        process = { song, emit ->
            val style = LyricPrefs.getLyricStyle()
            val prepared = LyricDataProcessor.executePreProcessing(song)
            emit(LyricDataProcessor.executeDisplayProcessing(prepared, style))
            val enhanced = LyricDataProcessor.executePostProcessingPipeline(prepared, style)
            emit(LyricDataProcessor.executeDisplayProcessing(enhanced, style))
        },
        deliver = { song -> listeners.forEach { it.onSongChanged(song) } },
        onError = { Log.e(TAG, "Song processing failed", it) },
    )

    fun providerSnapshot(): String? = providerInfo?.let { io.github.proify.android.extensions.json.encodeToString(it) }

    fun close() {
        closed = true
        scope.cancel()
        io.github.proify.lyricon.xposed.hook.ReloadResources.onMain {
            mainHandler.removeCallbacksAndMessages(null)
            processing.invalidate()
            listeners.clear()
            providerInfo = null
        }
    }

    private fun onMain(action: () -> Unit) {
        if (closed || io.github.proify.lyricon.xposed.hook.ReloadResources.retiring) return
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post {
            if (!closed && !io.github.proify.lyricon.xposed.hook.ReloadResources.retiring) action()
        }
    }

    fun addListener(listener: ActivePlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ActivePlayerListener) {
        listeners.remove(listener)
    }

    fun reprocessCurrentSong() = onMain { processing.reprocess() }

    override fun onSongChanged(song: Song?) = onMain { processing.replace(song) }

    // --- 纯状态透传 (不涉及加工) ---

    override fun onReceiveText(text: String?) = onMain {
        processing.invalidate()
        listeners.forEach { it.onReceiveText(text) }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) = onMain {
        listeners.forEach { it.onPlaybackStateChanged(isPlaying) }
    }

    override fun onPositionChanged(position: Long) {
        listeners.forEach { it.onPositionChanged(position) }
    }

    override fun onSeekTo(position: Long) = onMain {
        listeners.forEach { it.onSeekTo(position) }
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = onMain {
        listeners.forEach { it.onDisplayTranslationChanged(isDisplayTranslation) }
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = onMain {
        listeners.forEach { it.onDisplayRomaChanged(isDisplayRoma) }
    }

    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) = onMain {
        if (this.providerInfo == providerInfo) return@onMain
        processing.invalidate()
        this.providerInfo = providerInfo
        listeners.forEach { it.onActiveProviderChanged(providerInfo) }
    }
}
