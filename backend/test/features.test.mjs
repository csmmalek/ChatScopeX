import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/index.js";

class D1Adapter {
  constructor(db) { this.db = db; }
  prepare(sql) {
    const db = this.db;
    return { bind(...a) { return {
      first() { return db.prepare(sql).get(...a) ?? null; },
      all() { return { results: db.prepare(sql).all(...a) }; },
      run() { const r = db.prepare(sql).run(...a); return { success: true, meta: { changes: Number(r.changes) } }; },
    }; } };
  }
  async batch(stmts) {
    this.db.exec("BEGIN");
    try { const out = []; for (const s of stmts) out.push(await s.run()); this.db.exec("COMMIT"); return out; }
    catch (e) { this.db.exec("ROLLBACK"); throw e; }
  }
}

// Minimal R2 stand-in
class FakeBucket {
  constructor() { this.map = new Map(); }
  async put(key, data) { this.map.set(key, data); }
  async get(key) { const d = this.map.get(key); return d ? { body: d } : null; }
}

let env, sqlite, bucket;
beforeEach(() => {
  sqlite = new DatabaseSync(":memory:");
  const dir = new URL("../migrations/", import.meta.url);
  for (const f of readdirSync(dir).filter((n) => n.endsWith(".sql")).sort()) sqlite.exec(readFileSync(new URL(f, dir), "utf8"));
  bucket = new FakeBucket();
  env = { DB: new D1Adapter(sqlite), MEDIA: bucket };
});

async function call(method, path, { body, token, raw, headers = {} } = {}) {
  const h = { ...headers };
  if (body !== undefined && !raw) h["content-type"] = "application/json";
  if (token) h.authorization = `Bearer ${token}`;
  const res = await worker.fetch(new Request("https://api.test" + path, {
    method, headers: h, body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined),
  }), env);
  const type = res.headers.get("content-type") || "";
  return { status: res.status, data: type.includes("json") ? await res.json() : null, res };
}

async function person(name, extra = {}) {
  const r = await call("POST", "/api/signup", { body: { email: `${name}@example.com`, password: "password123", username: name, displayName: name.toUpperCase(), ...extra } });
  assert.equal(r.status, 201, JSON.stringify(r.data));
  return { token: r.data.token, id: r.data.user.id };
}
const dm = (from, to, text, clientId) => call("POST", "/api/messages", { token: from.token, body: { to: to.id, text, clientId } });

// ---------------------------------------------------------------- login throttle
test("login is blocked after 10 wrong passwords for one email, even with the right password", async () => {
  await person("alice1");
  for (let i = 0; i < 10; i++) {
    const r = await call("POST", "/api/login", { body: { email: "alice1@example.com", password: "wrong-password" } });
    assert.equal(r.status, 401);
  }
  const blocked = await call("POST", "/api/login", { body: { email: "alice1@example.com", password: "password123" } });
  assert.equal(blocked.status, 429);
  assert.equal(blocked.data.error.code, "too_many_attempts");
});

test("a successful login clears that email's failure count", async () => {
  await person("alice1");
  for (let i = 0; i < 5; i++) await call("POST", "/api/login", { body: { email: "alice1@example.com", password: "wrong-password" } });
  assert.equal((await call("POST", "/api/login", { body: { email: "alice1@example.com", password: "password123" } })).status, 200);
  assert.equal(sqlite.prepare("SELECT COUNT(*) AS n FROM login_failures WHERE key = 'e:alice1@example.com'").get().n, 0);
});

test("old failures stop counting after the window", async () => {
  await person("alice1");
  for (let i = 0; i < 10; i++) await call("POST", "/api/login", { body: { email: "alice1@example.com", password: "wrong-password" } });
  sqlite.exec("UPDATE login_failures SET at = at - 16 * 60 * 1000");
  assert.equal((await call("POST", "/api/login", { body: { email: "alice1@example.com", password: "password123" } })).status, 200);
});

// ---------------------------------------------------------------- profiles
test("signup stores a default picture; /api/me and /api/users/:id return it", async () => {
  const a = await person("alice1", { avatar: "a3" });
  const me = await call("GET", "/api/me", { token: a.token });
  assert.equal(me.data.user.avatar, "a3");
  const b = await person("bob123");
  const other = await call("GET", `/api/users/${a.id}`, { token: b.token });
  assert.equal(other.data.user.username, "alice1");
  assert.equal(other.data.user.avatar, "a3");
  assert.equal(other.data.user.email, undefined, "email is never exposed");
  assert.equal((await call("GET", "/api/users/nobody", { token: b.token })).status, 404);
});

