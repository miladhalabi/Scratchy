import { describe, expect, it } from "vitest";
import { type Note, RECENT_MS, pickCardNote, recentNotes } from "../src";

const n = (id: string, o: Partial<Note> = {}): Note => ({
  id, title: id, body: "x", createdAt: 1, updatedAt: 1000, pinned: false, images: [], secret: false, rev: 1, ...o,
});
const NOW = 10_000_000;
const id = () => "fresh";

describe("pickCardNote", () => {
  it("recent: continues a note used within 30 minutes", () => {
    const p = pickCardNote([n("a")], { mode: "recent", lastCardNote: "a", lastCardAt: NOW - 60_000 }, NOW, id);
    expect(p.existing).toBe(true); expect(p.note.id).toBe("a");
  });
  it("recent: starts a new note after 30 minutes", () => {
    const p = pickCardNote([n("a")], { mode: "recent", lastCardNote: "a", lastCardAt: NOW - RECENT_MS - 1 }, NOW, id);
    expect(p.existing).toBe(false); expect(p.note.id).toBe("fresh");
  });
  it("recent: an edit made elsewhere recently also counts as use", () => {
    const p = pickCardNote([n("a", { updatedAt: NOW - 1000 })], { mode: "recent", lastCardNote: "a", lastCardAt: 5 }, NOW, id);
    expect(p.existing).toBe(true);
  });
  it("last: always continues the last note, however old", () => {
    const p = pickCardNote([n("a")], { mode: "last", lastCardNote: "a", lastCardAt: 1 }, NOW, id);
    expect(p.note.id).toBe("a");
  });
  it("last: falls back to a new note when it is gone", () => {
    expect(pickCardNote([], { mode: "last", lastCardNote: "gone" }, NOW, id).existing).toBe(false);
  });
  it("new: always blank", () => {
    expect(pickCardNote([n("a")], { mode: "new", lastCardNote: "a", lastCardAt: NOW }, NOW, id).existing).toBe(false);
  });
  it("scratch: creates the pinned Scratch note once, then reuses it", () => {
    const first = pickCardNote([], { mode: "scratch" }, NOW, id);
    expect(first.create).toBe(true); expect(first.note.title).toBe("Scratch"); expect(first.note.pinned).toBe(true);
    const again = pickCardNote([first.note], { mode: "scratch", scratchId: first.note.id }, NOW, id);
    expect(again.create).toBeUndefined(); expect(again.note.id).toBe(first.note.id);
  });
  it("never offers a secret note", () => {
    const p = pickCardNote([n("a", { secret: true })], { mode: "last", lastCardNote: "a" }, NOW, id);
    expect(p.existing).toBe(false);
  });
});

describe("recentNotes", () => {
  it("pinned first, then newest, secret excluded, limited", () => {
    const list = [n("old", { updatedAt: 1 }), n("new", { updatedAt: 9 }), n("pin", { pinned: true, updatedAt: 0 }), n("sec", { secret: true, updatedAt: 99 })];
    expect(recentNotes(list).map((x) => x.id)).toEqual(["pin", "new", "old"]);
    expect(recentNotes(list, 2)).toHaveLength(2);
  });
});
