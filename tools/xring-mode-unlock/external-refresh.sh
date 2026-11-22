#!/system/bin/sh
# Select the initial DP mode on the tested violin firmware and monitor.
set -eu
set -o pipefail
umask 077
ROOT=/data/adb/extbridge-external-refresh
DP=/sys/class/drm/card0-DP-1
HAL=android.hardware.graphics.composer3-service.xring
CAP=extbridge_618_mode_cap
EDID=1c334b8c809eb6325336d71aa8b52cde41ce55d1eabebcaf5b4105e3bc9fe1a1
TOOL=$ROOT/live-mode-table
DRM=$ROOT/drm-read-current
LOCK=$ROOT/session

fail() { echo "ERROR $*" >&2; exit 1; }
sha() { sha256sum "$1" | cut -d ' ' -f 1; }
dp_status() { cat "$DP/status"; }
now() { cut -d . -f 1 /proc/uptime; }
state() { printf '%s\n' "$*" > "$ROOT/status.next"; mv "$ROOT/status.next" "$ROOT/status"; echo "$*"; }
same_composer() { [ "$(pidof "$HAL")" = "$composer" ]; }
number() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; }

scan_mode() {
    "$DRM" | awk -v clock="$1" '
        $1=="CRTC" && $2=="id=205" && $3=="valid=1" &&
        $5 ~ /^2560x1440@/ && $6=="clock_kHz="clock { print; found=1 }
        END { exit !found }'
}

check() {
    [ "$(getprop ro.build.fingerprint)" = Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys ] || fail 'Firmware mismatch'
    [ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ] || fail 'Kernel mismatch'
    [ "$(getprop sys.boot_completed)" = 1 ] || fail 'Android is still starting'
    [ "$(cat /sys/fs/selinux/enforce)" = 1 ] || fail 'SELinux must be enforcing'
    [ "$(sha /vendor/lib64/libxdmcore.so)" = bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85 ] || fail 'HAL library mismatch'
    ! grep -q ' /vendor/lib64/libxdmcore.so ' /proc/self/mounts || fail 'Display library overlay present'
    [ "$(sha "$TOOL")" = cfe33f5e3f4729e0b7e736725d2dbe2036de4bd839441af24d5e587f1e87867d ] || fail 'Live helper mismatch'
    [ "$(sha "$DRM")" = aa85db6bdc514c059d9c8691ff7e7675871b10f00ce4e64b24a3da07854f2461 ] || fail 'DRM reader mismatch'
    [ ! -d /sys/module/$CAP ] && [ ! -d /sys/module/extbridge_618_lane_trial ] || fail 'Existing DP experiment present'
    if [ "$rate" = 165 ]; then
        [ "$(getprop ro.boot.slot_suffix)" = _a ] || fail 'Vendor slot mismatch'
        [ "$(sha /dev/block/by-name/vendor_boot_a)" = 23d0ffc22a5e9d14586b35dad7e4de08953a3a0dec79a1b9220bc35b5628e63f ] || fail 'Vendor boot mismatch'
        [ "$(sha "$ROOT/$CAP.ko")" = 268e745ad21028e2a5a215171e3a3fd0da45f5fc82c7f3bbe755ded6de6003c1 ] || fail '165 Hz mode-cap module mismatch'
    fi
    composer=$(pidof "$HAL")
    number "$composer" || fail 'Ambiguous composer process identity'
    identity="$boot $composer"
    case "$(dp_status)" in
        connected)
            [ "$(sha "$DP/edid")" = "$EDID" ] || fail 'Monitor EDID mismatch'
            printf '%s\n' "$identity" > "$ROOT/baseline"
            ;;
        disconnected)
            [ -r "$ROOT/baseline" ] && [ "$(cat "$ROOT/baseline")" = "$identity" ] ||
                fail 'Connect the tested DP monitor first'
            ;;
        *) fail 'Unknown DP link state' ;;
    esac
    "$TOOL" "dp$rate-check" "$composer" || fail 'Display selector differs from stock'
    echo CHECK_OK
}

