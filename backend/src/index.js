import { hashPassword, verifyPassword, newToken, sha256Hex } from "./auth.js";
import { json, fail, readBody, conversationKey } from "./http.js";
import { handleSocial } from "./social.js";
import { handleCalls } from "./calls.js";
import { handleMedia, parseMessageBody } from "./media.js";
export { Inbox } from "./inbox.js";

const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const EMAIL_RE = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,24}$/;
const USERNAME_RE = /^[a-z0-9_]{3,20}$/;
const AVATAR_RE = /^a[0-7]$/;
const LOGIN_WINDOW_MS = 15 * 60 * 1000;
const LOGIN_MAX_PER_EMAIL = 10;
const LOGIN_MAX_PER_IP = 30;

let dummyHash; // used on unknown-email logins so both paths cost the same time

const userJson = (u) => ({ id: u.id, username: u.username, displayName: u.displayName, avatar: u.avatar ?? null });

/** Resolves "Authorization: Bearer <token>" to the signed-in user, or null. */
async function currentSession(request, env) {
  const m = /^Bearer ([A-Za-z0-9_-]{32,128})$/.exec(request.headers.get("authorization") || "");
  if (!m) return null;
  const tokenHash = await sha256Hex(m[1]);
  const user = await env.DB.prepare(
    `SELECT u.id, u.username, u.display_name AS displayName, u.avatar
       FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = ? AND s.expires_at > ?`
  ).bind(tokenHash, Date.now()).first();
  return user ? { user, tokenHash } : null;
}

async function createSession(env, userId) {
  const token = newToken();
  const now = Date.now();
  await env.DB.prepare(
    "INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)"
  ).bind(await sha256Hex(token), userId, now, now + TOKEN_TTL_MS).run();
  return token;
}

// ---------------------------------------------------------------- accounts

async function signup(request, env) {
  const { body, error } = await readBody(request);
  if (error) return error;

  const email = String(body.email ?? "").trim().toLowerCase();
  const password = String(body.password ?? "");
  const username = String(body.username ?? "").trim().toLowerCase();
  const displayName = String(body.displayName ?? "").trim();
  const avatar = body.avatar === undefined ? null : String(body.avatar);

  if (!EMAIL_RE.test(email)) return fail(400, "invalid_email", "invalid email");
  if (password.length < 8 || password.length > 128) {
    return fail(400, "invalid_password", "password must be 8 to 128 characters");
  }
  if (!USERNAME_RE.test(username)) {
    return fail(400, "invalid_username", "username: 3 to 20 lowercase letters, digits or _");
  }
  if (displayName.length < 1 || displayName.length > 40) {
    return fail(400, "invalid_name", "display name: 1 to 40 characters");
  }
  if (avatar !== null && !AVATAR_RE.test(avatar)) return fail(400, "invalid_avatar", "unknown picture");

  const id = crypto.randomUUID();
  const hash = await hashPassword(password);
  try {
    await env.DB.prepare(
      "INSERT INTO users (id, email, password_hash, username, display_name, created_at, avatar) VALUES (?, ?, ?, ?, ?, ?, ?)"
    ).bind(id, email, hash, username, displayName, Date.now(), avatar).run();
  } catch (e) {
    if (String(e.message).includes("UNIQUE")) {
      // One message for both fields, so the form cannot be used to probe which emails exist.
      return fail(409, "taken", "this email or username is already in use");
    }
    throw e;
  }
  const token = await createSession(env, id);
  return json({ token, user: { id, username, displayName, avatar } }, 201);
}

async function failureCount(env, key, since) {
  const r = await env.DB.prepare("SELECT COUNT(*) AS n FROM login_failures WHERE key = ? AND at > ?")
    .bind(key, since).first();
  return r.n;
}

async function login(request, env) {
  const { body, error } = await readBody(request);
  if (error) return error;

  const email = String(body.email ?? "").trim().toLowerCase();
  const password = String(body.password ?? "");
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const emailKey = `e:${email.slice(0, 254)}`;
  const ipKey = `i:${ip}`;
  const now = Date.now();

  // Throttle before doing any password work, so a flood cannot burn CPU.
  if ((await failureCount(env, emailKey, now - LOGIN_WINDOW_MS)) >= LOGIN_MAX_PER_EMAIL ||
      (await failureCount(env, ipKey, now - LOGIN_WINDOW_MS)) >= LOGIN_MAX_PER_IP) {
    return fail(429, "too_many_attempts", "too many attempts, try again in a few minutes");
  }

  const row = await env.DB.prepare(
    `SELECT id, password_hash, username, display_name AS displayName, avatar FROM users WHERE email = ?`
  ).bind(email).first();

  dummyHash ??= hashPassword("not-a-real-password");
  const ok = row
    ? await verifyPassword(password, row.password_hash)
    : (await verifyPassword(password, await dummyHash), false);

  if (!ok) {
    await env.DB.prepare("INSERT INTO login_failures (key, at) VALUES (?, ?)").bind(emailKey, now).run();
    await env.DB.prepare("INSERT INTO login_failures (key, at) VALUES (?, ?)").bind(ipKey, now).run();
    await env.DB.prepare("DELETE FROM login_failures WHERE at < ?").bind(now - LOGIN_WINDOW_MS).run();
    return fail(401, "invalid_credentials", "email or password is wrong");
  }
  await env.DB.prepare("DELETE FROM login_failures WHERE key = ?").bind(emailKey).run();
  const token = await createSession(env, row.id);
  return json({ token, user: userJson(row) });
}