test("signup rejects an unknown picture key", async () => {
  const r = await call("POST", "/api/signup", { body: { email: "x@example.com", password: "password123", username: "xxx", displayName: "X", avatar: "zz" } });
  assert.equal(r.data.error.code, "invalid_avatar");
});

test("PATCH /api/me changes the name and picture, with validation", async () => {
  const a = await person("alice1");
  const ok = await call("PATCH", "/api/me", { token: a.token, body: { displayName: "Alice B", avatar: "a5" } });
  assert.equal(ok.data.user.displayName, "Alice B");
  assert.equal((await call("GET", "/api/me", { token: a.token })).data.user.avatar, "a5");
  assert.equal((await call("PATCH", "/api/me", { token: a.token, body: { displayName: "" } })).data.error.code, "invalid_name");
  assert.equal((await call("PATCH", "/api/me", { token: a.token, body: { avatar: "a9" } })).data.error.code, "invalid_avatar");
});

test("search also finds people by display name", async () => {
  const a = await person("alice1");
  await call("POST", "/api/signup", { body: { email: "z@example.com", password: "password123", username: "zed99", displayName: "Maya" } });
  const r = await call("GET", "/api/users?q=may", { token: a.token });
  assert.deepEqual(r.data.users.map((u) => u.username), ["zed99"]);
});

// ---------------------------------------------------------------- chat list and read receipts
test("chat list: one row per conversation, newest first, with unread flag", async () => {
  const a = await person("alice1"), b = await person("bob123"), c = await person("carol1");
  await dm(a, b, "hi bob", "m1");
  await dm(c, a, "hi alice", "m2");
  const list = (await call("GET", "/api/chats", { token: a.token })).data.chats;
  assert.equal(list.length, 2);
  assert.equal(list[0].otherId, c.id);
  assert.equal(list[0].unread, true, "carol's message is unread for alice");
  assert.equal(list[1].otherId, b.id);
  assert.equal(list[1].unread, false, "my own last message is never unread");
});

test("marking a chat read clears unread and shows up as otherReadAt for the sender", async () => {
  const a = await person("alice1"), b = await person("bob123");
  await dm(a, b, "ping", "m1");
  assert.equal((await call("GET", "/api/chats", { token: b.token })).data.chats[0].unread, true);
  await new Promise((r) => setTimeout(r, 5));
  assert.equal((await call("POST", "/api/messages/read", { token: b.token, body: { with: a.id } })).status, 200);
  assert.equal((await call("GET", "/api/chats", { token: b.token })).data.chats[0].unread, false);
  const view = await call("GET", `/api/messages?with=${b.id}`, { token: a.token });
  assert.ok(view.data.otherReadAt >= view.data.messages[0].createdAt, "alice sees the double tick");
});

// ---------------------------------------------------------------- images and voice
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
const upload = (user, type, bytes = JPEG) => call("POST", "/api/media", { token: user.token, raw: bytes, headers: { "content-type": type } });

test("upload an image, send it, and the other side can download it", async () => {
  const a = await person("alice1"), b = await person("bob123");
  const up = await upload(a, "image/jpeg");
  assert.equal(up.status, 201);
  const sent = await call("POST", "/api/messages", { token: a.token, body: { to: b.id, type: "image", mediaId: up.data.media.id, clientId: "img1" } });
  assert.equal(sent.status, 201);
  assert.equal(sent.data.message.type, "image");
  const got = await call("GET", `/api/media/${up.data.media.id}`, { token: b.token });
  assert.equal(got.status, 200);
  assert.equal(got.res.headers.get("content-type"), "image/jpeg");
  assert.equal(got.res.headers.get("x-content-type-options"), "nosniff");
});

test("a stranger cannot download someone else's media", async () => {
  const a = await person("alice1"), b = await person("bob123"), c = await person("carol1");
  const up = await upload(a, "image/png");
  await call("POST", "/api/messages", { token: a.token, body: { to: b.id, type: "image", mediaId: up.data.media.id, clientId: "img1" } });
  assert.equal((await call("GET", `/api/media/${up.data.media.id}`, { token: c.token })).status, 404);
  assert.equal((await call("GET", `/api/media/${up.data.media.id}`, { token: b.token })).status, 200);
});

