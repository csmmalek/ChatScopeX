package com.chatscopex.app.model;

import org.json.JSONObject;

/** Row in the groups list, from GET /api/groups. */
public class GroupSummary {
    public final String id;
    public final String name;
    public final String avatar;
    public final int memberCount;
    public final String lastText;
    public final String lastType;
    public final String lastSenderId;
    public final long lastTimeMillis;

    private GroupSummary(String id, String name, String avatar, int memberCount, String lastText,
                         String lastType, String lastSenderId, long lastTimeMillis) {
        this.id = id;
        this.name = name;
        this.avatar = avatar;
        this.memberCount = memberCount;
        this.lastText = lastText;
        this.lastType = lastType;
        this.lastSenderId = lastSenderId;
        this.lastTimeMillis = lastTimeMillis;
    }

    public static GroupSummary from(JSONObject o) {
        String avatar = o.isNull("avatar") ? null : o.optString("avatar", null);
        long last = o.isNull("lastAt") ? o.optLong("createdAt") : o.optLong("lastAt");
        return new GroupSummary(o.optString("id"), o.optString("name"), avatar, o.optInt("memberCount"),
                o.isNull("lastText") ? "" : o.optString("lastText"),
                o.isNull("lastType") ? "text" : o.optString("lastType"),
                o.isNull("lastSenderId") ? "" : o.optString("lastSenderId"), last);
    }
}
