#!/usr/bin/env bash
set -euo pipefail

# Build script for release APK. The JDK path is the JBR bundled with a
# specific Android Studio install; override via JAVA_HOME= in the env if
# you use a different location.
JAVA_HOME="${JAVA_HOME:-/home/dima/Downloads/android-studio-panda4-patch1-linux/android-studio/jbr}"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "ERROR: JAVA_HOME=$JAVA_HOME does not contain bin/java" >&2
    echo "       Override by running: JAVA_HOME=/path/to/jbr $0" >&2
    exit 1
fi

export JAVA_HOME
./gradlew assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
    echo "ERROR: build succeeded but $APK is missing" >&2
    exit 1
fi
shasum -a 256 "$APK" > "$APK.sha256"
echo "built: $APK"
echo "sha256: $(cat "$APK.sha256")"

# R8 mapping for crash deobfuscation. Produced automatically by AGP whenever
# isMinifyEnabled=true in the release buildType. CI should archive this
# alongside the APK — without it, stack traces from production crashes
# (Play Console, user bug reports) are unreadable.
MAPPING="app/build/outputs/mapping/release/mapping.txt"
if [[ -f "$MAPPING" ]]; then
    echo "r8 mapping: $MAPPING ($(wc -l < "$MAPPING") lines)"
else
    echo "WARN: $MAPPING not produced — is isMinifyEnabled=true for release?" >&2
fi
