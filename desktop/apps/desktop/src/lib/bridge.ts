import { emit, listen, type UnlistenFn } from "@tauri-apps/api/event";

/**
 * The bar, the card and the main window are separate web views. The main window is the engine: it owns the notes,
 * sync and the vault. The card asks it for things with small request/response messages.
 */
type Handler = (...args: any[]) => unknown | Promise<unknown>;

/** Engine side: answers requests from the other windows. */
export function serveEngine(handlers: Record<string, Handler>): Promise<UnlistenFn> {
  return listen<{ id: string; method: string; args: unknown[] }>("engine-req", async (e) => {
    const { id, method, args } = e.payload;
    try {
      if (!handlers[method]) throw new Error(`unknown request: ${method}`);
      await emit("engine-res", { id, result: await handlers[method](...args) });
    } catch (err) {
      await emit("engine-res", { id, error: String((err as Error)?.message ?? err) });
    }
  });
}

const waiting = new Map<string, { ok: (v: any) => void; fail: (e: Error) => void }>();
let listening: Promise<UnlistenFn> | null = null;
let counter = 0;

/** Card side: ask the engine. Fails after 8 seconds if the main window isn't answering. */
export async function call<T>(method: string, ...args: unknown[]): Promise<T> {
  listening ??= listen<{ id: string; result?: unknown; error?: string }>("engine-res", (e) => {
    const w = waiting.get(e.payload.id);
    if (!w) return;
    waiting.delete(e.payload.id);
    e.payload.error != null ? w.fail(new Error(e.payload.error)) : w.ok(e.payload.result);
  });
  await listening;
  const id = `${Date.now()}-${counter++}`;
  return new Promise<T>((ok, fail) => {
    waiting.set(id, { ok, fail });
    void emit("engine-req", { id, method, args });
    setTimeout(() => { if (waiting.delete(id)) fail(new Error("Scratchy isn't answering. Is the main window running?")); }, 8000);
  });
}
