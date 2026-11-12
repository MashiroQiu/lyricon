package io.github.proify.lyricon.xposed.systemui.lyric

import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Inputs and deliveries are serialized on the UI dispatcher; processing stays off it. */
internal class SongProcessingSession(
    private val scope: CoroutineScope,
    private val process: suspend (Song, suspend (Song) -> Unit) -> Unit,
    private val deliver: (Song?) -> Unit,
    private val onError: (Exception) -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deliveryDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    @Volatile private var generation = 0L
    private var input: Song? = null
    private var hasInput = false
    private var job: Job? = null
    private var lastDelivered: Song? = null
    private var hasDelivered = false

    fun replace(song: Song?) {
        if (hasInput && input == song) return
        val request = ++generation
        job?.cancel()
        input = song?.deepCopy()
        hasInput = true
        start(request)
    }

    fun reprocess() {
        if (!hasInput) return
        val request = ++generation
        job?.cancel()
        start(request)
    }

    fun invalidate() {
        generation++
        job?.cancel()
        job = null
        input = null
        hasInput = false
        lastDelivered = null
        hasDelivered = false
    }

    private fun start(request: Long) {
        val source = input
        job = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            try {
                if (source == null) publish(request, null)
                else process(source.deepCopy()) { publish(request, it) }
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                withContext(deliveryDispatcher) {
                    if (request == generation) {
                        hasInput = false
                        onError(error)
                    }
                }
            }
        }.also { it.start() }
    }

    private suspend fun publish(request: Long, song: Song?) {
        if (request != generation) return
        val normalized = song?.normalize()
        withContext(deliveryDispatcher) {
            if (request != generation) return@withContext
            if (hasDelivered && lastDelivered == normalized) return@withContext
            lastDelivered = normalized
            hasDelivered = true
            deliver(normalized)
        }
    }
}
