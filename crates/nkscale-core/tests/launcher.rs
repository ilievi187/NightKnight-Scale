use nkscale_core::{
    Filter, GameConfig, Monitor, Profile, Resolution, ScaleMode, build_gamescope_args,
    gamescope_environment, load_profile, resolve_display_index, save_profile,
};

fn sample_config() -> GameConfig {
    GameConfig {
        display_index: 2,
        source: Resolution {
            width: 1280,
            height: 720,
        },
        target: Resolution {
            width: 2560,
            height: 1080,
        },
        filter: Filter::Fsr,
        scale_mode: ScaleMode::Fit,
        sharpness: 6,
        fullscreen: true,
        expose_wayland: true,
        command: "'/opt/My Game/game' --quality 'high detail'".into(),
    }
}

#[test]
fn fsr_launch_uses_selected_monitor_resolutions_and_wayland_client_support() {
    let args = build_gamescope_args(&sample_config()).unwrap();
    let strings: Vec<_> = args
        .iter()
        .map(|s| s.to_string_lossy().into_owned())
        .collect();
    for pair in [
        ["--backend", "sdl"],
        ["--display-index", "2"],
        ["--nested-width", "1280"],
        ["--nested-height", "720"],
        ["--output-width", "2560"],
        ["--output-height", "1080"],
        ["--filter", "fsr"],
        ["--scaler", "fit"],
        ["--sharpness", "6"],
    ] {
        assert!(
            strings.windows(2).any(|w| w == pair),
            "missing {pair:?} in {strings:?}"
        );
    }
    assert!(strings.contains(&"--fullscreen".into()));
    assert!(strings.contains(&"--expose-wayland".into()));
    let separator = strings.iter().position(|s| s == "--").unwrap();
    assert_eq!(
        &strings[separator + 1..],
        &["/opt/My Game/game", "--quality", "high detail"]
    );
}

#[test]
fn launch_rejects_empty_commands_and_invalid_dimensions() {
    let mut config = sample_config();
    config.command = "  ".into();
    assert!(build_gamescope_args(&config).is_err());
    config = sample_config();
    config.source.width = 0;
    assert!(build_gamescope_args(&config).is_err());
}

#[test]
fn sharpness_is_omitted_for_non_fsr_filters() {
    let mut config = sample_config();
    config.filter = Filter::Nearest;
    let args = build_gamescope_args(&config).unwrap();
    let strings: Vec<_> = args
        .iter()
        .map(|s| s.to_string_lossy().into_owned())
        .collect();
    assert!(!strings.iter().any(|s| s == "--sharpness"));
    assert!(strings.windows(2).any(|w| w == ["--filter", "nearest"]));
}

#[test]
fn windowed_launch_does_not_request_fullscreen() {
    let mut config = sample_config();
    config.fullscreen = false;
    let args = build_gamescope_args(&config).unwrap();
    assert!(!args.iter().any(|s| s == "--fullscreen"));
}

#[test]
fn profile_round_trip_preserves_user_settings() {
    let root = std::env::temp_dir().join(format!("nkscale-profile-test-{}", std::process::id()));
    let path = root.join("profile.json");
    let profile = Profile {
        name: "Living room 4K".into(),
        settings: sample_config(),
    };
    save_profile(&path, &profile).unwrap();
    assert_eq!(load_profile(&path).unwrap(), profile);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn wayland_sdl_driver_is_used_by_gamescope_for_consistent_display_indices() {
    assert_eq!(gamescope_environment(), [("SDL_VIDEODRIVER", "wayland")]);
}

#[test]
fn selected_display_is_revalidated_by_stable_identity_after_reordering() {
    let selected = Monitor {
        index: 3,
        name: String::new(),
        width: 1920,
        height: 1080,
        refresh_hz: 60.0,
        x: 8320,
        y: 0,
    };
    let refreshed = [
        Monitor {
            index: 4,
            name: String::new(),
            width: 1920,
            height: 1080,
            refresh_hz: 60.0,
            x: 10240,
            y: 0,
        },
        Monitor {
            index: 5,
            name: String::new(),
            width: 1920,
            height: 1080,
            refresh_hz: 60.0,
            x: 8320,
            y: 0,
        },
    ];

    assert_eq!(resolve_display_index(&selected, &refreshed), Some(5));
}

#[test]
fn selected_display_revalidation_fails_closed_when_output_disappears() {
    let selected = Monitor {
        index: 3,
        name: String::new(),
        width: 1920,
        height: 1080,
        refresh_hz: 60.0,
        x: 8320,
        y: 0,
    };

    assert_eq!(resolve_display_index(&selected, &[]), None);
}

#[test]
fn monitor_description_includes_refresh_and_dimensions() {
    let monitor = Monitor {
        index: 1,
        name: "HDMI-A-1".into(),
        width: 3840,
        height: 2160,
        refresh_hz: 60.0,
        x: 0,
        y: 0,
    };
    assert!(monitor.label().contains("3840×2160"));
    assert!(monitor.label().contains("60 Hz"));
}
