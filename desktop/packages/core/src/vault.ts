import { deriveKey, fromBase64, openText, randomBytes, sealText, toBase64 } from "./crypto";

/**
 * The vault lock for secret notes on this computer. The password is never stored: we keep a random salt and a
 * "check" value (a known string sealed with the key derived from the password). A password is right when it
 * opens the check. Same idea as meta.json in Drive.
 */
export interface VaultMeta { v: 1; salt: string; check: string }

export const MIN_PASSWORD = 4;
const CHECK_TEXT = "scratchy-vault";

export async function createVault(password: string): Promise<VaultMeta> {
  const salt = randomBytes(16);
  const key = await deriveKey(password, salt);
  return { v: 1, salt: toBase64(salt), check: toBase64(await sealText(key, CHECK_TEXT)) };
}

/** Returns the derived key when the password is right, otherwise null. */
export async function verifyVault(password: string, meta: VaultMeta): Promise<Uint8Array | null> {
  const key = await deriveKey(password, fromBase64(meta.salt));
  return (await openText(key, fromBase64(meta.check))) === CHECK_TEXT ? key : null;
}

/** The text of a secret note as it is stored on disk, sealed with the local data key (kept in the OS keyring). */
export async function sealSecret(dataKey: Uint8Array, text: { title: string; body: string }): Promise<string> {
  return toBase64(await sealText(dataKey, JSON.stringify(text)));
}

export async function openSecret(dataKey: Uint8Array, blob: string): Promise<{ title: string; body: string } | null> {
  const t = await openText(dataKey, fromBase64(blob));
  if (t == null) return null;
  try { const o = JSON.parse(t); return { title: String(o.title ?? ""), body: String(o.body ?? "") }; } catch { return null; }
}
