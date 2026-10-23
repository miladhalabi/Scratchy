import { get, writable } from "svelte/store";
import { MIN_PASSWORD, type VaultMeta, createVault, fromBase64, randomBytes, toBase64, verifyVault } from "@scratchy/core";
import { files } from "./files";
import { secrets } from "./secrets";

/**
 * The vault lock. The password only lets you into the secret notes inside the app; on disk they are sealed with a
 * random key kept in the OS keyring (see LocalStore). The vault locks again after you leave the app for a while.
 */
export const vaultOpen = writable(false);
export const vaultSetUp = writable(false);

const LOCK_AFTER_AWAY_MS = 30_000;
const LOCK_AFTER_IDLE_MS = 5 * 60_000;

let meta: VaultMeta | null = null;
let awayTimer: ReturnType<typeof setTimeout> | undefined;
let idleTimer: ReturnType<typeof setTimeout> | undefined;
let onAutoLock: () => void = () => {};

/** The key that seals secret notes on disk. Created on first use and kept in the keyring. */
export async function loadDataKey(): Promise<Uint8Array> {
  const existing = await secrets.get("vault-data-key");
  if (existing) return fromBase64(existing);
  const k = randomBytes(32);
  await secrets.set("vault-data-key", toBase64(k));
  return k;
}

export async function loadVault(autoLock: () => void) {
  onAutoLock = autoLock;
  const t = await files.readText("vault.json");
  meta = t ? (JSON.parse(t) as VaultMeta) : null;
  vaultSetUp.set(meta != null);
  addEventListener("blur", () => {
    clearTimeout(awayTimer);
    if (get(vaultOpen)) awayTimer = setTimeout(lock, LOCK_AFTER_AWAY_MS);
  });
  addEventListener("focus", () => clearTimeout(awayTimer));
  for (const ev of ["keydown", "pointerdown", "wheel"]) addEventListener(ev, touch, { passive: true });
}

function touch() {
  if (!get(vaultOpen)) return;
  clearTimeout(idleTimer);
  idleTimer = setTimeout(lock, LOCK_AFTER_IDLE_MS);
}

export async function setupVault(password: string) {
  if (password.length < MIN_PASSWORD) throw new Error(`Use at least ${MIN_PASSWORD} characters.`);
  meta = await createVault(password);
  await files.writeText("vault.json", JSON.stringify(meta));
  vaultSetUp.set(true);
  vaultOpen.set(true);
  touch();
}

/** True when the password is right (and the vault is now open). */
export async function unlock(password: string): Promise<boolean> {
  if (!meta) return false;
  if (!(await verifyVault(password, meta))) return false;
  vaultOpen.set(true);
  touch();
  return true;
}

export function lock() {
  clearTimeout(awayTimer); clearTimeout(idleTimer);
  if (!get(vaultOpen)) return;
  vaultOpen.set(false);
  onAutoLock();
}
