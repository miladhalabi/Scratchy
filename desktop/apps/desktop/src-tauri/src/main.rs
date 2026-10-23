use base64::{engine::general_purpose::STANDARD, Engine};
use std::{
    fs,
    io::{Read, Write},
    net::TcpListener,
    path::PathBuf,
    sync::Mutex,
    time::{Duration, Instant},
};
use tauri::{AppHandle, Manager, State};

/// Only plain file names: no folders, no "..", nothing hidden.
fn safe(name: &str) -> Result<(), String> {
    let ok = !name.is_empty() && name.len() < 200 && !name.starts_with('.')
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
    if ok { Ok(()) } else { Err(format!("bad file name: {name}")) }
}

fn dir(app: &AppHandle, sub: Option<&str>) -> Result<PathBuf, String> {
    let mut d = app.path().app_data_dir().map_err(|e| e.to_string())?;
    if let Some(s) = sub { d.push(s); }
    fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    private(&d, 0o700);
    Ok(d)
}

/// Notes and sign-in data are for this user only.
#[cfg(unix)]
fn private(path: &std::path::Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(mode));
}
#[cfg(not(unix))]
fn private(_: &std::path::Path, _: u32) {}

/// Writes through a temp file and renames, so a crash never leaves a half-written file.
fn write_atomic(path: PathBuf, data: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    let mut f = fs::File::create(&tmp).map_err(|e| e.to_string())?;
    f.write_all(data).map_err(|e| e.to_string())?;
    f.sync_all().map_err(|e| e.to_string())?;
    private(&tmp, 0o600);
    fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

#[tauri::command]
fn read_text(app: AppHandle, name: String) -> Result<Option<String>, String> {
    safe(&name)?;
    match fs::read_to_string(dir(&app, None)?.join(&name)) {
        Ok(s) => Ok(Some(s)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
fn write_text(app: AppHandle, name: String, data: String) -> Result<(), String> {
    safe(&name)?;
    write_atomic(dir(&app, None)?.join(&name), data.as_bytes())
}

/// Images are stored under attachments/ and travel as base64 text.
#[tauri::command]
fn read_attachment(app: AppHandle, name: String) -> Result<Option<String>, String> {
    safe(&name)?;
    match fs::read(dir(&app, Some("attachments"))?.join(&name)) {
        Ok(b) => Ok(Some(STANDARD.encode(b))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
fn write_attachment(app: AppHandle, name: String, b64: String) -> Result<(), String> {
    safe(&name)?;
    let bytes = STANDARD.decode(b64).map_err(|e| e.to_string())?;
    write_atomic(dir(&app, Some("attachments"))?.join(&name), &bytes)
}

#[tauri::command]
fn has_attachment(app: AppHandle, name: String) -> Result<bool, String> {
    safe(&name)?;
    Ok(dir(&app, Some("attachments"))?.join(&name).exists())
}

#[tauri::command]
fn remove_text(app: AppHandle, name: String) -> Result<(), String> {
    safe(&name)?;
    match fs::remove_file(dir(&app, None)?.join(&name)) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct GoogleClient { client_id: String, client_secret: String }

/// The Google "Desktop app" credentials, from the environment (set by run.sh from desktop/.env.local)
/// or baked in at build time. A desktop client secret is not confidential, but it stays out of git.
#[tauri::command]
fn google_client() -> Result<GoogleClient, String> {
    let get = |name: &str, built: Option<&str>| std::env::var(name).ok().or(built.map(String::from)).filter(|v| !v.is_empty());
    match (
        get("GOOGLE_CLIENT_ID", option_env!("GOOGLE_CLIENT_ID")),
        get("GOOGLE_CLIENT_SECRET", option_env!("GOOGLE_CLIENT_SECRET")),
    ) {
        (Some(client_id), Some(client_secret)) => Ok(GoogleClient { client_id, client_secret }),
        _ => Err("Google sign-in isn't set up: GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are missing".into()),
    }
}

/// The sign-in redirect comes back to a short-lived server on 127.0.0.1.
struct Oauth(Mutex<Option<TcpListener>>);

#[tauri::command]
fn oauth_start(state: State<Oauth>) -> Result<u16, String> {
    let l = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    let port = l.local_addr().map_err(|e| e.to_string())?.port();
    *state.0.lock().unwrap() = Some(l);
    Ok(port)
}

/// Waits (up to 3 minutes) for Google to send the browser back, and returns the query string of that request.
#[tauri::command]
async fn oauth_wait(state: State<'_, Oauth>) -> Result<String, String> {
    let listener = state.0.lock().unwrap().take().ok_or("sign-in was not started")?;
    tauri::async_runtime::spawn_blocking(move || {
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let deadline = Instant::now() + Duration::from_secs(180);
        loop {
            if Instant::now() > deadline { return Err("Sign-in timed out".to_string()); }
            match listener.accept() {
                Ok((mut stream, _)) => {
                    let _ = stream.set_nonblocking(false);
                    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                    let mut buf = [0u8; 4096];
                    let n = stream.read(&mut buf).unwrap_or(0);
                    let req = String::from_utf8_lossy(&buf[..n]).to_string();
                    let target = req.lines().next().unwrap_or("").split_whitespace().nth(1).unwrap_or("").to_string();
                    let query = target.split_once('?').map(|(_, q)| q.to_string()).unwrap_or_default();
                    let body = if query.contains("code=") || query.contains("error=") {
                        "<html><body style=\"font-family:system-ui;padding:40px\"><h3>Signed in to Scratchy.</h3>You can close this tab.</body></html>"
                    } else { "" };
                    let status = if body.is_empty() { "404 Not Found" } else { "200 OK" };
                    let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
                    if !body.is_empty() { return Ok(query); }           // ignore stray requests such as /favicon.ico
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(100)),
                Err(e) => return Err(e.to_string()),
            }
        }
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Opens Google's sign-in page in the default browser. Only Google sign-in addresses are allowed.
#[tauri::command]
fn open_url(url: String) -> Result<(), String> {
    if !url.starts_with("https://accounts.google.com/") { return Err("refusing to open that address".into()); }
    #[cfg(target_os = "linux")]
    let mut c = std::process::Command::new("xdg-open");
    #[cfg(target_os = "macos")]
    let mut c = std::process::Command::new("open");
    #[cfg(target_os = "windows")]
    let mut c = { let mut c = std::process::Command::new("rundll32"); c.arg("url.dll,FileProtocolHandler"); c };
    c.arg(&url).spawn().map(|_| ()).map_err(|e| e.to_string())
}

#[tauri::command]
fn data_path(app: AppHandle) -> Result<String, String> {
    Ok(dir(&app, None)?.to_string_lossy().into_owned())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .manage(Oauth(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            read_text, write_text, remove_text, read_attachment, write_attachment, has_attachment, data_path,
            google_client, oauth_start, oauth_wait, open_url
        ])
        .run(tauri::generate_context!())
        .expect("error while running Scratchy");
}
