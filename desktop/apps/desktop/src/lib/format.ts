/** "17:16" today, "Yesterday", otherwise "Oct 23". Same rule as the phone. */
export function formatWhen(ms: number, now = new Date()): string {
  const d = new Date(ms);
  const sameDay = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (sameDay(d, now)) return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
  const y = new Date(now); y.setDate(y.getDate() - 1);
  if (sameDay(d, y)) return "Yesterday";
  return d.toLocaleDateString([], { month: "short", day: "numeric" });
}

/** Title, or the first non-blank body line when there is no title. */
export const heading = (n: { title: string; body: string }) =>
  n.title.trim() || n.body.split("\n").find((l) => l.trim())?.trim() || "";

/** Text shown under the heading in lists. */
export function preview(n: { title: string; body: string }): string {
  if (n.title.trim()) return n.body.trim();
  const lines = n.body.split("\n");
  const first = lines.findIndex((l) => l.trim());
  return first < 0 ? "" : lines.slice(first + 1).join("\n").trim();
}
