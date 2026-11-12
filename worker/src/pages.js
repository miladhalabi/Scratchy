// The public pages Google's OAuth consent screen links to: a home page, the privacy policy and the terms.
// The relay itself lives under /v1/; these are plain HTML and touch nothing else.

const CONTACT = "halabimilad@gmail.com";
const UPDATED = "12 November 2026";

const page = (title, body) => `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>
  :root { color-scheme: light dark; --text: #1c1b1f; --muted: #5f5d66; --bg: #fbfaff; --accent: #0b7a75; }
  @media (prefers-color-scheme: dark) { :root { --text: #e6e1e5; --muted: #a8a3ad; --bg: #141218; --accent: #5fd4cd; } }
  body { margin: 0; background: var(--bg); color: var(--text); font: 17px/1.6 system-ui, sans-serif; }
  main { max-width: 680px; margin: 0 auto; padding: 48px 20px 64px; }
  h1 { font-size: 30px; line-height: 1.2; margin: 0 0 6px; }
  h2 { font-size: 20px; margin: 32px 0 6px; }
  p, li { color: var(--text); }
  .muted { color: var(--muted); font-size: 15px; }
  a { color: var(--accent); }
  nav { margin-top: 40px; font-size: 15px; }
  nav a { margin-right: 16px; }
</style>
</head>
<body><main>
${body}
<nav><a href="/">Scratchy</a><a href="/privacy">Privacy policy</a><a href="/terms">Terms</a></nav>
</main></body>
</html>`;

const home = page("Scratchy", `
<h1>Scratchy</h1>
<p class="muted">Quick notes over whatever you are doing, on your phone and your computer.</p>
<p>Scratchy is a small personal notes app for Android and Linux. A thin bar on the edge of the phone's screen (or a
shortcut on the computer) opens a note card over any app, so an idea can be written down without leaving what you
are doing.</p>
<p>Sync between your devices is optional. When you turn it on, your notes are encrypted on your device and the
encrypted files are kept in a hidden folder of <b>your own</b> Google Drive that only Scratchy can see. This site
also runs the small relay that passes encrypted changes between your devices so they arrive in about a second.</p>
<p>The source code is at <a href="https://github.com/miladhalabi/Scratchy">github.com/miladhalabi/Scratchy</a>.
Questions: <a href="mailto:${CONTACT}">${CONTACT}</a>.</p>
`);

const privacy = page("Scratchy privacy policy", `
<h1>Privacy policy</h1>
<p class="muted">Last updated ${UPDATED}</p>

<p>Scratchy keeps your notes on your own devices. It has no accounts, no ads, no analytics and no tracking.
Nothing leaves your device unless you turn on sync or use an AI feature.</p>

<h2>Google Drive</h2>
<p>If you turn on sync, Scratchy asks for one permission: <b>"See, create, and delete its own configuration data in
your Google Drive"</b> (<code>drive.appdata</code>). It uses that permission only to store your notes, images and a
small settings file in Scratchy's hidden app folder in your Drive, and to read them back on your other devices.
Scratchy cannot see any of your other Drive files.</p>
<p>Before anything is uploaded it is encrypted on your device with AES-256-GCM, using a key made from your vault
password. Google stores only encrypted files, and the developer never receives them or your password.</p>
<p>Scratchy's use of information received from Google APIs adheres to the
<a href="https://developers.google.com/terms/api-services-user-data-policy">Google API Services User Data Policy</a>,
including the Limited Use requirements. Data from Google is used only to provide sync to you, is never sold, never
used for advertising, and never shared with anyone.</p>
<p>You can stop at any time: disconnect sync in Scratchy's settings, and remove Scratchy's access at
<a href="https://myaccount.google.com/permissions">myaccount.google.com/permissions</a>. Its hidden folder can be
deleted from Google Drive's settings under "Manage apps".</p>

<h2>The relay</h2>
<p>To make changes arrive in about a second, your devices stay connected to a small relay run on Cloudflare at this
address. It passes encrypted note changes between your own devices and cannot read them. It keeps none of your notes;
the only thing it stores is the time of the last change, so the other devices know to check Drive.</p>

<h2>AI features</h2>
<p>AI titles, sorting into categories and formatting are optional and only run when you use them with your own
OpenRouter key. The note you ask about (and its first image, for titles) is then sent to OpenRouter and the model you
chose, under their terms. Secret notes are never sent.</p>

<h2>On your device</h2>
<p>Notes are stored in the app's private storage. The text of secret notes is also encrypted there, with a key held
by the system (Android Keystore, or your computer's keyring). Screenshots you choose to add are copied into the
note.</p>

<h2>Children</h2>
<p>Scratchy is not directed at children and collects no information from anyone.</p>

<h2>Changes and contact</h2>
<p>If this policy changes, the new version will be posted here with a new date. Questions:
<a href="mailto:${CONTACT}">${CONTACT}</a>.</p>
`);

const terms = page("Scratchy terms", `
<h1>Terms of use</h1>
<p class="muted">Last updated ${UPDATED}</p>
<p>Scratchy is a free personal app, provided as it is, without any warranty. Keep your own backups of anything
important: if you forget your vault password, the encrypted copy in Drive cannot be recovered by anyone.</p>
<p>You are responsible for what you write in your notes and for your use of Google Drive and of any AI service you
connect, under their own terms.</p>
<p>Questions: <a href="mailto:${CONTACT}">${CONTACT}</a>.</p>
`);

const pages = { "/": home, "/privacy": privacy, "/terms": terms };

/** The page for this path, or null when the path is not one of them. */
export function servePage(path) {
  const html = pages[path];
  return html ? new Response(html, { headers: { "content-type": "text/html; charset=utf-8" } }) : null;
}
