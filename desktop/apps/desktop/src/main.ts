import { mount } from "svelte";
import Card from "./card/Card.svelte";

const which = new URLSearchParams(location.search).get("w");
// Only the card for now; the notes window comes next.
export default which === "card" || !which ? mount(Card, { target: document.getElementById("app")! }) : null;
