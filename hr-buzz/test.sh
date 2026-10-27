#!/usr/bin/env bash
# Runs the plain-JVM tests for the decode, alarm, session maths and export logic.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/.cache/polar-hr-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build/test-classes
mkdir -p build/test-classes

javac -d build/test-classes \
    src/com/enftc/hrbuzz/HrLogic.java \
    src/com/enftc/hrbuzz/Training.java \
    src/com/enftc/hrbuzz/Export.java \
    src/com/enftc/hrbuzz/Json.java \
    src/com/enftc/hrbuzz/CoachLogic.java \
    src/com/enftc/hrbuzz/Provider.java \
    src/com/enftc/hrbuzz/Llm.java \
    src/com/enftc/hrbuzz/Reports.java \
    src/com/enftc/hrbuzz/Import.java \
    src/com/enftc/hrbuzz/Move.java \
    wear/src/com/enftc/hrbuzz/WearLogic.java \
    test/com/enftc/hrbuzz/HrLogicTest.java \
    test/com/enftc/hrbuzz/TrainingTest.java \
    test/com/enftc/hrbuzz/CoachTest.java \
    test/com/enftc/hrbuzz/LlmTest.java \
    test/com/enftc/hrbuzz/ReportsTest.java \
    test/com/enftc/hrbuzz/ImportTest.java \
    test/com/enftc/hrbuzz/MoveTest.java \
    test/com/enftc/hrbuzz/WearLogicTest.java

java -cp build/test-classes com.enftc.hrbuzz.HrLogicTest
java -cp build/test-classes com.enftc.hrbuzz.TrainingTest
java -cp build/test-classes com.enftc.hrbuzz.CoachTest
java -cp build/test-classes com.enftc.hrbuzz.LlmTest
java -cp build/test-classes com.enftc.hrbuzz.ReportsTest
java -cp build/test-classes com.enftc.hrbuzz.ImportTest
java -cp build/test-classes com.enftc.hrbuzz.MoveTest
java -cp build/test-classes com.enftc.hrbuzz.WearLogicTest
