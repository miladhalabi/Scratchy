import { describe, expect, it } from "vitest";
import { continueChecklist, flipBox, toggleChecklist } from "../src";

describe("checklist", () => {
  it("toggle adds a box to the line, and removes it again", () => {
    const a = toggleChecklist("milk\neggs", 6);
    expect(a.body).toBe("milk\n☐ eggs"); expect(a.cursor).toBe(8);
    const b = toggleChecklist(a.body, a.cursor);
    expect(b.body).toBe("milk\neggs"); expect(b.cursor).toBe(6);
  });
  it("Enter on an item starts the next one", () => {
    const e = continueChecklist("☐ milk", 6)!;
    expect(e.body).toBe("☐ milk\n☐ "); expect(e.cursor).toBe(9);
  });
  it("Enter on an empty item ends the list", () => {
    const e = continueChecklist("☐ milk\n☐ ", 9)!;
    expect(e.body).toBe("☐ milk\n"); expect(e.cursor).toBe(7);
  });
  it("Enter on a normal line is left alone", () => {
    expect(continueChecklist("plain", 5)).toBeNull();
  });
  it("clicking a box flips it, clicking text does not", () => {
    expect(flipBox("☐ milk", 0)).toBe("☑ milk");
    expect(flipBox("☑ milk", 1)).toBe("☐ milk");
    expect(flipBox("☐ milk", 4)).toBeNull();
  });
});
