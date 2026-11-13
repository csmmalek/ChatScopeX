package com.chatscopex.app.model;

import org.json.JSONObject;

/** Public profile from the server. Contains no email and no secrets. avatar = default-image key (a0..a7) or null. */
public class UserProfile {
    public final String uid;
    public final String displayName;
    public final String username;
    public final String avatar;

    public UserProfile(String uid, String displayName, String username, String avatar) {
        this.uid = uid;
        this.displayName = displayName;
        this.username = username;
        this.avatar = avatar;
    }

    public static UserProfile from(JSONObject o) {
        String avatar = o.isNull("avatar") ? null : o.optString("avatar", null);
        return new UserProfile(o.optString("id"), o.optString("displayName"), o.optString("username"), avatar);
    }
}
