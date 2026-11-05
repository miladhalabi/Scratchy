use base64::{engine::general_purpose::STANDARD, Engine};
use std::{
    fs,
    io::{Read, Write},
    net::TcpListener,
    path::PathBuf,
    sync::Mutex,
    time::{Duration, Instant},
};
mod overlay;
mod remote;
mod hypr;
mod shots;
mod tray;
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
/// One write at a time: commands run off the main thread now, so two saves of one file could otherwise overlap.
static WRITES: Mutex<()> = Mutex::new(());

fn write_atomic(path: PathBuf, data: &[u8]) -> Result<(), String> {
    let _one = WRITES.lock().unwrap_or_else(|e| e.into_inner());
    let tmp = path.with_extension("tmp");
    let mut f = fs::File::create(&tmp).map_err(|e| e.to_string())?;
    f.write_all(data).map_err(|e| e.to_string())?;
    f.sync_all().map_err(|e| e.to_string())?;
    private(&tmp, 0o600);
    fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

#[tauri::command]
async fn read_text(app: AppHandle, name: String) -> Result<Option<String>, String> {
    safe(&name)?;
    match fs::read_to_string(dir(&app, None)?.join(&name)) {
        Ok(s) => Ok(Some(s)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
async fn write_text(app: AppHandle, name: String, data: String) -> Result<(), String> {
    safe(&name)?;
    write_atomic(dir(&app, None)?.join(&name), data.as_bytes())
}

/// Images are stored under attachments/ and travel as base64 text.
#[tauri::command]
async fn read_attachment(app: AppHandle, name: String) -> Result<Option<String>, String> {
    safe(&name)?;
    match fs::read(dir(&app, Some("attachments"))?.join(&name)) {
        Ok(b) => Ok(Some(STANDARD.encode(b))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
async fn write_attachment(app: AppHandle, name: String, b64: String) -> Result<(), String> {
    safe(&name)?;
    let bytes = STANDARD.decode(b64).map_err(|e| e.to_string())?;
    write_atomic(dir(&app, Some("attachments"))?.join(&name), &bytes)
}

#[tauri::command]
async fn has_attachment(app: AppHandle, name: String) -> Result<bool, String> {
    safe(&name)?;
    Ok(dir(&app, Some("attachments"))?.join(&name).exists())
}

#[tauri::command]
async fn remove_text(app: AppHandle, name: String) -> Result<(), String> {
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
async fn google_client() -> Result<GoogleClient, String> {
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
async fn open_url(url: String) -> Result<(), String> {
    if !url.starts_with("https://accounts.google.com/") { return Err("refusing to open that address".into()); }
    #[cfg(target_os = "linux")]
    let mut c = std::process::Command::new("xdg-open");
    #[cfg(target_os = "macos")]
    let mut c = std::process::Command::new("open");
    #[cfg(target_os = "windows")]
    let mut c = { let mut c = std::process::Command::new("rundll32"); c.arg("url.dll,FileProtocolHandler"); c };
    c.arg(&url).spawn().map(|_| ()).map_err(|e| e.to_string())
}

const KEYRING_SERVICE: &str = "com.scratchy.desktop";

/// The operating system's secret store (Secret Service on Linux, Credential Manager, Keychain).
/// Returns Ok(None) when the secret doesn't exist yet and Err when no store is available.
#[tauri::command]
async fn secret_get(name: String) -> Result<Option<String>, String> {
    safe(&name)?;
    let e = keyring::Entry::new(KEYRING_SERVICE, &name).map_err(|e| e.to_string())?;
    match e.get_password() {
        Ok(v) => Ok(Some(v)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
async fn secret_set(name: String, value: String) -> Result<(), String> {
    safe(&name)?;
    keyring::Entry::new(KEYRING_SERVICE, &name).map_err(|e| e.to_string())?
        .set_password(&value).map_err(|e| e.to_string())
}

#[tauri::command]
async fn secret_delete(name: String) -> Result<(), String> {
    safe(&name)?;
    match keyring::Entry::new(KEYRING_SERVICE, &name).map_err(|e| e.to_string())?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

/// The desktop's accent color, like the phone's wallpaper color: the Omarchy theme's accent, else GNOME's
/// accent setting. None when the desktop has no accent of its own.
#[tauri::command]
async fn system_accent() -> Option<String> {
    #[cfg(target_os = "linux")]
    {
        let home = std::env::var("HOME").ok()?;
        if let Ok(t) = fs::read_to_string(format!("{home}/.config/omarchy/current/theme/colors.toml")) {
            for line in t.lines() {
                if let Some(v) = line.trim().strip_prefix("accent") {
                    let v = v.trim_start().strip_prefix('=')?.trim().trim_matches('"');
                    if v.len() == 7 && v.starts_with('#') { return Some(v.to_lowercase()); }
                }
            }
        }
        if std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default().contains("GNOME") {
            let out = std::process::Command::new("gsettings").args(["get", "org.gnome.desktop.interface", "accent-color"]).output().ok()?;
            let name = String::from_utf8_lossy(&out.stdout).trim().trim_matches('\'').to_string();
            // GNOME's own accent palette.
            let hex = match name.as_str() {
                "blue" => "#3584e4", "teal" => "#2190a4", "green" => "#3a944a", "yellow" => "#c88800", "orange" => "#ed5b00",
                "red" => "#e62d42", "pink" => "#d56199", "purple" => "#9141ac", "slate" => "#6f8396", _ => return None,
            };
            return Some(hex.into());
        }
        None
    }
    #[cfg(not(target_os = "linux"))]
    { None }
}

/// The small program the desktop's shortcut runs (it sits next to this one), for the line Settings shows.
#[tauri::command]
async fn toggle_path() -> String {
    if installed() { return "scratchy-toggle".into() }                 // on the PATH
    let exe = std::env::current_exe().unwrap_or_default();
    exe.with_file_name(if cfg!(windows) { "scratchy-toggle.exe" } else { "scratchy-toggle" }).to_string_lossy().into_owned()
}

/// Start at login, the XDG way (uwsm and most desktops run ~/.config/autostart). Quiet: it starts in the tray.
#[cfg(target_os = "linux")]
fn autostart_file() -> Option<PathBuf> {
    let base = std::env::var("XDG_CONFIG_HOME").ok().filter(|v| !v.is_empty())
        .or_else(|| std::env::var("HOME").ok().map(|h| format!("{h}/.config")))?;
    Some(PathBuf::from(base).join("autostart").join("scratchy.desktop"))
}

#[tauri::command]
async fn autostart_get() -> bool {
    #[cfg(target_os = "linux")]
    { autostart_file().is_some_and(|f| f.exists()) }
    #[cfg(not(target_os = "linux"))]
    { false }
}

#[tauri::command]
async fn autostart_set(on: bool) -> Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        let f = autostart_file().ok_or("no home folder")?;
        if !on { let _ = fs::remove_file(&f); return Ok(()); }
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        // Installed: the `scratchy` command sets everything up. A build run from the project needs the same
        // libraries it was started with.
        let exec = if installed() { "scratchy --background".to_string() } else {
            let mut env = String::new();
            for (k, v) in launch_env() { env += &format!("{k}={v} "); }
            if env.is_empty() { format!("\"{}\" --background", exe.display()) } else { format!("env {env}\"{}\" --background", exe.display()) }
        };
        fs::create_dir_all(f.parent().unwrap()).map_err(|e| e.to_string())?;
        let text = format!("[Desktop Entry]\nType=Application\nName=Scratchy\nComment=Quick notes\nExec={exec}\nTerminal=false\nX-GNOME-Autostart-enabled=true\n");
        write_atomic(f, text.as_bytes())
    }
    #[cfg(not(target_os = "linux"))]
    { let _ = on; Ok(()) }
}

#[tauri::command]
async fn data_path(app: AppHandle) -> Result<String, String> {
    Ok(dir(&app, None)?.to_string_lossy().into_owned())
}

#[tauri::command]
fn card_hide(app: AppHandle) { overlay::hide(&app) }
/// Pinned: only the card's rectangle takes clicks, and the keyboard only after a click.
#[tauri::command]
fn card_input(app: AppHandle, rect: Option<(i32, i32, i32, i32)>) { overlay::input(&app, rect) }
#[tauri::command]
fn card_keyboard(app: AppHandle, exclusive: bool) { overlay::keyboard(&app, exclusive) }
/// The page is ready: if `scratchy --toggle` started the app, the card opens now.
#[tauri::command]
fn card_boot(app: AppHandle) {
    if overlay::OPEN_ON_BOOT.swap(false, std::sync::atomic::Ordering::SeqCst) { overlay::toggle(&app) } else { overlay::warm_up(&app) }
}

/// The notes window. It is made at startup, hidden unless asked for, and closing it only hides it, so it opens
/// at once every time (the notes engine lives in the card's window either way).
fn make_main(app: &AppHandle, visible: bool) {
    let _ = tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::App("index.html?w=main".into()))
        .title("Scratchy").inner_size(1040.0, 740.0).min_inner_size(420.0, 480.0)
        .visible(visible)
        .disable_drag_drop_handler()                         // dropped images reach the page
        .build();
}

pub fn open_main(app: &AppHandle) {
    let a = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(w) = a.get_webview_window("main") { let _ = w.show(); let _ = w.unminimize(); let _ = w.set_focus(); return; }
        make_main(&a, true);
    });
}

#[tauri::command]
fn show_main(app: AppHandle) { open_main(&app) }

/// The Downloads folder: XDG's, else ~/Downloads.
fn downloads() -> PathBuf {
    if let Ok(o) = std::process::Command::new("xdg-user-dir").arg("DOWNLOAD").output() {
        let d = String::from_utf8_lossy(&o.stdout).trim().to_string();
        if o.status.success() && !d.is_empty() { return PathBuf::from(d) }
    }
    PathBuf::from(std::env::var("HOME").unwrap_or_default()).join("Downloads")
}

/// Copies a note's image into Downloads, named after the note ("Shopping list.jpg", then "Shopping list (2).jpg"
/// rather than replacing anything). Returns where it went.
#[tauri::command]
async fn save_image(app: AppHandle, name: String, title: String) -> Result<String, String> {
    safe(&name)?;
    let from = dir(&app, Some("attachments"))?.join(&name);
    let ext = from.extension().and_then(|e| e.to_str()).unwrap_or("jpg").to_string();
    let clean: String = title.chars().map(|c| if c == '/' || c == '\\' || c.is_control() { ' ' } else { c }).collect();
    let mut base: String = clean.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(60).collect();
    base = base.trim_matches('.').trim().to_string();
    if base.is_empty() { base = "Scratchy image".into() }
    let to_dir = downloads();
    fs::create_dir_all(&to_dir).map_err(|e| e.to_string())?;
    let mut to = to_dir.join(format!("{base}.{ext}"));
    let mut n = 2;
    while to.exists() { to = to_dir.join(format!("{base} ({n}).{ext}")); n += 1; }
    fs::copy(&from, &to).map_err(|e| e.to_string())?;
    Ok(to.to_string_lossy().into_owned())
}

/// What the clipboard holds for the Paste button: an image (base64) if there is one, else its text.
#[tauri::command]
async fn clipboard_read() -> Result<serde_json::Value, String> {
    use std::process::Command;
    let types = Command::new("wl-paste").arg("--list-types").output().map_err(|e| e.to_string())?;
    let types = String::from_utf8_lossy(&types.stdout).into_owned();
    let image = ["image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp"].into_iter()
        .find(|t| types.lines().any(|l| l.trim() == *t));
    if let Some(t) = image {
        let out = Command::new("wl-paste").args(["--no-newline", "--type", t]).output().map_err(|e| e.to_string())?;
        if out.status.success() && !out.stdout.is_empty() {
            return Ok(serde_json::json!({ "kind": "image", "mime": t, "b64": STANDARD.encode(&out.stdout) }));
        }
    }
    if types.trim().is_empty() { return Ok(serde_json::json!({ "kind": "empty" })); }
    let out = Command::new("wl-paste").arg("--no-newline").output().map_err(|e| e.to_string())?;
    Ok(serde_json::json!({ "kind": "text", "text": String::from_utf8_lossy(&out.stdout) }))
}

/// The notes window's zoom, the whole page like a browser's (the card zooms only its text, in the page).
#[tauri::command]
fn set_zoom(window: tauri::WebviewWindow, factor: f64) -> Result<(), String> {
    window.set_zoom(factor.clamp(0.5, 2.0)).map_err(|e| e.to_string())
}

/// Errors from the pages, printed with the app's own output.
#[tauri::command]
async fn log(line: String) { eprintln!("[page] {line}"); }

/// Installed by the package (/usr/lib/scratchy, started through /usr/bin/scratchy), not a build in the project.
fn installed() -> bool {
    std::env::current_exe().is_ok_and(|e| e.starts_with("/usr/lib/scratchy"))
}

/// LD_PRELOAD and LD_LIBRARY_PATH as the app was started (for the autostart entry of a project build).
static LAUNCH_ENV: std::sync::OnceLock<Vec<(String, String)>> = std::sync::OnceLock::new();
fn launch_env() -> &'static [(String, String)] { LAUNCH_ENV.get().map(|v| v.as_slice()).unwrap_or(&[]) }

fn main() {
    let _ = LAUNCH_ENV.set(["LD_PRELOAD", "LD_LIBRARY_PATH"].iter()
        .filter_map(|k| std::env::var(k).ok().filter(|v| !v.is_empty()).map(|v| (k.to_string(), v))).collect());
    // gtk-layer-shell is preloaded for this process only: the helpers it starts (wl-paste, hyprctl…) don't
    // need GTK loaded into them. Nothing else runs yet, so changing the environment is safe here.
    std::env::remove_var("LD_PRELOAD");
    let args: Vec<String> = std::env::args().collect();
    let toggle = args.iter().any(|a| a == "--toggle");
    // Started at login: stay in the background until the shortcut or the tray is used.
    let background = args.iter().any(|a| a == "--background");
    // A second launch (the Hyprland keybinding) hands the request to the running app and quits at once.
    if remote::send(if toggle { "toggle" } else { "show" }) { return; }
    if toggle { overlay::OPEN_ON_BOOT.store(true, std::sync::atomic::Ordering::SeqCst); }

    #[allow(unused_mut)]
    let mut b = tauri::Builder::default();
    #[cfg(not(unix))]
    {
        b = b.plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            if argv.iter().any(|a| a == "--toggle") { overlay::toggle(app) }
        }));
    }
    b.plugin(tauri_plugin_http::init())
        // att://localhost/<name>: the note images, read by the page straight from disk (no base64 round trip).
        .register_asynchronous_uri_scheme_protocol("att", |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            let name = request.uri().path().trim_start_matches('/').to_string();
            std::thread::spawn(move || {
                let res = (|| {
                    safe(&name).ok()?;
                    let bytes = fs::read(dir(&app, Some("attachments")).ok()?.join(&name)).ok()?;
                    let mime = if name.ends_with(".png") { "image/png" } else if name.ends_with(".webp") { "image/webp" } else { "image/jpeg" };
                    tauri::http::Response::builder()
                        .header("Content-Type", mime)
                        .header("Cache-Control", "max-age=31536000, immutable")     // names never get new contents
                        .header("Access-Control-Allow-Origin", "*")
                        .body(bytes).ok()
                })();
                responder.respond(res.unwrap_or_else(|| tauri::http::Response::builder().status(404).body(Vec::new()).unwrap()));
            });
        })
        .manage(Oauth(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            read_text, write_text, remove_text, read_attachment, write_attachment, has_attachment, data_path, system_accent, toggle_path, autostart_get, autostart_set,
            google_client, oauth_start, oauth_wait, open_url, secret_get, secret_set, secret_delete,
            card_hide, card_input, card_keyboard, card_boot, show_main, clipboard_read, set_zoom, save_image, shots::copy_image,
            shots::shot_peek, shots::shot_take, shots::shot_clear, shots::shots_dir, shots::latest_screenshot, hypr::card_blur, hypr::hyprland, log
        ])
        .setup(move |app| {
            let h = app.handle().clone();
            overlay::setup(&h);
            if let Err(e) = tray::start(&h) { eprintln!("no tray icon: {e}"); }
            shots::start(&h);
            #[cfg(unix)]
            hypr::start();
            make_main(&h, !toggle && !background);
            remote::listen(h);
            Ok(())
        })
        // Closing the notes window hides it, ready for next time. Quit is in the tray.
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" { api.prevent_close(); let _ = window.hide(); }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Scratchy");
}
