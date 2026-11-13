-- Groups, group messages and 24-hour statuses.
-- Table is named chat_groups because "groups" is a reserved word in newer SQLite.
CREATE TABLE IF NOT EXISTS chat_groups (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  created_by TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS group_members (
  group_id  TEXT NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
  user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (group_id, user_id)
);
CREATE INDEX IF NOT EXISTS group_members_user ON group_members(user_id);

-- (group_id, sender_id, client_id) is unique, so a retried send cannot duplicate.
CREATE TABLE IF NOT EXISTS group_messages (
  group_id   TEXT NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
  sender_id  TEXT NOT NULL,
  client_id  TEXT NOT NULL,
  text       TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (group_id, sender_id, client_id)
);
CREATE INDEX IF NOT EXISTS group_messages_time ON group_messages(group_id, created_at);

CREATE TABLE IF NOT EXISTS statuses (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  text       TEXT NOT NULL,
  bg         INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS statuses_user_expiry ON statuses(user_id, expires_at);
