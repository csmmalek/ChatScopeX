package com.chatscopex.app.net;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.chatscopex.app.BuildConfig;
import com.chatscopex.app.ui.LauncherActivity;
import com.chatscopex.app.util.ProfileCache;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP client for the Cloudflare Worker. Requests run on a small thread pool and callbacks always
 * arrive on the main thread, so UI code can use the results directly.
 */
public final class Api {
    public interface Cb {
        void ok(JSONObject json);
        void fail(ApiError error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static Context app;

    private Api() {}

    public static void init(Context appContext) { app = appContext.getApplicationContext(); }

    /** JSON request. body may be null. The session token is attached automatically. */
    public static void call(final String method, final String path, final JSONObject body, final Cb cb) {
        POOL.execute(() -> {
            try {
                byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
                Result r = exec(method, path, payload, "application/json");
                if (r.status >= 400) {
                    deliverFail(r, cb);
                    return;
                }
                String text = new String(r.body, StandardCharsets.UTF_8);
                final JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                MAIN.post(() -> cb.ok(json));
            } catch (Exception e) {
                networkFail(cb, e);
            }
        });
    }

    /** Uploads raw bytes (an image or a voice note). */
    public static void upload(final String path, final byte[] data, final String contentType, final Cb cb) {
        POOL.execute(() -> {
            try {
                Result r = exec("POST", path, data, contentType);
                if (r.status >= 400) {
                    deliverFail(r, cb);
                    return;
                }
                final JSONObject json = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
                MAIN.post(() -> cb.ok(json));
            } catch (Exception e) {
                networkFail(cb, e);
            }
        });
    }

    /** Blocking download. Call only from a background thread. */
    public static byte[] getBytes(String path) throws IOException {
        Result r = exec("GET", path, null, null);
        if (r.status >= 400) throw new IOException("HTTP " + r.status);
        return r.body;
    }

    private static final class Result {
        final int status;
        final byte[] body;
        Result(int status, byte[] body) { this.status = status; this.body = body; }
    }

    private static Result exec(String method, String path, byte[] payload, String contentType) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.API_BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            String token = Session.token();
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (payload != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(payload.length);
                OutputStream os = c.getOutputStream();
                try { os.write(payload); } finally { os.close(); }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Result(code, is == null ? new byte[0] : readAll(is));
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            is.close();
        }
        return out.toByteArray();
    }

    private static void deliverFail(Result r, Cb cb) {
        String code = "unknown";
        String message = "";
        try {
            JSONObject err = new JSONObject(new String(r.body, StandardCharsets.UTF_8)).optJSONObject("error");
            if (err != null) {
                code = err.optString("code", "unknown");
                message = err.optString("message", "");
            }
        } catch (Exception ignored) {
            // not JSON: keep the generic error
        }
        final ApiError e = new ApiError(r.status, code, message);
        MAIN.post(() -> {
            // A rejected token means the session is over (expired or logged out elsewhere).
            if (e.status == 401 && "unauthorized".equals(e.code)) expireSession();
            cb.fail(e);
        });
    }

    private static void networkFail(Cb cb, Exception e) {
        final ApiError err = new ApiError(0, "network", String.valueOf(e.getMessage()));
        MAIN.post(() -> cb.fail(err));
    }

    private static void expireSession() {
        if (!Session.signedIn() || app == null) return;
        Session.clear();
        ProfileCache.clear();
        Intent i = new Intent(app, LauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        app.startActivity(i);
    }
}
