use eframe::egui::{self, Align, Color32, FontId, Layout, RichText, Stroke, Vec2};
use nkscale_core::{
    Filter, GameConfig, Monitor, Profile, Resolution, ScaleMode, build_gamescope_args,
    gamescope_environment, list_displays, load_profile, resolve_display_index, save_profile,
};
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::Duration;

const BG: Color32 = Color32::from_rgb(7, 13, 24);
const PANEL: Color32 = Color32::from_rgb(14, 24, 39);
const PANEL_ALT: Color32 = Color32::from_rgb(18, 31, 49);
const CYAN: Color32 = Color32::from_rgb(60, 220, 239);
const VIOLET: Color32 = Color32::from_rgb(158, 121, 255);
const MUTED: Color32 = Color32::from_rgb(143, 163, 185);
const TEXT: Color32 = Color32::from_rgb(229, 239, 249);
const GOOD: Color32 = Color32::from_rgb(96, 226, 171);
const WARNING: Color32 = Color32::from_rgb(255, 195, 99);

struct Diagnostics {
    monitors: Result<Vec<Monitor>, String>,
    gamescope: String,
    gpu: String,
}

struct ScaleApp {
    config: GameConfig,
    monitors: Vec<Monitor>,
    monitor_error: Option<String>,
    gamescope_status: String,
    gpu_status: String,
    diagnostics_rx: Receiver<Diagnostics>,
    log_rx: Option<Receiver<String>>,
    child: Option<Child>,
    status: String,
    last_log: String,
    profile_path: PathBuf,
}

impl ScaleApp {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        apply_theme(&cc.egui_ctx);
        let (tx, diagnostics_rx) = mpsc::channel();
        thread::spawn(move || {
            let monitors = list_displays().map_err(|err| err.to_string());
            let gamescope = probe_gamescope();
            let gpu = probe_vulkan();
            let _ = tx.send(Diagnostics {
                monitors,
                gamescope,
                gpu,
            });
        });

