// Voice/video call signaling through D1 (the clients poll), plus ICE/TURN server list.
import { json, fail, readBody } from "./http.js";

const SDP_MAX = 100000;
const RING_MS = 60 * 1000;
const MAX_CANDIDATES = 300;

/** STUN always; Cloudflare TURN credentials too when TURN_KEY_ID and TURN_API_TOKEN are set as secrets. */
export async function iceServers(env) {
  const stun = { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] };
  if (!env.TURN_KEY_ID || !env.TURN_API_TOKEN) return [stun];
  try {
    const res = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${env.TURN_API_TOKEN}`, "content-type": "application/json" },
        body: JSON.stringify({ ttl: 3600 }),
      }
    );
    if (!res.ok) return [stun];
    const data = await res.json();
    const s = data.iceServers;
    const list = Array.isArray(s) ? s : s ? [s] : [];
    return list.length ? [...list, stun] : [stun];
  } catch {
    return [stun];
  }
}

async function loadCall(env, id, me) {
  return env.DB.prepare(
    `SELECT id, caller_id AS callerId, callee_id AS calleeId, type, status,
            offer, answer, created_at AS createdAt
       FROM calls WHERE id = ? AND (caller_id = ? OR callee_id = ?)`
  ).bind(id, me.id, me.id).first();
}

async function createCall(request, me, env) {
  const { body, error } = await readBody(request, 200 * 1024);
  if (error) return error;
  const to = String(body.to ?? "");
  const type = String(body.type ?? "");
  const sdp = typeof body.sdp === "string" ? body.sdp : "";
  if (!to || to === me.id) return fail(400, "invalid_recipient", "choose someone else to call");
  if (type !== "audio" && type !== "video") return fail(400, "invalid_type", "type must be audio or video");
  if (sdp.length < 1 || sdp.length > SDP_MAX) return fail(400, "invalid_sdp", "offer missing or too large");
  const callee = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(to).first();
  if (!callee) return fail(404, "no_such_user", "user not found");

  const id = crypto.randomUUID();
  await env.DB.prepare(
    "INSERT INTO calls (id, caller_id, callee_id, type, status, offer, created_at) VALUES (?, ?, ?, ?, 'ringing', ?, ?)"
  ).bind(id, me.id, to, type, sdp, Date.now()).run();
  return json({ call: { id } }, 201);
}

async function listCalls(me, env) {
  const rows = await env.DB.prepare(
    `SELECT id, caller_id AS callerId, callee_id AS calleeId, type, status,
            (answer IS NOT NULL) AS answered, created_at AS createdAt
       FROM calls WHERE caller_id = ? OR callee_id = ?
      ORDER BY created_at DESC LIMIT 50`
  ).bind(me.id, me.id).all();
  return json({ calls: rows.results.map((r) => ({ ...r, answered: Boolean(r.answered) })) });
}

async function incoming(me, env) {
  const rows = await env.DB.prepare(
    `SELECT id, caller_id AS callerId, type, created_at AS createdAt FROM calls
      WHERE callee_id = ? AND status = 'ringing' AND created_at > ?
      ORDER BY created_at DESC LIMIT 5`
  ).bind(me.id, Date.now() - RING_MS).all();
  return json({ calls: rows.results });
}

async function getCall(id, me, env) {
  const c = await loadCall(env, id, me);
  if (!c) return fail(404, "no_such_call", "call not found");
  return json({
    call: {
      id: c.id, callerId: c.callerId, calleeId: c.calleeId, type: c.type, status: c.status,
      offerSdp: c.offer, answerSdp: c.answer, createdAt: c.createdAt,
    },
  });
}

async function answer(request, id, me, env) {
  const { body, error } = await readBody(request, 200 * 1024);
  if (error) return error;
  const sdp = typeof body.sdp === "string" ? body.sdp : "";
  if (sdp.length < 1 || sdp.length > SDP_MAX) return fail(400, "invalid_sdp", "answer missing or too large");
  const r = await env.DB.prepare(
    "UPDATE calls SET status = 'active', answer = ?, answered_at = ? WHERE id = ? AND callee_id = ? AND status = 'ringing'"
  ).bind(sdp, Date.now(), id, me.id).run();
  if (r.meta?.changes === 1) return json({ ok: true });
  const c = await loadCall(env, id, me);
  if (!c || c.calleeId !== me.id) return fail(404, "no_such_call", "call not found");
  return fail(409, "not_ringing", "this call is no longer ringing");
}

async function decline(id, me, env) {
  const r = await env.DB.prepare(
    "UPDATE calls SET status = 'declined' WHERE id = ? AND callee_id = ? AND status = 'ringing'"
  ).bind(id, me.id).run();
  if (r.meta?.changes === 1) return json({ ok: true });
  const c = await loadCall(env, id, me);
  if (!c || c.calleeId !== me.id) return fail(404, "no_such_call", "call not found");
  return json({ ok: true }); // already answered or ended: nothing to do
}

async function end(id, me, env) {
  const c = await loadCall(env, id, me);
  if (!c) return fail(404, "no_such_call", "call not found");
  await env.DB.prepare(
    "UPDATE calls SET status = 'ended' WHERE id = ? AND status IN ('ringing', 'active')"
  ).bind(id).run();
  return json({ ok: true });
}

async function addCandidate(request, id, me, env) {
  const { body, error } = await readBody(request);
  if (error) return error;
  const candidate = typeof body.candidate === "string" ? body.candidate : "";
  if (candidate.length < 1 || candidate.length > 2000) return fail(400, "invalid_candidate", "candidate invalid");
  const mid = body.sdpMid == null ? null : String(body.sdpMid).slice(0, 64);
  const line = Number.isInteger(body.sdpMLineIndex) ? body.sdpMLineIndex : null;

  const c = await loadCall(env, id, me);
  if (!c) return fail(404, "no_such_call", "call not found");
  if (c.status !== "ringing" && c.status !== "active") return fail(409, "call_over", "call is over");
  const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM call_candidates WHERE call_id = ? AND sender_id = ?")
    .bind(id, me.id).first();
  if (n.n >= MAX_CANDIDATES) return fail(429, "too_many", "too many candidates");
  await env.DB.prepare(
    "INSERT INTO call_candidates (call_id, sender_id, candidate, sdp_mid, sdp_mline) VALUES (?, ?, ?, ?, ?)"
  ).bind(id, me.id, candidate, mid, line).run();
  return json({ ok: true }, 201);
}

async function listCandidates(url, id, me, env) {
  const c = await loadCall(env, id, me);
  if (!c) return fail(404, "no_such_call", "call not found");
  const after = Number(url.searchParams.get("after") || 0);
  const rows = await env.DB.prepare(
    `SELECT seq, candidate, sdp_mid AS sdpMid, sdp_mline AS sdpMLineIndex FROM call_candidates
      WHERE call_id = ? AND sender_id != ? AND seq > ? ORDER BY seq LIMIT 100`
  ).bind(id, me.id, Number.isFinite(after) ? after : 0).all();
  return json({ candidates: rows.results });
}

export async function handleCalls(request, url, me, env) {
  const p = url.pathname;
  const m = request.method;
  if (p === "/api/turn" && m === "GET") return json({ iceServers: await iceServers(env) });
  if (p === "/api/calls") {
    if (m === "POST") return createCall(request, me, env);
    if (m === "GET") return listCalls(me, env);
    return null;
  }
  if (p === "/api/calls/incoming" && m === "GET") return incoming(me, env);

  const r = /^\/api\/calls\/([A-Za-z0-9-]{1,64})(?:\/(answer|decline|end|candidates))?$/.exec(p);
  if (!r) return null;
  const id = r[1];
  const action = r[2];
  if (!action && m === "GET") return getCall(id, me, env);
  if (action === "answer" && m === "POST") return answer(request, id, me, env);
  if (action === "decline" && m === "POST") return decline(id, me, env);
  if (action === "end" && m === "POST") return end(id, me, env);
  if (action === "candidates" && m === "POST") return addCandidate(request, id, me, env);
  if (action === "candidates" && m === "GET") return listCandidates(url, id, me, env);
  return null;
}
