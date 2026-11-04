import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { deriveKey, fromBase64, liveId, open, openText, seal, sealText, sealWithIv, toBase64 } from "../src/crypto";

// Vectors made by test/fixtures/GenVectors.java with the same JCA calls Android uses.
const v = JSON.parse(readFileSync(new URL("./fixtures/vectors.json", import.meta.url), "utf8")) as {
  salt: string;
  cases: { password: string; key: string; plain: string; sealed: string; liveId: string }[];
};

describe("compatible with Android (Java vectors)", () => {
  for (const c of v.cases) {
    it(`password ${JSON.stringify(c.password).slice(0, 14)}`, async () => {
      const key = await deriveKey(c.password, fromBase64(v.salt));
      expect(toBase64(key)).toBe(c.key);                                           // PBKDF2 matches
      expect(await openText(key, fromBase64(c.sealed))).toBe(c.plain);             // opens what Android sealed
      const iv = fromBase64(c.sealed).subarray(0, 12);
      const again = await sealWithIv(key, iv, new TextEncoder().encode(c.plain));
      expect(toBase64(again)).toBe(c.sealed);                                      // seals to the identical bytes
      expect(await liveId(key)).toBe(c.liveId);                                    // same relay room
    });
  }
});

describe("seal / open", () => {
  it("round-trips and never repeats an IV", async () => {
    const key = await deriveKey("pw", new Uint8Array(16));
    const a = await sealText(key, "hello"), b = await sealText(key, "hello");
    expect(toBase64(a)).not.toBe(toBase64(b));
    expect(await openText(key, a)).toBe("hello");
  });

  it("returns null for the wrong key or a tampered blob", async () => {
    const salt = new Uint8Array(16);
    const key = await deriveKey("right", salt), wrong = await deriveKey("wrong", salt);
    const blob = await sealText(key, "secret");
    expect(await open(wrong, blob)).toBeNull();
    const bad = blob.slice(); bad[bad.length - 1] ^= 1;
    expect(await open(key, bad)).toBeNull();
    expect(await open(key, new Uint8Array(5))).toBeNull();
  });

  it("handles large payloads", async () => {
    const key = await deriveKey("pw", new Uint8Array(16));
    const big = new Uint8Array(300_000).map((_, i) => i % 251);
    expect(await open(key, await seal(key, big))).toEqual(big);
  });
});
