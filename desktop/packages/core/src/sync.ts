import { deriveKey, fromBase64, liveId, open, openText, randomBytes, seal, sealText, toBase64 } from "./crypto";
import { noteFromJson, payloadJson, sideFromJson } from "./format";
import { type Side, noteSide, resolve, staleRelay } from "./merge";
import { type Note, sameContent } from "./model";
import { type DriveApi, type RemoteFile, type Relay, type StateEntry, type Store, WrongPassword } from "./ports";

/**
 * The sync engine: a port of syncNow / applyLive / connect in Sync.kt, so desktop and phone read and write
 * exactly the same Drive layout:
 *   meta.json            { layout: 2, salt, check }   (not encrypted; "check" proves the password)
 *   n_<id>.enc           one note, or a deletion marker, sealed with the sync key
 *   img_<name>           one image, sealed with the sync key
 * Each note is merged on its own against the version both sides last agreed on.
 */

export const META_FILE = "meta.json";
export const NOTE_PREFIX = "n_";
export const NOTE_SUFFIX = ".enc";
export const IMG_PREFIX = "img_";
/** Notes bigger than this go through Drive only; the relay carries small changes. */
export const RELAY_MAX = 200_000;

const noteFile = (id: string) => `${NOTE_PREFIX}${id}${NOTE_SUFFIX}`;
const te = new TextEncoder();
const json = (o: unknown) => te.encode(JSON.stringify(o));

export interface SyncDeps {
  drive: DriveApi;
  store: Store;
  key: Uint8Array;
  relay?: Relay;
  /** Injectable for tests. */
  now?: () => number;
  newId?: () => string;
}

export interface SyncResult {
  /** This device's notes changed because of what was in Drive. */
  changedHere: boolean;
  uploaded: boolean;
  /** How many "(conflict)" copies were made. */
  conflicts: number;
}

/**
 * Derives the key from the vault password (using the salt already in Drive, or a new one for a brand-new Drive).
 * Throws WrongPassword when it doesn't match the password the other devices used.
 */
export async function connect(drive: DriveApi, password: string): Promise<Uint8Array> {
  const files = await drive.list();
  const meta = files.get(META_FILE);
  if (meta) {
    const o = JSON.parse(new TextDecoder().decode(await drive.download(meta.id)));
    if ((o.layout ?? 1) < 2) throw new Error("Your phone app is on the old layout. Update the phone app first.");
    const key = await deriveKey(password, fromBase64(o.salt));
    if ((await openText(key, fromBase64(o.check))) !== "scratchy") throw new WrongPassword();
    return key;
  }
  const salt = randomBytes(16);
  const key = await deriveKey(password, salt);
  const check = toBase64(await sealText(key, "scratchy"));
  await drive.upload(null, META_FILE, json({ v: 1, layout: 2, salt: toBase64(salt), check }));
  return key;
}

