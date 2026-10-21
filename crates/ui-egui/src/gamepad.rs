//! Gamepad controller support: virtual cursor navigation, primary/secondary click & drag,
//! right-stick scrolling, D-pad frame stepping and layer selection, shoulder-button undo/redo,
//! trigger zoom, transport play/pause, and visual cursor feedback.

use egui::{Color32, Event, Key, Modifiers, MouseWheelUnit, PointerButton, Pos2, Rect, Stroke, Vec2, pos2, vec2};
use serde_json::json;

use crate::EffectcraftApp;
use crate::state::Tool;

pub const BTN_SOUTH: u32 = 1 << 0;
pub const BTN_EAST: u32 = 1 << 1;
pub const BTN_WEST: u32 = 1 << 2;
pub const BTN_NORTH: u32 = 1 << 3;
pub const BTN_L1: u32 = 1 << 4;
pub const BTN_R1: u32 = 1 << 5;
pub const BTN_SELECT: u32 = 1 << 6;
pub const BTN_START: u32 = 1 << 7;
pub const BTN_L3: u32 = 1 << 8;
pub const BTN_R3: u32 = 1 << 9;
pub const BTN_DPAD_UP: u32 = 1 << 10;
pub const BTN_DPAD_DOWN: u32 = 1 << 11;
pub const BTN_DPAD_LEFT: u32 = 1 << 12;
pub const BTN_DPAD_RIGHT: u32 = 1 << 13;

/// Snapshot of a hardware gamepad's buttons and analog axes.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct GamepadInput {
    pub seq: u64,
    pub buttons: u32,
    pub lx: f32,
    pub ly: f32,
    pub rx: f32,
    pub ry: f32,
    pub lt: f32,
    pub rt: f32,
}

impl GamepadInput {
    /// Parse a line written by `EffectCraftActivity.flushGamepadState`
    /// (`"<seq> <buttons> <lx> <ly> <rx> <ry> <lt> <rt>"`).
    pub fn parse_line(line: &str) -> Option<Self> {
        let mut parts = line.split_whitespace();
        let seq = parts.next()?.parse::<u64>().ok()?;
        let buttons = parts.next()?.parse::<u32>().ok()?;
        let lx = parts.next()?.parse::<f32>().ok().unwrap_or(0.0);
        let ly = parts.next()?.parse::<f32>().ok().unwrap_or(0.0);
        let rx = parts.next()?.parse::<f32>().ok().unwrap_or(0.0);
        let ry = parts.next()?.parse::<f32>().ok().unwrap_or(0.0);
        let lt = parts.next()?.parse::<f32>().ok().unwrap_or(0.0);
        let rt = parts.next()?.parse::<f32>().ok().unwrap_or(0.0);
        Some(Self {
            seq,
            buttons,
            lx: sanitize_axis(lx),
            ly: sanitize_axis(ly),
            rx: sanitize_axis(rx),
            ry: sanitize_axis(ry),
            lt: sanitize_axis(lt).clamp(0.0, 1.0),
            rt: sanitize_axis(rt).clamp(0.0, 1.0),
        })
    }

    pub fn any_active(&self) -> bool {
        self.buttons != 0
            || self.lx.abs() > 0.01
            || self.ly.abs() > 0.01
            || self.rx.abs() > 0.01
            || self.ry.abs() > 0.01
            || self.lt > 0.05
            || self.rt > 0.05
    }
}

fn sanitize_axis(v: f32) -> f32 {
    if v.is_finite() { v.clamp(-1.0, 1.0) } else { 0.0 }
}

/// Persistent gamepad controller state owned by [`EffectcraftApp`].
#[derive(Clone, Debug)]
pub struct GamepadController {
    pub current: GamepadInput,
    pub prev_buttons: u32,
    pub cursor: Option<Pos2>,
    pub last_active_time: f64,
    pub last_step_time: f64,
    pub last_zoom_time: f64,
    pub primary_down: bool,
    pub secondary_down: bool,
}

impl Default for GamepadController {
    fn default() -> Self {
        Self {
            current: GamepadInput::default(),
            prev_buttons: 0,
            cursor: None,
            last_active_time: -100.0,
            last_step_time: 0.0,
            last_zoom_time: 0.0,
            primary_down: false,
            secondary_down: false,
        }
    }
}

