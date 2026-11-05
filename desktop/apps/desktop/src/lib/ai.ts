/**
 * AI through OpenRouter, a port of Ai.kt with the same prompts: three title options, filing notes into
 * categories, and adding markdown to a note without changing a word. Only the one note is sent, only when asked.
 * The key is typed in Settings and kept in the OS keyring.
 */
import { fetch as http } from "@tauri-apps/plugin-http";
import { firstDifference } from "@scratchy/core";
import { secrets } from "./secrets";
import { imageModel, textModel } from "./settings";

const URL_CHAT = "https://openrouter.ai/api/v1/chat/completions";

let key: string | null = null;
export async function aiKey(): Promise<string> { key ??= (await secrets.get("ai-key")) ?? ""; return key; }
export async function setAiKey(v: string) {
  key = v.trim();
  if (key) await secrets.set("ai-key", key); else await secrets.delete("ai-key");
}
export const aiConfigured = async () => (await aiKey()).length > 0;

type Content = string | ({ type: "text"; text: string } | { type: "image_url"; image_url: { url: string } })[];

async function chat(model: string, content: Content, maxTokens: number, temperature = 0.7): Promise<string> {
  const k = await aiKey();
  if (!k) throw new Error("Add your OpenRouter key in Settings");
  const r = await http(URL_CHAT, {
    method: "POST",
    headers: { authorization: `Bearer ${k}`, "content-type": "application/json", "x-title": "Scratchy" },
    // Thinking models can spend the whole budget reasoning and return no answer; none of this needs it.
    body: JSON.stringify({ model, messages: [{ role: "user", content }], max_tokens: maxTokens, temperature, reasoning: { enabled: false } }),
  });
  const text = await r.text();
  if (!r.ok) {
    let msg: string | undefined;
    try { msg = JSON.parse(text).error?.message; } catch { /* not JSON */ }
    throw new Error(r.status === 401 ? "The key was rejected. Check it in Settings."
      : r.status === 402 ? "Out of credits on OpenRouter."
      : r.status === 429 ? "Too many requests. Try again in a moment."
      : msg ? msg.slice(0, 140) : `HTTP ${r.status}`);
  }
  const choice = JSON.parse(text).choices?.[0];
  const c = choice?.message?.content;
  const out = typeof c === "string" ? c : Array.isArray(c) ? c.map((p: { text?: string }) => p.text ?? "").join("") : "";
  if (!out.trim()) {
    throw new Error(choice?.finish_reason === "length"
      ? "The model ran out of tokens while thinking. Pick a non-thinking model in Settings."
      : "The model sent an empty reply. Try another model in Settings.");
  }
  return out;
}

/** Sends a tiny message to check the key and model. */
export async function ping(): Promise<string> {
  try { return `Works. Model said: ${(await chat(textModel(), "Reply with the single word: ok", 20)).trim().slice(0, 40)}`; }
  catch (e) { return (e as Error).message || "Failed"; }
}

/** Three titles, shortest first, from the text and/or the first image (`image` is a data: URL). */
export async function suggestTitles(body: string, image: string | null): Promise<string[]> {
  const prompt = "You name notes. Give 3 title options for this note, from shortest to most detailed: " +
    "(1) 3 to 5 words, (2) 6 to 9 words, (3) 10 to 16 words that capture the main subject plus the key " +
    "specifics in the note (who, what, numbers, dates, places, decisions). Be concrete, never generic: " +
    "\"Meeting notes\" is too vague. No quotes and no trailing punctuation. Write in the same language " +
    "as the note text; for example Arabic text gets Arabic titles. If an image is attached, use what " +
    "it shows, including any visible text, the subject and the setting. " +
    "Reply with only a JSON array of 3 strings.";
  const text = body.trim() ? `${prompt}\n\nNote text:\n${body.slice(0, 4000)}` : prompt;
  const content: Content = image ? [{ type: "text", text }, { type: "image_url", image_url: { url: image } }] : text;
  const titles = parseTitles(await chat(image ? imageModel() : textModel(), content, 1500));
  if (!titles.length) throw new Error("No suggestions came back");
  return titles.slice(0, 3);
}

