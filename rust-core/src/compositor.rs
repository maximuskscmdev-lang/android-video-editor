/// Text / sticker compositing on RGBA then back to YUV is done via Kotlin-side Image path.
/// For YUV speed path we provide a simple rgba overlay blit onto YUV420 by converting small region.
///
/// This module provides CPU raster + blit for sideload APK (no GL). For 4K with many stickers,
/// future optimization: OpenGL shader in Kotlin.

use image::GenericImageView;

/// Blit RGBA sticker onto YUV420 planes.
/// sticker_rgba: w*h*4 bytes, already resized to target size.
/// x,y: top-left in luma pixels.
pub fn blit_rgba_onto_yuv420(
    y_plane: &mut [u8],
    u_plane: &mut [u8],
    v_plane: &mut [u8],
    frame_w: u32,
    frame_h: u32,
    sticker_rgba: &[u8],
    sticker_w: u32,
    sticker_h: u32,
    x: u32,
    y: u32,
) {
    let fw = frame_w as usize;
    let fh = frame_h as usize;
    let sw = sticker_w as usize;
    let sh = sticker_h as usize;
    for sy in 0..sh {
        for sx in 0..sw {
            let dx = x as usize + sx;
            let dy = y as usize + sy;
            if dx >= fw || dy >= fh {
                continue;
            }
            let s_idx = (sy * sw + sx) * 4;
            let r = sticker_rgba[s_idx] as f32;
            let g = sticker_rgba[s_idx + 1] as f32;
            let b = sticker_rgba[s_idx + 2] as f32;
            let a = sticker_rgba[s_idx + 3] as f32 / 255.0;
            if a < 0.01 {
                continue;
            }
            // RGB -> YUV BT.601
            let y_val = (0.299 * r + 0.587 * g + 0.114 * b).round().clamp(0.0, 255.0) as u8;
            let u_val = (-0.168736 * r - 0.331264 * g + 0.5 * b + 128.0).round().clamp(0.0, 255.0) as u8;
            let v_val = (0.5 * r - 0.418688 * g - 0.081312 * b + 128.0).round().clamp(0.0, 255.0) as u8;

            let y_idx = dy * fw + dx;
            // alpha blend on Y
            let dst_y = y_plane[y_idx] as f32;
            y_plane[y_idx] = (dst_y * (1.0 - a) + y_val as f32 * a).round() as u8;

            // Chroma subsampled: only write for even coords to avoid double-blend artifacts
            if dx % 2 == 0 && dy % 2 == 0 {
                let uv_w = fw / 2;
                let uv_idx = (dy / 2) * uv_w + (dx / 2);
                if uv_idx < u_plane.len() {
                    let dst_u = u_plane[uv_idx] as f32;
                    let dst_v = v_plane[uv_idx] as f32;
                    u_plane[uv_idx] = (dst_u * (1.0 - a) + u_val as f32 * a).round() as u8;
                    v_plane[uv_idx] = (dst_v * (1.0 - a) + v_val as f32 * a).round() as u8;
                }
            }
        }
    }
}

/// Decode sticker bytes (png) and resize to target norm size relative to frame.
pub fn decode_and_resize_sticker(
    png_bytes: &[u8],
    frame_w: u32,
    frame_h: u32,
    w_norm: f32,
    h_norm: f32,
) -> Result<(Vec<u8>, u32, u32), String> {
    let img = image::load_from_memory(png_bytes).map_err(|e| format!("sticker decode: {e}"))?;
    let target_w = ((frame_w as f32 * w_norm).round() as u32).max(2);
    let target_h = ((frame_h as f32 * h_norm).round() as u32).max(2);
    let resized = image::imageops::resize(&img, target_w, target_h, image::imageops::FilterType::Triangle);
    let mut rgba = Vec::with_capacity((target_w * target_h * 4) as usize);
    for (_, _, px) in resized.enumerate_pixels() {
        rgba.extend_from_slice(&px.0);
    }
    Ok((rgba, target_w, target_h))
}

/// Simple text raster using rusttype: returns RGBA buffer.
/// For production, Kotlin should use Android Canvas to render text with system fonts and pass RGBA to Rust.
/// This is fallback for pure-Rust path when font bytes are bundled.
pub fn raster_text_rgba(
    text: &str,
    size_px: u32,
    rgba_color: u32, // 0xRRGGBBAA
    font_bytes: &[u8],
) -> Result<(Vec<u8>, u32, u32), String> {
    let font = rusttype::Font::try_from_bytes(font_bytes).ok_or("invalid font")?;
    let scale = rusttype::Scale::uniform(size_px as f32);
    let v_metrics = font.v_metrics(scale);
    let height = (v_metrics.ascent - v_metrics.descent).ceil() as u32;
    let width: u32 = {
        let g: Vec<_> = font.layout(text, scale, rusttype::point(0.0, v_metrics.ascent)).collect();
        g.iter()
            .rev()
            .next()
            .map(|g| {
                let bb = g.pixel_bounding_box();
                bb.map(|b| b.max.x as u32).unwrap_or(0)
            })
            .unwrap_or((text.len() as u32 * size_px / 2).max(10))
    };
    let width = width.max(1);
    let height = height.max(1);
    let mut buf = vec![0u8; (width * height * 4) as usize];
    let r = ((rgba_color >> 24) & 0xFF) as u8;
    let g = ((rgba_color >> 16) & 0xFF) as u8;
    let b = ((rgba_color >> 8) & 0xFF) as u8;
    let a_col = (rgba_color & 0xFF) as u8;

    for glyph in font.layout(text, scale, rusttype::point(0.0, v_metrics.ascent)) {
        if let Some(bb) = glyph.pixel_bounding_box() {
            glyph.draw(|x, y, v| {
                let px = (bb.min.x + x as i32) as u32;
                let py = (bb.min.y + y as i32) as u32;
                if px < width && py < height {
                    let idx = ((py * width + px) * 4) as usize;
                    let alpha = (v * (a_col as f32 / 255.0) * 255.0).round() as u8;
                    buf[idx] = r;
                    buf[idx + 1] = g;
                    buf[idx + 2] = b;
                    buf[idx + 3] = alpha;
                }
            });
        }
    }
    Ok((buf, width, height))
}
