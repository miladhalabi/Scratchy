/**
 * The vault password on this computer (Vault.kt on the phone). It is never stored: vault.json keeps a salt and a
 * check value that only the right password opens. The same password makes the sync key, so connecting sync sets
 * it here too. The text of secret notes on disk is sealed with a separate random key kept in the OS keyring.
 */
import { MIN_PASSWORD, createVault, fromBase64, randomBytes, toBase64, verifyVault, type VaultMeta } from "@scratchy/core";
import { files } from "./files";
import { secrets } from "./secrets";

let meta: VaultMeta | null = null;

export async function loadVault() {
  const t = await files.readText("vault.json");
  try { meta = t ? (JSON.parse(t) as VaultMeta) : null; } catch { meta = null; }
}

export const vaultSetUp = () => meta != null;

export async function setVaultPassword(password: string) {
  if (password.length < MIN_PASSWORD) throw new Error(`Use at least ${MIN_PASSWORD} characters`);
  meta = await createVault(password);
  await files.writeText("vault.json", JSON.stringify(meta));
}

export async function checkVaultPassword(password: string): Promise<boolean> {
  return meta != null && (await verifyVault(password, meta)) != null;
}

/** The key that seals secret notes on disk. Made on first use and kept in the keyring. */
export async function loadDataKey(): Promise<Uint8Array> {
  const existing = await secrets.get("vault-data-key");
  if (existing) return fromBase64(existing);
  const k = randomBytes(32);
  await secrets.set("vault-data-key", toBase64(k));
  return k;
}
