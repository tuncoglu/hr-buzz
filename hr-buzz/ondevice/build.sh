#!/usr/bin/env bash
# Builds hr-buzz-ondevice.apk: HR Buzz with the on-device coach (Gemma 4 through AICore), for a
# phone enrolled in the AICore developer preview. Debug build, signed with the local debug key
# so it installs over `DEBUG_BUILD=1 ./build.sh` and keeps its data. The Play build is still
# ../build.sh, which has no engine and hides the "use this phone" option.
#   ./build.sh                 build ../hr-buzz-ondevice.apk
#   ./build.sh install         ...and install it with adb
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
open("app/src/main/AndroidManifest.xml", "w").write(y)
PY

"$GRADLE" --no-daemon -q :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../hr-buzz-ondevice.apk
ls -lh ../hr-buzz-ondevice.apk
[ "${1:-}" = install ] && adb install -r ../hr-buzz-ondevice.apk
exit 0
