package com.chatscopex.app.model;

import org.json.JSONObject;

/** A call as shown in the call history, from GET /api/calls. */
public class CallRecord {
    public final String id;
    public final String callerId;
    public final String calleeId;
    public final String type;     // "audio" or "video"
    public final String status;   // ringing, active, ended, declined
    public final boolean answered;
    public final long createdMillis;

    private CallRecord(String id, String callerId, String calleeId, String type, String status,
                       boolean answered, long createdMillis) {
        this.id = id;
        this.callerId = callerId;
        this.calleeId = calleeId;
        this.type = type;
        this.status = status;
        this.answered = answered;
        this.createdMillis = createdMillis;
    }

    public boolean isOutgoing(String myUid) { return myUid.equals(callerId); }

    public String otherUid(String myUid) { return isOutgoing(myUid) ? calleeId : callerId; }

    public boolean isVideo() { return "video".equals(type); }

    public static CallRecord from(JSONObject o) {
        return new CallRecord(o.optString("id"), o.optString("callerId"), o.optString("calleeId"),
                o.optString("type"), o.optString("status"), o.optBoolean("answered"), o.optLong("createdAt"));
    }
}