async function updateMe(request, me, env) {
  const { body, error } = await readBody(request);
  if (error) return error;
  const next = { displayName: me.displayName, avatar: me.avatar ?? null };
  if (body.displayName !== undefined) {
    const name = String(body.displayName).trim();
    if (name.length < 1 || name.length > 40) return fail(400, "invalid_name", "display name: 1 to 40 characters");
    next.displayName = name;
  }
  if (body.avatar !== undefined) {
    const a = String(body.avatar);
    if (!AVATAR_RE.test(a)) return fail(400, "invalid_avatar", "unknown picture");
    next.avatar = a;
  }
  await env.DB.prepare("UPDATE users SET display_name = ?, avatar = ? WHERE id = ?")
    .bind(next.displayName, next.avatar, me.id).run();
  return json({ user: userJson({ ...me, ...next }) });
}

async function searchUsers(url, me, env) {
  const q = (url.searchParams.get("q") || "").trim().toLowerCase().replace(/^@/, "");
  if (q.length < 2 || q.length > 20) return fail(400, "invalid_query", "search needs 2 to 20 characters");
  const pattern = q.replace(/[\\%_]/g, (c) => "\\" + c) + "%";
  const rows = await env.DB.prepare(
    `SELECT id, username, display_name AS displayName, avatar FROM users
      WHERE (username LIKE ? ESCAPE '\\' OR display_name LIKE ? ESCAPE '\\') AND id != ?
      ORDER BY username LIMIT 20`
  ).bind(pattern, pattern, me.id).all();
  return json({ users: rows.results });
}

async function getUser(id, env) {
  const u = await env.DB.prepare(
    "SELECT id, username, display_name AS displayName, avatar FROM users WHERE id = ?"
  ).bind(id).first();
  if (!u) return fail(404, "no_such_user", "user not found");
  return json({ user: userJson(u) });
}

// ---------------------------------------------------------------- direct messages

const MSG_COLUMNS = `sender_id AS senderId, client_id AS clientId, recipient_id AS recipientId,
                     type, media_id AS mediaId, text, created_at AS createdAt`;

async function push(env, userId, payload) {
  if (!env.INBOX) return; // live push is optional; clients also poll
  const stub = env.INBOX.get(env.INBOX.idFromName(userId));
  return stub.fetch("https://inbox/push", { method: "POST", body: payload });
}

