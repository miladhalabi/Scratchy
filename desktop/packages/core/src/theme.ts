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

export const themeNames = ["blue", "orange", "teal", "green", "purple"] as const;
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