export async function syncNow(d: SyncDeps): Promise<SyncResult> {
  const { drive, store, key } = d;
  const now = d.now ?? Date.now;
  const files = new Map(await drive.list());
  const listedAt = now();
  const state = await store.syncState();
  const localNotes = new Map((await store.notes()).map((n) => [n.id, n]));
  const localTombs = await store.tombstones();
  let uploaded = false;
  let conflicts = 0;
  const relayed: string[] = [];
  let needDoorbell = false;             // something went up that the relay doesn't carry

  // Pictures first, so a note never arrives before the images it shows.
  const localImages = new Set([...localNotes.values()].flatMap((n) => n.images));
  for (const name of localImages) {
    if (!files.has(IMG_PREFIX + name) && (await store.imageExists(name))) {
      files.set(IMG_PREFIX + name, await drive.upload(null, IMG_PREFIX + name, await seal(key, await store.readImage(name))));
      uploaded = true; needDoorbell = true;
    }
  }

  const ids = new Set<string>([...localNotes.keys(), ...Object.keys(localTombs)]);
  for (const f of files.keys()) {
    if (f.startsWith(NOTE_PREFIX) && f.endsWith(NOTE_SUFFIX)) ids.add(f.slice(NOTE_PREFIX.length, f.length - NOTE_SUFFIX.length));
  }

  const puts = new Map<string, Note>();
  const removes = new Map<string, number>();
  const seenRev = new Map<string, number>();
  const newState: Record<string, StateEntry> = { ...state };
  const oldEntries = { ...state };
  let incomplete = false;               // some note could not be settled this time

  for (const id of ids) {
    const name = noteFile(id);
    let settled = false;
    for (let attempt = 0; attempt < 3; attempt++) {
      const ours: Side | null = localNotes.has(id) ? noteSide(localNotes.get(id)!)
        : id in localTombs ? { note: null, deletedAt: localTombs[id] } : null;
      const entry = state[id];
      const remote = files.get(name);
      const unchanged = remote != null && entry != null && entry.ver === remote.version;
      const localSame = entry != null && ours != null && (ours.note != null
        ? entry.base != null && entry.base.rev === ours.note.rev && sameContent(entry.base, ours.note)
        : entry.base == null && entry.tomb === ours.deletedAt);
      if (unchanged && localSame) { settled = true; break; }          // nothing to do for this note

      let theirs: Side | null;
      if (remote == null) theirs = null;
      else if (unchanged) theirs = entry!.base ? noteSide(entry!.base) : { note: null, deletedAt: entry!.tomb };
      else {
        const plain = await open(key, await drive.download(remote.id));
        if (plain == null) throw new WrongPassword();
        theirs = sideFromJson(JSON.parse(new TextDecoder().decode(plain)));
      }
      const res = resolve(entry?.base ?? null, ours, theirs, d.newId);
      if (res == null) { settled = true; break; }
      const resSide: Side = { note: res.note, deletedAt: res.deletedAt };

      const same = theirs != null && remote != null && (res.note != null
        ? theirs.note != null && theirs.note.rev === res.note.rev && sameContent(theirs.note, res.note)
        : theirs.note == null);
      let newVersion = remote?.version ?? "";
      if (!same) {
        // Safe write: only replace the file if nobody changed it since we read it.
        if (remote != null && now() - listedAt > 2500) {
          const v = await drive.version(remote.id);
          if (v !== remote.version) { files.set(name, { id: remote.id, version: v }); continue; }   // changed under us: merge again
        }
        const sealed = await seal(key, json(payloadJson(resSide, id)));
        const up = await drive.upload(remote?.id ?? null, name, sealed);
        files.set(name, up); newVersion = up.version; uploaded = true;
        if (sealed.length <= RELAY_MAX) relayed.push(toBase64(sealed)); else needDoorbell = true;
      }

      seenRev.set(id, ours?.note?.rev ?? -1);
      if (res.note != null) {
        if (ours?.note == null || ours.note.rev !== res.note.rev || !sameContent(ours.note, res.note)) puts.set(id, res.note);
      } else if (ours?.note != null) removes.set(id, res.deletedAt);
      else if (ours == null || ours.deletedAt !== res.deletedAt) removes.set(id, res.deletedAt);
      newState[id] = { base: res.note, ver: newVersion, tomb: res.deletedAt };

      const copy = res.conflictCopy;
      if (copy) {
        conflicts++;
        const cname = noteFile(copy.id);
        const sealedCopy = await seal(key, json(payloadJson(noteSide(copy), copy.id)));
        const up = await drive.upload(null, cname, sealedCopy);
        files.set(cname, up); uploaded = true;
        if (sealedCopy.length <= RELAY_MAX) relayed.push(toBase64(sealedCopy)); else needDoorbell = true;
        puts.set(copy.id, copy); seenRev.set(copy.id, -1);
        newState[copy.id] = { base: copy, ver: up.version, tomb: 0 };
      }
      settled = true;
      break;
    }
    if (!settled) incomplete = true;
  }

  if (relayed.length) d.relay?.relay(relayed);                         // the change itself, instantly
  if (uploaded && (needDoorbell || relayed.length === 0)) d.relay?.tick();

  // Apply to this device. Notes edited meanwhile are skipped and merged on the next sync.
  const skipped = await store.applySynced(puts, removes, seenRev);
  for (const id of skipped) {
    const old = oldEntries[id];
    newState[id] = { base: old?.base ?? null, ver: "", tomb: old?.tomb ?? 0 };      // forces a fresh look next time
  }
  await store.saveSyncState(newState);
  const changedHere = [...puts.keys(), ...removes.keys()].some((id) => !skipped.has(id));

  // Pictures: fetch missing ones, tidy ones nothing uses any more.
  const wanted = new Set((await store.notes()).flatMap((n) => n.images));
  for (const name of wanted) {
    const remote = files.get(IMG_PREFIX + name);
    if (remote && !(await store.imageExists(name))) {
      const plain = await open(key, await drive.download(remote.id));
      if (plain) await store.writeImage(name, plain);
    }
  }
  // Only tidy when every note was settled, so no note's images are ever judged unused by mistake.
  if (skipped.size === 0 && !incomplete) {
    for (const [n, r] of files) {
      if (n.startsWith(IMG_PREFIX) && !wanted.has(n.slice(IMG_PREFIX.length))) await drive.delete(r.id);
    }
  }
  return { changedHere, uploaded, conflicts };
}

