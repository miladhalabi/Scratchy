import { mount } from "svelte";
import "./app.css";
import { applyTheme } from "./lib/state";

// One page, three windows: the notes app, the edge bar and the floating card pick their screen by ?w=.
const which = new URLSearchParams(location.search).get("w") ?? "main";
if (which !== "main") document.documentElement.classList.add("overlay");
applyTheme();
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => applyTheme());

const screens = {
  main: () => import("./App.svelte"),
  bar: () => import("./Bar.svelte"),
  card: () => import("./Card.svelte"),
  catcher: () => import("./Catcher.svelte"),
} as const;

const load = screens[which as keyof typeof screens] ?? screens.main;
load().then((m) => mount(m.default, { target: document.getElementById("app")! }));
