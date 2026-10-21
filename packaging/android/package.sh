#!/usr/bin/env bash
# Build, package and sign EffectCraft for Android (arm64-v8a APK) on Linux / macOS.
#
# Produces, in ${DIST:-dist/release}:
#   effectcraft-<version>-android-arm64.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${EFFECTCRAFT_VERSION:-$(awk '/^\[workspace\.package\]/{f=1;next} /^\[/{f=0} f && /^version[[:space:]]*=/{gsub(/.*"|"[^"]*$/,"");print;exit}' "$ROOT/Cargo.toml")}"
TARGET="aarch64-linux-android"
DIST="${DIST:-$ROOT/dist/release}"
TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/target}"
mkdir -p "$DIST"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK/ndk/"* 2>/dev/null | sort -V | tail -n 1)}"
BUILD_TOOLS="$(ls -d "$SDK/build-tools/"* 2>/dev/null | sort -V | tail -n 1)"
PLATFORM_DIR="$(ls -d "$SDK/platforms/android-"* 2>/dev/null | sort -V | tail -n 1)"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

HOST_TAG="linux-x86_64"
if [[ "$(uname -s)" == "Darwin" ]]; then
  HOST_TAG="darwin-x86_64"
fi
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"
export CC_aarch64_linux_android="$LLVM_BIN/aarch64-linux-android26-clang"
export CXX_aarch64_linux_android="$LLVM_BIN/aarch64-linux-android26-clang++"
export AR_aarch64_linux_android="$LLVM_BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LLVM_BIN/aarch64-linux-android26-clang"
export CARGO_TARGET_DIR="$TARGET_DIR"

APK_MANIFEST="$ROOT/packaging/android/apk/Cargo.toml"
APK_LOCK="$ROOT/packaging/android/apk/Cargo.lock"
cp "$ROOT/Cargo.lock" "$APK_LOCK"
trap 'rm -f "$APK_LOCK"' EXIT

cargo build --manifest-path "$APK_MANIFEST" --target "$TARGET" --release

SO_PATH="$TARGET_DIR/$TARGET/release/libeffectcraft.so"
STAGE="$TARGET_DIR/android-package"
rm -rf "$STAGE"
mkdir -p "$STAGE/res/mipmap-mdpi" "$STAGE/res/mipmap-xhdpi" "$STAGE/res/mipmap-xxhdpi" "$STAGE/res/mipmap-xxxhdpi" "$STAGE/gen" "$STAGE/classes" "$STAGE/dex" "$STAGE/apk/lib/arm64-v8a"

cp "$ROOT/assets/app-icon/hicolor/48x48/apps/ai.storyteller.effectcraft.png" "$STAGE/res/mipmap-mdpi/ic_launcher.png"
cp "$ROOT/assets/app-icon/hicolor/128x128/apps/ai.storyteller.effectcraft.png" "$STAGE/res/mipmap-xhdpi/ic_launcher.png"
cp "$ROOT/assets/app-icon/hicolor/256x256/apps/ai.storyteller.effectcraft.png" "$STAGE/res/mipmap-xxhdpi/ic_launcher.png"
cp "$ROOT/assets/app-icon/hicolor/512x512/apps/ai.storyteller.effectcraft.png" "$STAGE/res/mipmap-xxxhdpi/ic_launcher.png"

"$BUILD_TOOLS/aapt2" compile --dir "$STAGE/res" -o "$STAGE/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$STAGE/base.apk" -I "$ANDROID_JAR" \
  --manifest "$ROOT/packaging/android/AndroidManifest.xml" \
  --java "$STAGE/gen" --version-name "$VERSION" --auto-add-overlay "$STAGE/res.zip"

javac --release 11 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$STAGE/classes" \
  "$ROOT/packaging/android/java/ai/storyteller/effectcraft/EffectCraftActivity.java" \
  "$STAGE/gen/ai/storyteller/effectcraft/R.java"

"$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$STAGE/dex" \
  "$STAGE/classes/ai/storyteller/effectcraft/"*.class

cp "$STAGE/base.apk" "$STAGE/unaligned.apk"
cp "$STAGE/dex/classes.dex" "$STAGE/apk/classes.dex"
cp "$SO_PATH" "$STAGE/apk/lib/arm64-v8a/libeffectcraft.so"
LIBCPP="$LLVM_BIN/../sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [[ -f "$LIBCPP" ]]; then
  cp "$LIBCPP" "$STAGE/apk/lib/arm64-v8a/libc++_shared.so"
fi
(cd "$STAGE/apk" && zip -q -r "$STAGE/unaligned.apk" classes.dex lib/arm64-v8a)

"$BUILD_TOOLS/zipalign" -f -p 4 "$STAGE/unaligned.apk" "$STAGE/aligned.apk"

KEYSTORE="${ANDROID_KEYSTORE:-$STAGE/debug.keystore}"
KEY_ALIAS="${ANDROID_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${ANDROID_KEYSTORE_PASS:-android}"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass "$KEY_PASS" -alias "$KEY_ALIAS" \
    -keypass "$KEY_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=EffectCraft Android,O=Storyteller,C=US"
fi

FINAL_APK="$DIST/effectcraft-$VERSION-android-arm64.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" --out "$FINAL_APK" "$STAGE/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$FINAL_APK"
echo "==> Built and signed APK: $FINAL_APK"