test("upload rejects bad types, empty files and files over 5 MB", async () => {
  const a = await person("alice1");
  assert.equal((await upload(a, "text/html")).status, 415);
  assert.equal((await upload(a, "image/jpeg", new Uint8Array(0))).status, 400);
  assert.equal((await upload(a, "image/jpeg", new Uint8Array(5 * 1024 * 1024 + 1))).status, 413);
});

test("you cannot send media you did not upload, or with the wrong type", async () => {
  const a = await person("alice1"), b = await person("bob123");
  const up = await upload(a, "image/jpeg");
  const stolen = await call("POST", "/api/messages", { token: b.token, body: { to: a.id, type: "image", mediaId: up.data.media.id, clientId: "x1" } });
  assert.equal(stolen.status, 404);
  const wrong = await call("POST", "/api/messages", { token: a.token, body: { to: b.id, type: "audio", mediaId: up.data.media.id, clientId: "x2" } });
  assert.equal(wrong.data.error.code, "invalid_media");
});

test("voice notes work in groups, visible to members only", async () => {
  const a = await person("alice1"), b = await person("bob123"), c = await person("carol1");
  const g = (await call("POST", "/api/groups", { token: a.token, body: { name: "G", memberIds: [b.id], avatar: "g2" } })).data.group;
  assert.equal(g.avatar, "g2");
  const up = await upload(a, "audio/mp4", new Uint8Array([1, 2, 3, 4]));
  const sent = await call("POST", `/api/groups/${g.id}/messages`, { token: a.token, body: { type: "audio", mediaId: up.data.media.id, clientId: "v1" } });
  assert.equal(sent.status, 201);
  assert.equal((await call("GET", `/api/media/${up.data.media.id}`, { token: b.token })).status, 200);
  assert.equal((await call("GET", `/api/media/${up.data.media.id}`, { token: c.token })).status, 404);
  const list = (await call("GET", "/api/groups", { token: b.token })).data.groups[0];
  assert.equal(list.lastType, "audio");
  assert.equal(list.avatar, "g2");
});

test("without an R2 bucket, media endpoints answer 501 instead of crashing", async () => {
  env.MEDIA = undefined;
  const a = await person("alice1");
  assert.equal((await upload(a, "image/jpeg")).status, 501);
});

// ---------------------------------------------------------------- calls
const SDP = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n";

test("call flow: ring, accept with an answer, exchange candidates, end", async () => {
  const a = await person("alice1"), b = await person("bob123");
  const made = await call("POST", "/api/calls", { token: a.token, body: { to: b.id, type: "video", sdp: SDP } });
  assert.equal(made.status, 201);
  const id = made.data.call.id;

  const inc = await call("GET", "/api/calls/incoming", { token: b.token });
  assert.deepEqual(inc.data.calls.map((c) => [c.id, c.callerId, c.type]), [[id, a.id, "video"]]);
  assert.equal((await call("GET", "/api/calls/incoming", { token: a.token })).data.calls.length, 0);

  const seen = await call("GET", `/api/calls/${id}`, { token: b.token });
  assert.equal(seen.data.call.offerSdp, SDP);
  assert.equal(seen.data.call.status, "ringing");

  assert.equal((await call("POST", `/api/calls/${id}/answer`, { token: b.token, body: { sdp: "answer-sdp" } })).status, 200);
  const after = await call("GET", `/api/calls/${id}`, { token: a.token });
  assert.equal(after.data.call.status, "active");
  assert.equal(after.data.call.answerSdp, "answer-sdp");
  assert.equal((await call("GET", "/api/calls/incoming", { token: b.token })).data.calls.length, 0);

  const cand = (n) => ({ candidate: `candidate:${n} 1 udp 1 1.2.3.4 5000 typ host`, sdpMid: "0", sdpMLineIndex: 0 });
  await call("POST", `/api/calls/${id}/candidates`, { token: a.token, body: cand(1) });
  await call("POST", `/api/calls/${id}/candidates`, { token: a.token, body: cand(2) });
  await call("POST", `/api/calls/${id}/candidates`, { token: b.token, body: cand(3) });
  const forB = (await call("GET", `/api/calls/${id}/candidates?after=0`, { token: b.token })).data.candidates;
  assert.equal(forB.length, 2, "b sees only a's candidates");
  const forBNext = (await call("GET", `/api/calls/${id}/candidates?after=${forB[1].seq}`, { token: b.token })).data.candidates;
  assert.equal(forBNext.length, 0, "'after' only returns newer ones");
  assert.equal((await call("GET", `/api/calls/${id}/candidates`, { token: a.token })).data.candidates.length, 1);

  assert.equal((await call("POST", `/api/calls/${id}/end`, { token: a.token })).status, 200);
  assert.equal((await call("GET", `/api/calls/${id}`, { token: b.token })).data.call.status, "ended");
  assert.equal((await call("POST", `/api/calls/${id}/candidates`, { token: b.token, body: cand(4) })).status, 409);
});

