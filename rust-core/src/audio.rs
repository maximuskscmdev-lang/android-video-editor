/// Audio handling notes for MediaCodec path.
/// VideoEngine.kt handles demux/remux. Rust provides helper math for audio timeline.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioClip {
    pub path: String,
    pub trim_start_ms: u64,
    pub trim_end_ms: u64,
    pub volume: f32,
}

/// Calculate audio PTS mapping for speed/reverse.
/// Returns closure: input_pts_us -> output_pts_us
pub fn pts_map_for_speed(speed: f32, reverse: bool, total_duration_us: i64) -> impl Fn(i64) -> i64 {
    move |pts| {
        let mapped = (pts as f32 / speed) as i64;
        if reverse {
            total_duration_us - mapped
        } else {
            mapped
        }
    }
}

/// Mix two PCM i16 buffers (mono) with volumes. Caller handles resampling if needed.
/// For AAC path, Kotlin decodes to PCM, Rust mixes, then re-encodes.
pub fn mix_pcm_i16(a: &[i16], b: &[i16], vol_a: f32, vol_b: f32) -> Vec<i16> {
    let len = a.len().max(b.len());
    let mut out = Vec::with_capacity(len);
    for i in 0..len {
        let sa = a.get(i).copied().unwrap_or(0) as f32 * vol_a;
        let sb = b.get(i).copied().unwrap_or(0) as f32 * vol_b;
        let mixed = (sa + sb).round().clamp(i16::MIN as f32, i16::MAX as f32) as i16;
        out.push(mixed);
    }
    out
}
