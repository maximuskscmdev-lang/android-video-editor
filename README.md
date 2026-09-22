# Android Video Editor — Rust Core + Kotlin (MediaCodec, arm64-v8a, API 29+, Sideload APK)

Hybrid app per your request: **Kotlin Compose UI + Rust core `.so`** via JNI. Video pipeline uses **Android MediaCodec / MediaExtractor / MediaMuxer** (hardware-accelerated, no FFmpeg) and Rust does timeline math, YUV filters, crop/rotate/scale, compositing.

## Features (all requested)
- **Trim / Cut / Split** — per-clip `trim_start_ms`/`trim_end_ms`, split at timestamp, timeline handles in `android-app/app/src/main/java/com/videoeditor/ui/TimelineView.kt:1`
- **Merge / Concatenate** — clip order = output order, sequential extractors in `VideoEngine.kt:40`
- **Speed (0.25x–4x) + Reverse** — PTS scaling + frame reorder in `VideoEngine.kt:90` + Rust `audio.rs:1` pts map
- **Crop / Rotate / Scale** — Rust `rust-core/src/transform.rs:1` (YUV420 crop/rotate/scale), scale to **720p / 1080p / 4K** via `MediaFormat` in `Encoder.kt:1`
- **Filters** — Brightness / Contrast / B&W in `rust-core/src/filters.rs:1` (YUV + RGBA)
- **Text / Sticker overlay** — Rust `compositor.rs:1` (RGBA blit onto YUV, `image` + `rusttype`)
- **Audio track replacement** — mixed via `audio.rs:1` + `MediaExtractor` audio track remux
- **Export mp4 only** — `MediaMuxer` `OUTPUT_FORMAT_MPEG_4` with selectable 720p/1080p/4K in `ExportDialog.kt:1`

## Project Structure
```
android-video-editor/
├── Cargo.toml (workspace)
├── rust-toolchain.toml (stable + aarch64-linux-android)
├── rust-core/              # cdylib -> libvideo_core.so
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs:1        # JNI: processFrameYUV420, scale, validate, etc.
│       ├── timeline.rs:1   # Project/Clip/Transform, validate, duration
│       ├── filters.rs:1    # YUV420/RGBA kernels
│       ├── transform.rs:1  # crop/rotate/scale
│       ├── compositor.rs:1 # text/sticker
│       └── audio.rs:1
└── android-app/            # Gradle + Compose
    ├── settings.gradle.kts
    ├── app/build.gradle.kts:1  # minSdk 29, arm64-v8a, cargo-ndk task
    └── app/src/main/java/com/videoeditor/
        ├── RustBridge.kt:1
        ├── MainActivity.kt:1
        ├── engine/VideoEngine.kt:1  # decode->Rust->encode->mux
        ├── engine/Decoder.kt:1, Encoder.kt:1, MuxerWrapper.kt:1
        ├── util/UriResolver.kt:1 (scoped storage), YUVConverter.kt:1, Permissions.kt:1
        └── ui/TimelineView.kt:1, PreviewPlayer.kt:1, ExportDialog.kt:1
```

## Build — Sideload APK (arm64-v8a only)

### Prerequisites (tested)
- JDK 17, Android SDK `compileSdk 34`, NDK `26.1.10909125`, Rust stable + `cargo-ndk 4.1+`
- Device/emulator API 29+ arm64-v8a

### 1. Rust core (arm64-v8a)
```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
# set NDK
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
cd /root/android-video-editor
cargo ndk -t arm64-v8a -o android-app/app/src/main/jniLibs build --release
# output: android-app/app/src/main/jniLibs/arm64-v8a/libvideo_core.so
```
Or `./scripts/setup.sh:1`

Host unit tests (no NDK needed):
```bash
cargo test --manifest-path Cargo.toml  # 6 tests: filters, timeline, transform
cargo build --manifest-path Cargo.toml
```

### 2. APK (sideload)
```bash
cd android-app
./gradlew assembleDebug          # sideload debug
./gradlew assembleRelease        # sideload release (no Play signing needed)
adb install -r app/build/outputs/apk/debug/app-debug.apk
# release: app/build/outputs/apk/release/app-release-unsigned.apk  (sign manually if wanted)
```
Gradle `preBuild` auto-calls `cargo ndk` if `ANDROID_NDK_HOME` is set; otherwise warns and builds APK without `.so` (Rust calls gracefully fail with toast).

### Scoped Storage (API 29+)
Pick via `PickVisualMedia` returns `content://` → `UriResolver.kt:1` copies to `cacheDir` for `MediaExtractor` + Rust path.

### Verify on Device
```bash
adb logcat -s RustBridge VideoEngine
# pick video -> trim handles -> Export 720p -> check /storage/.../Android/data/com.videoeditor/files/VideoEditor/export_*.mp4
adb shell ls /sdcard/Android/data/com.videoeditor*/files/VideoEditor/
```

## How MediaCodec is Used
- `Decoder.kt:1` `MediaExtractor` + `MediaCodec` decoder (ByteBuffer mode)
- `YUVConverter.kt:1` Image `YUV_420_888` → I420 planar direct buffers
- `RustBridge.kt:1` `processFrameYUV420` mutates Y/U/V (filters), `scaleFrameYUV420` for resolution change
- `Encoder.kt:1` `MediaCodec` AVC encoder `COLOR_FormatYUV420Flexible` + `MuxerWrapper.kt:1` `MediaMuxer`

Speed via `presentationTimeUs / speed`; reverse caches frames then reverses queue; concat sequential; crop via `RustBridge.getCroppedDimensions` → new alloc.

## Next Steps
- Add async `MediaCodec.Callback` + `Surface` + OpenGL shader path for 4K 60fps
- Bundle stickers in `assets/stickers/` and fonts for `compositor.rs`
- Add instrumentation test: generate 2-sec synthetic mp4, run full Export, assert output duration/bitrate
