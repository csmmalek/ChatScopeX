package com.chatscopex.app.util;

import android.content.Context;

import com.chatscopex.app.R;
import com.chatscopex.app.model.Message;

/** Text shown in lists for the last message: the text itself, or a label for an image or voice note. */
public final class Previews {
    private Previews() {}

    public static String text(Context c, String type, String text) {
        if (Message.IMAGE.equals(type)) return c.getString(R.string.msg_image);
        if (Message.AUDIO.equals(type)) return c.getString(R.string.msg_audio);
        return text;
    }
}
