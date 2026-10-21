#!/usr/bin/env bash
# Runs the plain-JVM tests for the decode + alarm logic.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build/test-classes
mkdir -p build/test-classes

javac -d build/test-classes \
    src/com/emre/hrbuzz/HrLogic.java \
    test/com/emre/hrbuzz/HrLogicTest.java

java -cp build/test-classes com.emre.hrbuzz.HrLogicTest
