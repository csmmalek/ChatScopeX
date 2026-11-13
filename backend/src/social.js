// Groups, group messages and 24-hour statuses. Every route here requires a signed-in user.
// Access rules: a group is visible only to its members (others get 404, not 403, so ids cannot be probed).
// A status is visible to its owner and to people who share a direct chat or a group with the owner.
import { json as jsonRes, fail as failRes, readBody as bodyOrError } from "./http.js";
import { parseMessageBody } from "./media.js";

const SOCIAL_MAX_MEMBERS = 50;
const SOCIAL_GROUP_NAME_MAX = 50;
const SOCIAL_STATUS_TEXT_MAX = 200;
const SOCIAL_STATUS_TTL_MS = 24 * 60 * 60 * 1000;

async function isMember(env, groupId, userId) {
  const row = await env.DB.prepare(
    "SELECT 1 AS ok FROM group_members WHERE group_id = ? AND user_id = ?"
  ).bind(groupId, userId).first();
  return Boolean(row);
}

async function existingUserIds(env, ids) {
  if (ids.length === 0) return [];
  const marks = ids.map(() => "?").join(",");
  const rows = await env.DB.prepare(`SELECT id FROM users WHERE id IN (${marks})`).bind(...ids).all();
  return rows.results.map((r) => r.id);
}

function cleanIds(list, me) {
  if (!Array.isArray(list)) return [];
  return [...new Set(list.map(String))].filter((x) => x && x !== me.id && x.length <= 64);
}

// ---------------------------------------------------------------- groups

async function createGroup(request, me, env) {
  const { body, error } = await bodyOrError(request);
  if (error) return error;
  const name = String(body.name ?? "").trim();
  if (name.length < 1 || name.length > SOCIAL_GROUP_NAME_MAX) {
    return failRes(400, "invalid_name", "group name: 1 to 50 characters");
  }
  const avatar = body.avatar === undefined ? "g0" : String(body.avatar);
  if (!/^g[0-5]$/.test(avatar)) return failRes(400, "invalid_avatar", "unknown picture");
  const ids = cleanIds(body.memberIds, me);
  if (ids.length < 1 || ids.length > SOCIAL_MAX_MEMBERS - 1) {
    return failRes(400, "invalid_members", "a group needs 1 to 49 other members");
  }
  const found = await existingUserIds(env, ids);
  if (found.length !== ids.length) return failRes(404, "no_such_user", "a member does not exist");

  const id = crypto.randomUUID();
  const now = Date.now();
  const stmts = [
    env.DB.prepare("INSERT INTO chat_groups (id, name, created_by, created_at, avatar) VALUES (?, ?, ?, ?, ?)")
      .bind(id, name, me.id, now, avatar),
    env.DB.prepare("INSERT INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, ?)")
      .bind(id, me.id, now),
    ...ids.map((uid) =>
      env.DB.prepare("INSERT INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, ?)")
        .bind(id, uid, now)),
  ];
  await env.DB.batch(stmts);
  return jsonRes({ group: { id, name, avatar, createdAt: now, memberCount: ids.length + 1 } }, 201);
}

async function listGroups(me, env) {
  const rows = await env.DB.prepare(
    `SELECT g.id, g.name, g.avatar, g.created_at AS createdAt,
            (SELECT COUNT(*) FROM group_members x WHERE x.group_id = g.id) AS memberCount,
            (SELECT l.text FROM group_messages l WHERE l.group_id = g.id ORDER BY l.created_at DESC LIMIT 1) AS lastText,
            (SELECT l.type FROM group_messages l WHERE l.group_id = g.id ORDER BY l.created_at DESC LIMIT 1) AS lastType,
            (SELECT l.sender_id FROM group_messages l WHERE l.group_id = g.id ORDER BY l.created_at DESC LIMIT 1) AS lastSenderId,
            (SELECT l.created_at FROM group_messages l WHERE l.group_id = g.id ORDER BY l.created_at DESC LIMIT 1) AS lastAt
       FROM chat_groups g
       JOIN group_members gm ON gm.group_id = g.id AND gm.user_id = ?
      ORDER BY COALESCE(lastAt, g.created_at) DESC LIMIT 100`
  ).bind(me.id).all();
  return jsonRes({ groups: rows.results });
}

