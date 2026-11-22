#!/system/bin/sh
# Exact-device diagnostic: temporarily remove the MIUI external-display vote.
# The detached watchdog restores the original setting even if ADB disconnects.
set -eu
ROOT=/data/local/tmp/extbridge-internal-policy-trial
STATE=$ROOT/active
SELF=$ROOT/trial.sh
KEY=external_display_connected
FINGERPRINT='Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys'

check() {
    [ "$(id -u)" = 0 ] || { echo 'Root required' >&2; exit 1; }
    [ "$(getprop ro.build.fingerprint)" = "$FINGERPRINT" ] || exit 1
    [ -x /data/local/tmp/extbridge-drm-read-current ] || exit 1
}

restore() {
    [ -d "$STATE" ] || return 0
    [ "$(cat "$STATE/token")" = "$1" ] || return 0
    saved=$(cat "$STATE/value")
    case "$saved" in
        null) settings delete system "$KEY" ;;
        0|1) settings put system "$KEY" "$saved" ;;
        *) echo 'Invalid saved setting; refusing restore' >&2; return 1 ;;
    esac
    [ "$(settings get system "$KEY")" = "$saved" ] || return 1
    date
    echo "RESTORED $KEY=$saved"
    /data/local/tmp/extbridge-drm-read-current
    rm "$STATE/token" "$STATE/value"
    rmdir "$STATE"
}

check
mkdir -p "$ROOT"
case "${1:-status}" in
    check) echo 'CHECK_OK: exact firmware and root' ;;
    start)
        current=$(settings get system "$KEY")
        [ "$current" = 1 ] || { echo "Expected connected=1, got $current" >&2; exit 1; }
        mkdir "$STATE"
        token=$(cat /proc/sys/kernel/random/uuid)
        printf '%s\n' "$current" > "$STATE/value"
        printf '%s\n' "$token" > "$STATE/token"
        nohup sh "$SELF" watchdog "$token" </dev/null >> "$ROOT/trial.log" 2>&1 &
        settings put system "$KEY" 0
        date
        echo "TRIAL_STARTED: 45 seconds; watchdog=$!"
        ;;
    watchdog) sleep 45; restore "$2" ;;
    restore) [ ! -d "$STATE" ] || restore "$(cat "$STATE/token")" ;;
    status)
        settings get system "$KEY"
        /data/local/tmp/extbridge-drm-read-current
        ;;
    *) exit 2 ;;
esac
