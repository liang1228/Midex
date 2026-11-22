#!/system/bin/sh
# Runtime-only OEM capability flag. Does not change the physical external mode.
set -eu
ROOT=/data/local/tmp/extbridge-builtin-high-refresh
STATE=$ROOT/active
SELF=$ROOT/unlock.sh
KEY=vendor.display.support_builtin_high_fps_when_pluggable_connected
RESETPROP=/data/adb/ksu/bin/resetprop
STOCK=bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85

fail() { echo "ERROR: $*" >&2; exit 1; }
identity() {
    [ "$(id -u)" = 0 ] || fail 'Root required'
    [ "$(getprop ro.build.fingerprint)" = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys' ] || fail 'Unsupported firmware'
    [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ] || fail 'Init namespace required'
    [ -x "$RESETPROP" ] || fail 'KernelSU resetprop required for exact restoration'
    [ -x /data/local/tmp/extbridge-drm-read-current ] || fail 'DRM probe required'
}
check() {
    identity
    [ "$(sha256sum /vendor/lib64/libxdmcore.so | cut -d ' ' -f 1)" = "$STOCK" ] || fail 'Stock HAL required'
    [ "$(settings get system external_display_connected)" = 1 ] || fail 'External display must be connected'
    case "$(getprop "$KEY")" in ''|0) ;; *) fail 'Capability is already set' ;; esac
    echo 'CHECK_OK: exact firmware, root, stock HAL and external connection'
}
restore() {
    [ -d "$STATE" ] || return 0
    [ "$(cat "$STATE/token")" = "$1" ] || return 0
    saved=$(cat "$STATE/property")
    case "$saved" in
        absent) "$RESETPROP" -d "$KEY" ;;
        empty) setprop "$KEY" '' ;;
        0) setprop "$KEY" 0 ;;
        *) fail 'Invalid saved property' ;;
    esac
    [ "$(getprop "$KEY")" = "$(case "$saved" in 0) echo 0 ;; esac)" ] || fail 'Property restore failed'
    date
    echo 'RESTORED_CAPABILITY; reloading display/Android services'
    setprop ctl.restart vendor.hwcomposer-3
    rm -f "$STATE/keep" "$STATE/token" "$STATE/property"
    rmdir "$STATE"
}

identity
mkdir -p "$ROOT"
case "${1:-status}" in
    check) check ;;
    start)
        check
        mkdir "$STATE" || fail 'An unlock session is already active'
        chmod 0700 "$ROOT"
        token=$(cat /proc/sys/kernel/random/uuid)
        saved=$(getprop "$KEY")
        if [ -z "$saved" ]; then
            if getprop | grep -Fq "[$KEY]:"; then saved=empty; else saved=absent; fi
        fi
        printf '%s\n' "$saved" > "$STATE/property"
        printf '%s\n' "$token" > "$STATE/token"
        nohup sh "$SELF" watchdog "$token" </dev/null >> "$ROOT/trial.log" 2>&1 &
        echo "WATCHDOG=$!; restoring property and services in 45 seconds unless verified and kept"
        setprop "$KEY" 1
        [ "$(getprop "$KEY")" = 1 ] || fail 'Property set failed'
        date
        setprop ctl.restart vendor.hwcomposer-3
        ;;
    keep)
        [ -d "$STATE" ] || fail 'No active session'
        [ "$(getprop "$KEY")" = 1 ] || fail 'Capability not enabled'
        /data/local/tmp/extbridge-drm-read-current | awk '/3200x2136@/ {split($5, m, "@"); if (m[2] >= 119.9 && m[2] <= 144.1) ok=1} END {exit !ok}' || fail 'Verify real internal 120/144 Hz first'
        : > "$STATE/keep"
        echo 'KEPT: runtime only; restore with unlock.sh restore; reboot also clears this non-persist property'
        ;;
    watchdog)
        sleep 45
        if [ "$(cat "$STATE/token" 2>/dev/null || true)" = "${2:-missing}" ] && [ ! -f "$STATE/keep" ]; then restore "$2"; fi
        ;;
    restore) [ ! -d "$STATE" ] || restore "$(cat "$STATE/token")" ;;
    status)
        echo "capability=$(getprop "$KEY")"
        echo "external_display_connected=$(settings get system external_display_connected)"
        /data/local/tmp/extbridge-drm-read-current
        ;;
    *) fail 'Use check, start, keep, restore or status' ;;
esac