        let config = GameConfig {
            command: test_pattern_command(),
            ..GameConfig::default()
        };
        Self {
            config,
            monitors: Vec::new(),
            monitor_error: None,
            gamescope_status: "Checking Gamescope…".into(),
            gpu_status: "Probing Vulkan adapters…".into(),
            diagnostics_rx,
            log_rx: None,
            child: None,
            status: "Choose a display and launch the built-in test pattern.".into(),
            last_log: String::new(),
            profile_path: profile_path(),
        }
    }

    fn accept_diagnostics(&mut self) {
        if let Ok(result) = self.diagnostics_rx.try_recv() {
            self.gamescope_status = result.gamescope;
            self.gpu_status = result.gpu;
            match result.monitors {
                Ok(monitors) => {
                    self.monitors = monitors;
                    if !self
                        .monitors
                        .iter()
                        .any(|monitor| monitor.index == self.config.display_index)
                        && let Some(first) = self.monitors.first()
                    {
                        self.config.display_index = first.index;
                        self.config.target = Resolution {
                            width: first.width,
                            height: first.height,
                        };
                    }
                }
                Err(error) => self.monitor_error = Some(error),
            }
        }
        if let Some(receiver) = &self.log_rx {
            while let Ok(line) = receiver.try_recv() {
                if !line.trim().is_empty() {
                    self.last_log = line;
                }
            }
        }
        let exit = self.child.as_mut().map(Child::try_wait);
        match exit {
            Some(Ok(Some(code))) => {
                self.child = None;
                self.status = format!("Gamescope exited with {code}.");
            }
            Some(Err(error)) => {
                self.child = None;
                self.status = format!("Could not read Gamescope process state: {error}");
            }
            _ => {}
        }
    }

    fn start_scaling(&mut self) {
        if self.child.is_some() {
            self.status = "A Gamescope session is already running.".into();
            return;
        }
        if !self.gamescope_status.starts_with("Available:") {
            self.status = format!("Cannot launch: {}", self.gamescope_status);
            return;
        }
        let Some(selected_monitor) = self
            .monitors
            .iter()
            .find(|monitor| monitor.index == self.config.display_index)
            .cloned()
        else {
            self.status = self
                .monitor_error
                .clone()
                .unwrap_or_else(|| "Select an available output display first.".into());
            return;
        };
        let refreshed_monitors = match list_displays() {
            Ok(monitors) => monitors,
            Err(error) => {
                self.status = format!("Cannot revalidate the selected Wayland display: {error}");
                return;
            }
        };
        let Some(display_index) = resolve_display_index(&selected_monitor, &refreshed_monitors)
        else {
            self.monitors = refreshed_monitors;
            self.monitor_error = Some(
                "The selected display disappeared or has an ambiguous identity; select it again."
                    .into(),
            );
            self.status = self.monitor_error.clone().unwrap_or_default();
            return;
        };
        if self.config.target
            == (Resolution {
                width: selected_monitor.width,
                height: selected_monitor.height,
            })
            && let Some(current_monitor) = refreshed_monitors
                .iter()
                .find(|monitor| monitor.index == display_index)
        {
            self.config.target = Resolution {
                width: current_monitor.width,
                height: current_monitor.height,
            };
        }
        self.monitors = refreshed_monitors;
        self.config.display_index = display_index;
        let args = match build_gamescope_args(&self.config) {
            Ok(args) => args,
            Err(error) => {
                self.status = error.to_string();
                return;
            }
        };
        let mut command = Command::new("gamescope");
        command
            .args(args)
            .envs(gamescope_environment())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped());
        match command.spawn() {
            Ok(mut child) => {
                let (tx, rx) = mpsc::channel();
                if let Some(stderr) = child.stderr.take() {
                    thread::spawn(move || {
                        for line in BufReader::new(stderr).lines() {
                            match line {
                                Ok(line) => {
                                    let _ = tx.send(line);
                                }
                                Err(error) => {
                                    let _ = tx.send(format!("stderr read failed: {error}"));
                                    break;
                                }
                            }
                        }
                    });
                    self.log_rx = Some(rx);
                }
                self.child = Some(child);
                self.last_log.clear();
                self.status = "Gamescope is running the selected application.".into();
            }
            Err(error) => self.status = format!("Could not start Gamescope: {error}"),
        }
    }

    fn stop_scaling(&mut self) {
        if let Some(child) = &mut self.child {
            match child.kill() {
                Ok(()) => self.status = "Stopping the Gamescope session…".into(),
                Err(error) => self.status = format!("Could not stop Gamescope: {error}"),
            }
        }
    }

    fn save_current_profile(&mut self) {
        let profile = Profile {
            name: "Current setup".into(),
            settings: self.config.clone(),
        };
        self.status = match save_profile(&self.profile_path, &profile) {
            Ok(()) => format!("Profile saved to {}", self.profile_path.display()),
            Err(error) => format!("Could not save profile: {error}"),
        };
    }

    fn load_saved_profile(&mut self) {
        match load_profile(&self.profile_path) {
            Ok(profile) => {
                self.config = profile.settings;
                self.status = format!("Loaded profile ‘{}’.", profile.name);
            }
            Err(error) => self.status = format!("Could not load profile: {error}"),
        }
    }

    fn reset_settings(&mut self) {
        self.config = GameConfig {
            command: test_pattern_command(),
            ..GameConfig::default()
        };
        if let Some(monitor) = self.monitors.first() {
            self.config.display_index = monitor.index;
            self.config.target = Resolution {
                width: monitor.width,
                height: monitor.height,
            };
        }
        self.status = "Settings reset to the test-pattern preset.".into();
    }

    fn source_panel(&mut self, ui: &mut egui::Ui) {
        panel(ui, "Source", "01", |ui| {
            ui.label(RichText::new("Launch an application inside Gamescope.").color(MUTED));
            ui.add_space(8.0);
            ui.label(RichText::new("Launch command").color(TEXT).strong());
            ui.add(
                egui::TextEdit::singleline(&mut self.config.command)
                    .desired_width(ui.available_width().max(240.0))
                    .clip_text(true),
            );
            ui.add_space(6.0);
            ui.label(
                RichText::new("Arguments may be quoted. Shell expressions are not executed.")
                    .small()
                    .color(MUTED),
            );
            ui.add_space(8.0);
            ui.add_enabled(
                false,
                egui::RadioButton::new(false, "Monitor/window capture — planned for Milestone 3"),
            );
        });
    }

    fn target_panel(&mut self, ui: &mut egui::Ui) {
        panel(ui, "Output", "02", |ui| {
            ui.label(RichText::new("Present the Gamescope surface on this display.").color(MUTED));
            ui.add_space(8.0);
            let selected = self
                .monitors
                .iter()
                .find(|monitor| monitor.index == self.config.display_index);
            let selected_text = selected
                .map(Monitor::label)
                .unwrap_or_else(|| "No display selected".into());
            let mut chosen_index = self.config.display_index;
            egui::ComboBox::from_id_salt("target-display")
                .selected_text(selected_text)
                .width(ui.available_width())
                .show_ui(ui, |ui| {
                    for monitor in &self.monitors {
                        ui.selectable_value(&mut chosen_index, monitor.index, monitor.label());
                    }
                });
            if chosen_index != self.config.display_index {
                self.config.display_index = chosen_index;
                if let Some(monitor) = self
                    .monitors
                    .iter()
                    .find(|monitor| monitor.index == chosen_index)
                {
                    self.config.target = Resolution {
                        width: monitor.width,
                        height: monitor.height,
                    };
                }
            }
            if let Some(error) = &self.monitor_error {
                ui.label(RichText::new(format!("Monitor discovery: {error}")).color(WARNING));
            }
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.label("Output size");
                ui.add(
                    egui::DragValue::new(&mut self.config.target.width)
                        .range(1..=16_384)
                        .suffix(" px"),
                );
                ui.label("×");
                ui.add(
                    egui::DragValue::new(&mut self.config.target.height)
                        .range(1..=16_384)
                        .suffix(" px"),
                );
            });
            ui.checkbox(
                &mut self.config.fullscreen,
                "Fullscreen on selected display",
            );
        });
    }

    fn upscale_panel(&mut self, ui: &mut egui::Ui) {
        panel(ui, "Upscaling", "03", |ui| {
            ui.horizontal(|ui| {
                ui.label("Game render size");
                ui.add(
                    egui::DragValue::new(&mut self.config.source.width)
                        .range(1..=16_384)
                        .suffix(" px"),
                );
                ui.label("×");
                ui.add(
                    egui::DragValue::new(&mut self.config.source.height)
                        .range(1..=16_384)
                        .suffix(" px"),
                );
            });
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.label("Filter");
                egui::ComboBox::from_id_salt("filter")
                    .selected_text(filter_label(self.config.filter))
                    .show_ui(ui, |ui| {
                        ui.selectable_value(
                            &mut self.config.filter,
                            Filter::Fsr,
                            "FSR 1.0 · Gamescope",
                        );
                        ui.selectable_value(
                            &mut self.config.filter,
                            Filter::Linear,
                            "Bilinear / linear",
                        );
                        ui.selectable_value(
                            &mut self.config.filter,
                            Filter::Nearest,
                            "Nearest neighbor",
                        );
                        ui.selectable_value(
                            &mut self.config.filter,
                            Filter::Pixel,
                            "Pixel-art filter",
                        );
                    });
            });
            ui.horizontal(|ui| {
                ui.label("Scale mode");
                egui::ComboBox::from_id_salt("scale-mode")
                    .selected_text(scale_label(self.config.scale_mode))
                    .show_ui(ui, |ui| {
                        ui.selectable_value(
                            &mut self.config.scale_mode,
                            ScaleMode::Fit,
                            "Fit · preserve aspect ratio",
                        );
                        ui.selectable_value(
                            &mut self.config.scale_mode,
                            ScaleMode::Integer,
                            "Integer scale",
                        );
                        ui.selectable_value(
                            &mut self.config.scale_mode,
                            ScaleMode::Auto,
                            "Automatic",
                        );
                        ui.selectable_value(
                            &mut self.config.scale_mode,
                            ScaleMode::Fill,
                            "Fill output",
                        );
                        ui.selectable_value(
                            &mut self.config.scale_mode,
                            ScaleMode::Stretch,
                            "Stretch",
                        );
                    });
            });
            ui.add_space(4.0);
            if self.config.filter == Filter::Fsr {
                ui.add(egui::Slider::new(&mut self.config.sharpness, 0..=20).text("FSR sharpness"));
                ui.label(
                    RichText::new("Gamescope scale: 0 = strongest sharpening, 20 = least.")
                        .small()
                        .color(MUTED),
                );
            } else {
                ui.label(
                    RichText::new("Sharpness is specific to the Gamescope FSR path.")
                        .small()
                        .color(MUTED),
                );
            }
            ui.add_space(4.0);
            ui.label(
                RichText::new(format!(
                    "{}×{} → {}×{}",
                    self.config.source.width,
                    self.config.source.height,
                    self.config.target.width,
                    self.config.target.height
                ))
                .color(CYAN)
                .strong(),
            );
        });
    }

    fn performance_panel(&mut self, ui: &mut egui::Ui) {
        panel(ui, "Runtime", "04", |ui| {
            metric(
                ui,
                "Capture FPS",
                "Gamescope-managed",
                "No separate capture path in Mode A.",
            );
            metric(
                ui,
                "Output FPS",
                "Not sampled",
                "Gamescope statistics integration is not enabled yet.",
            );
            metric(
                ui,
                "GPU / latency",
                "Not sampled",
                "No fabricated telemetry; native instrumentation is planned.",
            );
            metric(
                ui,
                "Dropped frames",
                "Not exposed",
                "This controller does not infer frame pacing.",
            );
        });
    }

    fn actions_panel(&mut self, ui: &mut egui::Ui) {
        egui::Frame::new()
            .fill(PANEL_ALT)
            .stroke(Stroke::new(1.0, Color32::from_rgb(38, 58, 83)))
            .corner_radius(egui::CornerRadius::same(12))
            .inner_margin(egui::Margin::same(12))
            .show(ui, |ui| {
                ui.horizontal_wrapped(|ui| {
                    let can_start = self.child.is_none();
                    if ui
                        .add_enabled(
                            can_start,
                            egui::Button::new(
                                RichText::new("Start scaling")
                                    .strong()
                                    .color(Color32::from_rgb(5, 18, 28)),
                            )
                            .fill(CYAN)
                            .min_size(Vec2::new(150.0, 38.0)),
                        )
                        .clicked()
                    {
                        self.start_scaling();
                    }
                    if ui
                        .add_enabled(
                            self.child.is_some(),
                            egui::Button::new("Stop session").min_size(Vec2::new(112.0, 38.0)),
                        )
                        .clicked()
                    {
                        self.stop_scaling();
                    }
                    if ui.button("Save profile").clicked() {
                        self.save_current_profile();
                    }
                    if ui.button("Load profile").clicked() {
                        self.load_saved_profile();
                    }
                    if ui.button("Reset settings").clicked() {
                        self.reset_settings();
                    }
                });
                ui.add_space(8.0);
                let status_color = if self.child.is_some() { GOOD } else { MUTED };
                ui.label(RichText::new(format!("●  {}", self.status)).color(status_color));
                if !self.last_log.is_empty() {
                    ui.label(
                        RichText::new(format!("Gamescope: {}", self.last_log))
                            .small()
                            .color(MUTED),
                    );
                }
            });
    }
}

