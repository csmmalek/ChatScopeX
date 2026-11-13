CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  username      TEXT NOT NULL UNIQUE,
  display_name  TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);

-- Only a SHA-256 of the token is stored, never the token itself.
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);

-- conv = the two user ids sorted and joined by ':', so both sides query the same key.
-- (sender_id, client_id) is the primary key, so a retried send cannot create a duplicate.
CREATE TABLE IF NOT EXISTS messages (
  sender_id    TEXT NOT NULL,
  client_id    TEXT NOT NULL,
  recipient_id TEXT NOT NULL,
  conv         TEXT NOT NULL,
  text         TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  PRIMARY KEY (sender_id, client_id)
);
CREATE INDEX IF NOT EXISTS messages_conv_time ON messages(conv, created_at);
