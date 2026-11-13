-- Profile pictures (default image keys), group pictures, message types (text/image/audio),
-- read receipts, media metadata, calls (signaling through D1) and login throttling.
ALTER TABLE users ADD COLUMN avatar TEXT;
ALTER TABLE chat_groups ADD COLUMN avatar TEXT;
ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'text';
ALTER TABLE messages ADD COLUMN media_id TEXT;
ALTER TABLE group_messages ADD COLUMN type TEXT NOT NULL DEFAULT 'text';
ALTER TABLE group_messages ADD COLUMN media_id TEXT;

CREATE TABLE IF NOT EXISTS chat_reads (
  user_id TEXT NOT NULL,
  conv    TEXT NOT NULL,
  read_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, conv)
);

CREATE TABLE IF NOT EXISTS media (
  id           TEXT PRIMARY KEY,
  owner_id     TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size         INTEGER NOT NULL,
  created_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS calls (
  id          TEXT PRIMARY KEY,
  caller_id   TEXT NOT NULL,
  callee_id   TEXT NOT NULL,
  type        TEXT NOT NULL,
  status      TEXT NOT NULL,
  offer       TEXT NOT NULL,
  answer      TEXT,
  created_at  INTEGER NOT NULL,
  answered_at INTEGER
);
CREATE INDEX IF NOT EXISTS calls_caller ON calls(caller_id, created_at);
CREATE INDEX IF NOT EXISTS calls_callee ON calls(callee_id, status, created_at);

CREATE TABLE IF NOT EXISTS call_candidates (
  seq        INTEGER PRIMARY KEY AUTOINCREMENT,
  call_id    TEXT NOT NULL,
  sender_id  TEXT NOT NULL,
  candidate  TEXT NOT NULL,
  sdp_mid    TEXT,
  sdp_mline  INTEGER
);
CREATE INDEX IF NOT EXISTS call_candidates_call ON call_candidates(call_id, seq);

CREATE TABLE IF NOT EXISTS login_failures (
  key TEXT NOT NULL,
  at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS login_failures_key ON login_failures(key, at);
