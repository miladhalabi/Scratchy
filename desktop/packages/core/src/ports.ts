import type { Note } from "./model";

/** A file in Drive's app-data folder. `version` changes every time the content changes. */
export interface RemoteFile { id: string; version: string }

export interface DriveApi {
  /** name -> file, for everything in the app-data folder. */
  list(): Promise<Map<string, RemoteFile>>;
  download(id: string): Promise<Uint8Array>;
  /** Creates the file, or replaces its content when `id` is given. */
  upload(id: string | null, name: string, data: Uint8Array): Promise<RemoteFile>;
  version(id: string): Promise<string>;
  delete(id: string): Promise<void>;
}

/** What each note looked like the last time this device and Drive agreed. */
export interface StateEntry { base: Note | null; ver: string; tomb: number }

/** Local persistence, as seen by the sync engine. */
export interface Store {
  notes(): Promise<Note[]>;
  tombstones(): Promise<Record<string, number>>;
  syncState(): Promise<Record<string, StateEntry>>;
  saveSyncState(s: Record<string, StateEntry>): Promise<void>;
  /**
   * Applies what a sync decided, note by note. A note is only touched if it is still exactly as the sync
   * saw it (`seenRev`, -1 = absent). Returns the ids that were skipped for that reason.
   */
  applySynced(puts: Map<string, Note>, removes: Map<string, number>, seenRev: Map<string, number>): Promise<Set<string>>;
  imageExists(name: string): Promise<boolean>;
  readImage(name: string): Promise<Uint8Array>;
  writeImage(name: string, data: Uint8Array): Promise<void>;
}

/** The instant-update channel to the other devices. Fire and forget. */
export interface Relay {
  /** Hands encrypted note changes (base64) straight to the other devices. */
  relay(payloads: string[]): void;
  /** Tells the other devices "something changed in Drive, go and look". */
  tick(): void;
}

export class WrongPassword extends Error {
  constructor() { super("That isn't the password used on your other device"); }
}
