#!/usr/bin/env bash
# Builds hr-buzz.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/android-sdk}"
PLATFORM="$SDK/platforms/android-36/android.jar"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

MIN_SDK=31
TARGET_SDK=34
OUT="build"
APK="hr-buzz.apk"

command -v javac >/dev/null || { echo "javac not found (set JAVA_HOME)"; exit 1; }
[ -f "$PLATFORM" ] || { echo "android.jar missing: $PLATFORM"; exit 1; }

rm -rf "$OUT" gen
mkdir -p "$OUT" gen

echo "==> compiling resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "==> linking resources"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --java gen \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code 3 \
    --version-name 2.1 \
    "$OUT/res.zip"

echo "==> compiling java"
find src gen -name '*.java' > "$OUT/sources.txt"
javac --release 11 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

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
