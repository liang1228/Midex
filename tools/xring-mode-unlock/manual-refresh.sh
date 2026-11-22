#!/system/bin/sh
set -eu
set -o pipefail
umask 077
BASE=${0%/*}
ROOT=${BASE%/*}
LOCK=$ROOT/session
DP=/sys/class/drm/card0-DP-1
HAL=android.hardware.graphics.composer3-service.xring
DRM=$ROOT/drm-read-current
SELECTOR=$BASE/manual-selector
TIMING=$BASE/manual-timing
DEBUG=$LOCK/debug
OVERRIDE=$DEBUG/dri/0/DP-1/edid_override

fail() { echo "ERROR $*" >&2; exit 1; }
sha() { sha256sum "$1" | cut -d ' ' -f 1; }
now() { cut -d . -f 1 /proc/uptime; }
port() { cat "$DP/status"; }
same_composer() { [ "$(pidof "$HAL")" = "$composer" ]; }
owns_lock() { [ "$(cat "$LOCK/owner" 2>/dev/null)" = "$boot manual" ]; }
state() {
    printf '%s\n' "$*" > "$BASE/status.next"
    mv "$BASE/status.next" "$BASE/status"
    echo "$*"
}

scan_mode() {
    "$DRM" | awk -v clock="${1:-}" '
        $1=="CRTC" && $2=="id=205" && $3=="valid=1" && $5 ~ /^2560x1440@/ &&
        (!length(clock) || $6=="clock_kHz="clock) { print; found=1 }
        END { exit !found }'
}

check_device() {
    [ "$(getprop ro.build.fingerprint)" = Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys ] || fail UNSUPPORTED
    [ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ] || fail UNSUPPORTED
    [ "$(getprop sys.boot_completed)" = 1 ] || fail BOOTING
    [ "$(cat /sys/fs/selinux/enforce)" = 1 ] || fail SELINUX
    [ "$(sha "$DRM")" = aa85db6bdc514c059d9c8691ff7e7675871b10f00ce4e64b24a3da07854f2461 ] || fail DRM_READER
    [ "$(sha /vendor/lib64/libxdmcore.so)" = bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85 ] || fail HAL
    ! grep -q ' /vendor/lib64/libxdmcore.so ' /proc/self/mounts || fail HAL_OVERLAY
    [ ! -d /sys/module/extbridge_618_mode_cap ] && [ ! -d /sys/module/extbridge_618_lane_trial ] || fail SESSION_BUSY
}

link_budget() {
    [ "$(port)" = connected ] || fail NO_LINK
    scan_mode >/dev/null || fail NEED_2K
    sh "$BASE/trace-dp-policy.sh" "$BASE/drm-read-props" || fail LINK_BUDGET
}

clear_stale_lock() {
    [ -r "$LOCK/boot" ] && [ "$(cat "$LOCK/boot")" != "$boot" ] || return 0
    if [ -d "$DEBUG" ]; then rmdir "$DEBUG"; fi
    rm -f "$LOCK/original.edid" "$LOCK/custom.edid" "$LOCK/owner" "$LOCK/boot" "$LOCK/stop"
    rmdir "$LOCK"
}

restore_selector() {
    for attempt in 1 2 3 4 5; do
        same_composer || return 0
        if "$SELECTOR" manual-check "$composer" "$rate" ||
            { "$SELECTOR" manual-restore "$composer" "$rate" && "$SELECTOR" manual-check "$composer" "$rate"; }; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

restore() {
    exit_status=$?
    trap - EXIT
    trap '' INT TERM
    set +e
    outcome=IDLE
    if [ "$exit_status" -ne 0 ]; then outcome="FAILED_RESTORED $failure"; fi
    if [ "$configured" = 1 ]; then outcome="CONFIGURED $rate $clock"; fi
    if [ "$armed" = 1 ]; then
        printf reset > "$OVERRIDE" && [ "$(wc -c < "$OVERRIDE")" = 0 ] || outcome=RESTORE_ERROR
    fi
    if [ "$patched" = 1 ]; then restore_selector || outcome=RESTORE_ERROR; fi
    if [ "$mounted" = 1 ]; then umount "$DEBUG" || outcome=RESTORE_ERROR; fi
    if [ "$outcome" != RESTORE_ERROR ]; then
        if [ -d "$DEBUG" ]; then rmdir "$DEBUG" || outcome=RESTORE_ERROR; fi
        if [ "$outcome" != RESTORE_ERROR ]; then
            state "$outcome"
            rm -f "$LOCK/original.edid" "$LOCK/custom.edid" "$LOCK/owner" "$LOCK/boot" "$LOCK/stop"
            rmdir "$LOCK" && return
            printf '%s\n' "$boot manual" > "$LOCK/owner"
            outcome=RESTORE_ERROR
        fi
    fi
    state "$outcome"
}

wait_port() {
    deadline=$(($(now) + $2))
    while [ "$(port)" != "$1" ]; do
        [ ! -e "$LOCK/stop" ] || exit 0
        same_composer || fail COMPOSER_CHANGED
        [ "$(now)" -lt "$deadline" ] || fail HOTPLUG_TIMEOUT
        sleep 1
    done
    [ ! -e "$LOCK/stop" ] || exit 0
}

run() {
    mounted=0 armed=0 patched=0 configured=0 failure=PRECHECK
    trap restore EXIT
    trap 'exit 0' INT TERM
    check_device
    composer=$(pidof "$HAL")
    "$SELECTOR" manual-check "$composer" "$rate" || fail HAL_SELECTOR
    cat "$DP/edid" > "$LOCK/original.edid"
    budget=$(link_budget)
    [ "$(sha "$DP/edid")" = "$(sha "$LOCK/original.edid")" ] || fail LINK_CHANGED
    failure=OUT_OF_BUDGET
    clock=$("$TIMING" prepare "$LOCK/original.edid" "$budget" "$rate" "$LOCK/custom.edid") ||
        fail OUT_OF_BUDGET
    failure=HOTPLUG
    state WAIT_UNPLUG
    wait_port disconnected 150
    mkdir "$DEBUG"
    mount -t debugfs -o rw,nodev,nosuid,noexec debugfs "$DEBUG"
    mounted=1
    [ "$(port)" = disconnected ] && same_composer || fail LINK_CHANGED
    [ -w "$OVERRIDE" ] && [ "$(wc -c < "$OVERRIDE")" = 0 ] || fail EXISTING_OVERRIDE
    armed=1
    cat "$LOCK/custom.edid" > "$OVERRIDE"
    expected=$(sha "$LOCK/custom.edid")
    [ "$(sha "$OVERRIDE")" = "$expected" ] || fail EDID_READBACK
    patched=1
    "$SELECTOR" manual-apply "$composer" "$rate" || fail HAL_SELECTOR
    state READY_TO_RECONNECT
    wait_port connected 60
    failure=MODE_REJECTED
    deadline=$(($(now) + 14))
    until [ "$(sha "$DP/edid")" = "$expected" ] && scan_mode "$clock"; do
        [ ! -e "$LOCK/stop" ] || exit 0
        same_composer && [ "$(port)" = connected ] || fail LINK_CHANGED
        [ "$(now)" -lt "$deadline" ] || fail MODE_REJECTED
        sleep 1
    done
    actual_budget=$(link_budget)
    [ "$clock" -le "$actual_budget" ] || fail LINK_BUDGET_CHANGED
    configured=1
}

[ "$(id -u)" = 0 ] || fail ROOT_REQUIRED
boot=$(cat /proc/sys/kernel/random/boot_id)
case "${1:-status}" in
    status)
        current=$(cat "$BASE/status" 2>/dev/null || echo IDLE)
        case "$current" in
            'CONFIGURED '*) scan_mode "${current##* }" >/dev/null || current=IDLE ;;
            STARTING|WAIT_UNPLUG|READY_TO_RECONNECT|RESTORE_ERROR) owns_lock || current=IDLE ;;
        esac
        echo "$current"
        ;;
    budget)
        clear_stale_lock
        [ ! -d "$LOCK" ] || fail SESSION_BUSY
        check_device
        budget=$(link_budget)
        "$TIMING" budget "$DP/edid" "$budget" || fail EDID
        ;;
    stop) if owns_lock; then touch "$LOCK/stop"; fi ;;
    start|_run)
        rate=${2:-}
        case "$rate" in ''|*[!0-9]*|0*) fail INVALID_RATE ;; esac
        [ "${#rate}" -le 3 ] && [ "$rate" -ge 61 ] && [ "$rate" -le 240 ] || fail INVALID_RATE
        if [ "$1" = _run ]; then
            owns_lock || fail SESSION_BUSY
            run
            exit
        fi
        clear_stale_lock
        mkdir "$LOCK" 2>/dev/null || fail SESSION_BUSY
        printf '%s\n' "$boot" > "$LOCK/boot"
        printf '%s\n' "$boot manual" > "$LOCK/owner"
        state STARTING
        nohup sh "$BASE/manual-refresh.sh" _run "$rate" < /dev/null > "$BASE/session.log" 2>&1 &
        ;;
    *) fail INVALID_ACTION ;;
esac