export interface LiveResult { applied: boolean; followUp: boolean }

/**
 * Another device handed us an encrypted note change directly. Merge it in right away; a normal sync should
 * follow when `followUp` is true (something needs publishing, or pictures are still to be fetched).
 */
export async function applyLive(d: SyncDeps, dataB64: string): Promise<LiveResult> {
  const { store, key } = d;
  const plain = await open(key, fromBase64(dataB64));
  if (plain == null) return { applied: false, followUp: false };
  const o = JSON.parse(new TextDecoder().decode(plain));
  const id = String(o.id);
  const theirs = sideFromJson(o);
  const local = (await store.notes()).find((n) => n.id === id) ?? null;
  const tombs = await store.tombstones();
  const ours: Side | null = local ? noteSide(local) : id in tombs ? { note: null, deletedAt: tombs[id] } : null;
  if (staleRelay(local, theirs.note)) return { applied: false, followUp: false };      // an older change arriving late
  const st = await store.syncState();
  const res = resolve(st[id]?.base ?? null, ours, theirs, d.newId);
  if (res == null) return { applied: false, followUp: false };
  if (res.conflictCopy) return { applied: false, followUp: true };                     // let the full sync handle clashes

  const seen = new Map([[id, local?.rev ?? -1]]);
  let skipped: Set<string>;
  if (res.note != null) {
    if (local && local.rev === res.note.rev && sameContent(local, res.note)) return { applied: false, followUp: false };
    skipped = await store.applySynced(new Map([[id, res.note]]), new Map(), seen);
  } else {
    skipped = await store.applySynced(new Map(), new Map([[id, res.deletedAt]]), seen);
  }
  const applied = skipped.size === 0;
  const merged = res.note;
  let followUp = false;
  // We now hold exactly what Drive has, so that is the new common ground for the next edit.
  // (Without this, the next edit is compared with an outdated base and looks like a clash.)
  const adopted = applied && (merged != null ? theirs.note != null && sameContent(merged, theirs.note) : theirs.note == null);
  if (adopted) {
    st[id] = { base: theirs.note, ver: "", tomb: theirs.deletedAt };
    await store.saveSyncState(st);
  }
  if (!applied || (merged != null && theirs.note != null && !sameContent(merged, theirs.note))) followUp = true;
  if (merged) for (const img of merged.images) if (!(await store.imageExists(img))) followUp = true;
  return { applied, followUp };
}

/** The relay room for this account (a hash of the key). */
export const roomId = (key: Uint8Array) => liveId(key);
export { noteFromJson };
export type { RemoteFile };