impl eframe::App for ScaleApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.accept_diagnostics();
        ui.ctx().request_repaint_after(Duration::from_millis(300));
        egui::Frame::new().fill(BG).inner_margin(egui::Margin::same(20)).show(ui, |ui| {
            egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new("NK").size(23.0).strong().color(CYAN));
                    ui.vertical(|ui| {
                        ui.label(RichText::new("NightKnight Scale").size(24.0).strong().color(TEXT));
                        ui.label(RichText::new("A Wayland-first scaling control room").color(MUTED));
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        status_chip(
                            ui,
                            "Gamescope",
                            &compact_gamescope_label(&self.gamescope_status),
                            self.gamescope_status.starts_with("Available:"),
                        );
                        status_chip(
                            ui,
                            "Vulkan",
                            &compact_gpu_label(&self.gpu_status),
                            self.gpu_status.contains("Vulkan adapter:"),
                        );
                    });
                });
                ui.add_space(16.0);
                ui.columns(2, |columns| {
                    self.source_panel(&mut columns[0]);
                    columns[0].add_space(14.0);
                    self.upscale_panel(&mut columns[0]);
                    self.target_panel(&mut columns[1]);
                    columns[1].add_space(14.0);
                    self.performance_panel(&mut columns[1]);
                });
                ui.add_space(14.0);
                self.actions_panel(ui);
                ui.add_space(14.0);
                egui::Frame::new().fill(Color32::from_rgb(27, 25, 40)).stroke(Stroke::new(1.0, Color32::from_rgb(76, 62, 111))).corner_radius(egui::CornerRadius::same(10)).inner_margin(egui::Margin::same(12)).show(ui, |ui| {
                    ui.label(RichText::new("Mode A uses the installed Gamescope compositor; it does not replace Hyprland or alter the physical desktop output.").color(TEXT));
                    ui.label(RichText::new("FSR 1.0 here is Gamescope's external backend path. Native Vulkan processing and portal-authorized capture are not implemented yet.").small().color(MUTED));
                });
                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    ui.label(RichText::new(format!("Vulkan · {}", self.gpu_status)).small().color(MUTED));
                    ui.separator();
                    ui.label(RichText::new(format!("Profile: {}", self.profile_path.display())).small().color(MUTED));
                });
            });
        });
    }
}

