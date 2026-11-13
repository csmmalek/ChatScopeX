package com.chatscopex.app.util;

import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory cache of public profiles. Main thread only (Api callbacks run on the main thread). */
public final class ProfileCache {
    public interface Callback { void onLoaded(UserProfile profile); }

    private static final Map<String, UserProfile> CACHE = new HashMap<>();
    private static final Map<String, List<Callback>> WAITING = new HashMap<>();

    private ProfileCache() {}

    public static UserProfile peek(String uid) { return CACHE.get(uid); }

    public static void put(UserProfile p) { CACHE.put(p.uid, p); }

    public static void clear() {
        CACHE.clear();
        WAITING.clear();
    }

    public static void load(final String uid, Callback cb) {
        UserProfile cached = CACHE.get(uid);
        if (cached != null) { cb.onLoaded(cached); return; }
        List<Callback> waiting = WAITING.get(uid);
        if (waiting != null) { waiting.add(cb); return; }   // a request for this user is already running
        waiting = new ArrayList<>();
        waiting.add(cb);
        WAITING.put(uid, waiting);
        Api.call("GET", "/api/users/" + uid, null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject u = json.optJSONObject("user");
                List<Callback> list = WAITING.remove(uid);
                if (u == null || list == null) return;
                UserProfile p = UserProfile.from(u);
                CACHE.put(uid, p);
                for (Callback c : list) c.onLoaded(p);
            }

            @Override
            public void fail(ApiError error) {
                // Callers show a placeholder name and can try again later.
                WAITING.remove(uid);
            }
        });
    }
}
