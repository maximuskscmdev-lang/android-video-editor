#!/usr/bin/env bash
set -e
# Setup for Android Video Editor - arm64-v8a, API 29+, MediaCodec
# Requires: JDK17, Android SDK, NDK r26, Rust stable

echo "[1/4] rustup"
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
source "$HOME/.cargo/env"
rustup target add aarch64-linux-android
cargo install cargo-ndk

echo "[2/4] Android SDK/NDK"
# Install cmdline-tools if missing
# sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125"

echo "[3/4] Check NDK"
if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$ANDROID_NDK_ROOT" ]; then
  if [ -d "$ANDROID_HOME/ndk/26.1.10909125" ]; then
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
  else
    echo "Set ANDROID_NDK_HOME to your NDK r26 path"
    exit 1
  fi
fi
echo "NDK: $ANDROID_NDK_HOME"

echo "[4/4] Build Rust core (arm64-v8a)"
cd "$(dirname "$0")/.."
cargo ndk -t arm64-v8a -o android-app/app/src/main/jniLibs build --release

echo "Done. Now: cd android-app && ./gradlew assembleDebug"
echo "APK: android-app/app/build/outputs/apk/debug/app-debug.apk"
echo "Install: adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk"
