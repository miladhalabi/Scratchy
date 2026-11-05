/**
 * The app's colors as CSS variables, following the system's light or dark mode. "System" takes the desktop's own
 * accent (the Omarchy theme's, or GNOME's), the way the phone takes its wallpaper's; without one it is the phone's
 * default blue. A change of theme or setting is picked up the next time the window is used.
 */
import { invoke } from "@tauri-apps/api/core";
import { themeColors, type Colors } from "@scratchy/core";
import { watchSettings } from "./settings";

const dark = matchMedia("(prefers-color-scheme: dark)");

export function themeVars(c: Colors): Record<string, string> {
  return {
    "--accent": c.accent, "--on-accent": c.onAccent, "--accent-inverse": c.accentInverse, "--wash": c.accentContainer, "--background": c.background,
    "--surface": c.surface, "--floating": c.floating, "--text": c.text, "--secondary": c.secondary,
    "--hairline": c.hairline, "--track": c.track, "--danger": c.danger, "--success": c.success,
  };
}

let systemAccent: string | null = null;
export const getSystemAccent = () => systemAccent;

/** Sets the variables on the page and keeps them in step; `fn` also gets the colors after every change. */
export function applyTheme(fn?: (c: Colors) => void) {
  let name = "system";
  const set = () => {
    const c = themeColors(name, dark.matches, systemAccent);
    for (const [k, v] of Object.entries(themeVars(c))) document.documentElement.style.setProperty(k, v);
    document.documentElement.style.colorScheme = dark.matches ? "dark" : "light";
    fn?.(c);
  };
  const refresh = async () => {
    const a = await invoke<string | null>("system_accent").catch(() => null);
    if (a !== systemAccent) { systemAccent = a; set(); }
  };
  set();
  dark.addEventListener("change", set);
  void watchSettings((s) => { name = s.color; set(); });
  void refresh();
  addEventListener("focus", refresh);
  return refresh;
}