test("calls: decline, history, and who may do what", async () => {
  const a = await person("alice1"), b = await person("bob123"), c = await person("carol1");
  const id = (await call("POST", "/api/calls", { token: a.token, body: { to: b.id, type: "audio", sdp: SDP } })).data.call.id;
  assert.equal((await call("GET", `/api/calls/${id}`, { token: c.token })).status, 404, "outsider cannot see it");
  assert.equal((await call("POST", `/api/calls/${id}/answer`, { token: a.token, body: { sdp: "x" } })).status, 404, "caller cannot answer");
  assert.equal((await call("POST", `/api/calls/${id}/decline`, { token: b.token })).status, 200);
  assert.equal((await call("POST", `/api/calls/${id}/answer`, { token: b.token, body: { sdp: "x" } })).status, 409, "too late");
  const hist = (await call("GET", "/api/calls", { token: b.token })).data.calls;
  assert.equal(hist.length, 1);
  assert.equal(hist[0].status, "declined");
  assert.equal(hist[0].answered, false);
});

test("a call that was ringing too long no longer shows as incoming", async () => {
  const a = await person("alice1"), b = await person("bob123");
  await call("POST", "/api/calls", { token: a.token, body: { to: b.id, type: "audio", sdp: SDP } });
  sqlite.exec("UPDATE calls SET created_at = created_at - 120000");
  assert.equal((await call("GET", "/api/calls/incoming", { token: b.token })).data.calls.length, 0);
});

test("calls validate input: no self-calls, known users, call type", async () => {
  const a = await person("alice1"), b = await person("bob123");
  assert.equal((await call("POST", "/api/calls", { token: a.token, body: { to: a.id, type: "audio", sdp: SDP } })).status, 400);
  assert.equal((await call("POST", "/api/calls", { token: a.token, body: { to: "ghost", type: "audio", sdp: SDP } })).status, 404);
  assert.equal((await call("POST", "/api/calls", { token: a.token, body: { to: b.id, type: "hologram", sdp: SDP } })).status, 400);
});

// ---------------------------------------------------------------- TURN
test("/api/turn gives STUN only when TURN is not configured", async () => {
  const a = await person("alice1");
  const r = await call("GET", "/api/turn", { token: a.token });
  assert.equal(r.data.iceServers.length, 1);
  assert.ok(r.data.iceServers[0].urls[0].startsWith("stun:"));
});

test("/api/turn adds Cloudflare TURN credentials when configured, and falls back if the API fails", async () => {
  const a = await person("alice1");
  env.TURN_KEY_ID = "key123"; env.TURN_API_TOKEN = "tok";
  const realFetch = globalThis.fetch;
  try {
    let seen;
    globalThis.fetch = async (url, init) => {
      seen = { url, auth: init.headers.authorization };
      return new Response(JSON.stringify({ iceServers: { urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: "u", credential: "c" } }), { status: 200 });
    };
    const ok = await call("GET", "/api/turn", { token: a.token });
    assert.ok(seen.url.includes("/turn/keys/key123/credentials/generate"));
    assert.equal(seen.auth, "Bearer tok");
    assert.equal(ok.data.iceServers[0].username, "u");
    assert.equal(ok.data.iceServers.length, 2);

    globalThis.fetch = async () => new Response("nope", { status: 500 });
    assert.equal((await call("GET", "/api/turn", { token: a.token })).data.iceServers.length, 1);
    globalThis.fetch = async () => { throw new Error("offline"); };
    assert.equal((await call("GET", "/api/turn", { token: a.token })).data.iceServers.length, 1);
  } finally {
    globalThis.fetch = realFetch;
  }
});
