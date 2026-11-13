// One Durable Object per user. Holds the user's open WebSocket connections
// (from any device) and forwards pushed events to all of them.
export class Inbox {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/connect") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("expected websocket", { status: 426 });
      }
      const [client, server] = Object.values(new WebSocketPair());
      this.state.acceptWebSocket(server); // hibernation API: no billing while idle
      return new Response(null, { status: 101, webSocket: client });
    }

    if (url.pathname === "/push" && request.method === "POST") {
      const body = await request.text();
      for (const ws of this.state.getWebSockets()) {
        try { ws.send(body); } catch { /* socket already closed; the client resyncs on reconnect */ }
      }
      return new Response("ok");
    }

    return new Response("not found", { status: 404 });
  }

  // Clients only receive; nothing to do on incoming frames.
  webSocketMessage() {}
  webSocketClose(ws) { try { ws.close(); } catch {} }
  webSocketError(ws) { try { ws.close(); } catch {} }
}
