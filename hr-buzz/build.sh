#!/usr/bin/env bash
# Builds hr-buzz.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#   ./build.sh              release build (never debuggable), signed with the upload key
#                           (run ./make-upload-key.sh once to create it)
#   DEBUG_BUILD=1 ./build.sh  debuggable build for adb run-as, signed with the local debug key;
#                           never commit or ship that one
#   VERSION_CODE=5 VERSION_NAME=2.3 ./build.sh   Play needs a higher code for every upload
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/android-sdk}"
PLATFORM="$SDK/platforms/android-37.0/android.jar"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

MIN_SDK=31
TARGET_SDK=37
VERSION_CODE="${VERSION_CODE:-4}"
VERSION_NAME="${VERSION_NAME:-2.2}"
OUT="build"
if [ -n "${DEBUG_BUILD:-}" ]; then
    APK="hr-buzz.apk"
else
    APK="hr-buzz-release.apk"
fi

command -v javac >/dev/null || { echo "javac not found (set JAVA_HOME)"; exit 1; }
[ -f "$PLATFORM" ] || { echo "android.jar missing: $PLATFORM"; exit 1; }

rm -rf "$OUT" gen
mkdir -p "$OUT" gen

echo "==> compiling resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

# A release build links a copy of the manifest with any android:debuggable line removed, so a
# local debugging edit of AndroidManifest.xml can't leak into something uploaded.
MANIFEST=AndroidManifest.xml
if [ -z "${DEBUG_BUILD:-}" ]; then
    MANIFEST="$OUT/AndroidManifest.xml"
    grep -v 'android:debuggable' AndroidManifest.xml > "$MANIFEST"
fi

echo "==> linking resources"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest "$MANIFEST" \
    --java gen \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    ${DEBUG_BUILD:+--debug-mode} \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
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

if [ -n "${DEBUG_BUILD:-}" ]; then
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
    KS_ARGS=(--ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android)
else
    # shellcheck source=/dev/null
    source "$(dirname "$0")/signing.sh"
    KS_ARGS=(--ks "$UPLOAD_KEYSTORE" --ks-key-alias "$UPLOAD_ALIAS"
             --ks-pass "pass:$UPLOAD_PASS" --key-pass "pass:$UPLOAD_PASS")
fi

echo "==> signing"
"$BT/apksigner" sign \
    "${KS_ARGS[@]}" \
    --v1-signing-enabled true --v2-signing-enabled true --v4-signing-enabled false \
    --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$APK" | head -4
echo
echo "==> built $(pwd)/$APK"
ls -lh "$APK"

# Play rejects debuggable uploads; make sure a release build really isn't one.
if [ -z "${DEBUG_BUILD:-}" ] && "$BT/aapt2" dump badging "$APK" | grep -q 'application-debuggable'; then
    echo "release build is debuggable; refusing"; rm -f "$APK"; exit 1
fi

# Release builds also make the Android App Bundle that Play takes.
if [ -z "${DEBUG_BUILD:-}" ]; then
    # shellcheck source=/dev/null
    source "$(dirname "$0")/bundle.sh"
    make_bundle "$MANIFEST" "$OUT/res.zip" "$OUT/dex/classes.dex" "$OUT" hr-buzz-release.aab
fi
