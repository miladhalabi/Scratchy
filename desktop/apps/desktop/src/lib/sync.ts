import { get, writable } from "svelte/store";
import { fetch as http } from "@tauri-apps/plugin-http";
import { invoke } from "@tauri-apps/api/core";
import {
  GoogleDrive, WrongPassword, applyLive, buildAuthUrl, connect as coreConnect, createPkce, exchangeCode, fromBase64,
  refreshAccessToken, relayChanges, roomId, syncNow as coreSync, toBase64, type OAuthClient, type Relay, type SyncDeps, type Tokens,
} from "@scratchy/core";
import { files } from "./files";
import { secrets } from "./secrets";
import { local } from "./state";

/**
 * Sync for the desktop app: Drive for storage (core.syncNow), plus the Cloudflare relay for instant updates.
 * Same behaviour as Sync.kt + Live.kt on the phone: changes go out 0.4 s after you stop typing, a Drive sync
 * follows 1.5 s later, and other devices are heard live over a WebSocket.
 */

const RELAY = "https://scratchy-sync.halabimilad.workers.dev";

export type SyncState = "off" | "idle" | "syncing" | "offline" | "attention";
export const syncStatus = writable<{ state: SyncState; text: string }>({ state: "off", text: "Not connected" });
/** One-off messages, such as "Two edits clashed…". */
export const syncNotice = writable<string>("");

interface Auth { refreshToken: string; key: string }
let auth: Auth | null = null;
let key: Uint8Array | null = null;
let room = "";
let device = "";
let tokens: Tokens | null = null;
let lastOk = 0;

const set = (state: SyncState, text: string) => syncStatus.set({ state, text });
const clock = () => new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });

async function client(): Promise<OAuthClient> { return invoke<OAuthClient>("google_client"); }

async function accessToken(): Promise<string> {
  if (!auth) throw new Error("Not connected");
  if (!tokens || Date.now() > tokens.expiresAt) tokens = await refreshAccessToken(await client(), auth.refreshToken, Date.now(), http as typeof fetch);
  return tokens.accessToken;
}

const drive = () => new GoogleDrive(accessToken, http as typeof fetch);

const relay: Relay = {
  relay(payloads) {
    for (const p of payloads) {
      void http(`${RELAY}/v1/${room}/relay`, { method: "POST", headers: { "x-device": device }, body: p }).catch(() => {});
    }
  },
  tick() {
    void http(`${RELAY}/v1/${room}/blob?name=tick`, { method: "PUT", headers: { "x-device": device }, body: String(Date.now()) }).catch(() => {});
  },
};

const deps = (): SyncDeps => ({ drive: drive(), store: local, key: key!, relay });

// ---- starting up, connecting, disconnecting ----

async function loadDevice() {
  const d = await files.readText("device.json");
  if (d) { device = JSON.parse(d).id; return; }
  device = crypto.randomUUID();
  await files.writeText("device.json", JSON.stringify({ id: device }));
}

/** Call once at app start: picks up a saved connection and starts syncing. */
export async function startSync() {
  await loadDevice();
  let a = await secrets.get("sync-auth");
  if (!a) {                                             // an older version kept it in a file: move it to the keyring
    a = await files.readText("sync_auth.json");
    if (a) { await secrets.set("sync-auth", a); await files.removeText("sync_auth.json"); }
  }
  if (!a) return;
  try {
    auth = JSON.parse(a) as Auth;
    key = fromBase64(auth.key);
    room = await roomId(key);
  } catch { auth = null; return; }
  begin();
}

function begin() {
  set("idle", "Connected");
  connectLive();
  void syncNow();
  clearInterval(timer);
  timer = setInterval(() => void syncNow(), 15 * 60 * 1000);
}

/**
 * Signs in to Google (browser), checks the vault password against the other devices, saves the connection
 * and does the first sync. The password is used to derive the key and is never stored.
 */
export async function connectGoogle(password: string) {
  const c = await client();
  const port = await invoke<number>("oauth_start");
  const redirect = `http://127.0.0.1:${port}/`;
  const { verifier, challenge, state } = await createPkce();
  await invoke("open_url", { url: buildAuthUrl(c, redirect, challenge, state) });
  const query = new URLSearchParams(await invoke<string>("oauth_wait"));
  if (query.get("error")) throw new Error("Google sign-in was cancelled");
  if (query.get("state") !== state || !query.get("code")) throw new Error("Google sign-in failed");
  const t = await exchangeCode(c, query.get("code")!, verifier, redirect, Date.now(), http as typeof fetch);
  if (!t.refreshToken) throw new Error("Google did not allow lasting access. Try again.");
  tokens = t;
  auth = { refreshToken: t.refreshToken, key: "" };
  const k = await coreConnect(new GoogleDrive(async () => t.accessToken, http as typeof fetch), password);   // throws WrongPassword
  key = k;
  auth.key = toBase64(k);
  room = await roomId(k);
  await secrets.set("sync-auth", JSON.stringify(auth));
  begin();
}