restore_hal() {
    for attempt in 1 2 3 4 5; do
        same_composer || return 0
        if "$TOOL" "dp$rate-check" "$composer" ||
            { "$TOOL" "dp$rate-restore" "$composer" && "$TOOL" "dp$rate-check" "$composer"; }; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

restore() {
    result=$?
    trap - EXIT
    trap '' INT TERM
    set +e
    restore_error=0
    if [ "$cap_armed" = 1 ] && [ -d /sys/module/$CAP ]; then
        rmmod "$CAP" || restore_error=1
    fi
    if [ "$hal_armed" = 1 ]; then
        restore_hal || restore_error=1
    fi
    if [ "$restore_error" = 0 ]; then
        rm -f "$LOCK/boot" "$LOCK/stop"
        rmdir "$LOCK" || restore_error=1
    fi
    if [ "$restore_error" != 0 ]; then
        state RESTORE_ERROR
        exit 1
    fi
    if [ "$result" != 0 ]; then state FAILED_RESTORED
    elif [ "$configured" = 1 ]; then state "CONFIGURED $rate"
    else state IDLE
    fi
    exit "$result"
}

wait_port() {
    deadline=$(($(now) + $2))
    while [ "$(dp_status)" != "$1" ]; do
        [ ! -e "$LOCK/stop" ] || exit 0
        same_composer || fail 'Composer changed during hotplug'
        [ "$(now)" -lt "$deadline" ] || fail "Timed out waiting for DP $1"
        sleep 1
    done
    [ ! -e "$LOCK/stop" ] || exit 0
}

run() {
    hal_armed=0 cap_armed=0 configured=0
    trap restore EXIT
    trap 'exit 0' INT TERM
    check
    state WAIT_UNPLUG
    wait_port disconnected 180
    same_composer && [ "$(dp_status)" = disconnected ] || fail 'DP link changed before arming'
    hal_armed=1
    "$TOOL" "dp$rate-apply" "$composer" || fail 'Could not arm the initial mode selector'
    if [ "$rate" = 165 ]; then
        cap_armed=1
        insmod "$ROOT/$CAP.ko" || fail 'Could not arm 165 Hz mode enumeration'
    fi
    state READY_TO_RECONNECT
    wait_port connected 65
    deadline=$(($(now) + 15))
    until [ "$(sha "$DP/edid")" = "$EDID" ] && scan_mode "$clock"; do
        [ ! -e "$LOCK/stop" ] || exit 0
        same_composer && [ "$(dp_status)" = connected ] || fail 'DP link changed during enumeration'
        [ "$(now)" -lt "$deadline" ] || fail "No initial 2560x1440 $rate Hz mode confirmed by DRM"
        sleep 1
    done
    configured=1
}

[ "$(id -u)" = 0 ] || fail 'Root required'
cd "$ROOT"
boot=$(cat /proc/sys/kernel/random/boot_id)
action=${1:-status}
case "$action" in
    status)
        current=$(cat "$ROOT/status" 2>/dev/null || echo IDLE)
        case "$current" in
            'CONFIGURED 144') scan_mode 580000 >/dev/null || current=IDLE ;;
            'CONFIGURED 165') scan_mode 699500 >/dev/null || current=IDLE ;;
        esac
        echo "$current"
        exit
        ;;
    stop) [ ! -d "$LOCK" ] || touch "$LOCK/stop"; exit ;;
    check|start|_run) ;;
    *) fail 'Use check|start [144|165], status or stop' ;;
esac
rate=${2:-165}
case "$rate" in
    144) clock=580000 ;;
    165) clock=699500 ;;
    *) fail 'Select 144 or 165 Hz' ;;
esac
case "$action" in
    check) [ ! -d "$LOCK" ] || fail 'Session already exists'; check ;;
    start)
        if [ -r "$LOCK/boot" ] && [ "$(cat "$LOCK/boot")" != "$boot" ]; then
            rm -f "$LOCK/boot" "$LOCK/stop"
            rmdir "$LOCK"
        fi
        mkdir "$LOCK" || fail 'Session already exists'
        echo "$boot" > "$LOCK/boot"
        state STARTING
        nohup sh "$ROOT/external-refresh.sh" _run "$rate" < /dev/null > "$ROOT/session.log" 2>&1 &
        ;;
    _run) [ "$(cat "$LOCK/boot")" = "$boot" ] || fail 'No valid armed session'; run ;;
esac
