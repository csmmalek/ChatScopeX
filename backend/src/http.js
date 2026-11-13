// Shared HTTP helpers.
export const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

export const fail = (status, code, message) => json({ error: { code, message } }, status);

/** Reads a JSON object body. Returns { body } or { error: Response }. */
export async function readBody(request, max = 64 * 1024) {
  if (Number(request.headers.get("content-length") || 0) > max) {
    return { error: fail(413, "too_large", "request too large") };
  }
  let body;
  try { body = await request.json(); } catch { body = null; }
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return { error: fail(400, "bad_json", "invalid JSON body") };
  }
  return { body };
}

export const CLIENT_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
export const conversationKey = (a, b) => [a, b].sort().join(":");
