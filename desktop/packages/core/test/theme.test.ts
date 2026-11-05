import { describe, expect, it } from "vitest";
import { contrast, fixedColors, onColor, themeNames } from "../src";

// A port of ThemeTest.kt: every fixed theme must be readable in light and dark mode.
const each = (check: (name: string, dark: boolean, c: ReturnType<typeof fixedColors>) => void) => {
  for (const name of themeNames) for (const dark of [false, true]) check(name, dark, fixedColors(name, dark));
};

describe("themes", () => {
  it("body text is readable", () => each((n, d, c) => {
    expect(contrast(c.text, c.background), `${n} dark=${d} bg`).toBeGreaterThanOrEqual(7);
    expect(contrast(c.text, c.surface), `${n} dark=${d} surface`).toBeGreaterThanOrEqual(7);
    expect(contrast(c.text, c.floating), `${n} dark=${d} floating`).toBeGreaterThanOrEqual(7);
  }));
  it("secondary text is readable", () => each((n, d, c) => {
    expect(contrast(c.secondary, c.surface), `${n} dark=${d}`).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.secondary, c.background), `${n} dark=${d}`).toBeGreaterThanOrEqual(4.5);
  }));
  it("accent works as text and under button labels", () => each((n, d, c) => {
    expect(contrast(c.accent, c.surface), `${n} dark=${d} surface`).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.accent, c.background), `${n} dark=${d} background`).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.onAccent, c.accent), `${n} dark=${d} onAccent`).toBeGreaterThanOrEqual(4.5);
  }));
  it("onColor picks the better of white and black", () => {
    expect(onColor("#1a5fd0")).toBe("#ffffff");
    expect(onColor("#ffab40")).toBe("#101114");
  });
});

describe("system accent", () => {
  it("is made readable on both backgrounds, and a readable one is kept as it is", async () => {
    const { readableAccent, themeColors, contrast } = await import("../src");
    for (const dark of [false, true]) {
      const a = readableAccent("#e68e0d", dark);
      expect(contrast(a, dark ? "#0f1013" : "#ffffff")).toBeGreaterThanOrEqual(4.5);
    }
    expect(readableAccent("#1a5fd0", false)).toBe("#1a5fd0");
    expect(themeColors("system", true, null).accent).toBe("#8ab4ff");          // no system color: the phone's blue
    expect(themeColors("teal", false, "#e68e0d").accent).toBe("#00796b");     // a fixed choice ignores it
  });
});
