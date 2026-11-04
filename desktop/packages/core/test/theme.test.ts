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
