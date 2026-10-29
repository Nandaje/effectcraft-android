use super::{Dialog, EffectcraftApp};

impl EffectcraftApp {
    /// Open `request` through [`Hooks::file_dialog_async`] when present and deliver its answer to
    /// [`menus::dialog_answered`]. Returns `true` when the async dialog was spawned.
    pub(crate) fn spawn_file_dialog(&self, request: FileDialog, command: impl Into<String>, key: &'static str, params: serde_json::Value) -> bool {
        let Some(spawn) = self.hooks.file_dialog_async.as_ref() else { return false };
        let tx = self.dialog_answers.0.clone();
        let answer = DialogAnswer { command: command.into(), key, params, paths: Vec::new() };
        spawn(
            request,
            Box::new(move |paths| {
                let _ = tx.send(DialogAnswer { paths, ..answer });
            }),
        );
        true
    }

    /// Show the crash-recovery dialog for a previous run that didn't exit cleanly.
    pub fn offer_recovery(&mut self, r: effectcraft_engine::autosave::Recovery) {
        if r.autosave.is_some() {
            self.recovery = Some(r);
            self.dialog = Some(Dialog::Recovery);
        }
    }
}
