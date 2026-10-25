package com.tsdlanm.documentreader.util;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One small background pool shared by the whole app, plus a main-thread handler. */
public final class Tasks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ExecutorService io;

    private Tasks() {
    }

    public static synchronized ExecutorService io() {
        if (io == null) {
            ThreadPoolExecutor ex = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(), r -> new Thread(() -> {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }, "io"));
            ex.allowCoreThreadTimeOut(true);
            io = ex;
        }
        return io;
    }

    public static void main(Runnable r) {
        MAIN.post(r);
    }
}