impl Drop for ScaleApp {
    fn drop(&mut self) {
        if let Some(child) = &mut self.child {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

fn main() -> eframe::Result {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|arg| arg == "--diagnostics") {
        print_runtime_diagnostics();
        return Ok(());
    }
    if args.iter().any(|arg| arg == "--test-pattern") {
        let resolution = match parse_test_pattern_resolution(&args) {
            Ok(resolution) => resolution,
            Err(error) => {
                eprintln!("Invalid test-pattern size: {error}");
                std::process::exit(2);
            }
        };
        return run_test_pattern(resolution);
    }
    let options = eframe::NativeOptions {
        renderer: eframe::Renderer::Wgpu,
        viewport: egui::ViewportBuilder::default()
            .with_title("NightKnight Scale")
            .with_inner_size([1160.0, 820.0])
            .with_min_inner_size([900.0, 680.0]),
        ..Default::default()
    };
    eframe::run_native(
        "NightKnight Scale",
        options,
        Box::new(|cc| Ok(Box::new(ScaleApp::new(cc)))),
    )
}

fn parse_test_pattern_resolution(args: &[String]) -> Result<Resolution, String> {
    let Some(size) = args
        .iter()
        .find_map(|arg| arg.strip_prefix("--pattern-size="))
    else {
        return Ok(Resolution {
            width: 1280,
            height: 720,
        });
    };
    let (width, height) = size
        .split_once('x')
        .ok_or_else(|| "expected WIDTHxHEIGHT, for example 1280x720".to_owned())?;
    let width = width
        .parse::<u32>()
        .map_err(|_| "width must be an integer between 1 and 16384".to_owned())?;
    let height = height
        .parse::<u32>()
        .map_err(|_| "height must be an integer between 1 and 16384".to_owned())?;
    if !(1..=16_384).contains(&width) || !(1..=16_384).contains(&height) {
        return Err("dimensions must be between 1 and 16384 pixels".into());
    }
    Ok(Resolution { width, height })
}

fn run_test_pattern(resolution: Resolution) -> eframe::Result {
    let options = eframe::NativeOptions {
        renderer: eframe::Renderer::Wgpu,
        viewport: egui::ViewportBuilder::default()
            .with_title("NightKnight Scale · Test Pattern")
            .with_inner_size([resolution.width as f32, resolution.height as f32])
            .with_min_inner_size([640.0, 360.0]),
        ..Default::default()
    };
    eframe::run_native(
        "NK Scale Test Pattern",
        options,
        Box::new(|_| Ok(Box::new(TestPattern { resolution }))),
    )
}

struct TestPattern {
    resolution: Resolution,
}

impl eframe::App for TestPattern {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        draw_pattern_chart(ui.painter(), ui.max_rect(), self.resolution);
    }
}

fn draw_pattern_chart(painter: &egui::Painter, root: egui::Rect, source: Resolution) {
    let root = root.shrink(14.0);
    painter.rect_filled(root, egui::CornerRadius::ZERO, BG);
    painter.text(
        egui::pos2(root.left() + 4.0, root.top() + 2.0),
        egui::Align2::LEFT_TOP,
        "NK SCALE · DETERMINISTIC SPATIAL-SCALER CHART",
        FontId::proportional(20.0),
        CYAN,
    );
    painter.text(
        egui::pos2(root.right() - 4.0, root.top() + 5.0),
        egui::Align2::RIGHT_TOP,
        format!("SOURCE {}×{}", source.width, source.height),
        FontId::monospace(12.0),
        TEXT,
    );

    let gap = 10.0;
    let chart_top = root.top() + 32.0;
    let col_width = (root.width() - gap) / 2.0;
    let row_height = (root.bottom() - chart_top - gap * 2.0) / 3.0;
    let panels = [
        egui::Rect::from_min_size(
            egui::pos2(root.left(), chart_top),
            Vec2::new(col_width, row_height),
        ),
        egui::Rect::from_min_size(
            egui::pos2(root.left() + col_width + gap, chart_top),
            Vec2::new(col_width, row_height),
        ),
        egui::Rect::from_min_size(
            egui::pos2(root.left(), chart_top + row_height + gap),
            Vec2::new(col_width, row_height),
        ),
        egui::Rect::from_min_size(
            egui::pos2(root.left() + col_width + gap, chart_top + row_height + gap),
            Vec2::new(col_width, row_height),
        ),
        egui::Rect::from_min_size(
            egui::pos2(root.left(), chart_top + (row_height + gap) * 2.0),
            Vec2::new(col_width, row_height),
        ),
        egui::Rect::from_min_size(
            egui::pos2(
                root.left() + col_width + gap,
                chart_top + (row_height + gap) * 2.0,
            ),
            Vec2::new(col_width, row_height),
        ),
    ];
    let content = [
        pattern_panel(painter, panels[0], "A · CHECKERBOARD / 1–8 PX EDGES"),
        pattern_panel(painter, panels[1], "B · FINE TEXT / GLYPH DETAIL"),
        pattern_panel(painter, panels[2], "C · DIAGONALS / HIGH-CONTRAST EDGES"),
        pattern_panel(painter, panels[3], "D · DETERMINISTIC TEXTURE"),
        pattern_panel(painter, panels[4], "E · SMALL UI ELEMENTS"),
        pattern_panel(painter, panels[5], "F · COLOR BARS / GRAY RAMP"),
    ];

    let checker_area = egui::Rect::from_min_max(
        content[0].min,
        egui::pos2(
            content[0].left() + content[0].width() * 0.58,
            content[0].bottom(),
        ),
    );
    draw_checkerboard(painter, checker_area, 8.0);
    let high_frequency = egui::Rect::from_min_size(
        egui::pos2(content[0].right() - 100.0, content[0].top()),
        Vec2::new(96.0, 96.0),
    );
    draw_checkerboard(painter, high_frequency, 3.0);
    for (index, width) in [1.0, 2.0, 3.0, 4.0, 6.0].into_iter().enumerate() {
        let x = content[0].left() + content[0].width() * 0.63 + index as f32 * 18.0;
        painter.rect_filled(
            egui::Rect::from_min_max(
                egui::pos2(x, content[0].top()),
                egui::pos2(x + width, content[0].bottom()),
            ),
            egui::CornerRadius::ZERO,
            TEXT,
        );
    }

    for (index, size) in [8.0, 9.0, 10.0, 12.0, 14.0, 18.0].into_iter().enumerate() {
        painter.text(
            egui::pos2(content[1].left(), content[1].top() + index as f32 * 21.0),
            egui::Align2::LEFT_TOP,
            format!("{size:>2.0}px  Sphinx of black quartz · 0123456789"),
            FontId::monospace(size),
            if index % 2 == 0 { TEXT } else { CYAN },
        );
    }

    for index in 0..18 {
        let x = content[2].left() + index as f32 * content[2].width() / 18.0;
        let end_x = (x + content[2].height()).min(content[2].right());
        painter.line_segment(
            [
                egui::pos2(x, content[2].bottom()),
                egui::pos2(end_x, content[2].top()),
            ],
            Stroke::new(if index % 3 == 0 { 2.0 } else { 1.0 }, TEXT),
        );
        let y = content[2].top() + index as f32 * content[2].height() / 18.0;
        painter.line_segment(
            [
                egui::pos2(content[2].left(), y),
                egui::pos2(content[2].right(), y),
            ],
            Stroke::new(1.0, if index % 2 == 0 { VIOLET } else { CYAN }),
        );
    }
    painter.rect_filled(
        egui::Rect::from_min_max(
            egui::pos2(content[2].left() + 10.0, content[2].top() + 10.0),
            egui::pos2(content[2].left() + 110.0, content[2].top() + 62.0),
        ),
        egui::CornerRadius::ZERO,
        Color32::BLACK,
    );
    painter.rect_filled(
        egui::Rect::from_min_max(
            egui::pos2(content[2].left() + 110.0, content[2].top() + 10.0),
            egui::pos2(content[2].left() + 210.0, content[2].top() + 62.0),
        ),
        egui::CornerRadius::ZERO,
        Color32::WHITE,
    );

    let texture_cell = 10.0;
    let rows = (content[3].height() / texture_cell).floor() as usize;
    let cols = (content[3].width() / texture_cell).floor() as usize;
    for row in 0..rows {
        for col in 0..cols {
            let seed = (row as u32)
                .wrapping_mul(73)
                .wrapping_add((col as u32).wrapping_mul(151))
                .wrapping_add((row as u32).wrapping_mul(col as u32).wrapping_mul(17));
            let shade = (seed % 192 + 32) as u8;
            let cell = egui::Rect::from_min_size(
                egui::pos2(
                    content[3].left() + col as f32 * texture_cell,
                    content[3].top() + row as f32 * texture_cell,
                ),
                Vec2::splat(texture_cell - 1.0),
            );
            painter.rect_filled(cell, egui::CornerRadius::ZERO, Color32::from_gray(shade));
        }
    }

    for row in 0..2 {
        for col in 0..3 {
            let x = content[4].left() + col as f32 * 138.0;
            let y = content[4].top() + row as f32 * 58.0;
            let card = egui::Rect::from_min_size(egui::pos2(x, y), Vec2::new(126.0, 46.0));
            painter.rect_filled(card, egui::CornerRadius::same(4), PANEL_ALT);
            painter.rect_filled(
                egui::Rect::from_min_size(egui::pos2(x + 8.0, y + 8.0), Vec2::new(10.0, 10.0)),
                egui::CornerRadius::same(2),
                if (row + col) % 2 == 0 { CYAN } else { VIOLET },
            );
            painter.text(
                egui::pos2(x + 25.0, y + 6.0),
                egui::Align2::LEFT_TOP,
                "UI 0123",
                FontId::monospace(11.0),
                TEXT,
            );
            painter.line_segment(
                [
                    egui::pos2(x + 8.0, y + 32.0),
                    egui::pos2(x + 116.0, y + 32.0),
                ],
                Stroke::new(2.0, MUTED),
            );
            painter.circle_filled(egui::pos2(x + 72.0, y + 32.0), 4.0, CYAN);
        }
    }

    let color_area = egui::Rect::from_min_max(
        egui::pos2(
            content[5].left(),
            content[5].top() + content[5].height() * 0.34,
        ),
        content[5].right_bottom(),
    );
    let gray_steps = 16;
    let gray_width = content[5].width() / gray_steps as f32;
    for index in 0..gray_steps {
        let shade = (index * 255 / (gray_steps - 1)) as u8;
        let ramp = egui::Rect::from_min_size(
            egui::pos2(
                content[5].left() + index as f32 * gray_width,
                content[5].top(),
            ),
            Vec2::new(gray_width - 1.0, content[5].height() * 0.30),
        );
        painter.rect_filled(ramp, egui::CornerRadius::ZERO, Color32::from_gray(shade));
    }
    let bars = [
        Color32::BLACK,
        Color32::WHITE,
        Color32::from_rgb(255, 0, 0),
        Color32::from_rgb(0, 255, 0),
        Color32::from_rgb(0, 0, 255),
        CYAN,
        VIOLET,
        Color32::from_rgb(255, 150, 40),
    ];
    let bar_width = color_area.width() / bars.len() as f32;
    for (index, color) in bars.into_iter().enumerate() {
        let bar = egui::Rect::from_min_size(
            egui::pos2(
                color_area.left() + index as f32 * bar_width,
                color_area.top(),
            ),
            Vec2::new(bar_width - 2.0, color_area.height()),
        );
        painter.rect_filled(bar, egui::CornerRadius::ZERO, color);
    }
}

fn pattern_panel(painter: &egui::Painter, rect: egui::Rect, title: &str) -> egui::Rect {
    painter.rect_filled(rect, egui::CornerRadius::same(4), PANEL);
    painter.line_segment(
        [rect.left_top(), rect.right_top()],
        Stroke::new(1.0, Color32::from_rgb(55, 85, 112)),
    );
    painter.text(
        egui::pos2(rect.left() + 8.0, rect.top() + 5.0),
        egui::Align2::LEFT_TOP,
        title,
        FontId::monospace(10.0),
        MUTED,
    );
    egui::Rect::from_min_max(
        egui::pos2(rect.left() + 8.0, rect.top() + 24.0),
        egui::pos2(rect.right() - 8.0, rect.bottom() - 8.0),
    )
}

fn draw_checkerboard(painter: &egui::Painter, rect: egui::Rect, cell_size: f32) {
    let rows = (rect.height() / cell_size).ceil() as usize;
    let cols = (rect.width() / cell_size).ceil() as usize;
    for row in 0..rows {
        for col in 0..cols {
            let x = rect.left() + col as f32 * cell_size;
            let y = rect.top() + row as f32 * cell_size;
            let cell = egui::Rect::from_min_max(
                egui::pos2(x, y),
                egui::pos2(
                    (x + cell_size).min(rect.right()),
                    (y + cell_size).min(rect.bottom()),
                ),
            );
            let color = if (row + col) % 2 == 0 {
                Color32::WHITE
            } else {
                Color32::BLACK
            };
            painter.rect_filled(cell, egui::CornerRadius::ZERO, color);
        }
    }
}

fn panel(ui: &mut egui::Ui, title: &str, sequence: &str, content: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::new()
        .fill(PANEL)
        .stroke(Stroke::new(1.0, Color32::from_rgb(33, 51, 73)))
        .corner_radius(egui::CornerRadius::same(12))
        .inner_margin(egui::Margin::same(14))
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(RichText::new(sequence).small().strong().color(VIOLET));
                ui.heading(RichText::new(title).color(TEXT));
            });
            ui.separator();
            content(ui);
        });
}

