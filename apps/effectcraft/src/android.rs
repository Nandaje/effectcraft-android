//! Android storage paths, native system file picker bridge, hardware gamepad bridge, and
//! `NativeActivity` helpers.
//!
//! Bridges EffectCraft's file dialogs (`File ▸ Import`, `File ▸ Open`, `File ▸ Save As`,
//! `File ▸ Export`, Render Queue `Output To`, Home Screen "Import Footage…", Project panel
//! "Import File…") to `EffectCraftActivity.java`, which launches Android's system document picker
//! (`Intent.ACTION_OPEN_DOCUMENT` / `Intent.ACTION_CREATE_DOCUMENT`), materializes `content://`
//! URIs into readable/writable local files, and syncs completed exports back to SAF destinations
//! and public Movies/Download folders.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use effectcraft_ui_egui::gamepad::GamepadInput;
use effectcraft_ui_egui::{DialogDone, EffectcraftApp, FileDialog};
use serde_json::json;

static NEXT_REQ_ID: AtomicU64 = AtomicU64::new(1);

/// Android shared storage root (`EXTERNAL_STORAGE`, or `/storage/emulated/0`).
pub fn shared_storage_root() -> PathBuf {
    std::env::var_os("EXTERNAL_STORAGE")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/storage/emulated/0"))
}

/// Directory shared between Rust and `EffectCraftActivity.java` for file-dialog IPC, gamepad state,
/// imported `content://` files, and staged exports.
pub fn ipc_dir() -> Option<PathBuf> {
    effectcraft_host::config_dir().or_else(|| {
        let internal = PathBuf::from("/data/data/ai.storyteller.effectcraft/files");
        internal.is_dir().then_some(internal)
    })
}

/// Check whether `dir` actually allows creating, writing, and renaming files (on Android 11+
/// Scoped Storage, `create_dir_all` can succeed on `/storage/emulated/0/...` even when creating
/// arbitrary files inside it fails with `EACCES`).
fn is_dir_writable(dir: &Path) -> bool {
    if std::fs::create_dir_all(dir).is_err() {
        return false;
    }
    let probe = dir.join(".ec_write_probe");
    let ok = std::fs::write(&probe, b"ok").is_ok();
    let _ = std::fs::remove_file(&probe);
    ok
}

/// Folder where `EffectCraftActivity.java` materializes files picked from `content://` providers.
pub fn imported_files_dir() -> PathBuf {
    if let Some(cfg) = ipc_dir() {
        let dir = cfg.join("Imported");
        let _ = std::fs::create_dir_all(&dir);
        return dir;
    }
    default_projects_dir()
}

/// Default folder for exported videos, frame sequences, and rendered previews on Android.
/// Uses `<ipc_dir>/Exports` (guaranteed writable under Android Scoped Storage and automatically
/// mirrored by `EffectCraftActivity.java` to `/storage/emulated/0/Movies/EffectCraft`).
pub fn default_exports_dir() -> PathBuf {
    if let Some(cfg) = ipc_dir() {
        let exports = cfg.join("Exports");
        if std::fs::create_dir_all(&exports).is_ok() {
            return exports;
        }
    }
    let shared_movies = shared_storage_root().join("Movies").join("EffectCraft");
    if is_dir_writable(&shared_movies) {
        return shared_movies;
    }
    std::env::temp_dir()
}

/// Default folder for saving and opening EffectCraft projects on Android.
pub fn default_projects_dir() -> PathBuf {
    if let Some(cfg) = ipc_dir() {
        let projects = cfg.join("Projects");
        if std::fs::create_dir_all(&projects).is_ok() {
            return projects;
        }
    }
    let shared_docs = shared_storage_root().join("Documents").join("EffectCraft");
    if is_dir_writable(&shared_docs) {
        return shared_docs;
    }
    default_exports_dir()
}

