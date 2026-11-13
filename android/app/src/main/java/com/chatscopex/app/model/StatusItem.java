package com.chatscopex.app.model;

import org.json.JSONObject;

/** A text status. Expires 24 hours after creation (the server also hides expired ones). */
public class StatusItem {
    public final String id;
    public final String ownerId;
    public final String text;
    public final int bg;              // index into Palette.STATUS_BG
    public final long createdMillis;
    public final long expireMillis;

    private StatusItem(String id, String ownerId, String text, int bg, long createdMillis, long expireMillis) {
        this.id = id;
        this.ownerId = ownerId;
        this.text = text;
        this.bg = bg;
        this.createdMillis = createdMillis;
        this.expireMillis = expireMillis;
    }

    public boolean isActive(long now) { return expireMillis > now; }

    public static StatusItem from(JSONObject o) {
        return new StatusItem(o.optString("id"), o.optString("userId"), o.optString("text"),
                o.optInt("bg"), o.optLong("createdAt"), o.optLong("expiresAt"));
    }
}