export async function disconnect() {
  clearInterval(timer); clearTimeout(fastTimer); clearTimeout(slowTimer);
  live?.close(); live = null;
  auth = null; key = null; tokens = null;
  await secrets.delete("sync-auth");
  await files.removeText("sync_auth.json");
  await files.writeText("sync_state.json", "{}");
  set("off", "Not connected");
}

export const isConnected = () => auth != null;

// ---- one sync at a time ----

let running: Promise<void> | null = null;
let again = false;
let timer: ReturnType<typeof setInterval> | undefined;

export function syncNow(): Promise<void> {
  if (!auth || !key) return Promise.resolve();
  if (running) { again = true; return running; }
  running = (async () => {
    do {
      again = false;
      set("syncing", "Syncing…");
      try {
        const r = await coreSync(deps());
        lastOk = Date.now();
        set("idle", `Synced ${clock()}`);
        if (r.conflicts > 0) syncNotice.set(r.conflicts === 1
          ? "Two edits clashed. The other one was kept as a note ending in (conflict)."
          : `${r.conflicts} notes had clashing edits. Copies ending in (conflict) were kept.`);
      } catch (e) {
        if (e instanceof WrongPassword) set("attention", "The vault password was changed on another device. Connect again with the new one.");
        else if (/network|connect|dns|timed out|sending request|resolve|fetch/i.test(String((e as Error)?.message ?? e))) set("offline", "Offline. Will sync when you're back online.");
        else set("attention", `Sync failed: ${String((e as Error)?.message ?? e).slice(0, 140)}`);
      }
    } while (again);
  })().finally(() => { running = null; });
  return running;
}

// ---- reacting to the user's edits ----

const fastIds = new Set<string>();
let fastTimer: ReturnType<typeof setTimeout> | undefined;
let slowTimer: ReturnType<typeof setTimeout> | undefined;

/** Hook this to local.onEdit. */
export function noteEdited(id: string) {
  if (!auth || !key) return;
  fastIds.add(id);
  clearTimeout(fastTimer);
  fastTimer = setTimeout(() => {
    const ids = [...fastIds]; fastIds.clear();
    void relayChanges(deps(), ids).catch(() => {});
  }, 400);
  clearTimeout(slowTimer);
  slowTimer = setTimeout(() => void syncNow(), 1500);
}

/** When the window comes back to the front, catch up (at most once a minute). */
export function onFocus() {
  if (auth && Date.now() - lastOk > 60_000) void syncNow();
}

// ---- instant updates from the other devices ----

let live: WebSocket | null = null;
let retry = 0;
let liveTimer: ReturnType<typeof setTimeout> | undefined;

function connectLive() {
  if (!auth || live) return;
  const ws = new WebSocket(`${RELAY.replace("https", "wss")}/v1/${room}/ws`);
  live = ws;
  ws.onopen = () => { retry = 0; void syncNow(); };                 // catch up on anything missed while offline
  ws.onmessage = async (e) => {
    let m: { type?: string; from?: string; data?: string };
    try { m = JSON.parse(String(e.data)); } catch { return; }
    if (m.from === device) return;
    if (m.type === "note" && m.data) {
      const r = await applyLive(deps(), m.data).catch(() => ({ applied: false, followUp: true }));
      if (r.followUp) void syncNow();
    } else if (m.type === "changed") void syncNow();
  };
  const dropped = () => {
    if (live !== ws) return;
    live = null;
    if (!auth) return;
    const wait = Math.min(60, 3 * 2 ** Math.min(retry++, 5));
    liveTimer = setTimeout(connectLive, wait * 1000);
  };
  ws.onclose = dropped;
  ws.onerror = () => ws.close();
  // WebSocket keep-alive: a tiny ping so idle proxies don't cut the connection.
  const ping = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send("ping"); else clearInterval(ping); }, 30_000);
  void liveTimer;
}

export const status = () => get(syncStatus);
