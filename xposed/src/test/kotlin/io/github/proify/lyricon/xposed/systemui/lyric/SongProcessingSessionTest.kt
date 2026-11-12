package io.github.proify.lyricon.xposed.systemui.lyric

import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class SongProcessingSessionTest {
    private class Queue : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class Harness(
        process: suspend (Song, suspend (Song) -> Unit) -> Unit = { song, emit -> emit(song) },
    ) : AutoCloseable {
        val worker = Queue()
        val main = Queue()
        val scope = CoroutineScope(SupervisorJob() + worker)
        val output = mutableListOf<Song?>()
        val errors = mutableListOf<Exception>()
        val session = SongProcessingSession(scope, process, { output.add(it) }, { errors.add(it) }, worker, main)
        fun pump() {
            while (worker.tasks.isNotEmpty() || main.tasks.isNotEmpty()) {
                worker.drain(); main.drain()
            }
        }
        override fun close() { session.invalidate(); scope.cancel(); pump() }
    }

    @Test fun queuedOldDisplayCannotOverwriteANewerInput() {
        Harness().use { h ->
            h.session.replace(Song(id = "old"))
            h.worker.drain()
            assertTrue(h.main.tasks.isNotEmpty())
            h.session.replace(Song(id = "new"))
            h.pump()
            assertEquals(listOf("new"), h.output.map { it?.id })
        }
    }

    @Test fun uncancellableOldEnhancementCannotResurrectAPreviousSong() {
        val release = CompletableDeferred<Unit>()
        Harness { song, emit ->
            emit(song)
            if (song.id == "old") withContext(NonCancellable) {
                release.await()
                emit(song.copy(name = "late enhancement"))
            }
        }.use { h ->
            h.session.replace(Song(id = "old")); h.pump()
            h.session.replace(Song(id = "new")); h.pump()
            release.complete(Unit); h.pump()
            assertEquals(listOf("old", "new"), h.output.map { it?.id })
            assertTrue(h.errors.isEmpty())
        }
    }

    @Test fun providerChangeInvalidatesPendingDeliveryAndClearsTheReprocessCache() {
        Harness().use { h ->
            h.session.replace(Song(id = "old")); h.worker.drain()
            h.session.invalidate()
            h.session.reprocess()
            h.pump()
            assertTrue(h.output.isEmpty())
            h.session.replace(Song(id = "new")); h.pump()
            assertEquals(listOf("new"), h.output.map { it?.id })
        }
    }

    @Test fun duplicateFullSongsDoNotRestartProcessingOrDispatchTwice() {
        var runs = 0
        Harness { song, emit -> runs++; emit(song); emit(song.copy()) }.use { h ->
            repeat(50) { h.session.replace(Song(id = "same")) }
            h.pump()
            repeat(50) { h.session.replace(Song(id = "same")) }
            h.pump()
            assertEquals(1, runs)
            assertEquals(1, h.output.size)
        }
    }

    @Test fun equalHashCodesCannotSuppressDifferentSongs() {
        val first = Song(id = "Aa")
        val second = Song(id = "BB")
        assertEquals(first.hashCode(), second.hashCode())
        Harness().use { h ->
            h.session.replace(first); h.pump()
            h.session.replace(second); h.pump()
            assertEquals(listOf("Aa", "BB"), h.output.map { it?.id })
        }
    }

    @Test fun explicitConfigurationChangeCanReprocessAnUnchangedRawSong() {
        var title = "before"
        Harness { song, emit -> emit(song.copy(name = title)) }.use { h ->
            h.session.replace(Song(id = "same")); h.pump()
            title = "after"
            h.session.reprocess(); h.pump()
            assertEquals(listOf("before", "after"), h.output.map { it?.name })
        }
    }

    @Test fun changingProviderCanRedisplayAnIdenticalSong() {
        Harness().use { h ->
            h.session.replace(Song(id = "same")); h.pump()
            h.session.invalidate()
            h.session.replace(Song(id = "same")); h.pump()
            assertEquals(2, h.output.size)
        }
    }
}
