package com.chatscopex.app.net;

/** An error answer from the server (code like "invalid_credentials") or a network failure (code "network"). */
public final class ApiError {
    public final int status;
    public final String code;
    public final String message;

    public ApiError(int status, String code, String message) {
        this.status = status;
        this.code = code == null ? "unknown" : code;
        this.message = message == null ? "" : message;
    }

    public boolean isNetwork() { return "network".equals(code); }
}
