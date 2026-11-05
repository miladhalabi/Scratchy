//! New screenshots for the card: a new image in the screenshot folder, or an image copied to the clipboard.
//! The newest one waits here until the card attaches it or lets it go; the page decides what to do and when it
//! is forgotten. Omarchy's screenshot both saves a file and copies it, so the two count as one.

use base64::{engine::general_purpose::STANDARD, Engine};
use std::{
    fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::Mutex,
    thread,
    time::{Duration, Instant, SystemTime},
};
use tauri::{AppHandle, Emitter};

struct Shot { bytes: Vec<u8>, mime: String }

static PENDING: Mutex<Option<Shot>> = Mutex::new(None);
/// The folder from Settings; empty means the usual one.
static DIR: Mutex<String> = Mutex::new(String::new());
/// When the last screenshot arrived, and a fingerprint of it, so the file and its clipboard copy count once.
static LAST: Mutex<Option<(Instant, u64)>> = Mutex::new(None);

const IMAGE_EXT: [&str; 4] = ["png", "jpg", "jpeg", "webp"];

/// Omarchy's own rule: OMARCHY_SCREENSHOT_DIR, else XDG_PICTURES_DIR, else ~/Pictures.
fn usual_dir() -> PathBuf {
    for v in ["OMARCHY_SCREENSHOT_DIR", "XDG_PICTURES_DIR"] {
        if let Ok(d) = std::env::var(v) { if !d.trim().is_empty() { return PathBuf::from(d) } }
    }
    if let Ok(o) = Command::new("xdg-user-dir").arg("PICTURES").output() {
        let d = String::from_utf8_lossy(&o.stdout).trim().to_string();
        if o.status.success() && !d.is_empty() { return PathBuf::from(d) }
    }
    PathBuf::from(std::env::var("HOME").unwrap_or_default()).join("Pictures")
}

fn dir() -> PathBuf {
    let d = DIR.lock().unwrap().clone();
    if d.is_empty() { return usual_dir() }
    match d.strip_prefix("~/") {
        Some(rest) => PathBuf::from(std::env::var("HOME").unwrap_or_default()).join(rest),
        None => PathBuf::from(d),
    }
}

fn mime_of(p: &Path) -> Option<&'static str> {
    match p.extension()?.to_str()?.to_ascii_lowercase().as_str() {
        "png" => Some("image/png"),
        "jpg" | "jpeg" => Some("image/jpeg"),
        "webp" => Some("image/webp"),
        _ => None,
    }
}

/// The images in the folder, newest first, with their times.
fn images(d: &Path) -> Vec<(SystemTime, PathBuf)> {
    let mut v: Vec<_> = fs::read_dir(d).into_iter().flatten().flatten()
        .filter(|e| e.path().extension().and_then(|x| x.to_str()).is_some_and(|x| IMAGE_EXT.contains(&x.to_ascii_lowercase().as_str())))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .collect();
    v.sort_by(|a, b| b.0.cmp(&a.0));
    v
}

/// A rough fingerprint: size plus a sample of the bytes. Enough to tell a re-offered clipboard image apart.
fn print(b: &[u8]) -> u64 {
    let mut h: u64 = b.len() as u64;
    for (i, x) in b.iter().step_by((b.len() / 4096).max(1)).enumerate() { h = h.wrapping_mul(31).wrapping_add(*x as u64 ^ i as u64) }
    h
}

fn arrived(app: &AppHandle, bytes: Vec<u8>, mime: &str, from_file: bool) {
    let fp = print(&bytes);
    {
        let mut last = LAST.lock().unwrap();
        if let Some((at, p)) = *last {
            // The clipboard copy of a file just saved (or the other way round), or the same image offered again.
            if at.elapsed() < Duration::from_secs(3) || (!from_file && p == fp) { *last = Some((at, fp)); return }
        }
        *last = Some((Instant::now(), fp));
    }
    eprintln!("[shots] new screenshot from the {}", if from_file { "folder" } else { "clipboard" });
    *PENDING.lock().unwrap() = Some(Shot { bytes, mime: mime.into() });
    let _ = app.emit_to("card", "shot", ());
    crate::tray::waiting(app, true);
}

