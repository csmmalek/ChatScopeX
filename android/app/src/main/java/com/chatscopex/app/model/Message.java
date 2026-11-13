package com.chatscopex.app.model;

import org.json.JSONObject;

/**
 * One chat message (text, image or voice note). "pending" = sent but not confirmed yet;
 * "failed" = rejected or no network, kept locally so the user can tap to retry.
 */
public class Message {
    public static final String TEXT = "text";
    public static final String IMAGE = "image";
    public static final String AUDIO = "audio";

    public final String id;          // the clientId, unique per sender
    public final String senderId;
    public final String text;
    public final String type;
    public final String mediaId;     // null for text
    public final long timeMillis;
    public final boolean pending;
    public final boolean failed;

    public Message(String id, String senderId, String text, String type, String mediaId,
                   long timeMillis, boolean pending, boolean failed) {
        this.id = id;
        this.senderId = senderId;
        this.text = text;
        this.type = type;
        this.mediaId = mediaId;
        this.timeMillis = timeMillis;
        this.pending = pending;
        this.failed = failed;
    }

    /** Key that is unique across senders. */
    public String key() { return senderId + ":" + id; }

    public static Message from(JSONObject o) {
        String media = o.isNull("mediaId") ? null : o.optString("mediaId", null);
        return new Message(o.optString("clientId"), o.optString("senderId"), o.optString("text"),
                o.optString("type", TEXT), media, o.optLong("createdAt"), false, false);
    }
}
