use base64::{engine::general_purpose::STANDARD, Engine};
use std::{fs, io::Write, path::PathBuf};
use tauri::{AppHandle, Manager};

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
    Ok(d)
}

/// Writes through a temp file and renames, so a crash never leaves a half-written file.
fn write_atomic(path: PathBuf, data: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    let mut f = fs::File::create(&tmp).map_err(|e| e.to_string())?;
    f.write_all(data).map_err(|e| e.to_string())?;
    f.sync_all().map_err(|e| e.to_string())?;
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
fn data_path(app: AppHandle) -> Result<String, String> {
    Ok(dir(&app, None)?.to_string_lossy().into_owned())
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            read_text, write_text, read_attachment, write_attachment, has_attachment, data_path
        ])
        .run(tauri::generate_context!())
        .expect("error while running Scratchy");
}
