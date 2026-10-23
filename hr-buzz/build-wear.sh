#!/usr/bin/env bash
# Builds hr-buzz-wear.apk (the Wear OS watch app) without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/android-sdk}"
PLATFORM="$SDK/platforms/android-36/android.jar"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

# DEBUG_BUILD=1 makes a debuggable build for adb run-as; never commit or ship that one.
MIN_SDK=34
TARGET_SDK=34
OUT="build-wear"
APK="hr-buzz-wear.apk"

command -v javac >/dev/null || { echo "javac not found (set JAVA_HOME)"; exit 1; }
[ -f "$PLATFORM" ] || { echo "android.jar missing: $PLATFORM"; exit 1; }

GEN="$OUT/gen"
rm -rf "$OUT"
mkdir -p "$OUT" "$GEN"

echo "==> compiling resources"
"$BT/aapt2" compile --dir wear/res -o "$OUT/res.zip"

echo "==> linking resources"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest wear/AndroidManifest.xml \
    --java "$GEN" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    ${DEBUG_BUILD:+--debug-mode} \
    --version-code 1 \
    --version-name 1.0 \
    "$OUT/res.zip"

echo "==> compiling java"
# Only the watch sources are listed; javac pulls the shared phone classes it needs
# (Training, SessionStore, Coach...) from src/ by itself. wear/src/CoachJob.java stands in for
# the phone's CoachJob of the same name: files listed here win over the ones found in src/.
find wear/src "$GEN" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -sourcepath src -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> dexing"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
mkdir -p "$OUT/dex"
"$BT/d8" --release --lib "$PLATFORM" --min-api "$MIN_SDK" \
    --output "$OUT/dex" "$OUT/classes.jar"

echo "==> packaging"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Keep the signing key outside build/ so rebuilds stay upgradeable.
KEYSTORE="$HOME/.cache/polar-hr-toolchain/hrbuzz-debug.keystore"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
    echo "==> creating debug keystore at $KEYSTORE"
    keytool -genkeypair -keystore "$KEYSTORE" -alias debug \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=HR Buzz Debug, O=Local, C=GB" >/dev/null 2>&1
fi

echo "==> signing"
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v4-signing-enabled false \
    --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$APK" | head -4
echo
echo "==> built $(pwd)/$APK"
ls -lh "$APK"
