/**
 * Read-only check of the desktop <-> phone sync data. It never writes to Drive and never prints note text.
 *   1. Signs in with Google (opens your browser; the token is kept in desktop/.local/).
 *   2. Lists Drive's app-data folder: do we see the same files the phone made?
 *   3. If you type your vault password, derives the key, checks it against meta.json and tries to
 *      decrypt every note, reporting only counts.
 * Run:  pnpm check-drive
 */
import { createServer } from "node:http";
import { execFile } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  GoogleDrive, buildAuthUrl, createPkce, deriveKey, exchangeCode, fromBase64, open, openText, refreshAccessToken,
  sideFromJson, type OAuthClient, type Tokens,
} from "../src";

const root = resolve(import.meta.dirname, "../../..");
const env: Record<string, string> = {};
for (const line of readFileSync(resolve(root, ".env.local"), "utf8").split("\n")) {
  const m = line.match(/^([A-Z_]+)=(.*)$/); if (m) env[m[1]] = m[2].trim();
}
const client: OAuthClient = { clientId: env.GOOGLE_CLIENT_ID, clientSecret: env.GOOGLE_CLIENT_SECRET };
const tokenFile = resolve(root, ".local/google-token.json");

async function signIn(): Promise<Tokens> {
  const { verifier, challenge, state } = await createPkce();
  const server = createServer();
  await new Promise<void>((ok) => server.listen(0, "127.0.0.1", ok));
  const port = (server.address() as { port: number }).port;
  const redirect = `http://127.0.0.1:${port}/`;
  const url = buildAuthUrl(client, redirect, challenge, state);
  console.log("\nOpening your browser to sign in to Google. If nothing opens, paste this link:\n\n" + url + "\n");
  execFile("xdg-open", [url], () => {});
  const code = await new Promise<string>((ok, fail) => {
    server.on("request", (req, res) => {
      const q = new URL(req.url ?? "/", redirect).searchParams;
      res.setHeader("content-type", "text/html; charset=utf-8");
      if (q.get("state") !== state || !q.get("code")) { res.end("Sign-in failed. You can close this tab."); fail(new Error(q.get("error") ?? "bad redirect")); return; }
      res.end("<h3>Signed in to Scratchy. You can close this tab.</h3>");
      ok(q.get("code")!);
    });
  });
  server.close();
  const t = await exchangeCode(client, code, verifier, redirect);
  mkdirSync(resolve(root, ".local"), { recursive: true, mode: 0o700 });
  writeFileSync(tokenFile, JSON.stringify(t), { mode: 0o600 });
  return t;
}

let tokens: Tokens | null = existsSync(tokenFile) ? JSON.parse(readFileSync(tokenFile, "utf8")) : null;
const token = async () => {
  if (!tokens) tokens = await signIn();
  else if (Date.now() > tokens.expiresAt) {
    try { tokens = await refreshAccessToken(client, tokens.refreshToken!); writeFileSync(tokenFile, JSON.stringify(tokens), { mode: 0o600 }); }
    catch { tokens = await signIn(); }
  }
  return tokens.accessToken;
};

function askHidden(prompt: string): Promise<string> {
  return new Promise((ok) => {
    process.stdout.write(prompt);
    const stdin = process.stdin; let s = "";
    stdin.setRawMode?.(true); stdin.resume(); stdin.setEncoding("utf8");
    const onData = (ch: string) => {
      for (const c of ch) {
        if (c === "\r" || c === "\n") { stdin.setRawMode?.(false); stdin.pause(); stdin.off("data", onData); process.stdout.write("\n"); ok(s); return; }
        if (c === "\u0003") process.exit(130);
        if (c === "\u007f") s = s.slice(0, -1); else s += c;
      }
    };
    stdin.on("data", onData);
  });
}

const drive = new GoogleDrive(token);
const files = await drive.list();
const names = [...files.keys()];
const notes = names.filter((n) => /^n_.*\.enc$/.test(n)), imgs = names.filter((n) => n.startsWith("img_"));
console.log("\nDrive app folder, as the desktop sees it:");
console.log(`  meta.json:          ${files.has("meta.json") ? "yes" : "NO"}`);
console.log(`  note files (n_*):   ${notes.length}`);
console.log(`  image files:        ${imgs.length}`);
console.log(`  old notes.enc:      ${files.has("notes.enc") ? "yes (left from the migration)" : "no"}`);
if (!files.has("meta.json")) { console.log("\nNo meta.json: the desktop does NOT see the phone's data. (Different Google project or account.)"); process.exit(0); }

const meta = JSON.parse(new TextDecoder().decode(await drive.download(files.get("meta.json")!.id)));
console.log(`  layout:             ${meta.layout ?? 1}`);
const pw = await askHidden("\nType your vault password to test decryption (Enter to skip): ");
if (!pw) { console.log("Skipped. Visibility test passed: the desktop sees the same app folder."); process.exit(0); }

const key = await deriveKey(pw, fromBase64(meta.salt));
if ((await openText(key, fromBase64(meta.check))) !== "scratchy") { console.log("\nWrong password for this Drive (the check value did not decrypt)."); process.exit(1); }
console.log("\nPassword accepted: the key matches the one the phone derived.");
let ok = 0, deleted = 0, secret = 0, bad = 0;
for (const n of notes) {
  const plain = await open(key, await drive.download(files.get(n)!.id));
  if (!plain) { bad++; continue; }
  const side = sideFromJson(JSON.parse(new TextDecoder().decode(plain)));
  if (side.note) { ok++; if (side.note.secret) secret++; } else deleted++;
}
console.log(`Decrypted ${ok} notes (${secret} secret), ${deleted} deletion markers, ${bad} unreadable.`);
console.log(bad === 0 ? "\nINTEROP OK: the desktop can read everything the phone wrote." : "\nSome files could not be decrypted.");