fn metric(ui: &mut egui::Ui, title: &str, value: &str, detail: &str) {
    ui.horizontal(|ui| {
        ui.vertical(|ui| {
            ui.label(RichText::new(title).small().color(MUTED));
            ui.label(RichText::new(value).strong().color(TEXT));
        });
    });
    ui.label(RichText::new(detail).small().color(MUTED));
    ui.add_space(4.0);
}

fn status_chip(ui: &mut egui::Ui, title: &str, value: &str, ready: bool) {
    let color = if ready { GOOD } else { WARNING };
    egui::Frame::new()
        .fill(PANEL_ALT)
        .corner_radius(egui::CornerRadius::same(8))
        .inner_margin(egui::Margin::symmetric(9, 6))
        .show(ui, |ui| {
            ui.set_min_width(150.0);
            ui.set_max_width(260.0);
            ui.vertical(|ui| {
                ui.label(RichText::new(title).small().color(MUTED));
                ui.label(RichText::new(value).small().color(color).strong());
            });
        });
    ui.add_space(6.0);
}

fn compact_gamescope_label(status: &str) -> String {
    if let Some(version) = status.strip_prefix("Available: Gamescope ") {
        version
            .split_whitespace()
            .next()
            .unwrap_or("ready")
            .to_owned()
    } else {
        short_label(status, 32)
    }
}

