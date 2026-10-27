#!/usr/bin/env bash
# Creates the Play upload key once. Back up $SIGNING_DIR somewhere off this machine: if it is
# lost, Play support has to reset the upload key (the app signing key itself stays with Google).
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"
SIGNING_DIR="${HRBUZZ_SIGNING_DIR:-$HOME/.config/hrbuzz-signing}"

if [ -f "$SIGNING_DIR/hrbuzz-upload.jks" ]; then
    echo "Upload key already exists at $SIGNING_DIR; not touching it."
    exit 0
fi
mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR"
PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n' | head -c 24)"
keytool -genkeypair -keystore "$SIGNING_DIR/hrbuzz-upload.jks" -alias upload \
    -keyalg RSA -keysize 4096 -validity 9125 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=HR Buzz Upload, O=ENFTC, C=GB" >/dev/null 2>&1
printf 'alias=upload\npassword=%s\n' "$PASS" > "$SIGNING_DIR/upload.properties"
chmod 600 "$SIGNING_DIR/upload.properties" "$SIGNING_DIR/hrbuzz-upload.jks"
echo "Upload key created in $SIGNING_DIR (hrbuzz-upload.jks + upload.properties). Back it up."