/// Standard Android directories scanned when searching for local projects and media files.
pub fn search_roots() -> Vec<PathBuf> {
    let root = shared_storage_root();
    let mut dirs = vec![
        imported_files_dir(),
        default_exports_dir(),
        default_projects_dir(),
        root.join("Movies").join("EffectCraft"),
        root.join("Download").join("EffectCraft"),
        root.join("Documents").join("EffectCraft"),
        root.join("Documents"),
        root.join("Download"),
        root.join("DCIM").join("Camera"),
        root.join("DCIM"),
        root.join("Movies"),
        root.join("Pictures"),
        root.join("Music"),
    ];
    if let Some(cfg) = ipc_dir() {
        dirs.push(cfg.join("Imported"));
        dirs.push(cfg.join("Exports"));
        dirs.push(cfg.join("Projects"));
        dirs.push(cfg);
    }
    let mut unique = Vec::new();
    for dir in dirs {
        if !unique.contains(&dir) {
            unique.push(dir);
        }
    }
    unique
}

/// Resolve a save path (`name` with optional required extension `ext`) inside `base_dir` when
/// `name` has no writable parent directory, ensuring its parent directory exists.
pub fn resolve_save_path_in(base_dir: &Path, name: &str, ext: &str) -> String {
    let fallback_name = if ext.eq_ignore_ascii_case("ecproj") || ext.is_empty() {
        "Untitled.ecproj"
    } else {
        "Export"
    };
    let trimmed = if name.trim().is_empty() { fallback_name } else { name.trim() };
    let candidate = Path::new(trimmed);
    let mut full = if candidate.is_absolute()
        && candidate
            .parent()
            .is_some_and(|p| p != Path::new("/") && is_dir_writable(p))
    {
        candidate.to_path_buf()
    } else {
        let file_name = candidate
            .file_name()
            .and_then(|n| n.to_str())
            .filter(|n| !n.is_empty())
            .unwrap_or(fallback_name);
        base_dir.join(file_name)
    };
    if !ext.is_empty() {
        let has_ext = full
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| e.eq_ignore_ascii_case(ext));
        if !has_ext {
            full.set_extension(ext);
        }
    }
    if let Some(parent) = full.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    full.to_string_lossy().to_string()
}

/// Resolve a save path under [`default_exports_dir`] (for exports/renders) or
/// [`default_projects_dir`] (for `.ecproj` / `.ecprojx` projects).
pub fn resolve_save_path(name: &str, ext: &str) -> Option<String> {
    let is_proj = ext.eq_ignore_ascii_case("ecproj")
        || ext.eq_ignore_ascii_case("ecprojx")
        || Path::new(name)
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| e.eq_ignore_ascii_case("ecproj") || e.eq_ignore_ascii_case("ecprojx"));
    let base = if is_proj { default_projects_dir() } else { default_exports_dir() };
    Some(resolve_save_path_in(&base, name, ext))
}

/// Collect matching files under `roots` (up to `max_depth` directory levels), newest first.
pub fn find_matching_files(roots: &[PathBuf], exts: &[&str], max_depth: usize) -> Vec<String> {
    let mut matches: Vec<(u64, PathBuf)> = Vec::new();
    for root in roots {
        collect_matching(root, exts, max_depth, &mut matches);
    }
    matches.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
    let mut out = Vec::new();
    for (_, path) in matches {
        let s = path.to_string_lossy().to_string();
        if !out.contains(&s) {
            out.push(s);
        }
    }
    out
}

fn collect_matching(dir: &Path, exts: &[&str], depth_left: usize, out: &mut Vec<(u64, PathBuf)>) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for entry in rd.flatten() {
        let path = entry.path();
        let name = entry.file_name();
        if name.to_string_lossy().starts_with('.') {
            continue;
        }
        let Ok(md) = entry.metadata() else { continue };
        if md.is_dir() {
            if depth_left > 0 {
                collect_matching(&path, exts, depth_left - 1, out);
            }
            continue;
        }
        let matches_ext = exts.is_empty()
            || path
                .extension()
                .and_then(|e| e.to_str())
                .is_some_and(|e| exts.iter().any(|want| e.eq_ignore_ascii_case(want)));
        if matches_ext {
            let modified = md
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map_or(0, |d| d.as_secs());
            out.push((modified, path));
        }
    }
}