fn compact_gpu_label(status: &str) -> String {
    let model = status
        .strip_prefix("Vulkan adapter:")
        .unwrap_or(status)
        .split('·')
        .next()
        .unwrap_or(status)
        .trim();
    short_label(model, 32)
}

fn short_label(value: &str, max_chars: usize) -> String {
    let mut chars = value.chars();
    let mut short: String = chars.by_ref().take(max_chars).collect();
    if chars.next().is_some() {
        short.push('…');
    }
    short
}

fn apply_theme(ctx: &egui::Context) {
    let mut visuals = egui::Visuals::dark();
    visuals.panel_fill = BG;
    visuals.window_fill = PANEL;
    visuals.extreme_bg_color = Color32::from_rgb(5, 10, 18);
    visuals.faint_bg_color = PANEL_ALT;
    visuals.selection.bg_fill = Color32::from_rgb(29, 111, 134);
    visuals.widgets.inactive.bg_fill = PANEL_ALT;
    visuals.widgets.inactive.fg_stroke.color = TEXT;
    visuals.widgets.hovered.bg_fill = Color32::from_rgb(30, 48, 70);
    visuals.widgets.hovered.fg_stroke.color = CYAN;
    visuals.widgets.active.bg_fill = Color32::from_rgb(25, 77, 97);
    visuals.widgets.active.fg_stroke.color = TEXT;
    ctx.set_visuals(visuals);
}

