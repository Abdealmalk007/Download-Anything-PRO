package com.downloadanything.pro;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Short local queue writes only; never run rejected work on the Android UI thread. */
final class ShareIntakeExecutor {
    private static final ThreadPoolExecutor EXECUTOR = create();
    private ShareIntakeExecutor() {}
    static ThreadPoolExecutor create() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "DA-ShareIntake");
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    static void execute(Runnable task) { EXECUTOR.execute(task); }
}
