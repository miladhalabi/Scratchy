import { describe, expect, it } from "vitest";
import { diff3, goneSide, noteSide, resolve, staleRelay } from "../src/merge";
import type { Note } from "../src/model";

// A port of MergeTest.kt: the same 21 cases, so Android and desktop merge identically.
const note = (body: string, o: Partial<Note> = {}): Note => ({
  id: "n1", title: "T", body, createdAt: 1, updatedAt: 100, pinned: false, images: [], secret: false, rev: 1, ...o,
});
const side = noteSide;
const gone = goneSide;

describe("diff3", () => {
  it("edits on different lines merge", () => {
    expect(diff3(["a", "b", "c", "d"], ["A", "b", "c", "d"], ["a", "b", "c", "D"])).toEqual(["A", "b", "c", "D"]);
  });
  it("same line changed differently clashes", () => {
    expect(diff3(["a", "b"], ["a", "X"], ["a", "Y"])).toBeNull();
  });
  it("same change on both is fine", () => {
    expect(diff3(["a", "b"], ["a", "X"], ["a", "X"])).toEqual(["a", "X"]);
  });
  it("insertions at opposite ends", () => {
    expect(diff3(["m"], ["top", "m"], ["m", "bottom"])).toEqual(["top", "m", "bottom"]);
  });
  it("insert at the same spot clashes", () => {
    expect(diff3(["a", "b"], ["a", "X", "b"], ["a", "Y", "b"])).toBeNull();
  });
  it("one side deletes, the other edits elsewhere", () => {
    expect(diff3(["a", "b", "c"], ["a", "c"], ["a", "b", "C"])).toEqual(["a", "C"]);
  });
});

describe("resolve", () => {
  it("only one side changed takes that side", () => {
    const r = resolve(note("one", { rev: 1 }), side(note("one", { rev: 1 })), side(note("two", { rev: 2 })))!;
    expect(r.note!.body).toBe("two");
    expect(r.conflictCopy).toBeUndefined();
  });

  it("both edited different lines merge without a copy", () => {
    const r = resolve(
      note("a\nb\nc"), side(note("A\nb\nc", { rev: 2, updatedAt: 200 })), side(note("a\nb\nC", { rev: 2, updatedAt: 210 })),
    )!;
    expect(r.note!.body).toBe("A\nb\nC");
    expect(r.conflictCopy).toBeUndefined();
    expect(r.note!.rev).toBe(3);
  });

  it("clashing edits keep the newer and make a conflict copy", () => {
    const base = note("a\nb");
    const ours = note("a\nOURS", { rev: 2, updatedAt: 200 });
    const theirs = note("a\nTHEIRS", { rev: 3, updatedAt: 150 });      // fewer minutes but higher rev wins
    const r = resolve(base, side(ours), side(theirs), () => "copy1")!;
    expect(r.note!.body).toBe("a\nTHEIRS");
    expect(r.conflictCopy!.body).toBe("a\nOURS");
    expect(r.conflictCopy!.id).toBe("copy1");
    expect(r.conflictCopy!.title.endsWith("(conflict)")).toBe(true);
  });

  it("clock skew does not beat a higher revision", () => {
    // Device B's clock is an hour slow, but it edited later (higher rev): its edit must win.
    const base = note("x");
    const a = note("from A", { rev: 2, updatedAt: 10_000_000 });
    const b = note("from B", { rev: 3, updatedAt: 10_000_000 - 3_600_000 });
    expect(resolve(base, side(a), side(b))!.note!.body).toBe("from B");
  });

  it("without a base nothing is lost", () => {
    const r = resolve(null, side(note("mine", { rev: 2 })), side(note("theirs", { rev: 3 })))!;
    expect(r.note!.body).toBe("theirs");
    expect(r.conflictCopy!.body).toBe("mine");
  });

  it("images are never lost on a conflict", () => {
    const base = note("a", { images: ["i1"] });
    const ours = note("OURS", { rev: 2, images: ["i1", "i2"] });
    const theirs = note("THEIRS", { rev: 3, images: ["i1", "i3"] });
    const r = resolve(base, side(ours), side(theirs))!;
    expect(new Set(r.note!.images)).toEqual(new Set(["i1", "i2", "i3"]));
    expect(r.conflictCopy!.images).toEqual([]);          // the copy must not share files that get deleted with it
  });

  it("merged images respect removals", () => {
    const base = note("t\nm", { images: ["i1", "i2"] });
    const ours = note("T\nm", { rev: 2, images: ["i1"] });                        // removed i2
    const theirs = note("t\nM", { rev: 2, images: ["i1", "i2", "i3"] });          // added i3
    const r = resolve(base, side(ours), side(theirs))!;
    expect(new Set(r.note!.images)).toEqual(new Set(["i1", "i3"]));
  });

  it("merging twice changes nothing", () => {
    const base = note("a\nb\nc");
    const first = resolve(base, side(note("A\nb\nc", { rev: 2 })), side(note("a\nb\nC", { rev: 2 })))!.note!;
    const again = resolve(first, side(first), side(first))!;
    expect(again.note!.body).toBe(first.body);
    expect(again.note!.rev).toBe(first.rev);
    expect(again.conflictCopy).toBeUndefined();
  });
});

describe("deletions", () => {
  it("delete beats an older edit", () => {
    const base = note("x", { rev: 1, updatedAt: 100 });
    expect(resolve(base, gone(500), side(note("x", { rev: 1, updatedAt: 100 })))!.note).toBeNull();
  });

  it("an edit after the delete brings the note back", () => {
    const base = note("x", { rev: 1, updatedAt: 100 });
    expect(resolve(base, gone(500), side(note("edited", { rev: 2, updatedAt: 900 })))!.note!.body).toBe("edited");
  });

  it("remote delete vs local edit, both ways", () => {
    const base = note("x", { rev: 1, updatedAt: 100 });
    expect(resolve(base, side(note("x", { rev: 1, updatedAt: 100 })), gone(500))!.note).toBeNull();
    expect(resolve(base, side(note("edited", { rev: 2, updatedAt: 900 })), gone(500))!.note!.body).toBe("edited");
  });

  it("missing on one side is copied over", () => {
    expect(resolve(null, side(note("x")), null)!.note!.body).toBe("x");
    expect(resolve(null, null, side(note("x")))!.note!.body).toBe("x");
  });
});

describe("relay", () => {
  it("a follow-up edit after adopting theirs is not a conflict", () => {
    const v0 = note("hello", { rev: 1 });
    const fromOther = note("hello world", { rev: 2 });
    const myEdit = note("hello world!", { rev: 3 });
    // With the stale base (v0) both sides look changed on the same line: a false clash.
    expect(resolve(v0, side(myEdit), side(fromOther))!.conflictCopy).toBeDefined();
    // With the base updated to what I adopted, it is simply my edit.
    const r = resolve(fromOther, side(myEdit), side(fromOther))!;
    expect(r.conflictCopy).toBeUndefined();
    expect(r.note!.body).toBe("hello world!");
  });

  it("a late older relay is ignored", () => {
    expect(staleRelay(note("new", { rev: 5 }), note("old", { rev: 4 }))).toBe(true);
  });

  it("same or newer relay is not ignored", () => {
    expect(staleRelay(note("x", { rev: 5 }), note("y", { rev: 5 }))).toBe(false);
    expect(staleRelay(note("x", { rev: 5 }), note("y", { rev: 6 }))).toBe(false);
    expect(staleRelay(null, note("y", { rev: 1 }))).toBe(false);
    expect(staleRelay(note("x", { rev: 5 }), null)).toBe(false);          // a deletion is not "older"
  });
});