fn probe_gamescope() -> String {
    let version_output = match Command::new("gamescope").arg("--version").output() {
        Ok(output) if output.status.success() => output,
        Ok(output) => return format!("Unavailable: Gamescope returned {}", output.status),
        Err(error) => return format!("Unavailable: {error}"),
    };
    let version_text = format!(
        "{}\n{}",
        String::from_utf8_lossy(&version_output.stdout),
        String::from_utf8_lossy(&version_output.stderr)
    );
    let version = version_text
        .lines()
        .find_map(|line| {
            line.split_once("gamescope version")
                .map(|(_, tail)| tail.trim())
        })
        .unwrap_or("version unavailable");
    let help_output = match Command::new("gamescope").arg("--help").output() {
        Ok(output) => output,
        Err(error) => return format!("Unavailable: cannot inspect Gamescope options: {error}"),
    };
    let help = format!(
        "{}\n{}",
        String::from_utf8_lossy(&help_output.stdout),
        String::from_utf8_lossy(&help_output.stderr)
    );
    let required = [
        "--backend",
        "--display-index",
        "--nested-width",
        "--nested-height",
        "--output-width",
        "--output-height",
        "--scaler",
        "--filter",
        "--sharpness",
        "--expose-wayland",
    ];
    if let Some(missing) = required.iter().find(|option| !help.contains(**option)) {
        return format!(
            "Unavailable: Gamescope {version} does not advertise required option {missing}"
        );
    }
    if !help.contains("fsr") {
        return format!("Unavailable: Gamescope {version} does not advertise its FSR filter");
    }
    format!("Available: Gamescope {version}")
}

