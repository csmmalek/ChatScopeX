// Tests for groups and statuses. By default they run against src/index.js.
// Set WORKER=/absolute/path/to/file.js to run the same tests against the single-file build.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

const workerPath = process.env.WORKER || new URL("../src/index.js", import.meta.url).href;
const worker = (await import(workerPath)).default;

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
  // D1 batches are atomic: all statements run, or none do.
  async batch(stmts) {
    this.db.exec("BEGIN");
    try {
      const out = [];
      for (const s of stmts) out.push(await s.run());
      this.db.exec("COMMIT");
      return out;
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
  }
}

const migrationsDir = new URL("../migrations/", import.meta.url);
let env, sqlite;

beforeEach(() => {
  sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys = ON;");
  for (const f of readdirSync(migrationsDir).filter((n) => n.endsWith(".sql")).sort()) {
    sqlite.exec(readFileSync(new URL(f, migrationsDir), "utf8"));
  }
  env = { DB: new D1Adapter(sqlite), INBOX: undefined };
});

async function call(method, path, { body, token } = {}) {
  const headers = {};
  if (body !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = `Bearer ${token}`;
  const req = new Request("https://api.test" + path, {
    method, headers, body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const res = await worker.fetch(req, env);
  return { status: res.status, data: await res.json() };
}

async function person(name) {
  const r = await call("POST", "/api/signup", {
    body: { email: `${name}@example.com`, password: "password123", username: name, displayName: name.toUpperCase() },
  });
  assert.equal(r.status, 201, JSON.stringify(r.data));
  return { token: r.data.token, id: r.data.user.id, name };
}

async function directChat(from, to, text = "hi") {
  const r = await call("POST", "/api/messages", { token: from.token, body: { to: to.id, text, clientId: "dm-" + text } });
  assert.ok(r.status === 201 || r.status === 200, JSON.stringify(r.data));
}

// ---------------------------------------------------------------- groups

test("create a group: the creator and the chosen members are in it", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  const r = await call("POST", "/api/groups", { token: a.token, body: { name: "Trip", memberIds: [b.id] } });
  assert.equal(r.status, 201, JSON.stringify(r.data));
  assert.equal(r.data.group.memberCount, 2);

  const mine = await call("GET", "/api/groups", { token: b.token });
  assert.deepEqual(mine.data.groups.map((g) => g.name), ["Trip"]);
});

test("create a group: needs at least one other member, and members must exist", async () => {
  const a = await person("alice1");
  const none = await call("POST", "/api/groups", { token: a.token, body: { name: "Solo", memberIds: [] } });
  assert.equal(none.status, 400);
  const ghost = await call("POST", "/api/groups", { token: a.token, body: { name: "G", memberIds: ["nobody"] } });
  assert.equal(ghost.status, 404);
});

test("group name is 1 to 50 characters", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  const r = await call("POST", "/api/groups", { token: a.token, body: { name: "x".repeat(51), memberIds: [b.id] } });
  assert.equal(r.data.error.code, "invalid_name");
});

test("outsiders cannot see a group (404, not 403)", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  const c = await person("carol1");
  const g = (await call("POST", "/api/groups", { token: a.token, body: { name: "G", memberIds: [b.id] } })).data.group;
  const r = await call("GET", `/api/groups/${g.id}`, { token: c.token });
  assert.equal(r.status, 404);
  const mineView = await call("GET", `/api/groups/${g.id}`, { token: b.token });
  assert.equal(mineView.status, 200);
  assert.equal(mineView.data.group.members.length, 2);
});

test("members can add people, but never more than 50 in total", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  const c = await person("carol1");
  const g = (await call("POST", "/api/groups", { token: a.token, body: { name: "G", memberIds: [b.id] } })).data.group;
  const add = await call("POST", `/api/groups/${g.id}/members`, { token: b.token, body: { userIds: [c.id] } });
  assert.equal(add.status, 200);
  assert.equal(add.data.added, 1);
  // adding the same person again adds nobody
  const again = await call("POST", `/api/groups/${g.id}/members`, { token: b.token, body: { userIds: [c.id] } });
  assert.equal(again.data.added, 0);
});

