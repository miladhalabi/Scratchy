import { invoke } from "@tauri-apps/api/core";
import { files } from "./files";

const MAX_SIDE = 1600;

const b64Of = (blob: Blob) => new Promise<string>((ok, no) => {
  const r = new FileReader();
  r.onload = () => ok(String(r.result).split(",", 2)[1] ?? "");
  r.onerror = () => no(r.error);
  r.readAsDataURL(blob);
});

function hasAlpha(g: CanvasRenderingContext2D, w: number, h: number) {
  const d = g.getImageData(0, 0, w, h).data;
  for (let i = 3; i < d.length; i += 4) if (d[i] < 255) return true;
  return false;
}

/**
 * Copies an image into the app like Attachments.import on the phone: at most 1600 px on its long side, JPEG at 90%
 * (PNG when it has see-through parts), named att_<uuid>. Returns the name to put in the note's images.
 */
export async function importImage(blob: Blob): Promise<string> {
  const bmp = await createImageBitmap(blob);
  const scale = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
  const w = Math.max(1, Math.round(bmp.width * scale)), h = Math.max(1, Math.round(bmp.height * scale));
  const c = document.createElement("canvas");
  c.width = w; c.height = h;
  const g = c.getContext("2d", { willReadFrequently: true })!;
  g.drawImage(bmp, 0, 0, w, h);
  bmp.close();
  const png = blob.type !== "image/jpeg" && hasAlpha(g, w, h);
  const out = await new Promise<Blob>((ok, no) => c.toBlob((b) => (b ? ok(b) : no(new Error("Couldn't add that image"))), png ? "image/png" : "image/jpeg", 0.9));
  const name = `att_${crypto.randomUUID()}.${png ? "png" : "jpg"}`;
  await files.writeAttachment(name, await b64Of(out));
  return name;
}

export const imageFiles = (list: FileList | null | undefined) => [...(list ?? [])].filter((f) => f.type.startsWith("image/"));

export type Clip = { kind: "image"; mime: string; b64: string } | { kind: "text"; text: string } | { kind: "empty" };
/** The clipboard, read on the Rust side (wl-paste), for the Paste button. */
export const readClipboard = () => invoke<Clip>("clipboard_read");
export const clipBlob = (c: { mime: string; b64: string }) =>
  new Blob([Uint8Array.from(atob(c.b64), (ch) => ch.charCodeAt(0))], { type: c.mime });

/** Object URLs for attachments, shared by every view that shows the same image. */
const urls = new Map<string, Promise<string | null>>();
export function imageUrl(name: string): Promise<string | null> {
  let u = urls.get(name);
  if (!u) {
    u = files.readAttachment(name).then((b64) => (b64 ? URL.createObjectURL(clipBlob({ mime: name.endsWith(".png") ? "image/png" : "image/jpeg", b64 })) : null))
      .catch(() => null);
    u.then((v) => { if (!v) urls.delete(name); });            // not here yet (it may still be syncing): try again next time
    urls.set(name, u);
  }
  return u;
}
