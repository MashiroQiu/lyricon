package io.github.proify.lyricon.common.util

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReloadJobsTest {
    @Test fun retirementWaitsForCancelledChildCleanup() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        ReloadJobs.enabled = true
        ReloadJobs.track(scope)
        val child = scope.launch {
            try { started.countDown(); awaitCancellation() }
            finally { withContext(NonCancellable) {
                cleanupStarted.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS))
            } }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val done = executor.submit { ReloadJobs.retire() }
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            assertFalse(done.isDone)
            releaseCleanup.countDown()
            done.get(2, TimeUnit.SECONDS)
            assertTrue(child.isCompleted)
            assertTrue(scope.coroutineContext[Job]!!.isCompleted)
        } finally {
            releaseCleanup.countDown()
            scope.cancel()
            executor.shutdownNow()
            ReloadJobs.retire()
        }
    }
    @Test fun ordinaryHostScopeIsNotRetired() {
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ReloadJobs.enabled = false
        ReloadJobs.track(host)
        ReloadJobs.retire()
        assertTrue(host.isActive)
        host.cancel()
    }
    @Test fun newGenerationRegistersNewScopesWithoutRetainingOldJobs() {
        repeat(3) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            ReloadJobs.enabled = true
            ReloadJobs.track(scope)
            ReloadJobs.retire()
            assertTrue(scope.coroutineContext[Job]!!.isCompleted)
        }
    }
}