test("leaving removes access; the rest of the group keeps it", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  const c = await person("carol1");
  const g = (await call("POST", "/api/groups", { token: a.token, body: { name: "G", memberIds: [b.id, c.id] } })).data.group;
  assert.equal((await call("POST", `/api/groups/${g.id}/leave`, { token: b.token })).status, 200);
  assert.equal((await call("GET", `/api/groups/${g.id}`, { token: b.token })).status, 404);
  assert.equal((await call("GET", `/api/groups/${g.id}`, { token: c.token })).status, 200);
});

test("group messages: members read and send; outsiders cannot; retries do not duplicate", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  const c = await person("carol1");
  const g = (await call("POST", "/api/groups", { token: a.token, body: { name: "G", memberIds: [b.id] } })).data.group;

  const send = { text: "hello group", clientId: "g-1" };
  assert.equal((await call("POST", `/api/groups/${g.id}/messages`, { token: b.token, body: send })).status, 201);
  assert.equal((await call("POST", `/api/groups/${g.id}/messages`, { token: b.token, body: send })).status, 200);
  assert.equal((await call("POST", `/api/groups/${g.id}/messages`, { token: c.token, body: { text: "x", clientId: "g-2" } })).status, 404);

  const list = await call("GET", `/api/groups/${g.id}/messages`, { token: a.token });
  assert.deepEqual(list.data.messages.map((m) => m.text), ["hello group"]);
  assert.equal(list.data.messages[0].senderId, b.id);
  assert.equal((await call("GET", `/api/groups/${g.id}/messages`, { token: c.token })).status, 404);
});

// ---------------------------------------------------------------- statuses

test("a status is saved with a 24-hour expiry", async () => {
  const a = await person("alice1");
  const r = await call("POST", "/api/statuses", { token: a.token, body: { text: "busy", bg: 3 } });
  assert.equal(r.status, 201);
  assert.equal(r.data.status.expiresAt - r.data.status.createdAt, 24 * 60 * 60 * 1000);
});

test("status text and color are validated", async () => {
  const a = await person("alice1");
  assert.equal((await call("POST", "/api/statuses", { token: a.token, body: { text: "", bg: 0 } })).data.error.code, "invalid_text");
  assert.equal((await call("POST", "/api/statuses", { token: a.token, body: { text: "x".repeat(201) } })).data.error.code, "invalid_text");
  assert.equal((await call("POST", "/api/statuses", { token: a.token, body: { text: "ok", bg: 8 } })).data.error.code, "invalid_bg");
});

test("statuses are visible to chat partners and group mates, not strangers", async () => {
  const a = await person("alice1");
  const b = await person("bob123");   // chats with a
  const c = await person("carol1");   // shares a group with a
  const d = await person("dave123");  // stranger
  await directChat(a, b);
  await call("POST", "/api/groups", { token: a.token, body: { name: "G", memberIds: [c.id] } });

  await call("POST", "/api/statuses", { token: a.token, body: { text: "hello world", bg: 1 } });
  const bobSees = await call("GET", "/api/statuses", { token: b.token });
  const carolSees = await call("GET", "/api/statuses", { token: c.token });
  const daveSees = await call("GET", "/api/statuses", { token: d.token });
  assert.deepEqual(bobSees.data.statuses.map((s) => s.text), ["hello world"]);
  assert.deepEqual(carolSees.data.statuses.map((s) => s.text), ["hello world"]);
  assert.equal(daveSees.data.statuses.length, 0);
});

test("expired statuses disappear", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  await directChat(a, b);
  await call("POST", "/api/statuses", { token: a.token, body: { text: "old", bg: 0 } });
  sqlite.exec("UPDATE statuses SET expires_at = 0");
  assert.equal((await call("GET", "/api/statuses", { token: b.token })).data.statuses.length, 0);
});

test("only the owner can delete a status", async () => {
  const a = await person("alice1");
  const b = await person("bob123");
  await directChat(a, b);
  const s = (await call("POST", "/api/statuses", { token: a.token, body: { text: "mine", bg: 0 } })).data.status;
  assert.equal((await call("DELETE", `/api/statuses/${s.id}`, { token: b.token })).status, 404);
  assert.equal((await call("DELETE", `/api/statuses/${s.id}`, { token: a.token })).status, 200);
  assert.equal((await call("GET", "/api/statuses", { token: b.token })).data.statuses.length, 0);
});

test("social routes require a signed-in user", async () => {
  assert.equal((await call("GET", "/api/groups")).status, 401);
  assert.equal((await call("GET", "/api/statuses")).status, 401);
});
