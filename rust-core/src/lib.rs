pub mod audio;
pub mod compositor;
pub mod filters;
pub mod timeline;
pub mod transform;

use jni::objects::{JByteBuffer, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use timeline::{FilterKind, Project, Resolution, Transform};

/// Version exposed to Kotlin.
#[no_mangle]
pub extern "system" fn Java_com_videoeditor_RustBridge_getVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let ver = format!("video_core {}", env!("CARGO_PKG_VERSION"));
    env.new_string(ver).unwrap_or_else(|_| env.new_string("unknown").unwrap())
}

/// Validate project JSON: returns 0 on success, 1 on error and throws IllegalArgumentException with msg.
#[no_mangle]
pub extern "system" fn Java_com_videoeditor_RustBridge_validateProject<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    j_json: JString<'local>,
) -> jint {
    let json: String = match env.get_string(&j_json) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    match serde_json::from_str::<Project>(&json).and_then(|p| {
        p.validate().map_err(|e| {
            serde_json::Error::io(std::io::Error::new(std::io::ErrorKind::Other, e))
        })
    }) {
        Ok(_) => 0,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", format!("validate: {e}"));
            1
        }
    }
}

/// Estimate duration (ms) for project JSON. Returns -1 on parse error.
#[no_mangle]
pub extern "system" fn Java_com_videoeditor_RustBridge_estimateDurationMs<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    j_json: JString<'local>,
) -> jlong {
    let json: String = match env.get_string(&j_json) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    match serde_json::from_str::<Project>(&json) {
        Ok(p) => p.estimated_duration_ms() as jlong,
        Err(_) => -1,
    }
}

/// Process single YUV420 frame: inputs are direct ByteBuffers (Y, U, V) + ints w,h + json ops.
/// We apply transforms in order defined by Transform vec encoded as JSON array string.
/// For Kotlin simplicity, we pass a single Clip's transforms as JSON array of Transform (not full Project).
/// Returns 0 on success (buffers mutated in place via direct buffer), 1 on error.
#[no_mangle]
pub extern "system" fn Java_com_videoeditor_RustBridge_processFrameYUV420<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    y_buf: JByteBuffer<'local>,
    u_buf: JByteBuffer<'local>,
    v_buf: JByteBuffer<'local>,
    width: jint,
    height: jint,
    j_ops_json: JString<'local>,
) -> jint {
    let ops_json: String = match env.get_string(&j_ops_json) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let w = width as u32;
    let h = height as u32;
    if w == 0 || h == 0 || w % 2 != 0 || h % 2 != 0 {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            format!("invalid w/h {w}x{h} must be even"),
        );
        return 1;
    }

    let transforms: Vec<Transform> = match serde_json::from_str(&ops_json) {
        Ok(v) => v,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", format!("ops json: {e}"));
            return 1;
        }
    };

    // Get direct buffer slices mut
    let y_ptr = match env.get_direct_buffer_address(&y_buf) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("y_buf not direct: {e}"));
            return 1;
        }
    };
    let u_ptr = match env.get_direct_buffer_address(&u_buf) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("u_buf not direct: {e}"));
            return 1;
        }
    };
    let v_ptr = match env.get_direct_buffer_address(&v_buf) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("v_buf not direct: {e}"));
            return 1;
        }
    };
    // Safety: direct buffers are backed by Kotlin ByteBuffer.allocateDirect, live for call duration
    let y_len = (w * h) as usize;
    let uv_len = (w / 2 * h / 2) as usize;
    let y_slice = unsafe { std::slice::from_raw_parts_mut(y_ptr as *mut u8, y_len) };
    let u_slice = unsafe { std::slice::from_raw_parts_mut(u_ptr as *mut u8, uv_len) };
    let v_slice = unsafe { std::slice::from_raw_parts_mut(v_ptr as *mut u8, uv_len) };

    for op in &transforms {
        match op {
            Transform::Filter(f) => {
                filters::apply_filter_yuv420(y_slice, u_slice, v_slice, w, h, f);
            }
            Transform::Crop { x, y, w: cw, h: ch } => {
                // Crop requires realloc; for in-place we allocate new and copy back truncated.
                // Caller should use output dims from Rust helper; here we just crop in place by copying.
                let rect = transform::CropRect { x: *x, y: *y, w: *cw, h: *ch };
                let (y2, u2, v2) = transform::crop_yuv420(y_slice, u_slice, v_slice, w, h, rect);
                // If crop smaller, we truncate buffers: Kotlin must call getCroppedDimensions to know new size.
                // Copy as much as fits (caller passes buffers sized to cropped dims in next frame)
                // For simplicity, copy cropped into start of buffer; Kotlin will handle new w/h via Rust helper.
                let copy_len = y2.len().min(y_slice.len());
                y_slice[..copy_len].copy_from_slice(&y2[..copy_len]);
                let uv_copy = u2.len().min(u_slice.len());
                u_slice[..uv_copy].copy_from_slice(&u2[..uv_copy]);
                v_slice[..uv_copy].copy_from_slice(&v2[..uv_copy]);
            }
            // Scale/rotate are handled via helper that returns new w/h; Kotlin will allocate new buffers.
            // Here we no-op; use dedicated JNI for those.
            Transform::Scale { .. } | Transform::Rotate { .. } => {
                // no-op, use processScale/processRotate JNI
            }
            _ => {}
        }
    }
    0
}

