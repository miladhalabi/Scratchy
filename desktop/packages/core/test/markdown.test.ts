import { describe, expect, it } from "vitest";
import {
  checkIndex, firstDifference, legacy, letters, listItem, nextItem, noteHeading, plain, toggleTaskLine, toggled, words,
} from "../src/markdown";
import { noteFromJson, noteToJson } from "../src/format";
import { newNote } from "../src/model";

// A port of MarkdownTest.kt, so both apps read lists and compare texts the same way.
const kind = (line: string) => listItem(line)?.kind;

describe("markdown", () => {
  it("recognises list lines", () => {
    expect(kind("- [ ] milk")).toBe("task");
    expect(kind("  * [x] done")).toBe("task");
    expect(kind("- milk")).toBe("bullet");
    expect(kind("+ milk")).toBe("bullet");
    expect(kind("12. twelve")).toBe("number");
    expect(kind("3) three")).toBe("number");
  });
  it("not lists", () => {
    for (const l of ["-5 degrees", "**bold** start", "*italic* start", "2024.5 was a year", "-", "plain text"]) expect(kind(l)).toBeUndefined();
  });
  it("prefix length covers the marker", () => {
    expect(listItem("- [ ] x")!.length).toBe(6);
    expect(listItem("- x")!.length).toBe(2);
    expect(listItem("  1. x")!.length).toBe(5);
  });
  it("next item continues the list", () => {
    expect(nextItem(listItem("- [x] done")!)).toBe("- [ ] ");
    expect(nextItem(listItem("  - nested")!)).toBe("  - ");
    expect(nextItem(listItem("* a")!)).toBe("* ");
    expect(nextItem(listItem("3. c")!)).toBe("4. ");
  });
  it("toggling a checkbox", () => {
    expect(toggled("- [ ] milk")).toBe("- [x] milk");
    expect(toggled("- [x] milk")).toBe("- [ ] milk");
    expect(toggled("  * [X] a")).toBe("  * [ ] a");
    expect(toggled("plain")).toBe("plain");
    expect(checkIndex("- [ ] a")).toBe(3);
  });
  it("checklist button cycles a line", () => {
    expect(toggleTaskLine("milk")).toBe("- [ ] milk");
    expect(toggleTaskLine("  milk")).toBe("  - [ ] milk");
    expect(toggleTaskLine("- [ ] milk")).toBe("milk");
    expect(toggleTaskLine("- milk")).toBe("- [ ] milk");
    expect(toggleTaskLine("1. first")).toBe("- [ ] first");
  });
  it("old checkbox notes become markdown", () => {
    expect(legacy("a\n☐ one\n☑ two\nb")).toBe("a\n- [ ] one\n- [x] two\nb");
    expect(legacy("- [ ] one")).toBe("- [ ] one");
    expect(legacy("x ☐ y")).toBe("x ☐ y");
    expect(legacy(legacy("☐ a"))).toBe(legacy("☐ a"));
  });
  it("plain text for headings and pickers", () => {
    expect(plain("## Weekly plan")).toBe("Weekly plan");
    expect(plain("- [ ] Buy milk")).toBe("Buy milk");
    expect(plain("1. Read the **docs** now")).toBe("Read the docs now");
    expect(plain("see [site](https://x.dev)")).toBe("see site");
    expect(plain("run `make`")).toBe("run make");
    expect(plain("> quoted")).toBe("quoted");
  });
  it("note heading skips the markdown symbols", () => {
    expect(noteHeading({ ...newNote("1", 1), body: "\n# My big idea\nmore text" })).toBe("My big idea");
    expect(noteHeading({ ...newNote("1", 1), title: "Titled", body: "# other" })).toBe("Titled");
  });
  it("words ignore markdown but not wording", () => {
    const p = "Shopping list\nmilk, eggs\nCall Sam at 5";
    const rich = "# Shopping list\n\n- [ ] milk\n- [ ] **eggs**\n1. Call [Sam](https://x.dev) at 5";
    expect(words(rich)).toEqual(words(p));
    expect(words(rich.replace("eggs", "egg"))).not.toEqual(words(p));
    expect(words(`${rich}\nExtra line`)).not.toEqual(words(p));
  });
  it("a table keeps the same words", () => {
    expect(words("| Name | Age |\n| --- | --- |\n| Bob | 4 |")).toEqual(words("Name Age\nBob 4"));
  });
  it("arabic prefixes may be spaced differently but letters must match", () => {
    const a = "اخر فترة وشتغلنا على مشاريع IoT والاتصالات";
    expect(letters("# اخر فترة\n- و شتغلنا على مشاريع **IoT** والاتصالات")).toBe(letters(a));
    expect(firstDifference(a, "اخر فترة وشتغلنا على مشاريع IoT والاتصالات")).toBeNull();
    expect(firstDifference(a, "اخر فترة وشتغلنا على مشاريع IoT والاتصالات.ا")).not.toBeNull();
  });
  it("diacritics count as changes", () => {
    expect(firstDifference("كتب", "كَتَبَ")).not.toBeNull();
  });
  it("first difference shows where", () => {
    const d = firstDifference("one two three", "one two tree")!;
    expect(d[0].includes("three") || d[0].includes("thr")).toBe(true);
  });
});

describe("note format", () => {
  it("reads old checkbox bodies as markdown and keeps the category", () => {
    const n = noteFromJson({ id: "a", body: "☐ milk", category: "Home" });
    expect(n.body).toBe("- [ ] milk");
    expect(n.category).toBe("Home");
  });
  it("never writes a category for a secret note", () => {
    expect(noteToJson({ ...newNote("a", 1), secret: true, category: "Work" }).category).toBe("");
    expect(noteToJson({ ...newNote("a", 1), category: "Work" }).category).toBe("Work");
  });
});
