import type { DriveApi, RemoteFile } from "./ports";

const API = "https://www.googleapis.com/drive/v3/files";
const UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";

/** Drive's hidden app-data folder over plain REST. `token()` returns a valid access token each time. */
export class GoogleDrive implements DriveApi {
  constructor(private token: () => Promise<string>, private fetchFn: typeof fetch = fetch) {}

  private async call(url: string, init: RequestInit = {}): Promise<Response> {
    const r = await this.fetchFn(url, { ...init, headers: { ...(init.headers as object), authorization: `Bearer ${await this.token()}` } });
    if (!r.ok) throw new Error(`Drive ${r.status} ${(await r.text()).slice(0, 160)}`);
    return r;
  }

  async list(): Promise<Map<string, RemoteFile>> {
    const out = new Map<string, RemoteFile>();
    let page: string | undefined;
    do {
      const q = new URLSearchParams({ spaces: "appDataFolder", pageSize: "1000", fields: "nextPageToken,files(id,name,version)" });
      if (page) q.set("pageToken", page);
      const o: any = await (await this.call(`${API}?${q}`)).json();
      for (const f of o.files ?? []) out.set(f.name, { id: f.id, version: String(f.version ?? "") });
      page = o.nextPageToken;
    } while (page);
    return out;
  }

  async download(id: string): Promise<Uint8Array> {
    return new Uint8Array(await (await this.call(`${API}/${id}?alt=media`)).arrayBuffer());
  }

  async version(id: string): Promise<string> {
    const o: any = await (await this.call(`${API}/${id}?fields=version`)).json();
    return String(o.version ?? "");
  }

  async upload(id: string | null, name: string, data: Uint8Array): Promise<RemoteFile> {
    let r: Response;
    if (id) {
      r = await this.call(`${UPLOAD}/${id}?uploadType=media&fields=id,version`, {
        method: "PATCH", headers: { "content-type": "application/octet-stream" }, body: data as unknown as BodyInit,
      });
    } else {
      const boundary = "scratchy" + Math.random().toString(36).slice(2);
      const meta = JSON.stringify({ name, parents: ["appDataFolder"] });
      const enc = new TextEncoder();
      const head = enc.encode(`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`);
      const tail = enc.encode(`\r\n--${boundary}--`);
      const body = new Uint8Array(head.length + data.length + tail.length);
      body.set(head, 0); body.set(data, head.length); body.set(tail, head.length + data.length);
      r = await this.call(`${UPLOAD}?uploadType=multipart&fields=id,version`, {
        method: "POST", headers: { "content-type": `multipart/related; boundary=${boundary}` }, body: body as unknown as BodyInit,
      });
    }
    const o: any = await r.json();
    return { id: o.id, version: String(o.version ?? "") };
  }

  async delete(id: string): Promise<void> {
    await this.call(`${API}/${id}`, { method: "DELETE" });
  }
}
