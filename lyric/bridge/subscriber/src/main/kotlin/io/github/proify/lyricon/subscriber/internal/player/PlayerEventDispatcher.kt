/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.subscriber.internal.player

import android.os.Build
import android.os.SharedMemory
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.IActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.subscriber.internal.wire.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 活跃播放器事件分发器。
 *
 * 作为 AIDL 回调接收中心服务推送的播放器事件，再分发给本地 [ActivePlayerListener]。
 * 播放进度通过 [SharedMemory] 轮询读取；轮询仅在绑定共享内存后运行。
 *
 * 单个监听器抛出的异常会被隔离并记录，不影响其他监听器与 AIDL 回调事务。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class PlayerEventDispatcher(context: android.content.Context) : IActivePlayerListener.Stub() {
    override fun asBinder(): android.os.IBinder = io.github.proify.lyricon.common.util.ReloadableBinders.wrap(this)

    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { io.github.proify.lyricon.common.util.ReloadJobs.track(it) }
    private val appContext = context.applicationContext
    private val pollLock = Any()
    private var playing = false
    private var destroyed = false
    private var interactive = appContext.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            synchronized(pollLock) {
                interactive = intent?.action == android.content.Intent.ACTION_SCREEN_ON
                restartPositionPoll()
            }
        }
    }
    init {
        androidx.core.content.ContextCompat.registerReceiver(appContext, screenReceiver,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON); addAction(android.content.Intent.ACTION_SCREEN_OFF)
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private var positionMemory: SharedMemory? = null
    private var positionBuffer: ByteBuffer? = null
    private var positionJob: Job? = null

    /** 注册本地活跃播放器监听器。 */
    fun registerActivePlayerListener(listener: ActivePlayerListener): Boolean = synchronized(pollLock) {
        listeners.add(listener).also { restartPositionPoll() }
    }

    /** 移除本地活跃播放器监听器。 */
    fun unregisterActivePlayerListener(listener: ActivePlayerListener): Boolean = synchronized(pollLock) {
        listeners.remove(listener).also { restartPositionPoll() }
    }

    /** 更新远端提供的播放进度共享内存；null 表示停止位置轮询。 */
    fun setPositionSharedMemory(memory: SharedMemory?) = synchronized(pollLock) {
        detachPositionMemory()
        if (destroyed) { memory?.close(); return@synchronized }
        if (memory == null) return@synchronized

        positionMemory = memory
        positionBuffer = try {
            memory.mapReadOnly()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map position memory", e)
            memory.close()
            positionMemory = null
            null
        }
        restartPositionPoll()
    }

    override fun onActiveProviderChanged(providerInfo: ByteArray?) {
        val info = providerInfo?.takeIf { it.isNotEmpty() }?.decode<ProviderInfo>()
        if (info == null) synchronized(pollLock) { playing = false; restartPositionPoll() }
        dispatch { it.onActiveProviderChanged(info) }
    }

    override fun onSongChanged(song: ByteArray?) {
        val value = song?.takeIf { it.isNotEmpty() }?.decode<Song>()
        dispatch { it.onSongChanged(value) }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        synchronized(pollLock) { playing = isPlaying; restartPositionPoll() }
        dispatch { it.onPlaybackStateChanged(isPlaying) }
    }

    override fun onSeekTo(position: Long) {
        dispatch { it.onSeekTo(position) }
    }

    override fun onReceiveText(text: String?) {
        dispatch { it.onReceiveText(text) }
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        dispatch { it.onDisplayTranslationChanged(isDisplayTranslation) }
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        dispatch { it.onDisplayRomaChanged(isDisplayRoma) }
    }

    /**
     * 释放共享内存；[clearListeners] 为 true 时同时结束调度器生命周期。
     */
    fun release(clearListeners: Boolean = false) = synchronized(pollLock) {
        detachPositionMemory()
        if (!clearListeners) return@synchronized
        if (!destroyed) appContext.unregisterReceiver(screenReceiver)
        destroyed = true

        positionJob?.cancel()
        positionJob = null
        scope.cancel()
        listeners.clear()
    }

    /** 释放共享内存映射并停止位置轮询。 */
    private fun detachPositionMemory() {
        val reader = positionJob
        val buffer = positionBuffer
        positionJob = null
        positionBuffer = null
        reader?.cancel()
        // A cancelled reader may still be inside getLong; unmap only after it has finished.
        if (buffer != null) {
            if (reader == null) SharedMemory.unmap(buffer)
            else reader.invokeOnCompletion { runCatching { SharedMemory.unmap(buffer) } }
        }
        positionMemory?.close()
        positionMemory = null
    }

    /** 重新启动位置轮询（每次绑定共享内存时调用）。 */
    private fun restartPositionPoll() {
        positionJob?.cancel()
        positionJob = null
        if (destroyed || !playing || !interactive || listeners.isEmpty() || positionBuffer == null) return
        positionJob = scope.launch { readPositions() }
    }

    private suspend fun readPositions() {
        var lastPosition = Long.MIN_VALUE
        while (true) {
            val buffer = positionBuffer ?: return
            val position = try {
                buffer.getLong(0)
            } catch (_: Exception) {
                null
            }
            if (position != null && position != lastPosition) {
                lastPosition = position
                dispatch { it.onPositionChanged(position) }
            }
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    /** 向所有监听器分发事件，隔离单个监听器的异常。 */
    private inline fun dispatch(crossinline block: (ActivePlayerListener) -> Unit) {
        for (listener in listeners) {
            try {
                block(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Dispatch failed for listener: " + listener.javaClass.name, e)
            }
        }
    }

    private inline fun <reified T> ByteArray.decode(): T? =
        runCatching { json.decodeFromString<T>(decodeToString()) }
            .onFailure { Log.e(TAG, "Failed to decode active-player payload", it) }
            .getOrNull()

    private companion object {
        private const val TAG = "PlayerEventDispatcher"
        private const val POSITION_POLL_INTERVAL_MS = 16L
    }
}