/** Accepts a JSON array, or falls back to one title per line. */
function parseTitles(reply: string): string[] {
  const a = reply.indexOf("["), b = reply.lastIndexOf("]");
  let raw: string[] | null = null;
  if (a >= 0 && b > a) { try { raw = (JSON.parse(reply.slice(a, b + 1)) as unknown[]).map(String); } catch { raw = null; } }
  const list = (raw ?? reply.split("\n"))
    .map((t) => t.trim().replace(/^[-*• ]+/, "").replace(/^["' .,]+|["' .,]+$/g, ""))
    .filter((t) => t && t.length <= 140 && t.toLowerCase() !== "null");
  return [...new Set(list)];
}

/**
 * A category for each text: an existing one when it fits, otherwise a short new name. Keys are indexes into `texts`.
 */
export async function categorize(texts: string[], existing: string[]): Promise<Map<number, string>> {
  const prompt = "You file notes into categories. Existing categories: " +
    (existing.length ? existing.map((c) => `"${c}"`).join(", ") : "none yet") + ". " +
    "For each numbered note below choose the best existing category. If no existing category fits, " +
    "make a new one: a short, general name of 1 to 2 words (like \"Work\", \"Shopping\", \"Ideas\", " +
    "\"Health\"), never a sentence. A note that is unlike the others still gets a category of its own " +
    "with a fitting new name. Reuse a category for similar notes instead of making near-duplicates. " +
    "Write new names in the same language as the note. " +
    "Reply with only a JSON object that maps each note number to its category, like {\"1\":\"Work\"}.";
  const body = texts.map((t, i) => `[${i + 1}] ${t.slice(0, 300).replace(/\n/g, " ")}`).join("\n");
  const reply = await chat(textModel(), `${prompt}\n\nNotes:\n${body}`, 2000);
  const a = reply.indexOf("{"), b = reply.lastIndexOf("}");
  if (a < 0 || b <= a) throw new Error("No categories came back");
  const o = JSON.parse(reply.slice(a, b + 1)) as Record<string, unknown>;
  const out = new Map<number, string>();
  for (const [k, v] of Object.entries(o)) { const i = parseInt(k.trim(), 10); if (!Number.isNaN(i)) out.set(i - 1, String(v ?? "")); }
  if (!out.size) throw new Error("No categories came back");
  return out;
}

/**
 * Adds markdown structure to `md` without touching its words. The answer is only accepted when it has exactly
 * the same words in the same order; up to three tries.
 */
export async function formatMarkdown(md: string): Promise<string> {
  let extra = "";
  let diff: [string, string] | null = null;
  let unchanged = false;
  for (let attempt = 0; attempt < 3; attempt++) {
    const prompt = "You are a Markdown formatter. Add Markdown formatting to the text below WITHOUT changing its words.\n" +
      "You MUST add visible formatting. Returning the text unchanged is a failure.\n" +
      "Hard rule: keep every word, number, name and symbol exactly as written, in the same order and the same language " +
      "(Arabic stays Arabic, English words stay English). Never add, remove, translate, correct, summarize or reorder anything, " +
      "and never invent a title or intro. Do not fix typos.\n" +
      "What to do with the structure that is already in the text:\n" +
      "- Each line is its own item. Turn lines that are items of a list into '- ' bullets (or '1. ' for steps), and to-do items into '- [ ] '.\n" +
      "- Turn a short title-like line (usually the first one) into a '# ' heading, and the name of a section into '## '.\n" +
      "- Put **bold** on the key terms: technologies, product and company names, English terms, numbers and labels before a colon.\n" +
      "- Use `code` for commands and code, '> ' for quotes, and a Markdown table when the text is clearly tabular.\n" +
      "- Put a blank line between groups of lines that belong together.\n" +
      "- Keep any Markdown that is already there.\n" +
      `Reply with only the Markdown, with no code fence around it.\n${extra}\nText:\n${md}`;
    const reply = await chat(textModel(), prompt, 4000, 0.2 + attempt * 0.1);
    const out = reply.trim().replace(/^```(markdown|md)?/, "").replace(/```$/, "").replace(/^\n+|\n+$/g, "");
    diff = firstDifference(md, out);
    unchanged = out.trim() === md.trim();
    if (out.trim() && diff == null && !unchanged) return out;
    extra = unchanged
      ? "Your last answer was the same text with no formatting. This time you MUST add Markdown: bullets, a heading and **bold** key terms.\n"
      : "Your last answer changed, added or dropped some words. This time keep every single word exactly as written.\n";
  }
  throw new Error(unchanged ? "The AI found nothing to format in this note. A stronger model under Settings → AI may do better."
    : `The AI changed the text (“${diff?.[0]}” became “${diff?.[1]}”), so your note was left as it was.`);
}