impl GamepadController {
    /// Update the controller with the latest hardware snapshot.
    pub fn set_input(&mut self, input: GamepadInput) {
        self.current = input;
    }

    /// Whether the virtual gamepad cursor should be drawn this frame.
    pub fn is_cursor_visible(&self, now: f64) -> bool {
        self.cursor.is_some() && (now - self.last_active_time) <= 12.0
    }
}

/// Advance the gamepad controller for one UI frame, injecting pointer/wheel/key events into
/// `app.synthetic` and running transport/timeline/tool commands.
pub fn step(app: &mut EffectcraftApp, ctx: &egui::Context, now: f64, dt: f32) {
    let input = app.gamepad.current;
    let prev = app.gamepad.prev_buttons;
    let pressed = input.buttons & !prev;
    let released = !input.buttons & prev;
    app.gamepad.prev_buttons = input.buttons;

    let has_activity = input.any_active() || pressed != 0 || released != 0;
    if has_activity {
        app.gamepad.last_active_time = now;
    }

    if !app.gamepad.is_cursor_visible(now) && !has_activity {
        return;
    }

    let screen = ctx.content_rect();
    if !screen.is_positive() {
        return;
    }

    let dt = dt.clamp(1.0 / 240.0, 0.1);
    let mut pos = app.gamepad.cursor.unwrap_or_else(|| screen.center());

    // Left stick moves the virtual cursor with a quadratic precision curve.
    if input.lx.abs() > 0.01 || input.ly.abs() > 0.01 {
        let base_speed = (screen.width().min(screen.height()) * 0.85).clamp(360.0, 950.0);
        let curve = |v: f32| v.signum() * (0.35 * v.abs() + 0.65 * v * v);
        let vx = curve(input.lx) * base_speed * dt;
        let vy = curve(input.ly) * base_speed * dt;
        pos.x = (pos.x + vx).clamp(screen.min.x + 2.0, screen.max.x - 2.0);
        pos.y = (pos.y + vy).clamp(screen.min.y + 2.0, screen.max.y - 2.0);
        app.gamepad.cursor = Some(pos);
        app.synthetic.push(Event::PointerMoved(pos));
    } else if app.gamepad.cursor.is_none() && has_activity {
        pos = pos.clamp(screen.min + vec2(2.0, 2.0), screen.max - vec2(2.0, 2.0));
        app.gamepad.cursor = Some(pos);
        app.synthetic.push(Event::PointerMoved(pos));
    }

    // South button (A / Cross): primary mouse button press / hold-drag / release.
    if (pressed & BTN_SOUTH) != 0 {
        app.gamepad.primary_down = true;
        app.synthetic.push(Event::PointerMoved(pos));
        app.synthetic.push(Event::PointerButton {
            pos,
            button: PointerButton::Primary,
            pressed: true,
            modifiers: Modifiers::NONE,
        });
    }
    if (released & BTN_SOUTH) != 0 && app.gamepad.primary_down {
        app.gamepad.primary_down = false;
        app.synthetic.push(Event::PointerButton {
            pos,
            button: PointerButton::Primary,
            pressed: false,
            modifiers: Modifiers::NONE,
        });
    }

    // East button (B / Circle): close open modal dialog, or right-click at cursor.
    if (pressed & BTN_EAST) != 0 {
        if app.dialog.is_some() {
            app.synthetic.push(Event::Key {
                key: Key::Escape,
                physical_key: None,
                pressed: true,
                repeat: false,
                modifiers: Modifiers::NONE,
            });
            app.synthetic.push(Event::Key {
                key: Key::Escape,
                physical_key: None,
                pressed: false,
                repeat: false,
                modifiers: Modifiers::NONE,
            });
        } else {
            app.gamepad.secondary_down = true;
            app.synthetic.push(Event::PointerMoved(pos));
            app.synthetic.push(Event::PointerButton {
                pos,
                button: PointerButton::Secondary,
                pressed: true,
                modifiers: Modifiers::NONE,
            });
        }
    }
    if (released & BTN_EAST) != 0 && app.gamepad.secondary_down {
        app.gamepad.secondary_down = false;
        app.synthetic.push(Event::PointerButton {
            pos,
            button: PointerButton::Secondary,
            pressed: false,
            modifiers: Modifiers::NONE,
        });
    }

    // Right stick: scroll/pan the panel under the virtual cursor.
    if input.rx.abs() > 0.01 || input.ry.abs() > 0.01 {
        let scroll_speed = 520.0 * dt;
        let delta = vec2(-input.rx * scroll_speed, -input.ry * scroll_speed);
        app.synthetic.push(Event::PointerMoved(pos));
        app.synthetic.push(Event::MouseWheel {
            unit: MouseWheelUnit::Point,
            delta,
            modifiers: Modifiers::NONE,
            phase: egui::TouchPhase::Move,
        });
    }

    // West button (X / Square): Toggle Play / Pause preview.
    if (pressed & BTN_WEST) != 0 {
        let _ = crate::menus::invoke(app, ctx, "playback.toggle", json!({}));
    }

    // North button (Y / Triangle): Cycle primary tools (Selection -> Hand -> Rectangle -> Pen -> Type).
    if (pressed & BTN_NORTH) != 0 {
        let next_tool = match app.ui.tool {
            Tool::Selection => Tool::Hand,
            Tool::Hand => Tool::Rectangle,
            Tool::Rectangle | Tool::RoundedRect | Tool::Ellipse | Tool::Polygon | Tool::Star => Tool::Pen,
            Tool::Pen => Tool::Type,
            _ => Tool::Selection,
        };
        app.ui.tool = next_tool;
    }

    // L1 / R1 shoulder buttons: Undo / Redo (or L1+R1 together: Add Comp to Render Queue).
    if (pressed & (BTN_L1 | BTN_R1)) != 0 && (input.buttons & (BTN_L1 | BTN_R1)) == (BTN_L1 | BTN_R1) {
        let _ = crate::menus::invoke(app, ctx, "renderQueue.add", json!({}));
    } else {
        if (pressed & BTN_L1) != 0 {
            let _ = crate::menus::invoke(app, ctx, "edit.undo", json!({}));
        }
        if (pressed & BTN_R1) != 0 {
            let _ = crate::menus::invoke(app, ctx, "edit.redo", json!({}));
        }
    }

    // Start button: toggle Command Palette.
    if (pressed & BTN_START) != 0 {
        if app.dialog == Some(crate::Dialog::CommandPalette) {
            app.dialog = None;
        } else {
            let _ = crate::menus::invoke(app, ctx, "ui.commandPalette", json!({}));
        }
    }

    // Select / Back button: toggle Home screen.
    if (pressed & BTN_SELECT) != 0 {
        app.ui.start_screen = !app.ui.start_screen;
    }

    // L3 (Left stick click): Zoom Viewer to Fit.
    if (pressed & BTN_L3) != 0 {
        let _ = crate::menus::invoke(app, ctx, "view.zoomFit", json!({}));
    }

    // R3 (Right stick click): Maximize / Restore panel under cursor.
    if (pressed & BTN_R3) != 0 {
        let _ = crate::menus::invoke(app, ctx, "view.maximizePanel", json!({}));
    }

    // D-Pad Left / Right: step playhead backward / forward by 1 frame (with key-repeat when held).
    let step_repeat = (now - app.gamepad.last_step_time) >= 0.085;
    if (pressed & BTN_DPAD_LEFT) != 0 || ((input.buttons & BTN_DPAD_LEFT) != 0 && step_repeat) {
        app.gamepad.last_step_time = now;
        let _ = crate::menus::invoke(app, ctx, "time.prevFrame", json!({}));
    } else if (pressed & BTN_DPAD_RIGHT) != 0 || ((input.buttons & BTN_DPAD_RIGHT) != 0 && step_repeat) {
        app.gamepad.last_step_time = now;
        let _ = crate::menus::invoke(app, ctx, "time.nextFrame", json!({}));
    }

    // D-Pad Up / Down: select previous / next layer in the active composition.
    if (pressed & BTN_DPAD_UP) != 0 {
        step_layer_selection(app, -1);
    }
    if (pressed & BTN_DPAD_DOWN) != 0 {
        step_layer_selection(app, 1);
    }

    // Analog triggers (LT / RT): zoom out / zoom in.
    if (now - app.gamepad.last_zoom_time) >= 0.16 {
        if input.rt > 0.45 {
            app.gamepad.last_zoom_time = now;
            let _ = crate::menus::invoke(app, ctx, "view.zoomIn", json!({}));
        } else if input.lt > 0.45 {
            app.gamepad.last_zoom_time = now;
            let _ = crate::menus::invoke(app, ctx, "view.zoomOut", json!({}));
        }
    }

    if input.any_active() {
        ctx.request_repaint();
    }
}

