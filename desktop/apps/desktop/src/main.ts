import { mount } from "svelte";
import Card from "./card/Card.svelte";
import { invoke } from "@tauri-apps/api/core";

// Page errors go to the app's output, where they can be seen.
const report = (line: string) => { invoke("log", { line }).catch(() => {}); };
addEventListener("error", (e) => report(`${e.message} at ${e.filename}:${e.lineno}`));
addEventListener("unhandledrejection", (e) => report(`unhandled: ${e.reason?.stack ?? e.reason}`));

const which = new URLSearchParams(location.search).get("w");
// Only the card for now; the notes window comes next.
export default which === "card" || !which ? mount(Card, { target: document.getElementById("app")! }) : null;
