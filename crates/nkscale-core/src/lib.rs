use serde::{Deserialize, Serialize};
use std::ffi::OsString;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};
use thiserror::Error;

const MAX_DIMENSION: u32 = 16_384;

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Filter {
    Fsr,
    Linear,
    Nearest,
    Pixel,
}

impl Filter {
    pub const fn as_gamescope_arg(self) -> &'static str {
        match self {
            Self::Fsr => "fsr",
            Self::Linear => "linear",
            Self::Nearest => "nearest",
            Self::Pixel => "pixel",
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ScaleMode {
    Auto,
    Integer,
    Fit,
    Fill,
    Stretch,
}

impl ScaleMode {
    pub const fn as_gamescope_arg(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Integer => "integer",
            Self::Fit => "fit",
            Self::Fill => "fill",
            Self::Stretch => "stretch",
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct Resolution {
    pub width: u32,
    pub height: u32,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct GameConfig {
    /// SDL display index used by Gamescope's nested backend.
    pub display_index: u32,
    pub source: Resolution,
    pub target: Resolution,
    pub filter: Filter,
    pub scale_mode: ScaleMode,
    /// Gamescope uses the range 0 (sharpest) through 20 (least sharpening).
    pub sharpness: u8,
    pub fullscreen: bool,
    pub expose_wayland: bool,
    /// Executable plus arguments, parsed without shell expansion or evaluation.
    pub command: String,
}

impl Default for GameConfig {
    fn default() -> Self {
        Self {
            display_index: 0,
            source: Resolution {
                width: 1280,
                height: 720,
            },
            target: Resolution {
                width: 1920,
                height: 1080,
            },
            filter: Filter::Fsr,
            scale_mode: ScaleMode::Fit,
            sharpness: 6,
            fullscreen: true,
            expose_wayland: true,
            command: String::new(),
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct Profile {
    pub name: String,
    pub settings: GameConfig,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Monitor {
    pub index: u32,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub refresh_hz: f32,
    pub x: i32,
    pub y: i32,
}

impl Monitor {
    pub fn label(&self) -> String {
        format!(
            "{} · {}×{} · {:.0} Hz",
            self.name, self.width, self.height, self.refresh_hz
        )
    }
}

/// Resolve the current SDL index for a monitor selected during an earlier enumeration.
/// SDL indices are volatile; match the stable name/position and fail closed on ambiguity.
pub fn resolve_display_index(selected: &Monitor, current: &[Monitor]) -> Option<u32> {
    let mut matches = current.iter().filter(|candidate| {
        candidate.name == selected.name && candidate.x == selected.x && candidate.y == selected.y
    });
    let index = matches.next()?.index;
    matches.next().is_none().then_some(index)
}

/// Keep Gamescope's SDL output enumeration on the same Wayland driver as NKScale.
pub const fn gamescope_environment() -> [(&'static str, &'static str); 1] {
    [("SDL_VIDEODRIVER", "wayland")]
}

#[derive(Debug, Error)]
pub enum Error {
    #[error("source and target dimensions must be between 1 and {MAX_DIMENSION} pixels")]
    InvalidDimensions,
    #[error("Gamescope sharpness must be between 0 (sharpest) and 20 (least sharpening)")]
    InvalidSharpness,
    #[error("command is empty or contains invalid shell-style quoting")]
    InvalidCommand,
    #[error("profile I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("profile JSON is invalid: {0}")]
    Json(#[from] serde_json::Error),
    #[error("monitor enumeration failed: {0}")]
    DisplayEnumeration(String),
}

pub fn build_gamescope_args(config: &GameConfig) -> Result<Vec<OsString>, Error> {
    for dimension in [
        config.source.width,
        config.source.height,
        config.target.width,
        config.target.height,
    ] {
        if dimension == 0 || dimension > MAX_DIMENSION {
            return Err(Error::InvalidDimensions);
        }
    }
    if config.filter == Filter::Fsr && config.sharpness > 20 {
        return Err(Error::InvalidSharpness);
    }

    let command = shlex::split(&config.command).ok_or(Error::InvalidCommand)?;
    if command.is_empty() || command[0].is_empty() {
        return Err(Error::InvalidCommand);
    }

    let mut args = vec![
        "--backend".into(),
        "sdl".into(),
        "--display-index".into(),
        config.display_index.to_string().into(),
        "--nested-width".into(),
        config.source.width.to_string().into(),
        "--nested-height".into(),
        config.source.height.to_string().into(),
        "--output-width".into(),
        config.target.width.to_string().into(),
        "--output-height".into(),
        config.target.height.to_string().into(),
        "--scaler".into(),
        config.scale_mode.as_gamescope_arg().into(),
        "--filter".into(),
        config.filter.as_gamescope_arg().into(),
    ];
    if config.filter == Filter::Fsr {
        args.extend(["--sharpness".into(), config.sharpness.to_string().into()]);
    }
    if config.fullscreen {
        args.push("--fullscreen".into());
    }
    if config.expose_wayland {
        args.push("--expose-wayland".into());
    }
    args.push("--".into());
    args.extend(command.into_iter().map(OsString::from));
    Ok(args)
}

pub fn save_profile(path: &Path, profile: &Profile) -> Result<(), Error> {
    let parent = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let mut temporary = PathBuf::from(path);
    let suffix = format!("{}.{}.tmp", std::process::id(), stamp);
    temporary.set_extension(suffix);

    let result = (|| -> Result<(), Error> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        serde_json::to_writer_pretty(&mut file, profile)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

pub fn load_profile(path: &Path) -> Result<Profile, Error> {
    let contents = fs::read(path)?;
    Ok(serde_json::from_slice(&contents)?)
}

#[cfg(target_os = "linux")]
mod sdl_display {
    use super::{Error, Monitor};
    use std::ffi::{CStr, c_char, c_void};

    const WAYLAND_VIDEO_DRIVER: &CStr = c"wayland";

    #[repr(C)]
    struct SdlRect {
        x: i32,
        y: i32,
        w: i32,
        h: i32,
    }

    #[repr(C)]
    struct SdlDisplayMode {
        format: u32,
        w: i32,
        h: i32,
        refresh_rate: i32,
        driverdata: *mut c_void,
    }

    #[link(name = "SDL2")]
    unsafe extern "C" {
        fn SDL_VideoInit(driver_name: *const c_char) -> i32;
        fn SDL_VideoQuit();
        fn SDL_GetNumVideoDisplays() -> i32;
        fn SDL_GetDisplayName(display_index: i32) -> *const c_char;
        fn SDL_GetCurrentDisplayMode(display_index: i32, mode: *mut SdlDisplayMode) -> i32;
        fn SDL_GetDisplayBounds(display_index: i32, rect: *mut SdlRect) -> i32;
        fn SDL_GetError() -> *const c_char;
    }

    struct VideoSubsystem;
    impl Drop for VideoSubsystem {
        fn drop(&mut self) {
            // SAFETY: This balances the successful SDL_VideoInit call in `list`.
            unsafe { SDL_VideoQuit() };
        }
    }

    fn last_error() -> String {
        // SAFETY: SDL_GetError returns an SDL-owned NUL-terminated string.
        let ptr = unsafe { SDL_GetError() };
        if ptr.is_null() {
            return "unknown SDL error".into();
        }
        // SAFETY: SDL keeps the returned string valid until the next SDL call.
        unsafe { CStr::from_ptr(ptr) }
            .to_string_lossy()
            .into_owned()
    }

    pub(super) fn list() -> Result<Vec<Monitor>, Error> {
        // Select Wayland explicitly so this index list matches Gamescope's SDL backend.
        // SAFETY: SDL_VideoInit accepts a static NUL-terminated driver name.
        if unsafe { SDL_VideoInit(WAYLAND_VIDEO_DRIVER.as_ptr()) } != 0 {
            return Err(Error::DisplayEnumeration(last_error()));
        }
        let _video = VideoSubsystem;
        // SAFETY: The video subsystem is initialized above.
        let count = unsafe { SDL_GetNumVideoDisplays() };
        if count < 0 {
            return Err(Error::DisplayEnumeration(last_error()));
        }
        let mut monitors = Vec::with_capacity(count as usize);
        for index in 0..count {
            // SAFETY: `index` is in the range returned by SDL_GetNumVideoDisplays.
            let name_ptr = unsafe { SDL_GetDisplayName(index) };
            let name = if name_ptr.is_null() {
                format!("Display {index}")
            } else {
                // SAFETY: SDL returns a NUL-terminated display name.
                unsafe { CStr::from_ptr(name_ptr) }
                    .to_string_lossy()
                    .into_owned()
            };
            let mut mode = SdlDisplayMode {
                format: 0,
                w: 0,
                h: 0,
                refresh_rate: 0,
                driverdata: std::ptr::null_mut(),
            };
            let mut rect = SdlRect {
                x: 0,
                y: 0,
                w: 0,
                h: 0,
            };
            // SAFETY: Pointers refer to initialized storage, and index is valid.
            let mode_ok = unsafe { SDL_GetCurrentDisplayMode(index, &mut mode) } == 0;
            // SAFETY: Pointers refer to initialized storage, and index is valid.
            let bounds_ok = unsafe { SDL_GetDisplayBounds(index, &mut rect) } == 0;
            let width = if mode_ok && mode.w > 0 {
                mode.w
            } else {
                rect.w
            };
            let height = if mode_ok && mode.h > 0 {
                mode.h
            } else {
                rect.h
            };
            if width <= 0 || height <= 0 {
                continue;
            }
            monitors.push(Monitor {
                index: index as u32,
                name,
                width: width as u32,
                height: height as u32,
                refresh_hz: if mode_ok {
                    mode.refresh_rate as f32
                } else {
                    0.0
                },
                x: if bounds_ok { rect.x } else { 0 },
                y: if bounds_ok { rect.y } else { 0 },
            });
        }
        if monitors.is_empty() {
            return Err(Error::DisplayEnumeration(
                "SDL reported no usable displays".into(),
            ));
        }
        Ok(monitors)
    }
}

#[cfg(target_os = "linux")]
pub fn list_displays() -> Result<Vec<Monitor>, Error> {
    sdl_display::list()
}

#[cfg(not(target_os = "linux"))]
pub fn list_displays() -> Result<Vec<Monitor>, Error> {
    Err(Error::DisplayEnumeration(
        "the first release targets Linux".into(),
    ))
}