fn print_runtime_diagnostics() {
    println!("Gamescope: {}", probe_gamescope());
    println!("GPU: {}", probe_vulkan());
    match list_displays() {
        Ok(monitors) => {
            for monitor in monitors {
                println!(
                    "Display {}: {} at ({}, {})",
                    monitor.index,
                    monitor.label(),
                    monitor.x,
                    monitor.y
                );
            }
        }
        Err(error) => println!("Displays unavailable: {error}"),
    }
}

fn probe_vulkan() -> String {
    let result = std::panic::catch_unwind(|| {
        let mut descriptor = eframe::wgpu::InstanceDescriptor::new_without_display_handle();
        descriptor.backends = eframe::wgpu::Backends::VULKAN;
        let instance = eframe::wgpu::Instance::new(descriptor);
        let adapters =
            pollster::block_on(instance.enumerate_adapters(eframe::wgpu::Backends::VULKAN));
        let selected = adapters
            .iter()
            .map(|adapter| adapter.get_info())
            .find(|info| {
                matches!(
                    info.device_type,
                    eframe::wgpu::DeviceType::DiscreteGpu | eframe::wgpu::DeviceType::IntegratedGpu
                )
            })
            .or_else(|| adapters.first().map(|adapter| adapter.get_info()));
        match selected {
            Some(info) => format!(
                "Vulkan adapter: {} · {:?} · {}",
                info.name, info.device_type, info.driver_info
            ),
            None => "No Vulkan adapter found".into(),
        }
    });
    result.unwrap_or_else(|_| "Vulkan probe failed; check the installed Vulkan driver.".into())
}

fn profile_path() -> PathBuf {
    if let Some(config_home) = std::env::var_os("XDG_CONFIG_HOME") {
        PathBuf::from(config_home).join("nkscale/profile.json")
    } else if let Some(home) = std::env::var_os("HOME") {
        PathBuf::from(home).join(".config/nkscale/profile.json")
    } else {
        PathBuf::from(".config/nkscale/profile.json")
    }
}

fn test_pattern_command() -> String {
    let executable = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("nk-scale"));
    let words = [
        executable.to_string_lossy().into_owned(),
        "--test-pattern".into(),
    ];
    shlex::try_join(words.iter().map(String::as_str))
        .unwrap_or_else(|_| "nk-scale --test-pattern".into())
}

fn filter_label(filter: Filter) -> &'static str {
    match filter {
        Filter::Fsr => "FSR 1.0 · Gamescope",
        Filter::Linear => "Bilinear / linear",
        Filter::Nearest => "Nearest neighbor",
        Filter::Pixel => "Pixel-art filter",
    }
}

fn scale_label(mode: ScaleMode) -> &'static str {
    match mode {
        ScaleMode::Auto => "Automatic",
        ScaleMode::Integer => "Integer scale",
        ScaleMode::Fit => "Fit · preserve aspect ratio",
        ScaleMode::Fill => "Fill output",
        ScaleMode::Stretch => "Stretch",
    }
}

#[cfg(test)]
mod tests {
    use super::{compact_gamescope_label, compact_gpu_label, parse_test_pattern_resolution};
    use nkscale_core::Resolution;

    #[test]
    fn test_pattern_size_defaults_to_1280_by_720() {
        assert_eq!(
            parse_test_pattern_resolution(&[]),
            Ok(Resolution {
                width: 1280,
                height: 720,
            })
        );
    }

    #[test]
    fn test_pattern_size_accepts_an_explicit_cli_override() {
        let args = vec!["--pattern-size=1600x900".to_owned()];
        assert_eq!(
            parse_test_pattern_resolution(&args),
            Ok(Resolution {
                width: 1600,
                height: 900,
            })
        );
    }

    #[test]
    fn test_pattern_size_rejects_malformed_or_out_of_range_values() {
        for value in ["--pattern-size=0x900", "--pattern-size=1600xnope"] {
            assert!(parse_test_pattern_resolution(&[value.to_owned()]).is_err());
        }
    }

    #[test]
    fn status_chips_keep_the_version_and_gpu_model_without_driver_detail() {
        assert_eq!(
            compact_gamescope_label("Available: Gamescope 3.16.31 (gcc 16.2.1)"),
            "3.16.31"
        );
        assert_eq!(
            compact_gpu_label("Vulkan adapter: AMD Radeon RX 7900 XT · DiscreteGpu · Mesa 26.2.5"),
            "AMD Radeon RX 7900 XT"
        );
    }
}
