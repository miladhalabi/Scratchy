/**
 * Sync, the same way the phone does it (Sync.kt + Live.kt): Drive's hidden app folder is the permanent copy
 * (core.syncNow), and the Cloudflare relay carries each change to the other devices within about a second.
 *   - an edit here goes to the relay 0.4 s after the last keystroke, and a Drive sync follows 1.5 s later;
 *   - changes from the other devices arrive live over a WebSocket;
 *   - a catch-up sync runs every 15 minutes, and when the app is used after more than a minute.
 * It runs in the engine (the card's window); the notes window sees `status` and asks for things through it.
 */
import { fetch as http } from "@tauri-apps/plugin-http";
import { invoke } from "@tauri-apps/api/core";
import {
  GoogleDrive, WrongPassword, applyLive, buildAuthUrl, connect as coreConnect, createPkce, exchangeCode, fromBase64, rekey,
  refreshAccessToken, relayChanges, roomId, syncNow as coreSync, toBase64, type OAuthClient, type Relay, type SyncDeps, type Tokens,
} from "@scratchy/core";
import { files } from "./files";
import type { LocalStore } from "./localStore";
import { secrets } from "./secrets";
import { setVaultPassword } from "./vault";

const RELAY = "https://scratchy-sync.halabimilad.workers.dev";

export type SyncState = "off" | "idle" | "syncing" | "offline" | "attention";
export interface SyncStatus {
  connected: boolean;
  state: SyncState;
  /** One short line: "Synced 17:42", "Syncing…", "Offline. Will sync when you're back online." */
  text: string;
  /** Another device changed the vault password; this one needs the new one to carry on. */
  needsPassword: boolean;
  /** Google no longer accepts the saved sign-in (revoked, or expired): sign in again, the key is kept. */
  needsSignIn: boolean;
}

interface Auth { refreshToken: string; key: string }
/** What survives a restart, besides the secrets: shown until the first sync of the session. */
interface Saved { text: string; last: number; needsPassword: boolean; needsSignIn?: boolean }

let store: LocalStore;
let onStatus: (s: SyncStatus) => void = () => {};
let onNotice: (text: string) => void = () => {};

let auth: Auth | null = null;
let key: Uint8Array | null = null;
let room = "";
let device = "";
let tokens: Tokens | null = null;
let saved: Saved = { text: "", last: 0, needsPassword: false };
let state: SyncState = "off";

export const status = (): SyncStatus => ({
  connected: auth != null,
  state: auth ? state : "off",
  text: !auth ? "Not connected" : state === "syncing" ? "Syncing…" : saved.text || "Connected",
  needsPassword: auth != null && saved.needsPassword,
  needsSignIn: auth != null && !!saved.needsSignIn,
});

function set(s: SyncState, text?: string) {
  state = s;
  if (text != null) saved.text = text;
  void files.writeText("sync.json", JSON.stringify(saved));
  onStatus(status());
}

const clock = () => new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
const client = () => invoke<OAuthClient>("google_client");

async function accessToken(): Promise<string> {
  if (!auth) throw new Error("Not connected");
  if (!tokens || Date.now() > tokens.expiresAt) tokens = await refreshAccessToken(await client(), auth.refreshToken, Date.now(), http as typeof fetch);
  return tokens.accessToken;
}
const drive = () => new GoogleDrive(accessToken, http as typeof fetch);

const relay: Relay = {
  relay(payloads) {
    for (const p of payloads) void http(`${RELAY}/v1/${room}/relay`, { method: "POST", headers: { "x-device": device }, body: p }).catch(() => {});
  },
  tick() {
    void http(`${RELAY}/v1/${room}/blob?name=tick`, { method: "PUT", headers: { "x-device": device }, body: String(Date.now()) }).catch(() => {});
  },
};
const deps = (): SyncDeps => ({ drive: drive(), store, key: key!, relay });

// ---- starting, connecting, leaving ----

