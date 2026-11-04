import { beforeEach, describe, expect, it } from "vitest";
import { connect, deriveKey, relayChanges, WrongPassword } from "../src";
import { Device, FakeDrive } from "./helpers";

let drive: FakeDrive, key: Uint8Array, room: Device[], A: Device, B: Device;

beforeEach(async () => {
  drive = new FakeDrive();
  key = await connect(drive, "pw1234");
  room = [];
  A = new Device("A", drive, key, room);
  B = new Device("B", drive, key, room);
});

describe("connect", () => {
  it("the second device derives the same key from the same password", async () => {
    expect(await connect(drive, "pw1234")).toEqual(key);
    expect(drive.names()).toEqual(["meta.json"]);
  });
  it("a different password is refused", async () => {
    await expect(connect(drive, "other")).rejects.toBeInstanceOf(WrongPassword);
  });
});

describe("two devices", () => {
  it("a note made on A shows up on B, and a second sync changes nothing", async () => {
    A.store.save({ id: "n1", title: "Hello", body: "world" }, 1000);
    expect((await A.sync()).uploaded).toBe(true);
    const r = await B.sync();
    expect(r.changedHere).toBe(true);
    expect(B.store.get("n1")!.body).toBe("world");
    const before = drive.calls.upload;
    expect((await A.sync()).uploaded).toBe(false);
    expect((await B.sync()).uploaded).toBe(false);
    expect(drive.calls.upload).toBe(before);
  });

  it("different notes edited at the same time both end up everywhere", async () => {
    A.store.save({ id: "a", title: "from A" }, 1000);
    B.store.save({ id: "b", title: "from B" }, 1000);
    await A.sync(); await B.sync(); await A.sync();
    expect(A.store.titles()).toEqual(["from A", "from B"]);
    expect(B.store.titles()).toEqual(["from A", "from B"]);
  });

  it("the same note edited on different lines is merged, with no conflict copy", async () => {
    A.store.save({ id: "n", title: "T", body: "a\nb\nc" }, 1000);
    await A.sync(); await B.sync();
    A.store.save({ id: "n", body: "A\nb\nc" }, 2000);
    B.store.save({ id: "n", body: "a\nb\nC" }, 2100);
    await A.sync(); await B.sync(); await A.sync();
    expect(A.store.get("n")!.body).toBe("A\nb\nC");
    expect(B.store.get("n")!.body).toBe("A\nb\nC");
    expect(A.store.map.size).toBe(1);
    expect(B.store.map.size).toBe(1);
  });

  it("a clash keeps both: the newer stays, the other becomes a (conflict) note, on both devices", async () => {
    A.store.save({ id: "n", title: "T", body: "base" }, 1000);
    await A.sync(); await B.sync();
    A.store.save({ id: "n", body: "from A" }, 2000);
    B.store.save({ id: "n", body: "from B" }, 2100);
    const rA = await A.sync(); expect(rA.conflicts).toBe(0);
    const rB = await B.sync(); expect(rB.conflicts).toBe(1);
    await A.sync();
    for (const d of [A, B]) {
      expect(d.store.map.size).toBe(2);
      expect(d.store.titles()).toEqual(["T", "T (conflict)"]);
    }
    const bodies = [...A.store.map.values()].map((n) => n.body).sort();
    expect(bodies).toEqual(["from A", "from B"]);                       // nothing lost
  });

  it("a delete on A removes the note on B", async () => {
    A.store.save({ id: "n", body: "x" }, 1000);
    await A.sync(); await B.sync();
    A.store.remove("n", 3000);
    await A.sync(); await B.sync();
    expect(B.store.get("n")).toBeUndefined();
    expect(B.store.tombs["n"]).toBe(3000);
  });

  it("an edit made after a delete brings the note back", async () => {
    A.store.save({ id: "n", body: "x" }, 1000);
    await A.sync(); await B.sync();
    A.store.remove("n", 3000);
    B.store.save({ id: "n", body: "edited" }, 4000);
    await A.sync(); await B.sync(); await A.sync();
    expect(A.store.get("n")!.body).toBe("edited");
    expect(B.store.get("n")!.body).toBe("edited");
  });

  it("a device with a slow clock still wins when it edited later (higher rev)", async () => {
    A.store.save({ id: "n", title: "T", body: "v1" }, 10_000_000);
    await A.sync(); await B.sync();
    A.store.save({ id: "n", body: "A edit" }, 10_100_000);                    // rev 2
    await A.sync(); await B.sync();                                           // B pulls rev 2
    B.store.save({ id: "n", body: "B later edit" }, 10_100_000 - 3_600_000);  // rev 3 but the clock is an hour behind
    await B.sync(); await A.sync();
    expect(A.store.get("n")!.body).toBe("B later edit");
  });

  it("an edit made while a sync is running is not overwritten", async () => {
    A.store.save({ id: "n", title: "T", body: "base" }, 1000);
    await A.sync(); await B.sync();
    B.store.save({ id: "n", body: "from B" }, 2000);
    await B.sync();
    // A's sync reads "from B"; while it runs the user edits the note on A.
    const p = A.sync();
    A.store.save({ id: "n", body: "typed on A meanwhile" }, 2500);
    await p;
    await A.sync(); await B.sync(); await A.sync();
    const finalA = A.store.get("n")!;
    expect(finalA.body === "typed on A meanwhile" || A.store.map.size === 2).toBe(true);
    const all = [...A.store.map.values()].map((n) => n.body).join("|");
    expect(all).toContain("typed on A meanwhile");                           // the typing survived
  });
});

