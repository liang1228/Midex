#!/system/bin/sh
# One-boot, exact-library test. Does not restart any display service.
set -eu
ROOT=/data/local/tmp/extbridge-live-sf-trial
TOOL=/data/local/tmp/extbridge-live-mode-table
BOOT=5aa59f24-37a1-4780-aa75-0c7e2a22d547
SF=1652
COMPOSER=1579
# IDs below must be regenerated from the current SF displayModes after hotplug.
TARGET_MODE=${2:-27}
TARGET_FPS=${3:-120.000015}
HOLD=${4:-20}
[ "$HOLD" = 20 ] || [ "$HOLD" = 120 ] || [ "$HOLD" = 300 ] || exit 2
case "$TARGET_MODE:$TARGET_FPS" in
    *:120.000015|*:144.00002|*:165.0) ;;
    *) echo 'Unverified current display mode' >&2; exit 2 ;;
esac
check_identity() {
    [ "$(cat /proc/sys/kernel/random/boot_id)" = "$BOOT" ]
    [ "$(pidof surfaceflinger)" = "$SF" ]
    [ "$(pidof android.hardware.graphics.composer3-service.xring)" = "$COMPOSER" ]
    [ "$(sha256sum /system_ext/lib64/libsurfaceflinger.so | cut -d ' ' -f 1)" = 9d0349920af5a1a31e772d5af6c3898f79fb5c7c359485743b44265d39d1b264 ]
    [ "$(sha256sum /vendor/lib64/libxdmcore.so | cut -d ' ' -f 1)" = bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85 ]
}
restore() {
    check_identity || return 1
    dumpsys SurfaceFlinger > "$ROOT/restore-modes.txt"
    restore_mode=$(sed -n 's/.*{id=\([0-9]*\), hwcId=[0-9]*, resolution=2560x1440, vsyncRate=60.00 Hz.*/\1/p' "$ROOT/restore-modes.txt" | sort -u)
    case "$restore_mode" in ''|*[!0-9]*) echo 'Ambiguous current 2K60 mode' >&2; return 1 ;; esac
    # A code restore alone does not change the active physical mode.
    timeout 4 cmd display set-user-preferred-display-mode 2560 1440 60.000004 2 false || true
    timeout 4 service call SurfaceFlinger 1035 i32 "$restore_mode" i64 1 || true
    # The mode request is asynchronous; do not abandon code restoration after one sample.
    for step in $(seq 1 30); do
        /data/local/tmp/extbridge-drm-read-current > "$ROOT/restored-crtc.txt"
        grep -q 'CRTC id=205 valid=1 .*2560x1440@59.95055 clock_kHz=241500 ' "$ROOT/restored-crtc.txt" && break
        sleep 1
    done
    cat "$ROOT/restored-crtc.txt"
    grep -q 'CRTC id=205 valid=1 .*2560x1440@59.95055 clock_kHz=241500 ' "$ROOT/restored-crtc.txt"
    "$TOOL" sf-restore "$SF"
    "$TOOL" sf-check "$SF"
    echo RESTORE_DONE
}
case "${1:-}" in
    watchdog)
        mkdir -p "$ROOT"
        check_identity
        echo WATCHDOG_READY > "$ROOT/ready"
        sleep "$HOLD"
        restore
        ;;
    start)
        check_identity
        mkdir -p "$ROOT"
        [ ! -e "$ROOT/ready" ] || grep -q '^RESTORE_DONE$' "$ROOT/watchdog.log"
        rm -f "$ROOT/ready"
        dumpsys SurfaceFlinger > "$ROOT/before-sf.txt"
        grep -q 'resolution=2560x1440, vsyncRate=60.00 Hz' "$ROOT/before-sf.txt"
        grep -q "id=$TARGET_MODE, hwcId=$TARGET_MODE, resolution=2560x1440, vsyncRate=${TARGET_FPS%%.*}.00 Hz" "$ROOT/before-sf.txt"
        "$TOOL" sf-check "$SF"
        cmd display set-user-preferred-display-mode 2560 1440 60.000004 2 false
        nohup /system/bin/sh "$0" watchdog "$TARGET_MODE" "$TARGET_FPS" "$HOLD" > "$ROOT/watchdog.log" 2>&1 < /dev/null &
        echo "$!" > "$ROOT/watchdog.pid"
        for step in 1 2 3 4 5; do
            [ -s "$ROOT/ready" ] && break
            sleep 1
        done
        [ "$(cat "$ROOT/ready")" = WATCHDOG_READY ]
        kill -0 "$(cat "$ROOT/watchdog.pid")"
        "$TOOL" sf-apply "$SF"
        cmd display set-user-preferred-display-mode 2560 1440 "$TARGET_FPS" 2 false
        service call SurfaceFlinger 1035 i32 "$TARGET_MODE" i64 1
        for step in 1 2 3 4 5; do
            date '+%T'
            /data/local/tmp/extbridge-drm-read-current
            sleep 1
        done
        ;;
    restore) restore ;;
    *) exit 2 ;;
esac
