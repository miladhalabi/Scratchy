import { describe, expect, it } from "vitest";
import { canonical, inlineToMarkdown, numberOf, parse, parseInline, toMarkdown, type Line } from "../src/richtext";

// What RichText.kt writes back after reading a note, worked out from its code: the desktop must write the same,
// so a note edited on one device reads identically on the other.
describe("canonical form, as the phone writes it", () => {
  const cases: [string, string][] = [
    ["plain\n\nlines", "plain\n\nlines"],
    ["* a\n+ b", "- a\n- b"],
    ["1. a\n1. b\n7. c", "1. a\n2. b\n3. c"],
    ["1. a\n  - nested\n1. b", "1. a\n  - nested\n2. b"],
    ["- [X] done\n* [ ] todo", "- [x] done\n- [ ] todo"],
    ["\t- tab indent", "  - tab indent"],
    ["        - deep", "      - deep"],
    ["# **Bold** title", "# Bold title"],
    ["## ***both***", "## *both*"],
    ["__strong__ and _soft_", "**strong** and *soft*"],
    ["a*b", "a\\*b"],
    ["snake_case stays", "snake_case stays"],
    ["_lead", "\\_lead"],
    ["see [site](https://x.dev) now", "see [site](https://x.dev) now"],
    ["**bold **rest", "\\*\\*bold \\*\\*rest"],
    [">quote", "> quote"],
    ["\\# not a heading", "\\# not a heading"],
    ["\\- not a list", "\\- not a list"],
    ["3\\. not a number", "3\\. not a number"],
    ["---", "---"],
    ["| a | b |", "| a | b |"],
    ["|a|b|\n|-|-|", "|a|b|\n|-|-|"],
    ["| a | b |\n|:---|---:|\n| 1 |\n| x \\| y | z |", "| a | b |\n| --- | --- |\n| 1 |  |\n| x \\| y | z |"],
    ["```js\nlet a = 1\n\n```", "```js\nlet a = 1\n\n```"],
    ["```\nopen fence", "```\nopen fence\n```"],
    ["`co*de` and ~~gone~~", "`co*de` and ~~gone~~"],
  ];
  for (const [md, want] of cases) it(JSON.stringify(md), () => expect(canonical(md)).toBe(want));
});

describe("canonical form is stable", () => {
  const corpus = [
    "# Shopping\n- [ ] milk\n- [x] **eggs**\n  1. one\n  2. two\n> note this\n\n| Name | Age |\n| --- | --- |\n| Bob | 4 |\n```\ncode *raw*\n```",
    "mixed **bold *both* bold** ~~x~~ `y` [l](u) a_b _c_ \\* \\_",
    "اخر فترة **مهم** و *مائل*\n- بند",
    "trailing spaces   \n  leading spaces",
    "1) paren\n12. twelve",
  ];
  for (const md of corpus) it(md.slice(0, 30), () => expect(canonical(canonical(md))).toBe(canonical(md)));
});

describe("lines", () => {
  it("reads kinds, depth and marks", () => {
    const l = parse("## Hi\n  - [x] *done*\n> q");
    expect(l[0]).toEqual({ type: "heading", level: 2, runs: [{ text: "Hi" }] });
    expect(l[1]).toEqual({ type: "item", kind: "task", level: 1, checked: true, runs: [{ text: "done", italic: true }] });
    expect(l[2]).toEqual({ type: "quote", runs: [{ text: "q" }] });
  });
  it("nested marks become one run per change", () => {
    expect(parseInline("a **b *c* d** e")).toEqual([
      { text: "a " }, { text: "b ", bold: true }, { text: "c", bold: true, italic: true }, { text: " d", bold: true }, { text: " e" },
    ]);
  });
  it("writes marks back with the spaces outside", () => {
    expect(inlineToMarkdown([{ text: "hello ", bold: true }, { text: "world" }])).toBe("**hello** world");
  });
  it("numbers skip nested items and restart after a break", () => {
    const l: Line[] = parse("1. a\n  - x\n1. b\nplain\n1. c");
    expect([0, 2, 4].map((i) => numberOf(l, i))).toEqual([1, 2, 1]);
    expect(toMarkdown(l)).toBe("1. a\n  - x\n2. b\nplain\n1. c");
  });
});
