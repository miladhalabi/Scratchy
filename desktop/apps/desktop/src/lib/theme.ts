/** The app's colors as CSS variables, following the system's light or dark mode. */
import { fixedColors, type Colors } from "@scratchy/core";

const dark = matchMedia("(prefers-color-scheme: dark)");

export function themeVars(c: Colors): Record<string, string> {
  return {
    "--accent": c.accent, "--on-accent": c.onAccent, "--wash": c.accentContainer, "--background": c.background,
    "--surface": c.surface, "--floating": c.floating, "--text": c.text, "--secondary": c.secondary,
    "--hairline": c.hairline, "--track": c.track, "--danger": c.danger, "--success": c.success,
  };
}

/** Sets the variables on the page and keeps them in step with the system. */
export function applyTheme(name = "teal") {
  const set = () => {
    for (const [k, v] of Object.entries(themeVars(fixedColors(name, dark.matches)))) document.documentElement.style.setProperty(k, v);
    document.documentElement.style.colorScheme = dark.matches ? "dark" : "light";
  };
  set();
  dark.addEventListener("change", set);
}