fn step_layer_selection(app: &mut EffectcraftApp, dir: isize) {
    let Some(comp) = app.session.active_comp() else { return };
    if comp.layers.is_empty() {
        return;
    }
    let current = app.session.state.selected_layers.first().copied();
    let idx = current
        .and_then(|id| comp.layers.iter().position(|l| l.id == id))
        .map(|i| (i as isize + dir).rem_euclid(comp.layers.len() as isize) as usize)
        .unwrap_or(0);
    if let Some(layer) = comp.layers.get(idx) {
        let id = layer.id.0;
        let _ = app.session.execute("layer.select", json!({"layer": id}));
    }
}

/// Paint the virtual gamepad cursor on the foreground layer when gamepad control is active.
pub fn paint_cursor(app: &EffectcraftApp, ctx: &egui::Context, now: f64) {
    if !app.gamepad.is_cursor_visible(now) {
        return;
    }
    let Some(pos) = app.gamepad.cursor else { return };
    let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Tooltip, egui::Id::new("gamepad-cursor")));
    let down = app.gamepad.primary_down;
    let radius = if down { 7.5 } else { 9.0 };
    let ring_color = if down { app.tokens.accent } else { Color32::WHITE };

    // Outer shadow ring for contrast over both dark and light footage
    painter.circle_stroke(pos, radius + 1.5, Stroke::new(2.5, Color32::from_black_alpha(190)));
    painter.circle_stroke(pos, radius, Stroke::new(2.0, ring_color));
    painter.circle_filled(pos, if down { 3.5 } else { 2.2 }, app.tokens.accent);

    // Subtle crosshair ticks
    let d0 = radius + 2.5;
    let d1 = radius + 6.5;
    for dir in [Vec2::X, -Vec2::X, Vec2::Y, -Vec2::Y] {
        painter.line_segment([pos + dir * d0, pos + dir * d1], Stroke::new(1.5, ring_color));
    }
    let _ = (Rect::NOTHING, pos2(0.0, 0.0));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_gamepad_state_line_and_rejects_malformed() {
        let parsed = GamepadInput::parse_line("42 5 0.5000 -0.2500 0.0000 0.7500 0.1000 0.9000").unwrap();
        assert_eq!(parsed.seq, 42);
        assert_eq!(parsed.buttons, BTN_SOUTH | BTN_WEST);
        assert!((parsed.lx - 0.5).abs() < 1e-4);
        assert!((parsed.ly + 0.25).abs() < 1e-4);
        assert!((parsed.ry - 0.75).abs() < 1e-4);
        assert!((parsed.lt - 0.1).abs() < 1e-4);
        assert!((parsed.rt - 0.9).abs() < 1e-4);
        assert!(parsed.any_active());

        assert!(GamepadInput::parse_line("").is_none());
        assert!(GamepadInput::parse_line("not_a_number").is_none());
    }

    #[test]
    fn gamepad_moves_virtual_cursor_and_clicks() {
        let ctx = egui::Context::default();
        let mut app = EffectcraftApp::new(effectcraft_engine::Session::default());
        app.gamepad.set_input(GamepadInput {
            seq: 1,
            buttons: BTN_SOUTH,
            lx: 0.8,
            ly: -0.4,
            ..Default::default()
        });
        let raw = egui::RawInput {
            screen_rect: Some(Rect::from_min_size(pos2(0.0, 0.0), vec2(800.0, 600.0))),
            ..Default::default()
        };
        let mut out = ctx.run_ui(raw, |ui| {
            step(&mut app, ui.ctx(), 1.0, 1.0 / 60.0);
        });
        out.textures_delta.clear();
        assert!(app.gamepad.cursor.is_some());
        assert!(app.gamepad.primary_down);
        assert!(app.synthetic.iter().any(|e| matches!(e, Event::PointerButton { button: PointerButton::Primary, pressed: true, .. })));
    }
}
