package com.chatscopex.app.util;

import com.chatscopex.app.R;
import com.chatscopex.app.net.ApiError;

/** Maps server error codes to localized, user-friendly strings. Raw server text is never shown. */
public final class ErrorMapper {
    private ErrorMapper() {}

    public static int res(ApiError e) {
        if (e.isNetwork()) return R.string.err_network;
        switch (e.code) {
            case "invalid_credentials": return R.string.err_auth_credentials;
            case "taken": return R.string.err_taken;
            case "too_many_attempts": return R.string.err_too_many;
            case "unauthorized": return R.string.err_session;
            case "invalid_email": return R.string.err_email_invalid;
            case "invalid_password": return R.string.err_password_short;
            case "invalid_username": return R.string.err_username_format;
            case "invalid_name": return R.string.err_name_length;
            case "no_such_group": return R.string.err_no_group;
            case "media_disabled": return R.string.err_media_disabled;
            case "too_large": return R.string.err_file_big;
            case "not_ringing": return R.string.call_ended;
            default: return R.string.err_unknown;
        }
    }
}
