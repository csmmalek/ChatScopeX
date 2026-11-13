// Images and voice notes (stored in R2) and the shared validation of message bodies.
import { json, fail, CLIENT_ID_RE } from "./http.js";

const MAX_MEDIA_BYTES = 5 * 1024 * 1024;
const ALLOWED_TYPES = new Set([
  "image/jpeg", "image/png", "image/webp",
  "audio/mp4", "audio/aac", "audio/mpeg", "audio/ogg", "audio/webm", "audio/3gpp",
]);
const TEXT_MAX = 4000;
const CAPTION_MAX = 1000;

/** Validates a message body for direct and group chats. Returns { error } or the clean fields. */
export async function parseMessageBody(body, me, env) {
  const clientId = String(body.clientId ?? "");
  const type = body.type === undefined ? "text" : String(body.type);
  if (!["text", "image", "audio"].includes(type)) {
    return { error: fail(400, "invalid_type", "type must be text, image or audio") };
  }
  const text = typeof body.text === "string" ? body.text.trim() : "";

  if (type === "text") {
    if (text.length < 1 || text.length > TEXT_MAX) {
      return { error: fail(400, "invalid_text", `message: 1 to ${TEXT_MAX} characters`) };
    }
    if (!CLIENT_ID_RE.test(clientId)) return { error: fail(400, "invalid_client_id", "clientId required") };
    return { clientId, type, text, mediaId: null };
  }

  if (text.length > CAPTION_MAX) return { error: fail(400, "invalid_text", `caption: up to ${CAPTION_MAX} characters`) };
  if (!CLIENT_ID_RE.test(clientId)) return { error: fail(400, "invalid_client_id", "clientId required") };
  const mediaId = String(body.mediaId ?? "");
  const row = await env.DB.prepare(
    "SELECT owner_id AS ownerId, content_type AS contentType FROM media WHERE id = ?"
  ).bind(mediaId).first();
  if (!row || row.ownerId !== me.id) return { error: fail(404, "no_such_media", "upload the file first") };
  if (!row.contentType.startsWith(type + "/")) return { error: fail(400, "invalid_media", "file type does not match") };
  return { clientId, type, text, mediaId };
}

async function upload(request, me, env) {
  if (!env.MEDIA) return fail(501, "media_disabled", "media storage is not enabled on this server");
  const contentType = (request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
  if (!ALLOWED_TYPES.has(contentType)) return fail(415, "unsupported_type", "file type not allowed");
  const declared = Number(request.headers.get("content-length") || 0);
  if (declared > MAX_MEDIA_BYTES) return fail(413, "too_large", "file too large (5 MB max)");
  const data = await request.arrayBuffer();
  if (data.byteLength === 0) return fail(400, "empty", "empty file");
  if (data.byteLength > MAX_MEDIA_BYTES) return fail(413, "too_large", "file too large (5 MB max)");

  const id = crypto.randomUUID();
  await env.MEDIA.put(`m/${id}`, data, { httpMetadata: { contentType } });
  await env.DB.prepare(
    "INSERT INTO media (id, owner_id, content_type, size, created_at) VALUES (?, ?, ?, ?, ?)"
  ).bind(id, me.id, contentType, data.byteLength, Date.now()).run();
  return json({ media: { id, contentType, size: data.byteLength } }, 201);
}

/** A file is visible to its uploader, to the other side of a direct message that uses it, and to group members. */
async function download(id, me, env) {
  if (!env.MEDIA) return fail(501, "media_disabled", "media storage is not enabled on this server");
  const row = await env.DB.prepare(
    `SELECT m.content_type AS contentType FROM media m
      WHERE m.id = ? AND (
            m.owner_id = ?
         OR EXISTS (SELECT 1 FROM messages x WHERE x.media_id = m.id AND (x.sender_id = ? OR x.recipient_id = ?))
         OR EXISTS (SELECT 1 FROM group_messages g
                      JOIN group_members gm ON gm.group_id = g.group_id AND gm.user_id = ?
                     WHERE g.media_id = m.id))`
  ).bind(id, me.id, me.id, me.id, me.id).first();
  if (!row) return fail(404, "not_found", "file not found");
  const object = await env.MEDIA.get(`m/${id}`);
  if (!object) return fail(404, "not_found", "file not found");
  return new Response(object.body, {
    headers: {
      "content-type": row.contentType,
      "cache-control": "private, max-age=86400",
      "x-content-type-options": "nosniff",
    },
  });
}

export async function handleMedia(request, url, me, env) {
  if (url.pathname === "/api/media" && request.method === "POST") return upload(request, me, env);
  const m = /^\/api\/media\/([A-Za-z0-9-]{1,64})$/.exec(url.pathname);
  if (m && request.method === "GET") return download(m[1], me, env);
  return null;
}
