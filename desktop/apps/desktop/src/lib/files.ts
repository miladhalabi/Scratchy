import { invoke } from "@tauri-apps/api/core";

/**
 * Writes to one file go out one after another, in the order they were asked for: the commands run off the main
 * thread, so an older save could otherwise land after a newer one.
 */
const queue = new Map<string, Promise<unknown>>();
function inOrder<T>(name: string, run: () => Promise<T>): Promise<T> {
  const next = (queue.get(name) ?? Promise.resolve()).catch(() => {}).then(run);
  queue.set(name, next);
  void next.finally(() => { if (queue.get(name) === next) queue.delete(name); }).catch(() => {});
  return next;
}

/** Local files, through the Rust side (atomic writes, plain file names only). */
export const files = {
  readText: (name: string) => invoke<string | null>("read_text", { name }),
  writeText: (name: string, data: string) => inOrder(name, () => invoke<void>("write_text", { name, data })),
  removeText: (name: string) => inOrder(name, () => invoke<void>("remove_text", { name })),
  readAttachment: (name: string) => invoke<string | null>("read_attachment", { name }),
  writeAttachment: (name: string, b64: string) => inOrder(`att/${name}`, () => invoke<void>("write_attachment", { name, b64 })),
  hasAttachment: (name: string) => invoke<boolean>("has_attachment", { name }),
  dataPath: () => invoke<string>("data_path"),
};
