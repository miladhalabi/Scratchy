/**
 * Google sign-in for a desktop app: the "loopback + PKCE" flow.
 * 1. Open buildAuthUrl() in the browser; Google redirects to http://127.0.0.1:<port>/ with ?code=...
 * 2. exchangeCode() trades the code for a refresh token and a short-lived access token.
 * 3. refreshAccessToken() gets a new access token whenever the old one expires.
 * Only the narrow `drive.appdata` scope is requested.
 */

export const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata";
const AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN = "https://oauth2.googleapis.com/token";

export interface OAuthClient { clientId: string; clientSecret: string }
export interface Tokens { accessToken: string; refreshToken?: string; expiresAt: number }

const b64url = (b: Uint8Array) => btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

export async function createPkce() {
  const verifier = b64url(globalThis.crypto.getRandomValues(new Uint8Array(48)));
  const challenge = b64url(new Uint8Array(await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  const state = b64url(globalThis.crypto.getRandomValues(new Uint8Array(16)));
  return { verifier, challenge, state };
}

export function buildAuthUrl(c: OAuthClient, redirectUri: string, challenge: string, state: string): string {
  const q = new URLSearchParams({
    client_id: c.clientId, redirect_uri: redirectUri, response_type: "code", scope: DRIVE_SCOPE,
    code_challenge: challenge, code_challenge_method: "S256", state,
    access_type: "offline", prompt: "consent",          // consent every time so a refresh token is always issued
  });
  return `${AUTH}?${q}`;
}

async function tokenCall(body: Record<string, string>, fetchFn: typeof fetch): Promise<any> {
  const r = await fetchFn(TOKEN, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(body),
  });
  const o: any = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`Google sign-in failed: ${o.error ?? r.status} ${o.error_description ?? ""}`.trim());
  return o;
}

export async function exchangeCode(
  c: OAuthClient, code: string, verifier: string, redirectUri: string, now = Date.now(), fetchFn: typeof fetch = fetch,
): Promise<Tokens> {
  const o = await tokenCall({
    client_id: c.clientId, client_secret: c.clientSecret, code, code_verifier: verifier,
    redirect_uri: redirectUri, grant_type: "authorization_code",
  }, fetchFn);
  return { accessToken: o.access_token, refreshToken: o.refresh_token, expiresAt: now + (o.expires_in - 60) * 1000 };
}

export async function refreshAccessToken(
  c: OAuthClient, refreshToken: string, now = Date.now(), fetchFn: typeof fetch = fetch,
): Promise<Tokens> {
  const o = await tokenCall({
    client_id: c.clientId, client_secret: c.clientSecret, refresh_token: refreshToken, grant_type: "refresh_token",
  }, fetchFn);
  return { accessToken: o.access_token, refreshToken, expiresAt: now + (o.expires_in - 60) * 1000 };
}