/// Launch the Android system file picker via `EffectCraftActivity.java` from a background worker
/// thread and wait for the user's selection. Returns `None` only if the IPC directory is unavailable.
fn request_system_picker(mode: &str, exts: &[&str], default_name: &str) -> Option<Vec<String>> {
    let dir = ipc_dir()?;
    let _ = std::fs::create_dir_all(&dir);
    let req_id = NEXT_REQ_ID.fetch_add(1, Ordering::Relaxed).to_string();
    let req_path = dir.join(".ec_dialog_req");
    let req_tmp = dir.join(".ec_dialog_req.tmp");
    let resp_path = dir.join(".ec_dialog_resp");
    let _ = std::fs::remove_file(&resp_path);

    let payload = format!("{req_id}\n{mode}\n{}\n{default_name}\n", exts.join(","));
    std::fs::write(&req_tmp, payload.as_bytes()).ok()?;
    std::fs::rename(&req_tmp, &req_path).ok()?;

    // Wait up to 5 minutes while the user browses in the Android system file picker.
    let start = Instant::now();
    let timeout = Duration::from_secs(300);
    while start.elapsed() < timeout {
        if let Ok(text) = std::fs::read_to_string(&resp_path) {
            let mut lines = text.lines();
            let answered_id = lines.next().map(str::trim).unwrap_or_default();
            if answered_id == req_id {
                let _ = std::fs::remove_file(&resp_path);
                let paths: Vec<String> = lines.map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect();
                return Some(paths);
            }
        }
        std::thread::sleep(Duration::from_millis(45));
    }
    let _ = std::fs::remove_file(&req_path);
    Some(Vec::new())
}

/// Answer a [`FileDialog`] request on a background worker thread using Android's native system
/// file picker (with a fallback to shared-storage resolution when running outside `EffectCraftActivity`).
pub fn resolve_dialog(dialog: FileDialog) -> Vec<String> {
    match &dialog {
        FileDialog::Open { exts, many } => {
            let refs: Vec<&str> = exts.iter().map(String::as_str).collect();
            let mode = if *many { "OPEN_MULTI" } else { "OPEN" };
            if let Some(picked) = request_system_picker(mode, &refs, "") {
                return picked;
            }
            let mut found = find_matching_files(&search_roots(), &refs, 2);
            if !*many {
                found.truncate(1);
            }
            found
        }
        FileDialog::OpenProject => {
            let exts = ["ecproj", "ecprojx"];
            if let Some(picked) = request_system_picker("OPEN_PROJECT", &exts, "") {
                return picked;
            }
            let mut found = find_matching_files(&search_roots(), &exts, 2);
            found.truncate(1);
            found
        }
        FileDialog::Folder => {
            if let Some(picked) = request_system_picker("FOLDER", &[], "")
                && !picked.is_empty()
            {
                return picked;
            }
            vec![default_exports_dir().to_string_lossy().to_string()]
        }
        FileDialog::Save { default, ext, project } => {
            let inferred_ext = Path::new(default)
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or_default();
            let wanted_ext = if *project && ext.is_empty() {
                "ecproj"
            } else if !ext.is_empty() {
                ext.as_str()
            } else {
                inferred_ext
            };
            let default_with_ext = resolve_save_path(default, wanted_ext).unwrap_or_else(|| default.clone());
            let exts: Vec<&str> = if wanted_ext.is_empty() { Vec::new() } else { vec![wanted_ext] };
            if let Some(picked) = request_system_picker("SAVE", &exts, &default_with_ext) {
                return picked;
            }
            vec![default_with_ext]
        }
    }
}

/// Non-blocking `Hooks::file_dialog_async` implementation for Android.
pub fn show(dialog: FileDialog, done: DialogDone, ctx: egui::Context) {
    let spawned = std::thread::Builder::new()
        .name("ec-android-dialog".into())
        .stack_size(4 * 1024 * 1024)
        .spawn(move || {
            let paths = resolve_dialog(dialog);
            done(paths);
            ctx.request_repaint();
            for delay_ms in [80_u64, 200, 450, 900] {
                std::thread::sleep(Duration::from_millis(delay_ms));
                ctx.request_repaint();
            }
        });
    if let Err(e) = spawned {
        log::error!("android file dialog: {e}");
    }
}

/// Install non-blocking fallback hooks onto `app`.
///
/// Interactive dialogs on Android always go through [`Hooks::file_dialog_async`] ([`show`]).
/// These synchronous fallbacks never block `android_main` on `request_system_picker` (which would
/// deadlock `NativeActivity` when pausing the window to open the system picker).
pub fn install_hooks(app: &mut EffectcraftApp) {
    app.hooks.pick_files = Some(Box::new(|exts: &[&str]| find_matching_files(&search_roots(), exts, 2)));
    app.hooks.pick_save = Some(Box::new(|name: &str| resolve_save_path(name, "ecproj")));
    app.hooks.pick_open_project = Some(Box::new(|| {
        find_matching_files(&search_roots(), &["ecproj", "ecprojx"], 2).into_iter().next()
    }));
    app.hooks.pick_folder = Some(Box::new(|| Some(default_exports_dir().to_string_lossy().to_string())));
    app.hooks.pick_save_file = Some(Box::new(|name: &str, ext: &str| resolve_save_path(name, ext)));
}

