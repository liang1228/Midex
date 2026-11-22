#!/usr/bin/env bash
# Compose/Miuix build; requires JDK 21 and the Android SDK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
if [ ! -f .extbridge-debug.keystore ]; then
    "${JAVA_BIN}keytool" -genkeypair -keystore .extbridge-debug.keystore -storetype PKCS12 \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android -dname "CN=ExtBridge Debug,O=ExtBridge,C=US"
fi
# On ARM64, supply an executable aapt2 wrapper via -Pandroid.aapt2FromMavenOverride=/path/to/aapt2.
bash ./gradlew :helper:exportDebug "$@"
printf 'APK: %s/build/out/exthelper.apk\nDaemon: %s/build/out/extbridge.jar\n' "$ROOT" "$ROOT"
