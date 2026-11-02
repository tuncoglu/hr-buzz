#!/usr/bin/env bash
# HR Buzz with the on-device coach (Gemma 4 through AICore). This is the build Play gets.
#   ./build.sh release         ../hr-buzz-ondevice-release.aab (+ .apk): never debuggable, signed
#                              with the upload key (run ../make-upload-key.sh once). Version
#                              numbers are ../build.sh's, or VERSION_CODE=6 VERSION_NAME=2.4
#   ./build.sh                 ../hr-buzz-ondevice.apk: debug, signed with the local debug key so
#                              it installs over `DEBUG_BUILD=1 ../build.sh` and keeps its data
#   ./build.sh install         ...and install it with adb
# ../build.sh alone still makes the small build with no engine (the option is hidden there).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE="${GRADLE:-$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.*-bin/*/gradle-9.*/bin/gradle 2>/dev/null | sort -V | tail -1)}"
[ -x "$GRADLE" ] || { echo "Gradle 9 not found (set GRADLE)"; exit 1; }

# AGP takes the package from `namespace`, so the shared manifest is copied without it.
python3 -I - <<'PY'
x = open("../AndroidManifest.xml").read()
y = x.replace('\n    package="com.enftc.hrbuzz">', '>', 1)
assert y != x, "manifest layout changed: fix the package line handling in ondevice/build.sh"
# ML Kit queues usage reports (package, locale, install id, sizes: no prompt text) for Google's
# logging endpoint through Google Data Transport, which uploads them from this job service or
# alarm receiver. Removing both leaves the uploader nothing to run from, so nothing is sent;
# the model itself never needed the network. Checked on a Pixel 10 Pro XL: no upload job is
# scheduled and the coach still answers.
tools = 'xmlns:tools="http://schemas.android.com/tools"'
z = y.replace('xmlns:android="http://schemas.android.com/apk/res/android"',
              'xmlns:android="http://schemas.android.com/apk/res/android"\n    ' + tools, 1)
assert z != y, "manifest root changed: fix the xmlns handling in ondevice/build.sh"
DT = "com.google.android.datatransport.runtime.scheduling.jobscheduling."
cut = ('    <service android:name="' + DT + 'JobInfoSchedulerService" tools:node="remove" />\n'
       '    <receiver android:name="' + DT + 'AlarmManagerSchedulerBroadcastReceiver" '
       'tools:node="remove" />\n')
w = z.replace("</application>", cut + "</application>", 1)
assert w != z, "no </application> in the manifest"
open("app/src/main/AndroidManifest.xml", "w").write(w)
PY

# One place for the version: whatever ../build.sh defaults to, unless set in the environment.
export VERSION_CODE="${VERSION_CODE:-$(sed -n 's/^VERSION_CODE="${VERSION_CODE:-\(.*\)}"/\1/p' ../build.sh)}"
export VERSION_NAME="${VERSION_NAME:-$(sed -n 's/^VERSION_NAME="${VERSION_NAME:-\(.*\)}"/\1/p' ../build.sh)}"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "could not read the version from ../build.sh"; exit 1; }

if [ "${1:-}" = release ]; then
    # shellcheck source=/dev/null
    source ../signing.sh
    BT="$(ls -d "${ANDROID_HOME}"/build-tools/*/ | sort -V | tail -1)"
    export PATH="$JAVA_HOME/bin:$PATH"
    "$GRADLE" --no-daemon -q :app:assembleRelease :app:bundleRelease
    APK=app/build/outputs/apk/release/app-release.apk
    AAB=app/build/outputs/bundle/release/app-release.aab
    [ -f "$APK" ] && [ -f "$AAB" ] || { echo "Gradle did not make the release files"; exit 1; }
    # Play rejects debuggable uploads, and Gradle would sign with the debug key if the upload
    # key were missing, so check both rather than trust the build script.
    if "$BT/aapt2" dump badging "$APK" | grep -q 'application-debuggable'; then
        echo "release build is debuggable; refusing"; exit 1
    fi
    # The Data Transport uploader must stay out of the manifest (see the comment above): if a
    # library update brings it back under another name, ML Kit's reports would be sent again.
    if "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" | grep -qi "datatransport"; then
        if "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" \
                | grep -i "datatransport" | grep -qiE "Scheduler"; then
            echo "the release manifest has a Data Transport scheduler again; refusing"; exit 1
        fi
    fi
    "$BT/apksigner" verify --print-certs "$APK" | grep -q "CN=HR Buzz Upload" \
        || { echo "release APK is not signed by the upload key; refusing"; exit 1; }
    jarsigner -verify "$AAB" | grep -q '^jar verified' \
        && keytool -printcert -jarfile "$AAB" | grep -q "Owner: CN=HR Buzz Upload" \
        || { echo "release bundle is not signed by the upload key; refusing"; exit 1; }
    cp "$APK" ../hr-buzz-ondevice-release.apk
    cp "$AAB" ../hr-buzz-ondevice-release.aab
    echo "==> built version $VERSION_NAME (code $VERSION_CODE), signed by the upload key"
    ls -lh ../hr-buzz-ondevice-release.apk ../hr-buzz-ondevice-release.aab
    exit 0
fi

"$GRADLE" --no-daemon -q :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../hr-buzz-ondevice.apk
ls -lh ../hr-buzz-ondevice.apk
[ "${1:-}" = install ] && adb install -r ../hr-buzz-ondevice.apk
exit 0
