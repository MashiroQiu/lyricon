package io.github.proify.lyricon.xposed.hook;

import kotlinx.coroutines.Dispatchers;
import okhttp3.internal.concurrent.TaskRunner;

/** Shut down library workers only when they belong to this isolated module generation. */
public final class LibraryWorkers {
    private LibraryWorkers() {}
    public static void close() {
        ClassLoader owner = LibraryWorkers.class.getClassLoader();
        if (TaskRunner.class.getClassLoader() == owner) {
            TaskRunner.INSTANCE.cancelAll();
            if (TaskRunner.INSTANCE.getBackend() instanceof TaskRunner.RealBackend backend) {
                backend.getExecutor().shutdownNow();
                try {
                    if (!backend.getExecutor().awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("OkHttp workers did not terminate");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted retiring OkHttp workers", error);
                }
            }
        }
        OwnedOkio.retire(owner);
        if (Dispatchers.class.getClassLoader() == owner) Dispatchers.INSTANCE.shutdown();
    }
}
