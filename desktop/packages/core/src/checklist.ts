/** Checklist lines are plain text starting with ☐ or ☑, the same as on the phone. All functions are pure. */
export const BOX_OFF = "☐";
export const BOX_ON = "☑";

export interface Edit { body: string; cursor: number }

const lineStart = (body: string, pos: number) => body.lastIndexOf("\n", pos - 1) + 1;
const hasBox = (body: string, start: number) => body.startsWith(BOX_OFF + " ", start) || body.startsWith(BOX_ON + " ", start);

/** Adds or removes the checkbox at the start of the line with the cursor. */
export function toggleChecklist(body: string, pos: number): Edit {
  const start = lineStart(body, pos);
  if (hasBox(body, start)) return { body: body.slice(0, start) + body.slice(start + 2), cursor: Math.max(start, pos - 2) };
  return { body: body.slice(0, start) + BOX_OFF + " " + body.slice(start), cursor: pos + 2 };
}

/**
 * Enter on a checklist line starts a new item; Enter on an empty item ends the list.
 * Returns null when the line isn't a checklist line (let Enter behave normally).
 */
export function continueChecklist(body: string, pos: number): Edit | null {
  const start = lineStart(body, pos);
  const line = body.slice(start, pos);
  if (!line.startsWith(BOX_OFF + " ") && !line.startsWith(BOX_ON + " ")) return null;
  if (line.length === 2) return { body: body.slice(0, start) + body.slice(pos), cursor: start };
  return { body: body.slice(0, pos) + "\n" + BOX_OFF + " " + body.slice(pos), cursor: pos + 3 };
}

/** A click on the box at the start of a line flips it. Returns null when the click wasn't on a box. */
export function flipBox(body: string, pos: number): string | null {
  const start = lineStart(body, pos);
  const c = body[start];
  if (pos <= start + 1 && (c === BOX_OFF || c === BOX_ON)) return body.slice(0, start) + (c === BOX_OFF ? BOX_ON : BOX_OFF) + body.slice(start + 1);
  return null;
}
