# Sourced by the release builds: loads the upload key settings made by ./make-upload-key.sh.
# The key lives outside the repo (and outside ~/.cache, which is safe to wipe).
SIGNING_DIR="${HRBUZZ_SIGNING_DIR:-$HOME/.config/hrbuzz-signing}"
if [ ! -f "$SIGNING_DIR/upload.properties" ]; then
    echo "No upload key at $SIGNING_DIR. Run ./make-upload-key.sh once (or use DEBUG_BUILD=1)." >&2
    exit 1
fi
UPLOAD_KEYSTORE="$SIGNING_DIR/hrbuzz-upload.jks"
UPLOAD_ALIAS="$(sed -n 's/^alias=//p' "$SIGNING_DIR/upload.properties")"
UPLOAD_PASS="$(sed -n 's/^password=//p' "$SIGNING_DIR/upload.properties")"
