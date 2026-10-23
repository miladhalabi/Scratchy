// Scratchy sync relay. One Durable Object per account holds the encrypted notes blob and
// pushes every change to the other connected devices. It only ever sees ciphertext.
//
// The account id is the hex SHA-256 of a secret the app derives from the vault password.
// Knowing the id is what lets a device in; nothing here can be decrypted without the password.

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const m = url.pathname.match(/^\/v1\/([0-9a-f]{64})\/(ws|blob|relay)$/);
    if (!m) return new Response("scratchy-sync", { status: 404 });
    const stub = env.VAULT.get(env.VAULT.idFromName(m[1]));
    return stub.fetch(request);
  },
};

const MAX_BYTES = 8 * 1024 * 1024;

export class Vault {
  constructor(state, env) {
    this.state = state;
    this.sql = state.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS blobs (name TEXT PRIMARY KEY, data BLOB, rev INTEGER)");
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/ws")) {
      if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
      const pair = new WebSocketPair();
      this.state.acceptWebSocket(pair[1]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    // /relay: pass an encrypted note change straight to the other connected devices. Nothing is stored.
    if (url.pathname.endsWith("/relay")) {
      if (request.method !== "POST") return new Response("method not allowed", { status: 405 });
      const data = await request.text();
      if (data.length > 350_000) return new Response("too large", { status: 413 });
      const msg = JSON.stringify({ type: "note", from: request.headers.get("x-device") || "", data });
      let n = 0;
      for (const ws of this.state.getWebSockets()) { try { ws.send(msg); n++; } catch (_) {} }
      return new Response(JSON.stringify({ delivered: n }), { headers: { "content-type": "application/json" } });
    }

    // /blob?name=notes.enc  (GET reads, PUT writes)
    const name = url.searchParams.get("name");
    if (!name || !/^[\w.\-]{1,200}$/.test(name)) return new Response("bad name", { status: 400 });
    if (request.method === "GET") {
      const row = this.sql.exec("SELECT data, rev FROM blobs WHERE name = ?", name).toArray()[0];
      if (!row) return new Response("not found", { status: 404 });
      return new Response(row.data, { headers: { "x-rev": String(row.rev), "content-type": "application/octet-stream" } });
    }
    if (request.method === "PUT") {
      const data = await request.arrayBuffer();
      if (data.byteLength > MAX_BYTES) return new Response("too large", { status: 413 });
      const rev = Date.now();
      this.sql.exec("INSERT INTO blobs (name, data, rev) VALUES (?, ?, ?) ON CONFLICT(name) DO UPDATE SET data = excluded.data, rev = excluded.rev", name, data, rev);
      // Tell every connected device that something changed; they fetch what they need.
      const from = request.headers.get("x-device") || "";
      const msg = JSON.stringify({ type: "changed", name, rev, from });
      for (const ws of this.state.getWebSockets()) { try { ws.send(msg); } catch (_) {} }
      return new Response(JSON.stringify({ rev }), { headers: { "content-type": "application/json" } });
    }
    return new Response("method not allowed", { status: 405 });
  }

  // Keep-alive from the app; the object can hibernate between messages.
  webSocketMessage(ws, message) { if (message === "ping") ws.send("pong"); }
  webSocketClose(ws) { try { ws.close(); } catch (_) {} }
}
