package com.chatscopex.app.model;

import org.json.JSONObject;

/** Row in the chat list, from GET /api/chats. */
public class ChatSummary {
    public final String otherUid;
    public final String lastText;
    public final String lastType;
    public final String lastSenderId;
    public final long lastTimeMillis;
    public final boolean unread;

    private ChatSummary(String otherUid, String lastText, String lastType, String lastSenderId,
                        long lastTimeMillis, boolean unread) {
        this.otherUid = otherUid;
        this.lastText = lastText;
        this.lastType = lastType;
        this.lastSenderId = lastSenderId;
        this.lastTimeMillis = lastTimeMillis;
        this.unread = unread;
    }

    public static ChatSummary from(JSONObject o) {
        return new ChatSummary(o.optString("otherId"), o.optString("lastText"), o.optString("lastType", "text"),
                o.optString("lastSenderId"), o.optLong("lastAt"), o.optBoolean("unread"));
    }
}
