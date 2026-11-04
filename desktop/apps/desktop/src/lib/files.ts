import { invoke } from "@tauri-apps/api/core";

/** Local files, through the Rust side (atomic writes, plain file names only). */
export const files = {
  readText: (name: string) => invoke<string | null>("read_text", { name }),
  writeText: (name: string, data: string) => invoke<void>("write_text", { name, data }),
  removeText: (name: string) => invoke<void>("remove_text", { name }),
  readAttachment: (name: string) => invoke<string | null>("read_attachment", { name }),
  writeAttachment: (name: string, b64: string) => invoke<void>("write_attachment", { name, b64 }),
  hasAttachment: (name: string) => invoke<boolean>("has_attachment", { name }),
  dataPath: () => invoke<string>("data_path"),
};
