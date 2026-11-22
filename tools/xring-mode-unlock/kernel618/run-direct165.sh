#!/system/bin/sh
# Exact-current direct-DP experiment; HAL has a separate 45-second watchdog.
set -eu
ROOT=/data/local/tmp/extbridge-xring-mode-trial
LABEL=${2:-direct165}
case "$LABEL" in direct165|direct165-awake2|direct165-awake3|direct165-awake4) ;; *) echo 'Unknown evidence label' >&2; exit 1 ;; esac
OUT=$ROOT/$LABEL
TARGET=/vendor/lib64/libxdmcore.so
STOCK=bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85
MODECAP=268e745ad21028e2a5a215171e3a3fd0da45f5fc82c7f3bbe755ded6de6003c1
sha() { sha256sum "$1" | cut -d ' ' -f 1; }
fail() { echo "ERROR: $*" >&2; exit 1; }
check() {
    [ "$(id -u)" = 0 ] || fail 'Root required'
    # Refuse before insmod/output creation, not only after the module is loaded.
    sh "$ROOT/trial.sh" restart-check
    [ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ] || fail 'Kernel mismatch'
    [ "$(getprop ro.build.fingerprint)" = Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys ] || fail 'Firmware mismatch'
    [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ] || fail 'Wrong mount namespace'
    [ "$(getprop ro.boot.slot_suffix)" = _a ] || fail 'Slot mismatch'
    [ "$(sha /dev/block/by-name/vendor_boot_a)" = 23d0ffc22a5e9d14586b35dad7e4de08953a3a0dec79a1b9220bc35b5628e63f ] || fail 'Vendor boot mismatch'
    [ "$(sha /sys/class/drm/card0-DP-1/edid)" = 1c334b8c809eb6325336d71aa8b52cde41ce55d1eabebcaf5b4105e3bc9fe1a1 ] || fail 'EDID mismatch'
    [ "$(sha "$TARGET")" = "$STOCK" ] || fail 'HAL not stock'
    ! grep -q " $TARGET " /proc/self/mounts || fail 'HAL mount present'
    [ ! -d /sys/module/extbridge_618_mode_cap ] || fail 'Module already loaded'
    [ ! -d /sys/module/extbridge_618_lane_trial ] || fail 'Lane trial present'
    [ "$(sha "$ROOT/extbridge_618_mode_cap.ko")" = "$MODECAP" ] || fail 'Module mismatch'
    [ "$(sha "$ROOT/libxdmcore-prefer165-v2-trial.so")" = 012e8e1c4282e3745e7f96698f535164333e86781f5ed170471e5576cf02217f ] || fail 'Trial HAL mismatch'
    [ "$(settings get global extbridge_unlock_builtin_refresh)" = 1 ] || fail 'Existing internal unlock inactive'
    /data/local/tmp/extbridge-drm-read-current | grep -q 'CRTC id=205 valid=1 .*2560x1440@' || fail 'No valid physical 2K baseline'
    echo DIRECT165_CHECK_OK
}
snapshot() {
    date '+%F %T'
    cat /proc/sys/kernel/random/boot_id /proc/sys/kernel/tainted
    /data/local/tmp/extbridge-drm-read-current
    dumpsys power | grep -E 'mWakefulness=|mLastSleepReason=' || true
    echo "hal_sha256=$(sha "$TARGET")"
    if [ -d /sys/module/extbridge_618_mode_cap ]; then
        cat /sys/module/extbridge_618_mode_cap/parameters/enabled /sys/module/extbridge_618_mode_cap/parameters/overrides
    fi
    dumpsys display | grep -E 'DisplayDeviceInfo|mActiveModeId|mActiveSfDisplayMode|mSfDisplayModes' || true
    sha256sum /data/local/tmp/extbridge.conf /data/local/tmp/extbridge_apps.conf 2>/dev/null || true
}
restore_backend() {
    for pid in $(pidof app_process || true); do
        [ "$(tr '\000' ' ' < "/proc/$pid/cmdline")" = 'app_process /data/local/tmp com.extbridge.daemon.ExtBridge ' ] || continue
        grep -q '^Uid:[[:space:]]*0[[:space:]]' "/proc/$pid/status" || fail 'Daemon not root'
        tr '\000' '\n' < "/proc/$pid/environ" | grep -qx 'CLASSPATH=/data/local/tmp/extbridge.jar' || fail 'Daemon classpath mismatch'
        kill "$pid"
    done
    CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge > /data/local/tmp/extbridge-dp-direct-daemon.log 2>&1 < /dev/null &
    echo "backend_pid=$!"
}
wait_helper() {
    i=0
    while [ "$i" -lt 20 ]; do
        if pm path com.extbridge.helper 2>/dev/null | grep -q '^package:/data/app/'; then
            return 0
        fi
        sleep 2
        i=$((i + 1))
    done
    return 1
}
case "${1:-check}" in
    check) check ;;
    start)
        check
        mkdir "$OUT" || fail 'Output exists; preserve previous evidence'
        snapshot > "$OUT/before.txt" 2>&1
        insmod "$ROOT/extbridge_618_mode_cap.ko"
        # Restore stock on any orchestration error; watchdog also runs separately.
        trap 'sh "$ROOT/trial.sh" restore prefer165; rmmod extbridge_618_mode_cap' EXIT
        nohup sh "$ROOT/run-direct165.sh" _awake "$LABEL" > "$OUT/awake.txt" 2>&1 < /dev/null &
        sh "$ROOT/trial.sh" start prefer165
        sleep 8
        snapshot > "$OUT/t08.txt" 2>&1
        # Rebind only this project's backend after the display-service restart.
        restore_backend > "$OUT/backend-trial.txt" 2>&1
        wm dismiss-keyguard || true
        # Package scanning may still be in progress after the service restart.
        am start --display 0 -n com.extbridge.helper/.MainActivity > "$OUT/console-trial.txt" 2>&1 || true
        sleep 12
        snapshot > "$OUT/t20.txt" 2>&1
        if pm path com.extbridge.helper 2>/dev/null | grep -q '^package:/data/app/'; then
            restore_backend >> "$OUT/backend-trial.txt" 2>&1
            am start --display 0 -n com.extbridge.helper/.MainActivity >> "$OUT/console-trial.txt" 2>&1 || true
        fi
        sleep 15
        snapshot > "$OUT/t35.txt" 2>&1
        /data/local/tmp/extbridge-drm-read-modes > "$OUT/modes-active.txt"
        screencap -d 4639175198581658112 -p "$OUT/internal.png" || true
        screencap -d 1 -p "$OUT/external.png" || true
        sleep 23
        sh "$ROOT/trial.sh" restore prefer165
        [ "$(sha "$TARGET")" = "$STOCK" ] || fail 'Stock restoration not confirmed'
        rmmod extbridge_618_mode_cap
        trap - EXIT
        wait_helper || fail 'Stock restored; helper package is not ready'
        restore_backend > "$OUT/backend-restored.txt" 2>&1
        input keyevent KEYCODE_WAKEUP
        wm dismiss-keyguard
        am start --display 0 -n com.extbridge.helper/.MainActivity > "$OUT/console-restored.txt" 2>&1
        sleep 3
        snapshot > "$OUT/restored.txt" 2>&1
        dmesg | tail -n 500 > "$OUT/kernel-tail.txt"
        echo DIRECT165_FINISHED
        ;;
    _awake)
        # A display-service restart can start Android on the keyguard timeout.
        # Wake only; do not change settings or inject touch/pointer events.
        i=0
        while [ "$i" -lt 18 ]; do
            input keyevent KEYCODE_WAKEUP || true
            sleep 3
            i=$((i + 1))
        done
        ;;
    *) fail 'Use check or start' ;;
esac
