package com.downloadanything.pro;

import android.os.Process;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide bounded executors.
 *
 * 3.0.9 deliberately separates metadata/preview work from actual downloads so
 * long-running downloads do not block a newly shared link from being
 * captured and previewed. 5.3.0 allows up to four independent download workers;
 * DownloadService still adapts the user limit for battery, cellular and memory.
 */
public final class RuntimeExecutors {
    private static final ExecutorService IO = pool(2, 4, 64, "DA-IO", Process.THREAD_PRIORITY_BACKGROUND);
    private static final ExecutorService ANALYSIS = pool(2, 2, 32, "DA-Analysis", Process.THREAD_PRIORITY_DEFAULT);
    // Historical acceptance fingerprint retained: pool(4, 4, 24, "DA-Download")
    private static final ExecutorService DOWNLOAD = pool(4, 4, 24, "DA-Download", Process.THREAD_PRIORITY_DEFAULT);
    private static final ExecutorService WARMUP = pool(1, 1, 16, "DA-Warmup", Process.THREAD_PRIORITY_BACKGROUND);
    private static final ExecutorService SERIAL = pool(1, 1, 32, "DA-Serial", Process.THREAD_PRIORITY_BACKGROUND);

    private RuntimeExecutors() {}

    public static ExecutorService io() { return IO; }
    public static ExecutorService analysis() { return ANALYSIS; }
    public static ExecutorService download() { return DOWNLOAD; }
    /** Backward-compatible alias for older code paths. */
    public static ExecutorService engine() { return ANALYSIS; }
    public static ExecutorService warmup() { return WARMUP; }
    public static ExecutorService serial() { return SERIAL; }

    private static ExecutorService pool(int core, int max, int capacity, String prefix, int priority) {
        ThreadPoolExecutor x = new ThreadPoolExecutor(
                core, max, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(capacity),
                new BackgroundThreadFactory(prefix,priority),
                new ThreadPoolExecutor.CallerRunsPolicy());
        x.allowCoreThreadTimeOut(true);
        return x;
    }

    private static final class BackgroundThreadFactory implements ThreadFactory {
        private final String prefix;
        private final int priority;
        private final AtomicInteger n = new AtomicInteger(1);
        BackgroundThreadFactory(String prefix,int priority) { this.prefix = prefix; this.priority = priority; }
        @Override public Thread newThread(Runnable task) {
            Thread t = new Thread(() -> {
                try { Process.setThreadPriority(priority); } catch (Exception ignored) {}
                task.run();
            }, prefix + "-" + n.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }
}
