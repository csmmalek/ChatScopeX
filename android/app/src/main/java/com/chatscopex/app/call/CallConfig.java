package com.chatscopex.app.call;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ICE servers for calls. The server (GET /api/turn) returns STUN, plus short-lived TURN credentials
 * when a Cloudflare TURN key is configured. Without TURN, calls work on many Wi-Fi networks but often
 * fail between mobile networks or behind strict firewalls.
 */
public final class CallConfig {
    private CallConfig() {}

    /** Used when the server cannot be reached. */
    public static List<PeerConnection.IceServer> fallback() {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        out.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        return out;
    }

    public static List<PeerConnection.IceServer> fromJson(JSONArray arr) {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                List<String> urls = new ArrayList<>();
                JSONArray ua = o.optJSONArray("urls");
                if (ua != null) {
                    for (int j = 0; j < ua.length(); j++) urls.add(ua.optString(j));
                } else if (o.optString("urls").length() > 0) {
                    urls.addAll(Arrays.asList(o.optString("urls")));
                }
                if (urls.isEmpty()) continue;
                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urls);
                String user = o.optString("username", "");
                String cred = o.optString("credential", "");
                if (!user.isEmpty()) b.setUsername(user);
                if (!cred.isEmpty()) b.setPassword(cred);
                out.add(b.createIceServer());
            }
        }
        return out.isEmpty() ? fallback() : out;
    }
}
