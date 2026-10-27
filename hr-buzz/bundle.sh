# Sourced by the release builds. make_bundle <manifest> <res.zip> <classes.dex> <out dir> <out.aab>
# links the resources in protobuf form, lays them out as a base module, builds the Android App
# Bundle with bundletool and signs it with the upload key (signing.sh must be sourced first).
# bundletool: a standalone jar (github.com/google/bundletool/releases, bundletool-all-*.jar) at
# ~/.cache/polar-hr-toolchain/bundletool.jar, or a launcher script named bundletool beside it that
# runs the jars Gradle already downloaded (any Android Gradle build leaves them in ~/.gradle).
make_bundle() {
    local manifest="$1" reszip="$2" dex="$3" out="$4" aab="$5"
    # A bundletool-all jar, or the launcher that runs the jars Gradle downloaded (see below).
    local tool="${BUNDLETOOL:-}"
    if [ -z "$tool" ]; then
        for t in "$HOME/.cache/polar-hr-toolchain/bundletool.jar" "$HOME/.cache/polar-hr-toolchain/bundletool"; do
            [ -f "$t" ] && { tool="$t"; break; }
        done
    fi
    if [ -z "$tool" ] || [ ! -f "$tool" ]; then
        echo "bundletool not found (set BUNDLETOOL to a bundletool-all jar or a launcher); built the APK only." >&2
        return 0
    fi
    local run=("$tool")
    [[ "$tool" == *.jar ]] && run=(java -jar "$tool")
    echo "==> bundle"
    "$BT/aapt2" link --proto-format \
        -o "$out/base-proto.apk" \
        -I "$PLATFORM" \
        --manifest "$manifest" \
        --min-sdk-version "$MIN_SDK" \
        --target-sdk-version "$TARGET_SDK" \
        --version-code "$VERSION_CODE" \
        --version-name "$VERSION_NAME" \
        "$reszip"
    rm -rf "$out/module" "$out/base.zip" "$aab"
    mkdir -p "$out/module/manifest" "$out/module/dex"
    python3 - "$out/base-proto.apk" "$out/module" <<'PY'
import sys, zipfile
apk, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if n == "AndroidManifest.xml":
            z.extract(n, dest + "/manifest")
        else:
            z.extract(n, dest)
PY
    cp "$dex" "$out/module/dex/classes.dex"
    (cd "$out/module" && python3 -c "
import os, zipfile
with zipfile.ZipFile('../base.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk('.'):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, '.'))
")
    "${run[@]}" build-bundle --modules="$out/base.zip" --output="$aab"
    jarsigner -keystore "$UPLOAD_KEYSTORE" -storepass "$UPLOAD_PASS" -keypass "$UPLOAD_PASS" \
        -sigalg SHA256withRSA -digestalg SHA-256 "$aab" "$UPLOAD_ALIAS" >/dev/null
    # jarsigner exits 0 even for an unsigned file, so read its words, not its exit code.
    jarsigner -verify "$aab" | grep -q '^jar verified' || { echo "bundle is not signed"; rm -f "$aab"; return 1; }
    echo "bundle signed by the upload key"
    ls -lh "$aab"
}