/// Poll for files opened from an external Android file manager (`Intent.ACTION_VIEW` /
/// `Intent.ACTION_SEND`, written by `EffectCraftActivity.java` to `.ec_incoming_open`).
pub fn poll_incoming_files(app: &mut EffectcraftApp, ctx: &egui::Context) {
    let Some(dir) = ipc_dir() else { return };
    let incoming = dir.join(".ec_incoming_open");
    let Ok(text) = std::fs::read_to_string(&incoming) else { return };
    let _ = std::fs::remove_file(&incoming);
    let paths: Vec<String> = text.lines().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect();
    if paths.is_empty() {
        return;
    }
    let is_proj = |p: &str| {
        Path::new(p)
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| e.eq_ignore_ascii_case("ecproj") || e.eq_ignore_ascii_case("ecprojx"))
    };
    if let Some(proj) = paths.iter().find(|p| is_proj(p)) {
        if let Err(e) = effectcraft_ui_egui::menus::invoke(app, ctx, "file.open", json!({"path": proj})) {
            app.ui.status = e;
        } else {
            app.ui.start_screen = false;
        }
    }
    let media: Vec<String> = paths.into_iter().filter(|p| !is_proj(p)).collect();
    if !media.is_empty() {
        if let Err(e) = effectcraft_ui_egui::menus::invoke(app, ctx, "file.import", json!({"paths": media})) {
            app.ui.status = e;
        } else {
            app.ui.start_screen = false;
        }
    }
}

/// Poll `.ec_gamepad_state` written by `EffectCraftActivity` for hardware gamepad input.
pub fn poll_gamepad(app: &mut EffectcraftApp, ctx: &egui::Context) {
    let Some(dir) = ipc_dir() else { return };
    let path = dir.join(".ec_gamepad_state");
    let Ok(text) = std::fs::read_to_string(&path) else { return };
    let Some(first_line) = text.lines().next() else { return };
    let Some(state) = GamepadInput::parse_line(first_line) else { return };
    if state.seq != app.gamepad.current.seq || state != app.gamepad.current {
        app.gamepad.set_input(state);
        ctx.request_repaint();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn temp_dir(tag: &str) -> PathBuf {
        static N: AtomicUsize = AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!("ec-android-{tag}-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn resolve_save_path_in_creates_parent_and_adds_extension() {
        let base = temp_dir("save");
        let saved = resolve_save_path_in(&base, "Comp 1", "ecproj");
        assert_eq!(PathBuf::from(&saved), base.join("Comp 1.ecproj"));
        let already = resolve_save_path_in(&base, "Shot.ECPROJ", "ecproj");
        assert_eq!(PathBuf::from(&already), base.join("Shot.ECPROJ"));
        let mp4 = resolve_save_path_in(&base, "/Comp 1.mp4", "mp4");
        assert_eq!(PathBuf::from(&mp4), base.join("Comp 1.mp4"));
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn find_matching_files_filters_extensions_and_skips_hidden() {
        let base = temp_dir("find");
        let sub = base.join("sub");
        std::fs::create_dir_all(&sub).unwrap();
        std::fs::write(base.join("a.ecproj"), b"{}").unwrap();
        std::fs::write(base.join(".hidden.ecproj"), b"{}").unwrap();
        std::fs::write(sub.join("b.ecprojx"), b"<xml/>").unwrap();
        std::fs::write(sub.join("ignore.txt"), b"hi").unwrap();
        let found = find_matching_files(std::slice::from_ref(&base), &["ecproj", "ecprojx"], 2);
        assert_eq!(found.len(), 2);
        assert!(found.iter().any(|p| p.ends_with("a.ecproj")));
        assert!(found.iter().any(|p| p.ends_with("b.ecprojx")));
        let _ = std::fs::remove_dir_all(&base);
    }
}