async function getGroup(groupId, me, env) {
  if (!(await isMember(env, groupId, me.id))) return failRes(404, "no_such_group", "group not found");
  const group = await env.DB.prepare("SELECT id, name, avatar, created_at AS createdAt FROM chat_groups WHERE id = ?")
    .bind(groupId).first();
  if (!group) return failRes(404, "no_such_group", "group not found");
  const members = await env.DB.prepare(
    `SELECT u.id, u.username, u.display_name AS displayName
       FROM group_members gm JOIN users u ON u.id = gm.user_id
      WHERE gm.group_id = ? ORDER BY u.username`
  ).bind(groupId).all();
  return jsonRes({ group: { ...group, members: members.results } });
}

async function addMembers(request, groupId, me, env) {
  if (!(await isMember(env, groupId, me.id))) return failRes(404, "no_such_group", "group not found");
  const { body, error } = await bodyOrError(request);
  if (error) return error;

  const wanted = cleanIds(body.userIds, me);
  if (wanted.length === 0) return failRes(400, "invalid_members", "userIds required");
  const exists = await existingUserIds(env, wanted);
  if (exists.length !== wanted.length) return failRes(404, "no_such_user", "a user does not exist");

  const current = await env.DB.prepare("SELECT COUNT(*) AS n FROM group_members WHERE group_id = ?")
    .bind(groupId).first();
  const alreadyIn = await env.DB.prepare(
    `SELECT user_id FROM group_members WHERE group_id = ? AND user_id IN (${wanted.map(() => "?").join(",")})`
  ).bind(groupId, ...wanted).all();
  const fresh = wanted.filter((x) => !alreadyIn.results.some((r) => r.user_id === x));
  if (current.n + fresh.length > SOCIAL_MAX_MEMBERS) {
    return failRes(400, "too_many_members", "group limit is 50 members");
  }
  const now = Date.now();
  if (fresh.length > 0) {
    await env.DB.batch(fresh.map((uid) =>
      env.DB.prepare("INSERT INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, ?)")
        .bind(groupId, uid, now)));
  }
  return jsonRes({ added: fresh.length });
}

async function leaveGroup(groupId, me, env) {
  if (!(await isMember(env, groupId, me.id))) return failRes(404, "no_such_group", "group not found");
  await env.DB.prepare("DELETE FROM group_members WHERE group_id = ? AND user_id = ?")
    .bind(groupId, me.id).run();
  return jsonRes({ ok: true });
}

