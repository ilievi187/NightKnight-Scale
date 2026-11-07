use jni::EnvUnowned;
use jni::errors::{Error, JniError, ThrowRuntimeExAndDefault};
use jni::objects::JObject;
use jni::sys::{jint, jintArray};

/// Pixel dimensions passed to the deterministic EASU stage planner.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PixelSize {
    pub width: u32,
    pub height: u32,
}

/// Match the Android renderer's <=2x-per-axis EASU stage scheduling policy.
///
/// Inputs are bounded by Android's GLES texture limits in production. Saturating
/// multiplication also keeps this public planner safe for arbitrary callers.
pub fn easu_stages(source: PixelSize, target: PixelSize) -> Vec<PixelSize> {
    if source.width == 0 || source.height == 0 || target.width == 0 || target.height == 0 {
        return Vec::new();
    }
    if target.width < source.width || target.height < source.height {
        return vec![target];
    }

    let mut current = source;
    let mut stages = Vec::new();
    while current != target {
        let next = PixelSize {
            width: target.width.min(current.width.saturating_mul(2)),
            height: target.height.min(current.height.saturating_mul(2)),
        };
        if next == current {
            return Vec::new();
        }
        stages.push(next);
        current = next;
    }
    stages
}

fn flatten_stages(stages: &[PixelSize]) -> Option<Vec<jint>> {
    let mut flattened = Vec::with_capacity(stages.len() * 2);
    for stage in stages {
        flattened.push(jint::try_from(stage.width).ok()?);
        flattened.push(jint::try_from(stage.height).ok()?);
    }
    Some(flattened)
}

/// JNI entry point used by `NativeEasuStagePlanner` in the Android application.
/// The library keeps no GL state and is called only when a render plan is built.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_nightknight_scale_tv_NativeEasuStagePlanner_nativePlanEasuStages<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _instance: JObject<'local>,
    source_width: jint,
    source_height: jint,
    target_width: jint,
    target_height: jint,
) -> jintArray {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jintArray> {
            let source = PixelSize {
                width: source_width.max(0) as u32,
                height: source_height.max(0) as u32,
            };
            let target = PixelSize {
                width: target_width.max(0) as u32,
                height: target_height.max(0) as u32,
            };
            let flattened = flatten_stages(&easu_stages(source, target))
                .ok_or(Error::JniCall(JniError::InvalidArguments))?;
            let result = env.new_int_array(flattened.len())?;
            result.set_region(env, 0, &flattened)?;
            Ok(result.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::{PixelSize, easu_stages, flatten_stages};

    #[test]
    fn jni_dimensions_are_flattened_as_width_height_pairs() {
        assert_eq!(
            flatten_stages(&[
                PixelSize {
                    width: 1920,
                    height: 1080,
                },
                PixelSize {
                    width: 3840,
                    height: 2160,
                },
            ])
            .unwrap(),
            vec![1920, 1080, 3840, 2160],
        );
    }

    #[test]
    fn upscale_540p_to_4k_uses_two_easu_stages_capped_at_two_x_per_axis() {
        assert_eq!(
            easu_stages(
                PixelSize {
                    width: 960,
                    height: 540
                },
                PixelSize {
                    width: 3840,
                    height: 2160
                }
            ),
            vec![
                PixelSize {
                    width: 1920,
                    height: 1080
                },
                PixelSize {
                    width: 3840,
                    height: 2160
                },
            ],
        );
    }

    #[test]
    fn pal_576p_to_4k_uses_three_stages_and_reaches_exact_target() {
        let source = PixelSize {
            width: 720,
            height: 576,
        };
        let target = PixelSize {
            width: 3840,
            height: 2160,
        };
        let stages = easu_stages(source, target);
        assert_eq!(
            stages,
            vec![
                PixelSize {
                    width: 1440,
                    height: 1152
                },
                PixelSize {
                    width: 2880,
                    height: 2160
                },
                target,
            ],
        );
        let mut previous = source;
        for stage in stages {
            assert!(stage.width <= previous.width * 2);
            assert!(stage.height <= previous.height * 2);
            previous = stage;
        }
        assert_eq!(previous, target);
    }

    #[test]
    fn invalid_dimensions_produce_no_stages() {
        assert!(
            easu_stages(
                PixelSize {
                    width: 0,
                    height: 540
                },
                PixelSize {
                    width: 1920,
                    height: 1080
                }
            )
            .is_empty()
        );
        assert!(
            easu_stages(
                PixelSize {
                    width: 960,
                    height: 540
                },
                PixelSize {
                    width: 1920,
                    height: 0
                }
            )
            .is_empty()
        );
    }

    #[test]
    fn equal_size_produces_no_stages_and_downscale_preserves_existing_single_target_behavior() {
        let size = PixelSize {
            width: 1920,
            height: 1080,
        };
        assert!(easu_stages(size, size).is_empty());
        let smaller = PixelSize {
            width: 1280,
            height: 720,
        };
        assert_eq!(easu_stages(size, smaller), vec![smaller]);
    }

    #[test]
    fn large_positive_dimensions_avoid_overflow_and_terminate() {
        let stages = easu_stages(
            PixelSize {
                width: 1,
                height: 1,
            },
            PixelSize {
                width: i32::MAX as u32,
                height: 1,
            },
        );
        assert_eq!(
            stages.last(),
            Some(&PixelSize {
                width: i32::MAX as u32,
                height: 1
            })
        );
        let mut previous_width = 1_u32;
        for stage in stages {
            assert!(stage.width <= previous_width.saturating_mul(2));
            previous_width = stage.width;
        }
    }
}
