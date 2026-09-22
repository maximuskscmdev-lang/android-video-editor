/// Crop/rotate/scale helpers for YUV420 and generic buffers.
/// All dimensions are in pixels; Kotlin validates against decoder output size.

#[derive(Debug, Clone, Copy)]
pub struct CropRect {
    pub x: u32,
    pub y: u32,
    pub w: u32,
    pub h: u32,
}

impl CropRect {
    pub fn clamp(&self, src_w: u32, src_h: u32) -> Self {
        let x = self.x.min(src_w.saturating_sub(1));
        let y = self.y.min(src_h.saturating_sub(1));
        let w = self.w.min(src_w - x).max(2);
        let h = self.h.min(src_h - y).max(2);
        // YUV420 requires even dimensions/offsets
        CropRect {
            x: x & !1,
            y: y & !1,
            w: w & !1,
            h: h & !1,
        }
    }
}

/// Crop YUV420 planar.
pub fn crop_yuv420(
    y_in: &[u8],
    u_in: &[u8],
    v_in: &[u8],
    src_w: u32,
    src_h: u32,
    rect: CropRect,
) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
    let r = rect.clamp(src_w, src_h);
    let src_w = src_w as usize;
    let dst_w = r.w as usize;
    let dst_h = r.h as usize;

    // Y plane
    let mut y_out = vec![0u8; dst_w * dst_h];
    for row in 0..dst_h {
        let src_row = r.y as usize + row;
        let src_off = src_row * src_w + r.x as usize;
        let dst_off = row * dst_w;
        y_out[dst_off..dst_off + dst_w].copy_from_slice(&y_in[src_off..src_off + dst_w]);
    }
    // U/V planes half res
    let src_w_uv = src_w / 2;
    let dst_w_uv = dst_w / 2;
    let dst_h_uv = dst_h / 2;
    let mut u_out = vec![0u8; dst_w_uv * dst_h_uv];
    let mut v_out = vec![0u8; dst_w_uv * dst_h_uv];
    for row in 0..dst_h_uv {
        let src_row = (r.y as usize / 2) + row;
        let src_off = src_row * src_w_uv + (r.x as usize / 2);
        let dst_off = row * dst_w_uv;
        u_out[dst_off..dst_off + dst_w_uv]
            .copy_from_slice(&u_in[src_off..src_off + dst_w_uv]);
        v_out[dst_off..dst_off + dst_w_uv]
            .copy_from_slice(&v_in[src_off..src_off + dst_w_uv]);
    }
    (y_out, u_out, v_out)
}

/// Rotate YUV420 by 0/90/180/270. Returns new buffers + new (w,h).
pub fn rotate_yuv420(
    y_in: Vec<u8>,
    u_in: Vec<u8>,
    v_in: Vec<u8>,
    w: u32,
    h: u32,
    degrees: i32,
) -> (Vec<u8>, Vec<u8>, Vec<u8>, u32, u32) {
    match degrees {
        0 => (y_in, u_in, v_in, w, h),
        90 => {
            let (y, nw, nh) = rotate_plane_90(y_in, w, h);
            let (u, _, _) = rotate_plane_90(u_in, w / 2, h / 2);
            let (v, _, _) = rotate_plane_90(v_in, w / 2, h / 2);
            (y, u, v, nw, nh)
        }
        180 => {
            let y = rotate_plane_180(y_in);
            let u = rotate_plane_180(u_in);
            let v = rotate_plane_180(v_in);
            (y, u, v, w, h)
        }
        270 => {
            let (y, nw, nh) = rotate_plane_270(y_in, w, h);
            let (u, _, _) = rotate_plane_270(u_in, w / 2, h / 2);
            let (v, _, _) = rotate_plane_270(v_in, w / 2, h / 2);
            (y, u, v, nw, nh)
        }
        _ => (y_in, u_in, v_in, w, h),
    }
}

fn rotate_plane_90(data: Vec<u8>, w: u32, h: u32) -> (Vec<u8>, u32, u32) {
    let w = w as usize;
    let h = h as usize;
    let mut out = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            let src = y * w + x;
            // 90 cw: (x,y) -> (h-1-y, x) in new w'=h, h'=w
            let dst = x * h + (h - 1 - y);
            out[dst] = data[src];
        }
    }
    (out, h as u32, w as u32)
}

fn rotate_plane_270(data: Vec<u8>, w: u32, h: u32) -> (Vec<u8>, u32, u32) {
    let w = w as usize;
    let h = h as usize;
    let mut out = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            let src = y * w + x;
            let dst = (w - 1 - x) * h + y;
            out[dst] = data[src];
        }
    }
    (out, h as u32, w as u32)
}

fn rotate_plane_180(mut data: Vec<u8>) -> Vec<u8> {
    data.reverse();
    data
}

/// Bilinear scale YUV420 (simple, for 720p/1080p/4K export; Kotlin can also use MediaCodec surface scale if faster).
pub fn scale_yuv420(
    y_in: &[u8],
    u_in: &[u8],
    v_in: &[u8],
    src_w: u32,
    src_h: u32,
    dst_w: u32,
    dst_h: u32,
) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
    let y_out = scale_plane_bilinear(y_in, src_w, src_h, dst_w, dst_h);
    let u_out = scale_plane_bilinear(u_in, src_w / 2, src_h / 2, dst_w / 2, dst_h / 2);
    let v_out = scale_plane_bilinear(v_in, src_w / 2, src_h / 2, dst_w / 2, dst_h / 2);
    (y_out, u_out, v_out)
}

fn scale_plane_bilinear(src: &[u8], sw: u32, sh: u32, dw: u32, dh: u32) -> Vec<u8> {
    if sw == dw && sh == dh {
        return src.to_vec();
    }
    let sw = sw as usize;
    let sh = sh as usize;
    let dw = dw as usize;
    let dh = dh as usize;
    let mut out = vec![0u8; dw * dh];
    let x_ratio = sw as f32 / dw as f32;
    let y_ratio = sh as f32 / dh as f32;
    for y in 0..dh {
        for x in 0..dw {
            let sx = (x as f32 * x_ratio).clamp(0.0, (sw - 1) as f32);
            let sy = (y as f32 * y_ratio).clamp(0.0, (sh - 1) as f32);
            let x0 = sx.floor() as usize;
            let y0 = sy.floor() as usize;
            let x1 = (x0 + 1).min(sw - 1);
            let y1 = (y0 + 1).min(sh - 1);
            let fx = sx - x0 as f32;
            let fy = sy - y0 as f32;
            let p00 = src[y0 * sw + x0] as f32;
            let p10 = src[y0 * sw + x1] as f32;
            let p01 = src[y1 * sw + x0] as f32;
            let p11 = src[y1 * sw + x1] as f32;
            let top = p00 * (1.0 - fx) + p10 * fx;
            let bot = p01 * (1.0 - fx) + p11 * fx;
            let v = top * (1.0 - fy) + bot * fy;
            out[y * dw + x] = v.round().clamp(0.0, 255.0) as u8;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crop_even() {
        let w = 4;
        let h = 4;
        let y: Vec<u8> = (0..16).collect();
        let u: Vec<u8> = vec![1, 2, 3, 4];
        let v: Vec<u8> = vec![5, 6, 7, 8];
        let rect = CropRect { x: 1, y: 1, w: 2, h: 2 }.clamp(4, 4);
        assert_eq!(rect.x % 2, 0);
        let (y_out, _, _) = crop_yuv420(&y, &u, &v, w, h, rect);
        assert_eq!(y_out.len(), (rect.w * rect.h) as usize);
    }
}
