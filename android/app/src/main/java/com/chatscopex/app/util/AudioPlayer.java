package com.chatscopex.app.util;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.chatscopex.app.net.Api;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plays voice notes one at a time. Tapping the playing one stops it. Downloads go to the app cache. */
public final class AudioPlayer {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static String currentId;
    private static String loadingId;

    private AudioPlayer() {}

    public static boolean isPlaying(String mediaId) { return mediaId.equals(currentId); }

    public static void toggle(final Context ctx, final String mediaId, final Runnable onChange) {
        if (mediaId.equals(currentId) || mediaId.equals(loadingId)) {
            stop();
            onChange.run();
            return;
        }
        stop();
        loadingId = mediaId;
        final File dir = ctx.getApplicationContext().getCacheDir();
        POOL.execute(() -> {
            try {
                File f = new File(dir, "audio_" + mediaId + ".m4a");
                if (!f.exists()) {
                    byte[] bytes = Api.getBytes("/api/media/" + mediaId);
                    FileOutputStream out = new FileOutputStream(f);
                    try { out.write(bytes); } finally { out.close(); }
                }
                final String path = f.getAbsolutePath();
                MAIN.post(() -> {
                    if (!mediaId.equals(loadingId)) return;   // cancelled while downloading
                    loadingId = null;
                    start(path, mediaId, onChange);
                });
            } catch (Exception e) {
                MAIN.post(() -> {
                    loadingId = null;
                    onChange.run();
                });
            }
        });
    }

    private static void start(String path, final String mediaId, final Runnable onChange) {
        try {
            MediaPlayer p = new MediaPlayer();
            p.setDataSource(path);
            p.setOnCompletionListener(mp -> {
                stop();
                onChange.run();
            });
            p.prepare();
            p.start();
            player = p;
            currentId = mediaId;
        } catch (Exception e) {
            stop();
        }
        onChange.run();
    }

    public static void stop() {
        loadingId = null;
        currentId = null;
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
    }
}
