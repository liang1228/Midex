#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
contracts=tools/xring-mode-unlock/lsposed/shared/com/extbridge/refreshunlock

"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 11 -encoding UTF-8 -d "$classes" \
    bridge/src/com/extbridge/daemon/ExternalModeVerification.java \
    bridge/tests/com/extbridge/daemon/ExternalModeVerificationTest.java \
    "$contracts"/{ModeOverrideGuard,RefreshUnlockContract,WindowDecorationContract}.java \
    tools/xring-mode-unlock/lsposed/test/com/extbridge/refreshunlock/ModeOverrideGuardTest.java \
    helper/src/com/extbridge/helper/RefreshCapabilities.java \
    tools/refresh-extension-tests/RefreshCapabilitiesTest.java

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$java_bin" -cp "$classes" com.extbridge.daemon.ExternalModeVerificationTest
"$java_bin" -cp "$classes" com.extbridge.refreshunlock.ModeOverrideGuardTest
"$java_bin" -cp "$classes" RefreshCapabilitiesTest
