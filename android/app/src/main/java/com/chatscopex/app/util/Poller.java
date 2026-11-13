package com.chatscopex.app.util;

import android.os.Handler;
import android.os.Looper;

/** Runs a task now and then every intervalMs while started. Used because there is no push yet. */
public final class Poller {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable task;
    private final long intervalMs;
    private boolean running = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            task.run();
            handler.postDelayed(this, intervalMs);
        }
    };

    public Poller(Runnable task, long intervalMs) {
        this.task = task;
        this.intervalMs = intervalMs;
    }

    public void start() {
        if (running) return;
        running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }
}