describe("images", () => {
  it("an image goes up once and is fetched by the other device", async () => {
    A.store.images.set("pic.jpg", new Uint8Array([1, 2, 3, 4]));
    A.store.save({ id: "n", body: "with picture", images: ["pic.jpg"] }, 1000);
    await A.sync();
    expect(drive.names()).toContain("img_pic.jpg");
    await B.sync();
    expect([...B.store.images.get("pic.jpg")!]).toEqual([1, 2, 3, 4]);
  });

  it("an image nothing uses any more is removed from Drive", async () => {
    A.store.images.set("pic.jpg", new Uint8Array([9]));
    A.store.save({ id: "n", body: "x", images: ["pic.jpg"] }, 1000);
    await A.sync(); await B.sync();
    A.store.save({ id: "n", images: [] }, 2000);
    await A.sync(); await A.sync();
    expect(drive.names()).not.toContain("img_pic.jpg");
  });
});

describe("instant relay", () => {
  it("B applies A's change from the relay without touching Drive", async () => {
    A.store.save({ id: "n", title: "T", body: "one" }, 1000);
    await A.sync(); await B.sync();
    B.inbox.length = 0;                                   // forget the message from the first sync
    A.store.save({ id: "n", body: "two" }, 2000);
    await A.sync();
    const downloads = drive.calls.download;
    const [r] = await B.receive();
    expect(r.applied).toBe(true);
    expect(B.store.get("n")!.body).toBe("two");
    expect(drive.calls.download).toBe(downloads);
  });

  it("a follow-up edit after a relayed change is not a false conflict", async () => {
    A.store.save({ id: "n", title: "T", body: "hello" }, 1000);
    await A.sync(); await B.sync();
    A.store.save({ id: "n", body: "hello world" }, 2000);
    await A.sync(); await B.receive();
    B.store.save({ id: "n", body: "hello world!" }, 3000);
    const r = await B.sync();
    expect(r.conflicts).toBe(0);
    await A.sync();
    expect(A.store.get("n")!.body).toBe("hello world!");
    expect(A.store.map.size).toBe(1);
  });

  it("a late older relay message is ignored", async () => {
    A.store.save({ id: "n", title: "T", body: "v1" }, 1000);
    await A.sync(); await B.sync();
    A.store.save({ id: "n", body: "v2" }, 2000); await A.sync();
    const first = B.inbox.splice(0);
    A.store.save({ id: "n", body: "v3" }, 3000); await A.sync();
    const second = B.inbox.splice(0);
    B.inbox.push(...second, ...first);                     // newest arrives first, the older one late
    await B.receive();
    expect(B.store.get("n")!.body).toBe("v3");
  });

  it("a deletion is relayed too", async () => {
    A.store.save({ id: "n", body: "x" }, 1000);
    await A.sync(); await B.sync();
    A.store.remove("n", 2000);
    await A.sync(); await B.receive();
    expect(B.store.get("n")).toBeUndefined();
  });
});

describe("safety", () => {
  it("refuses data sealed with a different key", async () => {
    A.store.save({ id: "n", body: "x" }, 1000);
    await A.sync();
    const other = await deriveKey("different", new Uint8Array(16));
    const C = new Device("C", drive, other, room);
    await expect(C.sync()).rejects.toBeInstanceOf(WrongPassword);
  });

  it("a note changed in Drive between listing and writing is merged again, not overwritten", async () => {
    A.store.save({ id: "n", title: "T", body: "a\nb\nc" }, 1000);
    await A.sync(); await B.sync();
    A.store.save({ id: "n", body: "A\nb\nc" }, 2000);
    B.store.save({ id: "n", body: "a\nb\nC" }, 2100);
    await B.sync();                                               // B's edit is in Drive
    let t = 0;
    const slowClock = () => (t += 5000);                          // each call jumps 5 s, so the safe-write check runs
    await A.sync(slowClock);
    await B.sync(); await A.sync();
    expect(A.store.get("n")!.body).toBe("A\nb\nC");
    expect(B.store.get("n")!.body).toBe("A\nb\nC");
  });
});

describe("relayChanges", () => {
  it("sends edits straight away, before any Drive sync, and B applies them", async () => {
    A.store.save({ id: "n", title: "T", body: "one" }, 1000);
    await A.sync(); await B.sync();
    B.inbox.length = 0;
    A.store.save({ id: "n", body: "two" }, 2000);
    const sent = await A.relayNow(["n"]);                 // nothing has been uploaded to Drive yet
    expect(sent).toBe(1);
    const [r] = await B.receive();
    expect(r.applied).toBe(true);
    expect(B.store.get("n")!.body).toBe("two");
  });

  it("sends deletions too, and ignores ids that no longer exist anywhere", async () => {
    A.store.save({ id: "n", body: "x" }, 1000);
    await A.sync(); await B.sync();
    B.inbox.length = 0;
    A.store.remove("n", 2000);
    expect(await A.relayNow(["n", "ghost"])).toBe(1);
    await B.receive();
    expect(B.store.get("n")).toBeUndefined();
  });
});
