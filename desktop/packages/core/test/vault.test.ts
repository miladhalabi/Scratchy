import { describe, expect, it } from "vitest";
import { createVault, openSecret, randomBytes, sealSecret, verifyVault } from "../src";

describe("vault lock", () => {
  it("accepts the right password and refuses a wrong one", async () => {
    const meta = await createVault("correct horse");
    expect(await verifyVault("correct horse", meta)).not.toBeNull();
    expect(await verifyVault("wrong", meta)).toBeNull();
    expect(await verifyVault("", meta)).toBeNull();
  });

  it("never stores the password and uses a fresh salt each time", async () => {
    const a = await createVault("same"), b = await createVault("same");
    expect(JSON.stringify(a)).not.toContain("same");
    expect(a.salt).not.toBe(b.salt);
  });

  it("works with non-Latin passwords", async () => {
    const meta = await createVault("كلمة سر ✓");
    expect(await verifyVault("كلمة سر ✓", meta)).not.toBeNull();
  });
});

describe("secret note text at rest", () => {
  it("round-trips with the right key", async () => {
    const k = randomBytes(32);
    const blob = await sealSecret(k, { title: "عنوان", body: "line one\nسطر ٢" });
    expect(await openSecret(k, blob)).toEqual({ title: "عنوان", body: "line one\nسطر ٢" });
  });

  it("is unreadable with another key and does not show the text", async () => {
    const blob = await sealSecret(randomBytes(32), { title: "T", body: "very secret" });
    expect(await openSecret(randomBytes(32), blob)).toBeNull();
    expect(atob(blob)).not.toContain("very secret");
  });
});
