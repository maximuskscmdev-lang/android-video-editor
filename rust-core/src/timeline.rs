use serde::{Deserialize, Serialize};

/// Full project definition, serialized from Kotlin as JSON.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub clips: Vec<Clip>,
    pub output_width: u32,
    pub output_height: u32,
    /// Target output: 720p / 1080p / 4K
    pub resolution: Resolution,
    pub fps: u32,
    pub bitrate: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum Resolution {
    #[serde(rename = "720p")]
    P720,
    #[serde(rename = "1080p")]
    P1080,
    #[serde(rename = "4K")]
    K4,
    #[serde(rename = "custom")]
    Custom,
}

impl Resolution {
    pub fn dimensions(&self, custom: Option<(u32, u32)>) -> (u32, u32) {
        match self {
            Resolution::P720 => (1280, 720),
            Resolution::P1080 => (1920, 1080),
            Resolution::K4 => (3840, 2160),
            Resolution::Custom => custom.unwrap_or((1920, 1080)),
        }
    }
}

/// One source clip in timeline order (concat order = vec order).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Clip {
    /// Absolute path in app cache (after UriResolver copy). Not URI.
    pub path: String,
    /// Trim start in milliseconds (inclusive)
    pub trim_start_ms: u64,
    /// Trim end in milliseconds (exclusive, 0 = end of file)
    pub trim_end_ms: u64,
    pub transforms: Vec<Transform>,
    /// Per-clip volume 0.0..1.0, 0=mute
    pub volume: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Transform {
    Crop { x: u32, y: u32, w: u32, h: u32 },
    Rotate { degrees: i32 }, // 0,90,180,270
    Scale { w: u32, h: u32 },
    Speed { factor: f32 }, // 0.25 .. 4.0
    Reverse,
    Filter(FilterKind),
    OverlayText { text: String, x_norm: f32, y_norm: f32, size: u32, color_rgba: u32 },
    OverlaySticker { asset_name: String, x_norm: f32, y_norm: f32, w_norm: f32, h_norm: f32 },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum FilterKind {
    None,
    Grayscale,
    Brightness(i32), // -100..100
    Contrast(f32),   // 0.0..3.0, 1.0 = no change
}

impl Project {
    pub fn validate(&self) -> Result<(), String> {
        if self.clips.is_empty() {
            return Err("Project has no clips".to_string());
        }
        if self.fps == 0 || self.fps > 120 {
            return Err(format!("Invalid fps: {}", self.fps));
        }
        for (i, c) in self.clips.iter().enumerate() {
            if c.path.is_empty() {
                return Err(format!("Clip {i} has empty path"));
            }
            if c.trim_end_ms != 0 && c.trim_end_ms <= c.trim_start_ms {
                return Err(format!(
                    "Clip {i} trim_end ({}) must be > trim_start ({})",
                    c.trim_end_ms, c.trim_start_ms
                ));
            }
            for t in &c.transforms {
                match t {
                    Transform::Speed { factor } => {
                        if !(*factor >= 0.25 && *factor <= 4.0) {
                            return Err(format!("Clip {i} invalid speed {factor}"));
                        }
                    }
                    Transform::Rotate { degrees } => {
                        if ![0, 90, 180, 270].contains(degrees) {
                            return Err(format!("Clip {i} invalid rotate {degrees}"));
                        }
                    }
                    Transform::Filter(FilterKind::Brightness(v)) => {
                        if !(*v >= -100 && *v <= 100) {
                            return Err(format!("Clip {i} brightness out of range {v}"));
                        }
                    }
                    Transform::Filter(FilterKind::Contrast(v)) => {
                        if !(*v >= 0.0 && *v <= 3.0) {
                            return Err(format!("Clip {i} contrast out of range {v}"));
                        }
                    }
                    _ => {}
                }
            }
        }
        Ok(())
    }

    /// Estimated total output duration in ms, accounting for speed (reverse doesn't change duration).
    pub fn estimated_duration_ms(&self) -> u64 {
        self.clips
            .iter()
            .map(|c| {
                let raw = if c.trim_end_ms == 0 {
                    // unknown source duration -> assume 0, caller should probe via MediaExtractor
                    0
                } else {
                    c.trim_end_ms.saturating_sub(c.trim_start_ms)
                };
                let speed = c
                    .transforms
                    .iter()
                    .find_map(|t| match t {
                        Transform::Speed { factor } => Some(*factor),
                        _ => None,
                    })
                    .unwrap_or(1.0);
                (raw as f32 / speed) as u64
            })
            .sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_empty_fails() {
        let p = Project {
            clips: vec![],
            output_width: 1920,
            output_height: 1080,
            resolution: Resolution::P1080,
            fps: 30,
            bitrate: 10_000_000,
        };
        assert!(p.validate().is_err());
    }

    #[test]
    fn validate_ok() {
        let p = Project {
            clips: vec![Clip {
                path: "/cache/a.mp4".to_string(),
                trim_start_ms: 0,
                trim_end_ms: 5000,
                transforms: vec![Transform::Filter(FilterKind::Grayscale)],
                volume: 1.0,
            }],
            output_width: 1280,
            output_height: 720,
            resolution: Resolution::P720,
            fps: 30,
            bitrate: 5_000_000,
        };
        assert!(p.validate().is_ok());
    }

    #[test]
    fn speed_duration() {
        let p = Project {
            clips: vec![Clip {
                path: "/a.mp4".to_string(),
                trim_start_ms: 0,
                trim_end_ms: 4000,
                transforms: vec![Transform::Speed { factor: 2.0 }],
                volume: 1.0,
            }],
            output_width: 1920,
            output_height: 1080,
            resolution: Resolution::P1080,
            fps: 30,
            bitrate: 10_000_000,
        };
        assert_eq!(p.estimated_duration_ms(), 2000);
    }
}
