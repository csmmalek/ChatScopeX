import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { Inbox } from "../src/index.js";

// D1 adapter over node:sqlite, same surface the Worker uses (prepare/bind/first/all/run).
class D1Adapter {
  constructor(db) { this.db = db; }
  prepare(sql) {
    const db = this.db;
    return {
      bind(...args) {
        return {
          first() { return db.prepare(sql).get(...args) ?? null; },
          all() { return { results: db.prepare(sql).all(...args) }; },
          run() { const r = db.prepare(sql).run(...args); return { success: true, meta: { changes: Number(r.changes) } }; },
        };
      },
    };
  }
}

let env, sqlite, pushes;

beforeEach(() => {
  sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys = ON;");
  const dir = new URL("../migrations/", import.meta.url);
  for (const f of readdirSync(dir).filter((n) => n.endsWith(".sql")).sort()) {
    sqlite.exec(readFileSync(new URL(f, dir), "utf8"));
  }
  pushes = [];
  env = {
    DB: new D1Adapter(sqlite),
    INBOX: {
      idFromName: (name) => name,
      get: (id) => ({
        fetch: async (_url, init) => {
          pushes.push({ to: id, body: JSON.parse(init.body) });
          return new Response("ok");
        },
      }),
    },
  };
});

async function call(method, path, { body, token } = {}) {
  const headers = {};
  if (body !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = `Bearer ${token}`;
  const req = new Request("https://api.test" + path, {
    method, headers, body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const res = await worker.fetch(req, env);
  const data = await res.json();
  return { status: res.status, data };
}

const alice = { email: "alice@example.com", password: "correct horse", username: "alice", displayName: "Alice" };
const bob = { email: "bob@example.com", password: "battery staple", username: "bob", displayName: "Bob" };
const carol = { email: "carol@example.com", password: "tr0ub4dor&3", username: "carol", displayName: "Carol" };

async function signedUp(person) {
  const r = await call("POST", "/api/signup", { body: person });
  assert.equal(r.status, 201, JSON.stringify(r.data));
  return { token: r.data.token, id: r.data.user.id };
}

// ---------------------------------------------------------------- accounts
test("signup returns a token and the user", async () => {
  const r = await call("POST", "/api/signup", { body: alice });
  assert.equal(r.status, 201);
  assert.ok(r.data.token.length >= 43);
  assert.equal(r.data.user.username, "alice");
  assert.equal(r.data.user.displayName, "Alice");
});

test("signup rejects bad input with a specific code", async () => {
  assert.equal((await call("POST", "/api/signup", { body: { ...alice, email: "not-an-email" } })).data.error.code, "invalid_email");
  assert.equal((await call("POST", "/api/signup", { body: { ...alice, password: "short" } })).data.error.code, "invalid_password");
  assert.equal((await call("POST", "/api/signup", { body: { ...alice, username: "A B" } })).data.error.code, "invalid_username");
  assert.equal((await call("POST", "/api/signup", { body: { ...alice, displayName: "" } })).data.error.code, "invalid_name");
});

test("signup: duplicate email or username is 409 with one generic message", async () => {
  await signedUp(alice);
  const sameEmail = await call("POST", "/api/signup", { body: { ...bob, email: alice.email } });
  const sameName = await call("POST", "/api/signup", { body: { ...bob, username: alice.username } });
  assert.equal(sameEmail.status, 409);
  assert.equal(sameName.status, 409);
  assert.equal(sameEmail.data.error.message, sameName.data.error.message);
});

test("password is stored hashed, never in plain text", async () => {
  await signedUp(alice);
  const row = sqlite.prepare("SELECT password_hash FROM users WHERE username = 'alice'").get();
  assert.ok(row.password_hash.startsWith("pbkdf2_sha256$"));
  assert.ok(!row.password_hash.includes(alice.password));
});

test("login works with the right password and fails with the wrong one", async () => {
  await signedUp(alice);
  const ok = await call("POST", "/api/login", { body: { email: alice.email.toUpperCase(), password: alice.password } });
  assert.equal(ok.status, 200);
  const wrong = await call("POST", "/api/login", { body: { email: alice.email, password: "nope nope" } });
  const unknown = await call("POST", "/api/login", { body: { email: "ghost@example.com", password: "nope nope" } });
  assert.equal(wrong.status, 401);
  assert.equal(unknown.status, 401);
  assert.equal(wrong.data.error.message, unknown.data.error.message);
});

test("session tokens are stored only as hashes", async () => {
  const { token } = await signedUp(alice);
  const rows = sqlite.prepare("SELECT token_hash FROM sessions").all();
  assert.equal(rows.length, 1);
  assert.notEqual(rows[0].token_hash, token);
});

test("/api/me needs a valid token", async () => {
  const { token } = await signedUp(alice);
  assert.equal((await call("GET", "/api/me")).status, 401);
  assert.equal((await call("GET", "/api/me", { token: "x".repeat(43) })).status, 401);
  const me = await call("GET", "/api/me", { token });
  assert.equal(me.status, 200);
  assert.equal(me.data.user.username, "alice");
});

test("logout revokes the token", async () => {
  const { token } = await signedUp(alice);
  assert.equal((await call("POST", "/api/logout", { token })).status, 200);
  assert.equal((await call("GET", "/api/me", { token })).status, 401);
});

test("expired sessions are rejected", async () => {
  const { token } = await signedUp(alice);
  sqlite.exec("UPDATE sessions SET expires_at = 0");
  assert.equal((await call("GET", "/api/me", { token })).status, 401);
});

// ---------------------------------------------------------------- search
test("user search matches by username prefix and skips yourself", async () => {
  const a = await signedUp(alice);
  await signedUp(bob);
  await signedUp(carol);
  const r = await call("GET", "/api/users?q=b", { token: a.token });
  assert.equal(r.status, 400, "one character is too short");
  const r2 = await call("GET", "/api/users?q=bo", { token: a.token });
  assert.deepEqual(r2.data.users.map((u) => u.username), ["bob"]);
  const r3 = await call("GET", "/api/users?q=alice", { token: a.token });
  assert.equal(r3.data.users.length, 0, "does not return yourself");
});

test("user search treats % and _ literally", async () => {
  const a = await signedUp(alice);
  await signedUp(bob);
  const r = await call("GET", "/api/users?q=%25%25", { token: a.token });
  assert.equal(r.data.users.length, 0);
});

// ---------------------------------------------------------------- messages
test("a message is saved, pushed to both sides, and listed for both", async () => {
  const a = await signedUp(alice);
  const b = await signedUp(bob);
  const send = await call("POST", "/api/messages", {
    token: a.token, body: { to: b.id, text: "  hello bob  ", clientId: "m-1" },
  });
  assert.equal(send.status, 201);
  assert.equal(send.data.message.text, "hello bob");
  assert.equal(pushes.length, 2);
  assert.deepEqual(pushes.map((p) => p.to).sort(), [a.id, b.id].sort());
  assert.equal(pushes[0].body.type, "message");

  const bobView = await call("GET", `/api/messages?with=${a.id}`, { token: b.token });
  assert.equal(bobView.data.messages.length, 1);
  assert.equal(bobView.data.messages[0].senderId, a.id);
  const aliceView = await call("GET", `/api/messages?with=${b.id}`, { token: a.token });
  assert.equal(aliceView.data.messages.length, 1);
});

test("retrying the same clientId does not duplicate the message or push again", async () => {
  const a = await signedUp(alice);
  const b = await signedUp(bob);
  const body = { to: b.id, text: "once", clientId: "same-id" };
  const first = await call("POST", "/api/messages", { token: a.token, body });
  const again = await call("POST", "/api/messages", { token: a.token, body });
  assert.equal(first.status, 201);
  assert.equal(again.status, 200);
  assert.equal(again.data.created, false);
  assert.equal(pushes.length, 2, "only the first send pushes");
  const list = await call("GET", `/api/messages?with=${b.id}`, { token: a.token });
  assert.equal(list.data.messages.length, 1);
});

test("the same clientId from different senders is kept separately", async () => {
  const a = await signedUp(alice);
  const b = await signedUp(bob);
  const c = await signedUp(carol);
  await call("POST", "/api/messages", { token: a.token, body: { to: c.id, text: "x", clientId: "k" } });
  const r = await call("POST", "/api/messages", { token: b.token, body: { to: c.id, text: "y", clientId: "k" } });
  assert.equal(r.status, 201);
});

test("sending to yourself, to nobody, or with empty text is rejected", async () => {
  const a = await signedUp(alice);
  const b = await signedUp(bob);
  assert.equal((await call("POST", "/api/messages", { token: a.token, body: { to: a.id, text: "x", clientId: "c" } })).data.error.code, "self_message");
  assert.equal((await call("POST", "/api/messages", { token: a.token, body: { to: "nobody", text: "x", clientId: "c" } })).status, 404);
  assert.equal((await call("POST", "/api/messages", { token: a.token, body: { to: b.id, text: "   ", clientId: "c" } })).data.error.code, "invalid_text");
  assert.equal((await call("POST", "/api/messages", { token: a.token, body: { to: b.id, text: "x".repeat(4001), clientId: "c" } })).data.error.code, "invalid_text");
});

test("a third person does not see someone else's conversation", async () => {
  const a = await signedUp(alice);
  const b = await signedUp(bob);
  const c = await signedUp(carol);
  await call("POST", "/api/messages", { token: a.token, body: { to: b.id, text: "private", clientId: "p" } });
  const r = await call("GET", `/api/messages?with=${b.id}`, { token: c.token });
  assert.equal(r.data.messages.length, 0);
});

test("messages can be paged with 'before'", async () => {
  const a = await signedUp(alice);
  const b = await signedUp(bob);
  for (let i = 0; i < 5; i++) {
    sqlite.prepare("INSERT INTO messages (sender_id, client_id, recipient_id, conv, text, created_at) VALUES (?,?,?,?,?,?)")
      .run(a.id, `c${i}`, b.id, [a.id, b.id].sort().join(":"), `m${i}`, 1000 + i);
  }
  const page1 = await call("GET", `/api/messages?with=${b.id}&limit=2`, { token: a.token });
  assert.deepEqual(page1.data.messages.map((m) => m.text), ["m4", "m3"]);
  const page2 = await call("GET", `/api/messages?with=${b.id}&limit=2&before=${page1.data.messages[1].createdAt}`, { token: a.token });
  assert.deepEqual(page2.data.messages.map((m) => m.text), ["m2", "m1"]);
});

// ---------------------------------------------------------------- live inbox
test("inbox forwards a push to every open socket", async () => {
  const sent = [];
  const sockets = [{ send: (m) => sent.push(m) }, { send: (m) => sent.push(m) }];
  const state = { getWebSockets: () => sockets, acceptWebSocket() {} };
  const box = new Inbox(state, {});
  const res = await box.fetch(new Request("https://inbox/push", { method: "POST", body: '{"type":"message"}' }));
  assert.equal(res.status, 200);
  assert.equal(sent.length, 2);
});

test("websocket route refuses a plain GET", async () => {
  const { token } = await signedUp(alice);
  const r = await call("GET", "/api/ws", { token });
  assert.equal(r.status, 426);
});

test("unknown routes and oversized bodies are handled", async () => {
  assert.equal((await call("GET", "/nope")).status, 404);
  const big = new Request("https://api.test/api/signup", {
    method: "POST",
    headers: { "content-type": "application/json", "content-length": "999999" },
    body: "{}",
  });
  const res = await worker.fetch(big, env);
  assert.equal(res.status, 413);
});
