/** Colors, ported from Ui.kt (Themes + Contrast) so desktop and phone use the same fixed palette. */

export interface Colors {
  accent: string;
  /** Text and icons placed on top of `accent` (white or near-black, whichever reads better). */
  onAccent: string;
  /** The accent as it would look in the opposite light/dark mode: for text on the inverse surface (undo bar). */
  accentInverse: string;
  /** A see-through wash of the accent for chips and highlights. */
  accentContainer: string;
  background: string;
  surface: string;
  /** Fully opaque so text behind the floating card never shows through. */
  floating: string;
  text: string;
  secondary: string;
  hairline: string;
  /** Background of sliders, switches and segmented controls. */
  track: string;
  danger: string;
  success: string;
}

const rgb = (hex: string): [number, number, number] => {
  const n = parseInt(hex.slice(1, 7), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
};
const lin = (c: number) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
export const luminance = (hex: string) => { const [r, g, b] = rgb(hex); return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b); };
export const contrast = (a: string, b: string) => {
  const l1 = luminance(a), l2 = luminance(b);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
};
/** White or near-black, whichever stands out more against `bg`. */
export const onColor = (bg: string) => (contrast("#ffffff", bg) >= contrast("#101114", bg) ? "#ffffff" : "#101114");

export const themeNames = ["system", "orange", "blue", "teal", "green", "purple"] as const;
export type ThemeName = (typeof themeNames)[number];

/** Accent for a fixed theme: [light mode, dark mode]. */
export function accentOf(name: string): [string, string] {
  switch (name) {
    case "orange": return ["#a84c00", "#ffab40"];
    case "teal": return ["#00796b", "#4dd0c0"];
    case "green": return ["#2e7d32", "#7fd18b"];
    case "purple": return ["#6b4edd", "#b9a6ff"];
    default: return ["#1a5fd0", "#8ab4ff"];
  }
}

/** The calm neutral set, with the chosen accent. */
export function fixedColors(name: string, dark: boolean): Colors {
  const [aLight, aDark] = accentOf(name);
  const accent = dark ? aDark : aLight;
  return {
    accent, onAccent: onColor(accent), accentInverse: dark ? aLight : aDark, accentContainer: accent + "2e",
    background: dark ? "#0f1013" : "#f2f3f5",
    surface: dark ? "#1c1e22" : "#ffffff",
    floating: dark ? "#25272c" : "#ffffff",
    text: dark ? "#eceef1" : "#14161a",
    secondary: dark ? "#a4a9b2" : "#5b616b",
    hairline: dark ? "#ffffff33" : "#0000001f",
    track: dark ? "#30333a" : "#e3e5e9",
    danger: dark ? "#ff6b63" : "#d93025",
    success: dark ? "#5dd58b" : "#1e8e3e",
  };
}

const hex = (r: number, g: number, b: number) => "#" + [r, g, b].map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, "0")).join("");
const mix = (a: string, b: string, t: number) => { const x = rgb(a), y = rgb(b); return hex(x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t); };

/**
 * One system accent color (the desktop's version of the phone's wallpaper color) made readable in both modes:
 * darkened until text in it reads on white, lightened until it reads on the dark background.
 */
export function readableAccent(color: string, dark: boolean): string {
  const bg = dark ? "#0f1013" : "#ffffff", toward = dark ? "#ffffff" : "#000000";
  let c = color.toLowerCase();
  for (let i = 1; i <= 20 && contrast(c, bg) < 4.5; i++) c = mix(color, toward, i * 0.05);
  return c;
}

/** The neutral set with an accent from the system (`name` "system"), or a fixed accent. */
export function themeColors(name: string, dark: boolean, systemAccent: string | null): Colors {
  if (name !== "system" || !systemAccent || !/^#[0-9a-f]{6}$/i.test(systemAccent)) return fixedColors(name === "system" ? "blue" : name, dark);
  const base = fixedColors("blue", dark);
  const accent = readableAccent(systemAccent, dark);
  return { ...base, accent, onAccent: onColor(accent), accentInverse: readableAccent(systemAccent, !dark), accentContainer: accent + "2e" };
}
