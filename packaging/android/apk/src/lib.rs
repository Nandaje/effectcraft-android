//! Android `NativeActivity` entry point (`libeffectcraft.so`).
//!
//! `android-activity`'s glue exports `ANativeActivity_onCreate` and calls `android_main(AndroidApp)`.
//! This thin crate lives outside the workspace's `unsafe_code = "forbid"` lint so it can export
//! `#[unsafe(no_mangle)] pub fn android_main` while every workspace crate stays 100% safe Rust.

#![cfg(target_os = "android")]

#[unsafe(no_mangle)]
pub fn android_main(app: effectcraft::AndroidApp) {
    if let Some(dir) = app.external_data_path().or_else(|| app.internal_data_path()) {
        // SAFETY: called on the native main thread at process start before worker threads are spawned.
        unsafe {
            std::env::set_var("EFFECTCRAFT_CONFIG_DIR", &dir);
        }
    }
    if let Err(e) = effectcraft::run_android(app) {
        log::error!("effectcraft android exited with error: {e}");
    }
}
