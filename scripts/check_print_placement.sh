#!/usr/bin/env bash
# The same checks as check_print_placement.ps1, for Linux and macOS.
# Generate the runtime classpath without launching Minecraft:
#   ./gradlew :1.20.1-forge:writePrintTestClasspath
# then run this script under Java 17. Set JAVA_BIN to a JDK's bin folder to pick one.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
classpath_file="${1:-$root/build/verification/print/runtime-classpath.txt}"
if [[ ! -f "$classpath_file" ]]; then
    echo "Generate the runtime classpath using the Gradle command documented at the top of this script." >&2
    exit 1
fi
javac="${JAVA_BIN:+$JAVA_BIN/}javac"
java="${JAVA_BIN:+$JAVA_BIN/}java"
out="$root/build/verification/print-tests"
mkdir -p "$out"
# The node's compiled classes and resources are already on the written classpath.
classpath="$(paste -sd: "$classpath_file")"

"$javac" --release 17 -proc:none -classpath "$classpath" -d "$out" \
    scripts/PrintPlacementTest.java scripts/CreativePrintTest.java scripts/PrintBudgetTest.java \
    scripts/PrintTestBootstrap.java scripts/ConfigPersistenceTest.java scripts/PrintPlanTest.java

cd "$out"
for test in dev.willtda.simpleschematics.printing.PrintPlacementTest \
        dev.willtda.simpleschematics.printing.CreativePrintTest PrintBudgetTest \
        dev.willtda.simpleschematics.printing.ConfigPersistenceTest \
        dev.willtda.simpleschematics.printing.PrintPlanTest; do
    "$java" -classpath "$out:$classpath" "$test"
done
