import { mount } from "svelte";
import Card from "./card/Card.svelte";
import Main from "./main/Main.svelte";
import { invoke } from "@tauri-apps/api/core";

// Page errors go to the app's output, where they can be seen.
const report = (line: string) => { invoke("log", { line }).catch(() => {}); };
addEventListener("error", (e) => report(`${e.message} at ${e.filename}:${e.lineno}`));
addEventListener("unhandledrejection", (e) => report(`unhandled: ${e.reason?.stack ?? e.reason}`));

const which = new URLSearchParams(location.search).get("w");
const target = document.getElementById("app")!;
export default which === "main" ? mount(Main, { target }) : mount(Card, { target });