async function sendGroupMessage(request, groupId, me, env) {
  if (!(await isMember(env, groupId, me.id))) return failRes(404, "no_such_group", "group not found");
  const { body, error } = await bodyOrError(request);
  if (error) return error;
  const parsed = await parseMessageBody(body, me, env);
  if (parsed.error) return parsed.error;

  const now = Date.now();
  const result = await env.DB.prepare(
    `INSERT OR IGNORE INTO group_messages (group_id, sender_id, client_id, type, media_id, text, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).bind(groupId, me.id, parsed.clientId, parsed.type, parsed.mediaId, parsed.text, now).run();
  const message = await env.DB.prepare(
    `SELECT sender_id AS senderId, client_id AS clientId, type, media_id AS mediaId, text, created_at AS createdAt
       FROM group_messages WHERE group_id = ? AND sender_id = ? AND client_id = ?`
  ).bind(groupId, me.id, parsed.clientId).first();
  const created = result.meta?.changes === 1;
  return jsonRes({ message, created }, created ? 201 : 200);
}

async function listGroupMessages(url, groupId, me, env) {
  if (!(await isMember(env, groupId, me.id))) return failRes(404, "no_such_group", "group not found");
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 50), 1), 100);
  const before = Number(url.searchParams.get("before") || Date.now() + 1);
  if (!Number.isFinite(before)) return failRes(400, "invalid_before", "'before' must be a timestamp");
  const rows = await env.DB.prepare(
    `SELECT sender_id AS senderId, client_id AS clientId, type, media_id AS mediaId, text, created_at AS createdAt
       FROM group_messages
      WHERE group_id = ? AND created_at < ?
      ORDER BY created_at DESC LIMIT ?`
  ).bind(groupId, before, limit).all();
  return jsonRes({ messages: rows.results });
}

// ---------------------------------------------------------------- statuses

async function createStatus(request, me, env) {
  const { body, error } = await bodyOrError(request);
  if (error) return error;
  const text = typeof body.text === "string" ? body.text.trim() : "";
  const bg = body.bg === undefined ? 0 : body.bg;
  if (text.length < 1 || text.length > SOCIAL_STATUS_TEXT_MAX) {
    return failRes(400, "invalid_text", `status: 1 to ${SOCIAL_STATUS_TEXT_MAX} characters`);
  }
  if (!Number.isInteger(bg) || bg < 0 || bg > 7) return failRes(400, "invalid_bg", "bg must be 0 to 7");

  const id = crypto.randomUUID();
  const now = Date.now();
  await env.DB.prepare(
    "INSERT INTO statuses (id, user_id, text, bg, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)"
  ).bind(id, me.id, text, bg, now, now + SOCIAL_STATUS_TTL_MS).run();
  return jsonRes({ status: { id, userId: me.id, text, bg, createdAt: now, expiresAt: now + SOCIAL_STATUS_TTL_MS } }, 201);
}

async function listStatuses(me, env) {
  const rows = await env.DB.prepare(
    `SELECT s.id, s.user_id AS userId, u.username, u.display_name AS displayName, u.avatar,
            s.text, s.bg, s.created_at AS createdAt, s.expires_at AS expiresAt
       FROM statuses s JOIN users u ON u.id = s.user_id
      WHERE s.expires_at > ? AND (
            s.user_id = ?
         OR s.user_id IN (SELECT recipient_id FROM messages WHERE sender_id = ?)
         OR s.user_id IN (SELECT sender_id FROM messages WHERE recipient_id = ?)
         OR s.user_id IN (SELECT gm2.user_id FROM group_members gm1
                            JOIN group_members gm2 ON gm1.group_id = gm2.group_id
                           WHERE gm1.user_id = ?))
      ORDER BY s.created_at DESC LIMIT 200`
  ).bind(Date.now(), me.id, me.id, me.id, me.id).all();
  return jsonRes({ statuses: rows.results });
}

async function deleteStatus(statusId, me, env) {
  const result = await env.DB.prepare("DELETE FROM statuses WHERE id = ? AND user_id = ?")
    .bind(statusId, me.id).run();
  if (result.meta?.changes !== 1) return failRes(404, "not_found", "status not found");
  return jsonRes({ ok: true });
}

// ---------------------------------------------------------------- router

/** Returns a Response for the social routes, or null if the path is not one of them. */
export async function handleSocial(request, url, me, env) {
  const p = url.pathname;
  const m = request.method;

  if (p === "/api/groups") {
    if (m === "POST") return createGroup(request, me, env);
    if (m === "GET") return listGroups(me, env);
    return null;
  }

  const g = /^\/api\/groups\/([A-Za-z0-9-]{1,64})(?:\/(members|leave|messages))?$/.exec(p);
  if (g) {
    const groupId = g[1];
    const action = g[2];
    if (!action && m === "GET") return getGroup(groupId, me, env);
    if (action === "members" && m === "POST") return addMembers(request, groupId, me, env);
    if (action === "leave" && m === "POST") return leaveGroup(groupId, me, env);
    if (action === "messages" && m === "POST") return sendGroupMessage(request, groupId, me, env);
    if (action === "messages" && m === "GET") return listGroupMessages(url, groupId, me, env);
    return null;
  }

  if (p === "/api/statuses") {
    if (m === "POST") return createStatus(request, me, env);
    if (m === "GET") return listStatuses(me, env);
    return null;
  }

  const s = /^\/api\/statuses\/([A-Za-z0-9-]{1,64})$/.exec(p);
  if (s && m === "DELETE") return deleteStatus(s[1], me, env);
  return null;
}
