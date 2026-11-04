/** "16:40" today, "Yesterday", otherwise "Nov 3" (formatWhen in Note.kt). */
export function formatWhen(ms: number, now = new Date()): string {
  const then = new Date(ms);
  const day = (d: Date) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
  if (day(then) === day(now)) return then.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
  const y = new Date(now); y.setDate(y.getDate() - 1);
  if (day(then) === day(y)) return "Yesterday";
  return then.toLocaleDateString([], { month: "short", day: "numeric" });
}