/// Reads a file once it has stopped growing (the screenshot tool may still be writing it).
fn read_settled(p: &Path) -> Option<Vec<u8>> {
    let mut size = fs::metadata(p).ok()?.len();
    for _ in 0..20 {
        thread::sleep(Duration::from_millis(150));
        let now = fs::metadata(p).ok()?.len();
        if now == size && now > 0 { return fs::read(p).ok() }
        size = now;
    }
    None
}

fn watch_folder(app: AppHandle) {
    let mut seen = SystemTime::now();
    let mut watching = dir();
    loop {
        thread::sleep(Duration::from_secs(1));
        let d = dir();
        if d != watching { watching = d.clone(); seen = SystemTime::now(); continue }   // a new folder: only newer files count
        let Some((at, path)) = images(&d).into_iter().next() else { continue };
        if at <= seen { continue }
        seen = at;
        if SystemTime::now().duration_since(at).unwrap_or_default() > Duration::from_secs(30) { continue }
        if let (Some(bytes), Some(mime)) = (read_settled(&path), mime_of(&path)) { arrived(&app, bytes, mime, true) }
    }
}

fn clipboard_image() -> Option<(Vec<u8>, &'static str)> {
    let types = Command::new("wl-paste").arg("--list-types").output().ok()?;
    let types = String::from_utf8_lossy(&types.stdout).into_owned();
    let t = ["image/png", "image/jpeg", "image/webp"].into_iter().find(|t| types.lines().any(|l| l.trim() == *t))?;
    let out = Command::new("wl-paste").args(["--no-newline", "--type", t]).output().ok()?;
    (out.status.success() && !out.stdout.is_empty()).then_some((out.stdout, t))
}

/// `wl-paste --watch` prints a line each time the clipboard changes; an image then counts as a screenshot.
fn watch_clipboard(app: AppHandle) {
    loop {
        let started = Instant::now();
        let child = Command::new("wl-paste").args(["--watch", "echo", "changed"])
            .stdout(Stdio::piped()).stderr(Stdio::null()).spawn();
        let Ok(mut child) = child else { return };                  // no wl-paste: folder only
        if let Some(out) = child.stdout.take() {
            for _ in BufReader::new(out).lines() {
                if started.elapsed() < Duration::from_secs(2) { continue }   // what was on the clipboard before we started
                if let Some((bytes, mime)) = clipboard_image() { arrived(&app, bytes, mime, false) }
            }
        }
        let _ = child.wait();
        thread::sleep(Duration::from_secs(5));                      // the compositor went away: try again later
    }
}

pub fn start(app: &AppHandle) {
    let a = app.clone();
    thread::spawn(move || watch_folder(a));
    let a = app.clone();
    thread::spawn(move || watch_clipboard(a));
}

fn encode(bytes: &[u8], mime: &str) -> serde_json::Value {
    serde_json::json!({ "mime": mime, "b64": STANDARD.encode(bytes) })
}

/// The waiting screenshot, left waiting (for the chooser's preview).
#[tauri::command]
pub async fn shot_peek() -> Option<serde_json::Value> {
    PENDING.lock().unwrap().as_ref().map(|s| encode(&s.bytes, &s.mime))
}

/// The waiting screenshot, taken: nothing waits after this.
#[tauri::command]
pub async fn shot_take(app: AppHandle) -> Option<serde_json::Value> {
    let s = PENDING.lock().unwrap().take();
    crate::tray::waiting(&app, false);
    s.map(|s| encode(&s.bytes, &s.mime))
}

#[tauri::command]
pub async fn shot_clear(app: AppHandle) {
    *PENDING.lock().unwrap() = None;
    crate::tray::waiting(&app, false);
}

/// Sets the folder from Settings ("" for the usual one) and says which folder is watched.
#[tauri::command]
pub async fn shots_dir(dir: String) -> String {
    *DIR.lock().unwrap() = dir.trim().to_string();
    self::dir().to_string_lossy().into_owned()
}

/// The newest image in the screenshot folder, for the screenshot button.
#[tauri::command]
pub async fn latest_screenshot() -> Option<serde_json::Value> {
    let (_, p) = images(&dir()).into_iter().next()?;
    Some(encode(&fs::read(&p).ok()?, mime_of(&p)?))
}