/** Once, when the engine starts: picks up a saved connection and starts syncing. */
export async function startSync(s: LocalStore, statusFn: (s: SyncStatus) => void, noticeFn: (t: string) => void) {
  store = s; onStatus = statusFn; onNotice = noticeFn;
  const d = await files.readText("device.json");
  if (d) device = JSON.parse(d).id;
  else { device = crypto.randomUUID(); await files.writeText("device.json", JSON.stringify({ id: device })); }
  try { saved = { ...saved, ...JSON.parse((await files.readText("sync.json")) ?? "{}") }; } catch { /* first run */ }
  store.onEdit(noteEdited);

  const a = await secrets.get("sync-auth");
  if (!a) { onStatus(status()); return; }
  try {
    auth = JSON.parse(a) as Auth;
    key = fromBase64(auth.key);
    room = await roomId(key);
  } catch { auth = null; onStatus(status()); return; }
  begin();
}

let timer: ReturnType<typeof setInterval> | undefined;
function begin() {
  set("idle");
  connectLive();
  void syncNow();
  clearInterval(timer);
  timer = setInterval(() => void syncNow(), 15 * 60 * 1000);
}

async function saveAuth(k: Uint8Array) {
  key = k;
  auth!.key = toBase64(k);
  room = await roomId(k);
  await secrets.set("sync-auth", JSON.stringify(auth));
}

/**
 * Signs in to Google in the browser, checks the vault password against the other devices, and does the first
 * sync. The password becomes this computer's vault password too. It is never stored.
 */
/** Google's sign-in in the browser; returns fresh tokens with a lasting refresh token. */
async function googleSignIn(): Promise<Tokens> {
  const c = await client();
  const port = await invoke<number>("oauth_start");
  const redirect = `http://127.0.0.1:${port}/`;
  const { verifier, challenge, state: st } = await createPkce();
  await invoke("open_url", { url: buildAuthUrl(c, redirect, challenge, st) });
  const query = new URLSearchParams(await invoke<string>("oauth_wait"));
  if (query.get("error")) throw new Error("Google sign-in was cancelled");
  if (query.get("state") !== st || !query.get("code")) throw new Error("Google sign-in failed");
  const t = await exchangeCode(c, query.get("code")!, verifier, redirect, Date.now(), http as typeof fetch);
  if (!t.refreshToken) throw new Error("Google did not allow lasting access. Try again.");
  return t;
}

export async function connect(password: string) {
  const t = await googleSignIn();
  const k = await coreConnect(new GoogleDrive(async () => t.accessToken, http as typeof fetch), password);   // throws WrongPassword
  tokens = t;
  auth = { refreshToken: t.refreshToken!, key: "" };
  await saveAuth(k);
  await setVaultPassword(password);
  saved.needsPassword = false;
  saved.needsSignIn = false;
  begin();
}

/** The saved sign-in stopped working: sign in again and carry on with the same key. */
export async function signIn() {
  if (!auth || !key) throw new Error("Not connected");
  const t = await googleSignIn();
  tokens = t;
  auth.refreshToken = t.refreshToken!;
  await secrets.set("sync-auth", JSON.stringify(auth));
  saved.needsSignIn = false;
  begin();
}

/** Another device changed the vault password: rejoin with the new one (enterNewPassword on the phone). */
export async function enterNewPassword(password: string) {
  if (!auth) throw new Error("Not connected");
  const k = await coreConnect(drive(), password);
  await saveAuth(k);
  await setVaultPassword(password);
  saved.needsPassword = false;
  dropLive();
  begin();
}

/** Re-encrypts Drive with the new password; the vault password here follows only once Drive has it. */
export async function changePassword(password: string) {
  if (!auth || !key) { await setVaultPassword(password); return; }
  set("syncing", "Changing password…");
  try {
    const pending = {
      get: async () => { const s = await secrets.get("sync-pending-salt"); return s ? fromBase64(s) : null; },
      set: async (s: Uint8Array | null) => { if (s) await secrets.set("sync-pending-salt", toBase64(s)); else await secrets.delete("sync-pending-salt"); },
    };
    const k = await rekey(drive(), key, password, pending);
    await saveAuth(k);
    await setVaultPassword(password);
    // Every file was rewritten: compare afresh next time.
    const st = await store.syncState();
    for (const e of Object.values(st)) e.ver = "";
    await store.saveSyncState(st);
    dropLive();
    set("idle", "Password changed. Enter it on your other devices.");
    connectLive();
  } catch (e) {
    set("attention", `Couldn't change the password: ${message(e).slice(0, 100)}`);
    throw e;
  }
}

