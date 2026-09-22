use crate::timeline::FilterKind;

/// YUV420 planar (I420) layout: Y plane W*H, then U W/2*H/2, V W/2*H/2
/// Kotlin ensures buffers are COLOR_FormatYUV420Flexible de-tiled to this planar for Rust.
/// For NV12/NV21 we convert to I420 in Kotlin before calling Rust (see YUVConverter.kt).

pub fn apply_filter_yuv420(
    y_plane: &mut [u8],
    u_plane: &mut [u8],
    v_plane: &mut [u8],
    _width: u32,
    _height: u32,
    filter: &FilterKind,
) {
    match filter {
        FilterKind::None => {}
        FilterKind::Grayscale => {
            // Set chroma to neutral 128 (no color)
            u_plane.fill(128);
            v_plane.fill(128);
        }
        FilterKind::Brightness(delta) => {
            let d = *delta as i16;
            for b in y_plane.iter_mut() {
                let v = *b as i16 + d;
                *b = v.clamp(0, 255) as u8;
            }
            // slight chroma lift not needed; keep U/V
        }
        FilterKind::Contrast(factor) => {
            let f = *factor;
            for b in y_plane.iter_mut() {
                let v = (*b as f32 - 128.0) * f + 128.0;
                *b = v.round().clamp(0.0, 255.0) as u8;
            }
        }
    }
}

/// RGBA 8-bit per channel, width*height*4 bytes. Used for compositor after YUV->RGB conversion.
pub fn apply_filter_rgba(data: &mut [u8], filter: &FilterKind) {
    match filter {
        FilterKind::None => {}
        FilterKind::Grayscale => {
            for px in data.chunks_exact_mut(4) {
                let r = px[0] as f32;
                let g = px[1] as f32;
                let b = px[2] as f32;
                // BT.601 luma
                let luma = (0.299 * r + 0.587 * g + 0.114 * b) as u8;
                px[0] = luma;
                px[1] = luma;
                px[2] = luma;
            }
        }
        FilterKind::Brightness(delta) => {
            for px in data.chunks_exact_mut(4) {
                for i in 0..3 {
                    let v = px[i] as i16 + *delta as i16;
                    px[i] = v.clamp(0, 255) as u8;
                }
            }
        }
        FilterKind::Contrast(factor) => {
            for px in data.chunks_exact_mut(4) {
                for i in 0..3 {
                    let v = (px[i] as f32 - 128.0) * *factor + 128.0;
                    px[i] = v.round().clamp(0.0, 255.0) as u8;
                }
            }
        }
    }
}

/// Utility: YUV420 -> RGBA conversion (for preview/debug, not hot path if using YUV filters).
pub fn yuv420_to_rgba(y: &[u8], u: &[u8], v: &[u8], width: u32, height: u32) -> Vec<u8> {
    let w = width as usize;
    let h = height as usize;
    let mut out = vec![0u8; w * h * 4];
    for row in 0..h {
        for col in 0..w {
            let y_val = y[row * w + col] as i32;
            let uv_idx = (row / 2) * (w / 2) + (col / 2);
            let u_val = u[uv_idx] as i32 - 128;
            let v_val = v[uv_idx] as i32 - 128;
            // BT.601
            let mut r = y_val + (1.402 * v_val as f32) as i32;
            let mut g = y_val - (0.344136 * u_val as f32) as i32 - (0.714136 * v_val as f32) as i32;
            let mut b = y_val + (1.772 * u_val as f32) as i32;
            r = r.clamp(0, 255);
            g = g.clamp(0, 255);
            b = b.clamp(0, 255);
            let idx = (row * w + col) * 4;
            out[idx] = r as u8;
            out[idx + 1] = g as u8;
            out[idx + 2] = b as u8;
            out[idx + 3] = 255;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grayscale_sets_chroma() {
        let mut y = vec![100u8; 16];
        let mut u = vec![10u8; 4];
        let mut v = vec![20u8; 4];
        apply_filter_yuv420(&mut y, &mut u, &mut v, 4, 4, &FilterKind::Grayscale);
        assert!(u.iter().all(|&x| x == 128));
        assert!(v.iter().all(|&x| x == 128));
    }

    #[test]
    fn brightness_clamps() {
        let mut y = vec![250u8; 4];
        let mut u = vec![128u8; 1];
        let mut v = vec![128u8; 1];
        apply_filter_yuv420(&mut y, &mut u, &mut v, 2, 2, &FilterKind::Brightness(10));
        assert_eq!(y[0], 255);
    }
}
