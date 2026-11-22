#!/system/bin/sh
# Exact-firmware LSPosed switch. A trial rolls back unless physical scanout is verified.
set -eu
ROOT=/data/local/tmp/extbridge-refresh-unlock
STATE=$ROOT/active
SELF=$ROOT/control.sh
KEY=extbridge_unlock_builtin_refresh
PROBE=/data/local/tmp/extbridge-drm-read-current

fail() { echo "ERROR: $*" >&2; exit 1; }
check() {
    [ "$(id -u)" = 0 ] || fail 'Root required'
    [ "$(getprop ro.build.fingerprint)" = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys' ] || fail 'Unsupported firmware'
    [ -x "$PROBE" ] || fail 'Read-only DRM probe required'
}
restore_rate() {
    rate_saved=$(cat "$STATE/$2")
    case "$rate_saved" in
        null) settings delete "$1" "$2" ;;
        ''|*[!0-9.]*) fail 'Invalid saved refresh rate' ;;
        *) settings put "$1" "$2" "$rate_saved" ;;
    esac
    [ "$(settings get "$1" "$2")" = "$rate_saved" ] || fail 'Refresh-rate restore failed'
}
restore() {
    [ -d "$STATE" ] || return 0
    [ "$(cat "$STATE/token" 2>/dev/null || true)" = "$1" ] || return 0
    saved=$(cat "$STATE/value")
    case "$saved" in
        null) settings delete global "$KEY" ;;
        0) settings put global "$KEY" 0 ;;
        *) fail 'Invalid saved setting' ;;
    esac
    [ "$(settings get global "$KEY")" = "$saved" ] || fail 'Restore failed'
    restore_rate system peak_refresh_rate
    restore_rate secure miui_refresh_rate
    restore_rate secure user_refresh_rate
    rm -f "$STATE/keep" "$STATE/token" "$STATE/value" "$STATE/max" \
        "$STATE/peak_refresh_rate" "$STATE/miui_refresh_rate" "$STATE/user_refresh_rate"
    rmdir "$STATE"
    date
    echo "RESTORED $KEY=$saved"
}

check
mkdir -p "$ROOT"
chmod 0700 "$ROOT"
case "${1:-status}" in
    check) echo 'CHECK_OK: exact firmware and read-only DRM probe' ;;
    start)
        saved=$(settings get global "$KEY")
        case "$saved" in null|0) ;; *) fail 'Switch is already enabled or unexpected' ;; esac
        [ "$(settings get system external_display_connected)" = 1 ] || fail 'External display must be connected'
        mkdir "$STATE" || fail 'A trial is already active'
        token=$(cat /proc/sys/kernel/random/uuid)
        printf '%s\n' "$saved" > "$STATE/value"
        printf '%s\n' "$token" > "$STATE/token"
        settings get system peak_refresh_rate > "$STATE/peak_refresh_rate"
        settings get secure miui_refresh_rate > "$STATE/miui_refresh_rate"
        settings get secure user_refresh_rate > "$STATE/user_refresh_rate"
        nohup sh "$SELF" watchdog "$token" </dev/null >> "$ROOT/trial.log" 2>&1 &
        if [ "${2:-}" = max ]; then
            : > "$STATE/max"
            settings put system peak_refresh_rate 144
            settings put secure user_refresh_rate 144
            settings put secure miui_refresh_rate 144
        fi
        settings put global "$KEY" 1
        date
        echo "TRIAL_STARTED: 45 seconds; watchdog=$!"
        ;;
    keep)
        [ -d "$STATE" ] || fail 'No active trial'
        [ "$(settings get global "$KEY")" = 1 ] || fail 'Switch is not enabled'
        [ "$(settings get system external_display_connected)" = 1 ] || fail 'OEM connection flag changed'
        "$PROBE" | awk '/CRTC id=63 / && /3200x2136@/ {for (i=1;i<=NF;i++) if ($i ~ /3200x2136@/) {split($i,m,"@"); if (m[2]>=119.9 && m[2]<=144.1) ok=1}} END {exit !ok}' || fail 'Internal physical 120/144 Hz must be verified first'
        if [ -f "$STATE/max" ]; then
            "$PROBE" | awk '/CRTC id=63 / && /3200x2136@/ {for (i=1;i<=NF;i++) if ($i ~ /3200x2136@/) {split($i,m,"@"); if (m[2]>=143.9 && m[2]<=144.1) ok=1}} END {exit !ok}' || fail 'Internal physical 144 Hz must be verified first'
        fi
        : > "$STATE/keep"
        echo 'KEPT: LSPosed switch enabled; restore with control.sh restore'
        ;;
    watchdog)
        sleep 45
        if [ ! -f "$STATE/keep" ]; then restore "${2:-missing}"; fi
        ;;
    restore) [ ! -d "$STATE" ] || restore "$(cat "$STATE/token")" ;;
    off)
        if [ -d "$STATE" ]; then restore "$(cat "$STATE/token")"; fi
        settings put global "$KEY" 0
        echo 'DISABLED'
        ;;
    status)
        echo "switch=$(settings get global "$KEY")"
        echo "external_display_connected=$(settings get system external_display_connected)"
        "$PROBE"
        ;;
    *) fail 'Use check, start [max], keep, restore, off or status' ;;
esac