async function sendMessage(request, me, env) {
  const { body, error } = await readBody(request);
  if (error) return error;

  const to = String(body.to ?? "");
  if (!to || to.length > 64) return fail(400, "invalid_recipient", "recipient missing");
  if (to === me.id) return fail(400, "self_message", "cannot message yourself");
  const parsed = await parseMessageBody(body, me, env);
  if (parsed.error) return parsed.error;

  const recipient = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(to).first();
  if (!recipient) return fail(404, "no_such_user", "recipient not found");

  const now = Date.now();
  // INSERT OR IGNORE: a retry with the same clientId is a no-op, not a duplicate.
  const result = await env.DB.prepare(
    `INSERT OR IGNORE INTO messages (sender_id, client_id, recipient_id, conv, type, media_id, text, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(me.id, parsed.clientId, to, conversationKey(me.id, to), parsed.type, parsed.mediaId, parsed.text, now).run();

  const message = await env.DB.prepare(
    `SELECT ${MSG_COLUMNS} FROM messages WHERE sender_id = ? AND client_id = ?`
  ).bind(me.id, parsed.clientId).first();

  const created = result.meta?.changes === 1;
  if (created) {
    const payload = JSON.stringify({ type: "message", message });
    await Promise.allSettled([to, me.id].map((userId) => push(env, userId, payload)));
  }
  return json({ message, created }, created ? 201 : 200);
}

async function listMessages(url, me, env) {
  const other = url.searchParams.get("with") || "";
  if (!other || other.length > 64) return fail(400, "invalid_with", "'with' is required");
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 50), 1), 100);
  const before = Number(url.searchParams.get("before") || Date.now() + 1);
  if (!Number.isFinite(before)) return fail(400, "invalid_before", "'before' must be a timestamp");

  const conv = conversationKey(me.id, other);
  const rows = await env.DB.prepare(
    `SELECT ${MSG_COLUMNS} FROM messages
      WHERE conv = ? AND created_at < ? ORDER BY created_at DESC LIMIT ?`
  ).bind(conv, before, limit).all();
  const read = await env.DB.prepare("SELECT read_at AS readAt FROM chat_reads WHERE user_id = ? AND conv = ?")
    .bind(other, conv).first();
  return json({ messages: rows.results, otherReadAt: read ? read.readAt : 0 });
}

async function markRead(request, me, env) {
  const { body, error } = await readBody(request);
  if (error) return error;
  const other = String(body.with ?? "");
  if (!other || other.length > 64 || other === me.id) return fail(400, "invalid_with", "'with' is required");
  await env.DB.prepare(
    `INSERT INTO chat_reads (user_id, conv, read_at) VALUES (?, ?, ?)
     ON CONFLICT(user_id, conv) DO UPDATE SET read_at = excluded.read_at`
  ).bind(me.id, conversationKey(me.id, other), Date.now()).run();
  return json({ ok: true });
}

/** One row per conversation: the other person, the last message and whether it is unread. */
async function listChats(me, env) {
  const id = me.id;
  const rows = await env.DB.prepare(
    `SELECT m.sender_id AS senderId, m.type, m.text, m.created_at AS createdAt,
            u.id AS otherId, u.username, u.display_name AS displayName, u.avatar,
            COALESCE(r.read_at, 0) AS myReadAt
       FROM messages m
       JOIN (SELECT conv, MAX(created_at) AS t FROM messages
              WHERE sender_id = ? OR recipient_id = ? GROUP BY conv) x
         ON x.conv = m.conv AND x.t = m.created_at
       JOIN users u ON u.id = CASE WHEN m.sender_id = ? THEN m.recipient_id ELSE m.sender_id END
       LEFT JOIN chat_reads r ON r.user_id = ? AND r.conv = m.conv
      WHERE m.sender_id = ? OR m.recipient_id = ?
      ORDER BY m.created_at DESC LIMIT 100`
  ).bind(id, id, id, id, id, id).all();

  const seen = new Set();
  const chats = [];
  for (const r of rows.results) {
    if (seen.has(r.otherId)) continue; // two messages in the same millisecond
    seen.add(r.otherId);
    chats.push({
      otherId: r.otherId, username: r.username, displayName: r.displayName, avatar: r.avatar ?? null,
      lastText: r.text, lastType: r.type, lastSenderId: r.senderId, lastAt: r.createdAt,
      unread: r.senderId !== id && r.createdAt > r.myReadAt,
    });
    if (chats.length === 50) break;
  }
  return json({ chats });
}

// ---------------------------------------------------------------- router

async function route(request, env) {
  const url = new URL(request.url);
  const { pathname } = url;
  const method = request.method;

  if (pathname === "/api/health") return json({ ok: true });
  if (pathname === "/api/signup" && method === "POST") return signup(request, env);
  if (pathname === "/api/login" && method === "POST") return login(request, env);
  if (!pathname.startsWith("/api/")) return fail(404, "not_found", "no such route");

  const session = await currentSession(request, env);
  if (!session) return fail(401, "unauthorized", "sign in required");
  const me = session.user;

  if (pathname === "/api/me" && method === "GET") return json({ user: userJson(me) });
  if (pathname === "/api/me" && method === "PATCH") return updateMe(request, me, env);
  if (pathname === "/api/logout" && method === "POST") {
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(session.tokenHash).run();
    return json({ ok: true });
  }
  if (pathname === "/api/users" && method === "GET") return searchUsers(url, me, env);
  const u = /^\/api\/users\/([A-Za-z0-9-]{1,64})$/.exec(pathname);
  if (u && method === "GET") return getUser(u[1], env);

  if (pathname === "/api/chats" && method === "GET") return listChats(me, env);
  if (pathname === "/api/messages" && method === "POST") return sendMessage(request, me, env);
  if (pathname === "/api/messages" && method === "GET") return listMessages(url, me, env);
  if (pathname === "/api/messages/read" && method === "POST") return markRead(request, me, env);

  if (pathname === "/api/ws" && method === "GET") {
    if (request.headers.get("Upgrade") !== "websocket") return fail(426, "upgrade_required", "websocket expected");
    if (!env.INBOX) return fail(501, "live_disabled", "live connection is not enabled");
    const stub = env.INBOX.get(env.INBOX.idFromName(me.id));
    return stub.fetch(new Request("https://inbox/connect", { headers: { Upgrade: "websocket" } }));
  }

  for (const handler of [handleSocial, handleCalls, handleMedia]) {
    const res = await handler(request, url, me, env);
    if (res) return res;
  }
  return fail(404, "not_found", "no such route");
}

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      console.error("unhandled", e);
      return fail(500, "internal", "internal error");
    }
  },
};
