// Scratchy sync relay. One Durable Object per account passes sealed note changes between that account's connected
// devices the moment they arrive. It only ever sees ciphertext, and it stores nothing: Google Drive keeps the notes.
//
// The account id is the hex SHA-256 of a secret the app derives from the vault password.
// Knowing the id is what lets a device in; nothing here can be decrypted without the password.

import { servePage } from "./pages.js";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const shown = request.method === "GET" && servePage(url.pathname);
    if (shown) return shown;
    const m = url.pathname.match(/^\/v1\/([0-9a-f]{64})\/(ws|relay|tick|blob)$/);
    if (!m) return new Response("scratchy-sync", { status: 404 });
    const stub = env.VAULT.get(env.VAULT.idFromName(m[1]));
    return stub.fetch(request);
  },
};

/** A sealed note change, base64. The apps send notes up to 200 KB this way; bigger ones go through Drive only. */
const MAX_RELAY = 350_000;
/** Devices of one account connected at once. Far more than anyone has; it keeps a room from being flooded. */
const MAX_SOCKETS = 32;

export class Vault {
  constructor(state, env) {
    this.state = state;
    // Earlier versions kept small files here; nothing is stored any more.
    state.storage.sql.exec("DROP TABLE IF EXISTS blobs");
  }

  async fetch(request) {
    const url = new URL(request.url);
    const from = request.headers.get("x-device") || "";
    if (url.pathname.endsWith("/ws")) {
      if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
      if (this.state.getWebSockets().length >= MAX_SOCKETS) return new Response("too many devices", { status: 429 });
      const pair = new WebSocketPair();
      this.state.acceptWebSocket(pair[1]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    // /relay: an encrypted note change, straight to the other connected devices.
    if (url.pathname.endsWith("/relay")) {
      if (request.method !== "POST") return new Response("method not allowed", { status: 405 });
      if (Number(request.headers.get("content-length") || 0) > MAX_RELAY) return new Response("too large", { status: 413 });
      const data = await request.text();
      if (data.length > MAX_RELAY) return new Response("too large", { status: 413 });
      const n = this.send({ type: "note", from, data });
      return Response.json({ delivered: n });
    }
    // /tick: "something changed in Drive, go and look". The PUT /blob?name=tick of earlier app versions still rings
    // the bell, but nothing is kept.
    const tick = (url.pathname.endsWith("/tick") && request.method === "POST") ||
      (url.pathname.endsWith("/blob") && request.method === "PUT" && url.searchParams.get("name") === "tick");
    if (tick) {
      const rev = Date.now();
      this.send({ type: "changed", name: "tick", rev, from });
      return Response.json({ rev });
    }
    return new Response("not found", { status: 404 });
  }

  /** Sends a message to every connected device of this account; returns how many got it. */
  send(message) {
    const msg = JSON.stringify(message);
    let n = 0;
    for (const ws of this.state.getWebSockets()) { try { ws.send(msg); n++; } catch (_) {} }
    return n;
  }

  // Keep-alive from the app; the object can hibernate between messages.
  webSocketMessage(ws, message) { if (message === "ping") ws.send("pong"); }
  webSocketClose(ws) { try { ws.close(); } catch (_) {} }
}
