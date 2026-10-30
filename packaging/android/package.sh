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
mkdir -p "$STAGE/res/values" "$STAGE/res/mipmap-anydpi-v26" "$STAGE/gen" "$STAGE/classes" "$STAGE/dex" "$STAGE/apk/lib/arm64-v8a" "$STAGE/icongen"

cat > "$STAGE/icongen/IconGen.java" << 'EOF'
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public final class IconGen {
    private static int clamp(float v) {
        if (v <= 0f) return 0;
        if (v >= 255f) return 255;
        return (int)(v + 0.5f);
    }

    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        File resDir = new File(args[1]);
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage fg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage mono = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (a == 0) continue;
                float m = Math.max(0f, ((r - g) - 8f) / 162f);
                if (m > 1f) m = 1f;
                float fgAlpha = (a / 255f) * (1f - m);
                if (fgAlpha <= 0.01f) continue;
                float inv = 1f - m;
                int outR = clamp((r - 224f * m) / inv);
                int outG = clamp((g - 54f * m) / inv);
                int outB = clamp((b - 143f * m) / inv);
                int outA = clamp(fgAlpha * 255f);
                fg.setRGB(x, y, (outA << 24) | (outR << 16) | (outG << 8) | outB);
                float paper = Math.max(0f, Math.min(1f, (outG - 15f) / 215f));
                int monoA = clamp(fgAlpha * (0.15f + 0.85f * paper) * 255f);
                mono.setRGB(x, y, (monoA << 24) | 0x00FFFFFF);
            }
        }

        String[] densities = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        int[] adaptiveSizes = {108, 162, 216, 324, 432};
        int[] legacySizes = {48, 72, 96, 144, 192};
        Color bgPink = new Color(224, 54, 143, 255);

        for (int i = 0; i < densities.length; i++) {
            File dir = new File(resDir, densities[i]);
            dir.mkdirs();
            int aSize = adaptiveSizes[i], lSize = legacySizes[i];
            float sDp = 54.0f;
            int drawS = Math.round(aSize * (sDp / 108.0f));
            int drawX = Math.round(aSize * ((54.0f - 0.525f * sDp) / 108.0f));
            int drawY = Math.round(aSize * ((54.0f - 0.485f * sDp) / 108.0f));

            BufferedImage outFg = new BufferedImage(aSize, aSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g1 = createGraphics(outFg);
            g1.drawImage(fg, drawX, drawY, drawS, drawS, null);
            g1.dispose();
            ImageIO.write(outFg, "PNG", new File(dir, "ic_launcher_foreground.png"));

            BufferedImage outMono = new BufferedImage(aSize, aSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = createGraphics(outMono);
            g2.drawImage(mono, drawX, drawY, drawS, drawS, null);
            g2.dispose();
            ImageIO.write(outMono, "PNG", new File(dir, "ic_launcher_monochrome.png"));

            BufferedImage outRound = new BufferedImage(lSize, lSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g3 = createGraphics(outRound);
            float padR = lSize * (2.0f / 48.0f);
            float dR = lSize - padR * 2.0f;
            Ellipse2D circle = new Ellipse2D.Float(padR, padR, dR, dR);
            g3.setColor(bgPink);
            g3.fill(circle);
            g3.setClip(circle);
            int rS = Math.round(dR * (54.0f / 68.0f));
            int rX = Math.round((lSize * 0.5f) - 0.525f * rS);
            int rY = Math.round((lSize * 0.5f) - 0.485f * rS);
            g3.drawImage(fg, rX, rY, rS, rS, null);
            g3.dispose();
            ImageIO.write(outRound, "PNG", new File(dir, "ic_launcher_round.png"));

            BufferedImage outSq = new BufferedImage(lSize, lSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g4 = createGraphics(outSq);
            float padS = lSize * (3.0f / 48.0f);
            float dS = lSize - padS * 2.0f;
            float arc = dS * 0.44f;
            RoundRectangle2D sq = new RoundRectangle2D.Float(padS, padS, dS, dS, arc, arc);
            g4.setColor(bgPink);
            g4.fill(sq);
            g4.setClip(sq);
            int sqS = Math.round(dS * 0.88f);
            int sqX = Math.round((lSize * 0.5f) - 0.515f * sqS);
            int sqY = Math.round((lSize * 0.5f) - 0.495f * sqS);
            g4.drawImage(fg, sqX, sqY, sqS, sqS, null);
            g4.dispose();
            ImageIO.write(outSq, "PNG", new File(dir, "ic_launcher.png"));
        }
    }

    private static Graphics2D createGraphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }
}
EOF
javac --release 11 -encoding UTF-8 -d "$STAGE/icongen" "$STAGE/icongen/IconGen.java"
java -cp "$STAGE/icongen" IconGen "$ROOT/assets/app-icon/effectcraft-1024.png" "$STAGE/res"

cat > "$STAGE/res/values/colors.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#E0368F</color>
</resources>
EOF

cat > "$STAGE/res/mipmap-anydpi-v26/ic_launcher.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
EOF
cp "$STAGE/res/mipmap-anydpi-v26/ic_launcher.xml" "$STAGE/res/mipmap-anydpi-v26/ic_launcher_round.xml"

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
