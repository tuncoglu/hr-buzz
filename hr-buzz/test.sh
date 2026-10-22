#!/usr/bin/env bash
# Runs the plain-JVM tests for the decode, alarm, session maths and export logic.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build/test-classes
mkdir -p build/test-classes

javac -d build/test-classes \
    src/com/emre/hrbuzz/HrLogic.java \
    src/com/emre/hrbuzz/Training.java \
    src/com/emre/hrbuzz/Export.java \
    src/com/emre/hrbuzz/Json.java \
    src/com/emre/hrbuzz/CoachLogic.java \
    src/com/emre/hrbuzz/DeepSeek.java \
    src/com/emre/hrbuzz/Import.java \
    src/com/emre/hrbuzz/Move.java \
    test/com/emre/hrbuzz/HrLogicTest.java \
    test/com/emre/hrbuzz/TrainingTest.java \
    test/com/emre/hrbuzz/CoachTest.java \
    test/com/emre/hrbuzz/ImportTest.java \
    test/com/emre/hrbuzz/MoveTest.java

java -cp build/test-classes com.emre.hrbuzz.HrLogicTest
java -cp build/test-classes com.emre.hrbuzz.TrainingTest
java -cp build/test-classes com.emre.hrbuzz.CoachTest
java -cp build/test-classes com.emre.hrbuzz.ImportTest
java -cp build/test-classes com.emre.hrbuzz.MoveTest
