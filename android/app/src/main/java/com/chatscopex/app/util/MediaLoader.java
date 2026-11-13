package com.chatscopex.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.chatscopex.app.net.Api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads chat images from the server (with the session token), decodes them small, and caches them in memory. */
public final class MediaLoader {
    private static final int MAX_SIDE = 1024;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private MediaLoader() {}

    public static void load(final ImageView view, final String mediaId) {
        view.setTag(mediaId);
        Bitmap cached = CACHE.get(mediaId);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            try {
                byte[] bytes = Api.getBytes("/api/media/" + mediaId);
                final Bitmap bmp = decode(bytes);
                if (bmp == null) return;
                CACHE.put(mediaId, bmp);
                MAIN.post(() -> {
                    // The row may have been recycled for another message while this was loading.
                    if (mediaId.equals(view.getTag())) view.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                // Leave the placeholder empty; the next bind retries.
            }
        });
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }
}
