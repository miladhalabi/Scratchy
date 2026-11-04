import { invoke } from "@tauri-apps/api/core";
import { files } from "./files";

/**
 * Small secrets (the Google sign-in token, the sync key, the key that protects secret notes on disk).
 * They go to the operating system's keyring. If there is none (no Secret Service running), they fall back to a
 * private file, and `usingKeyring` says so, so the app can be honest about it.
 */
export let usingKeyring = true;

const FALLBACK = "secrets.json";
async function fileMap(): Promise<Record<string, string>> {
  const t = await files.readText(FALLBACK);
  try { return t ? JSON.parse(t) : {}; } catch { return {}; }
}

export const secrets = {
  async get(name: string): Promise<string | null> {
    try { return await invoke<string | null>("secret_get", { name }); }
    catch { usingKeyring = false; return (await fileMap())[name] ?? null; }
  },
  async set(name: string, value: string): Promise<void> {
    try { await invoke("secret_set", { name, value }); return; } catch { usingKeyring = false; }
    const m = await fileMap(); m[name] = value;
    await files.writeText(FALLBACK, JSON.stringify(m));
  },
  async delete(name: string): Promise<void> {
    try { await invoke("secret_delete", { name }); } catch { usingKeyring = false; }
    const m = await fileMap();
    if (name in m) { delete m[name]; await files.writeText(FALLBACK, JSON.stringify(m)); }
  },
};