export async function disconnect() {
  clearInterval(timer); clearTimeout(fastTimer); clearTimeout(slowTimer); clearTimeout(liveTimer);
  dropLive();
  auth = null; key = null; tokens = null;
  await secrets.delete("sync-auth");
  await store.saveSyncState({});
  saved = { text: "", last: 0, needsPassword: false };
  set("off", "");
}

// ---- one sync at a time ----

let running: Promise<void> | null = null;
let again = false;
const message = (e: unknown) => String((e as Error)?.message ?? e);

export function syncNow(): Promise<void> {
  if (!auth || !key) return Promise.resolve();
  if (running) { again = true; return running; }
  running = (async () => {
    do {
      again = false;
      set("syncing");
      try {
        let r;
        try { r = await coreSync(deps()); }
        catch (e) {
          if (!/Drive 401/.test(message(e))) throw e;
          tokens = null;                                        // the token ran out early: get a new one, once
          r = await coreSync(deps());
        }
        saved.last = Date.now();
        saved.needsPassword = false;
        saved.needsSignIn = false;
        set("idle", `Synced ${clock()}`);
        if (r.conflicts > 0) onNotice(r.conflicts === 1
          ? "Two edits clashed. The other one was kept as a note ending in (conflict)."
          : `${r.conflicts} notes had clashing edits. Copies ending in (conflict) were kept.`);
      } catch (e) {
        if (e instanceof WrongPassword) { saved.needsPassword = true; set("attention", "Vault password changed on another device. Enter the new one in Settings."); }
        else if (/invalid_grant/.test(message(e))) { saved.needsSignIn = true; set("attention", "Google sign-in expired. Sign in again in Settings."); }
        else if (/network|connect|dns|timed out|sending request|resolve|fetch|offline/i.test(message(e))) set("offline", "Offline. Will sync when you're back online.");
        else set("attention", `Sync failed: ${message(e).slice(0, 120)}`);
      }
    } while (again);
  })().finally(() => { running = null; });
  return running;
}

/** The app was used: catch up, but not more than once a minute. */
export function syncIfStale() {
  if (auth && !running && Date.now() - saved.last > 60_000) void syncNow();
}

// ---- edits made here ----

const fastIds = new Set<string>();
let fastTimer: ReturnType<typeof setTimeout> | undefined;
let slowTimer: ReturnType<typeof setTimeout> | undefined;

function noteEdited(id: string) {
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

// ---- changes from the other devices, as they happen ----

let live: WebSocket | null = null;
let retry = 0;
let liveTimer: ReturnType<typeof setTimeout> | undefined;
/** Live changes are merged one at a time, and never in the middle of a full sync. */
let liveQueue: Promise<unknown> = Promise.resolve();

/** Closes the live connection without reconnecting (the room changes with the key). */
function dropLive() { clearTimeout(liveTimer); const ws = live; live = null; ws?.close(); }

function connectLive() {
  if (!auth || live) return;
  const ws = new WebSocket(`${RELAY.replace("https", "wss")}/v1/${room}/ws`);
  live = ws;
  ws.onopen = () => { retry = 0; void syncNow(); };                 // catch up on anything missed while away
  ws.onmessage = (e) => {
    let m: { type?: string; from?: string; data?: string };
    try { m = JSON.parse(String(e.data)); } catch { return; }
    if (m.from === device) return;
    if (m.type === "note" && m.data) {
      const data = m.data;
      liveQueue = liveQueue.then(async () => {
        if (running) await running;
        const r = await applyLive(deps(), data).catch(() => ({ applied: false, followUp: true }));
        if (r.followUp) void syncNow();
      });
    } else void syncNow();
  };
  const ping = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send("ping"); }, 30_000);
  const dropped = () => {
    clearInterval(ping);
    if (live !== ws) return;
    live = null;
    if (!auth) return;
    const wait = Math.min(60, 3 * 2 ** Math.min(retry++, 5));          // 3 s, 6 s, 12 s ... up to a minute
    liveTimer = setTimeout(connectLive, wait * 1000);
  };
  ws.onclose = dropped;
  ws.onerror = () => ws.close();
}