/// Helper: get cropped dimensions for allocation before processFrame.
#[no_mangle]
pub extern "system" fn Java_com_videoeditor_RustBridge_getCroppedDimensions<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
    src_w: jint,
    src_h: jint,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
) -> jlong {
    let rect = transform::CropRect {
        x: x as u32,
        y: y as u32,
        w: w as u32,
        h: h as u32,
    }
    .clamp(src_w as u32, src_h as u32);
    // pack w/h into jlong: high 32 w, low 32 h
    ((rect.w as jlong) << 32) | (rect.h as jlong)
}

/// Scale helper: Kotlin allocates dst buffers, Rust scales.
#[no_mangle]
pub extern "system" fn Java_com_videoeditor_RustBridge_scaleFrameYUV420<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
    y_src: JByteBuffer<'local>,
    u_src: JByteBuffer<'local>,
    v_src: JByteBuffer<'local>,
    src_w: jint,
    src_h: jint,
    y_dst: JByteBuffer<'local>,
    u_dst: JByteBuffer<'local>,
    v_dst: JByteBuffer<'local>,
    dst_w: jint,
    dst_h: jint,
) -> jint {
    let sw = src_w as u32;
    let sh = src_h as u32;
    let dw = dst_w as u32;
    let dh = dst_h as u32;

    let y_src_ptr = match env.get_direct_buffer_address(&y_src) {
        Ok(p) => p,
        Err(_) => return -1,
    };
    let u_src_ptr = match env.get_direct_buffer_address(&u_src) {
        Ok(p) => p,
        Err(_) => return -1,
    };
    let v_src_ptr = match env.get_direct_buffer_address(&v_src) {
        Ok(p) => p,
        Err(_) => return -1,
    };
    let y_dst_ptr = match env.get_direct_buffer_address(&y_dst) {
        Ok(p) => p,
        Err(_) => return -1,
    };
    let u_dst_ptr = match env.get_direct_buffer_address(&u_dst) {
        Ok(p) => p,
        Err(_) => return -1,
    };
    let v_dst_ptr = match env.get_direct_buffer_address(&v_dst) {
        Ok(p) => p,
        Err(_) => return -1,
    };

    let y_src_slice = unsafe { std::slice::from_raw_parts(y_src_ptr as *const u8, (sw * sh) as usize) };
    let u_src_slice = unsafe { std::slice::from_raw_parts(u_src_ptr as *const u8, (sw / 2 * sh / 2) as usize) };
    let v_src_slice = unsafe { std::slice::from_raw_parts(v_src_ptr as *const u8, (sw / 2 * sh / 2) as usize) };
    let y_dst_slice = unsafe { std::slice::from_raw_parts_mut(y_dst_ptr as *mut u8, (dw * dh) as usize) };
    let u_dst_slice = unsafe { std::slice::from_raw_parts_mut(u_dst_ptr as *mut u8, (dw / 2 * dh / 2) as usize) };
    let v_dst_slice = unsafe { std::slice::from_raw_parts_mut(v_dst_ptr as *mut u8, (dw / 2 * dh / 2) as usize) };

    let (y_out, u_out, v_out) = transform::scale_yuv420(y_src_slice, u_src_slice, v_src_slice, sw, sh, dw, dh);
    y_dst_slice.copy_from_slice(&y_out);
    u_dst_slice.copy_from_slice(&u_out);
    v_dst_slice.copy_from_slice(&v_out);
    0
}

/// Simple filter test: apply grayscale to RGBA buffer (direct).
#[no_mangle]
pub extern "system" fn Java_com_videoeditor_RustBridge_applyFilterRGBA<'local>(
    mut env: JNIEnv<'local>,
    _obj: JObject<'local>,
    rgba_buf: JByteBuffer<'local>,
    width: jint,
    height: jint,
    j_filter_json: JString<'local>,
) -> jint {
    let json: String = match env.get_string(&j_filter_json) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let filter: FilterKind = match serde_json::from_str(&json) {
        Ok(f) => f,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", format!("filter json: {e}"));
            return 1;
        }
    };
    let w = width as u32;
    let h = height as u32;
    let ptr = match env.get_direct_buffer_address(&rgba_buf) {
        Ok(p) => p,
        Err(_) => return -1,
    };
    let len = (w * h * 4) as usize;
    let slice = unsafe { std::slice::from_raw_parts_mut(ptr as *mut u8, len) };
    filters::apply_filter_rgba(slice, &filter);
    0
}

// Needed for linker on Android (no-op)
#[no_mangle]
pub extern "C" fn JNI_OnLoad(_vm: jni::JavaVM, _reserved: *const std::ffi::c_void) -> jint {
    jni::sys::JNI_VERSION_1_6
}
