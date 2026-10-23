/**
 * Encryption for synced data. Byte-compatible with Sync.kt / Vault.kt on Android:
 *  - key = PBKDF2-HMAC-SHA256(password as UTF-8, salt, 120000 iterations, 256 bits)
 *  - sealed = 12-byte random IV + AES-256-GCM ciphertext + 16-byte tag
 *  - live room id = hex SHA-256("scratchy-live-v1" + key)
 * Uses WebCrypto only, so it runs in the Tauri web view and in Node.
 */

const ITERATIONS = 120_000;
const subtle = globalThis.crypto.subtle;
/** TypeScript 5.7+ is stricter about typed-array buffers; these arrays are always plain ArrayBuffers. */
const bs = (u: Uint8Array) => u as unknown as BufferSource;
const enc = new TextEncoder();
const dec = new TextDecoder();

export const toBase64 = (b: Uint8Array): string => {
  let s = "";
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
};

export const fromBase64 = (s: string): Uint8Array => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

export const randomBytes = (n: number): Uint8Array => globalThis.crypto.getRandomValues(new Uint8Array(n));

/** The sync/vault key from the vault password. Same salt on every device (it lives in Drive's meta.json). */
export async function deriveKey(password: string, salt: Uint8Array): Promise<Uint8Array> {
  const base = await subtle.importKey("raw", bs(enc.encode(password)), "PBKDF2", false, ["deriveBits"]);
  const bits = await subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: bs(salt), iterations: ITERATIONS }, base, 256);
  return new Uint8Array(bits);
}

const aesKey = (key: Uint8Array, usage: KeyUsage) => subtle.importKey("raw", bs(key), "AES-GCM", false, [usage]);

/** Exposed for tests (deterministic IV). Use [seal] everywhere else. */
export async function sealWithIv(key: Uint8Array, iv: Uint8Array, plain: Uint8Array): Promise<Uint8Array> {
  const ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv: bs(iv), tagLength: 128 }, await aesKey(key, "encrypt"), bs(plain)));
  const out = new Uint8Array(iv.length + ct.length);
  out.set(iv, 0);
  out.set(ct, iv.length);
  return out;
}

export const seal = (key: Uint8Array, plain: Uint8Array): Promise<Uint8Array> => sealWithIv(key, randomBytes(12), plain);

/** Returns null when the data can't be read (wrong key, or tampered with). */
export async function open(key: Uint8Array, blob: Uint8Array): Promise<Uint8Array | null> {
  if (blob.length < 12 + 16) return null;
  try {
    const plain = await subtle.decrypt(
      { name: "AES-GCM", iv: bs(blob.subarray(0, 12)), tagLength: 128 }, await aesKey(key, "decrypt"), bs(blob.subarray(12)),
    );
    return new Uint8Array(plain);
  } catch {
    return null;
  }
}

export const sealText = (key: Uint8Array, text: string) => seal(key, enc.encode(text));

export async function openText(key: Uint8Array, blob: Uint8Array): Promise<string | null> {
  const p = await open(key, blob);
  return p == null ? null : dec.decode(p);
}

/** The name of this account's room on the live relay. A hash, so it can't be guessed from anything public. */
export async function liveId(key: Uint8Array): Promise<string> {
  const prefix = enc.encode("scratchy-live-v1");
  const data = new Uint8Array(prefix.length + key.length);
  data.set(prefix, 0);
  data.set(key, prefix.length);
  const h = new Uint8Array(await subtle.digest("SHA-256", bs(data)));
  return Array.from(h, (x) => x.toString(16).padStart(2, "0")).join("");
}
