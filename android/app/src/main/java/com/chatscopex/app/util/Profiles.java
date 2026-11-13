package com.chatscopex.app.util;

import android.widget.ImageView;
import android.widget.TextView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.UserProfile;

/** Binds a user's default picture and display name, loading the profile when it is not cached yet. */
public final class Profiles {
    private Profiles() {}

    public static void bind(ImageView avatar, TextView name, String uid, Runnable onLoaded) {
        UserProfile p = ProfileCache.peek(uid);
        Avatars.setUser(avatar, p == null ? null : p.avatar, uid);
        name.setText(p != null ? p.displayName : name.getContext().getString(R.string.unknown_user));
        if (p == null && uid != null) {
            ProfileCache.load(uid, loaded -> { if (onLoaded != null) onLoaded.run(); });
        }
    }
}
