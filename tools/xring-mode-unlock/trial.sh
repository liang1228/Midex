#!/system/bin/sh
# Temporary bind mount only. No boot module, vendor write, or global SELinux change.
# start restarts vendor.hwcomposer-3 AND (by init.rc) SurfaceFlinger.
set -eu

ROOT=/data/local/tmp/extbridge-xring-mode-trial
TARGET=/vendor/lib64/libxdmcore.so
PATCHED=$ROOT/libxdmcore-unfiltered-trial.so
STOCK_HASH=bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85
TRIAL_HASH=fb8a55a51248d97035cda0debf807a871beb10c025f2e649ff996c044df41be3
LOG=$ROOT/trial.log
PROFILE=${2:-unfiltered}
if [ "${1:-}" = _watchdog ]; then PROFILE=${3:-unfiltered}; fi
case "$PROFILE" in
    unfiltered) ;;
    prefer120)
        PATCHED=$ROOT/libxdmcore-prefer120-trial.so
        TRIAL_HASH=1892d5722388d27d1aa569d669fe0bf928a708f6f1462534e1737adac4106f51
        ;;
    prefer144)
        PATCHED=$ROOT/libxdmcore-prefer144-trial.so
        TRIAL_HASH=afbaeb29842333f354e080b26e23290dc6afaab3ce0189e19a0198f039fbc069
        ;;
    prefer165)
        PATCHED=$ROOT/libxdmcore-prefer165-v2-trial.so
        TRIAL_HASH=012e8e1c4282e3745e7f96698f535164333e86781f5ed170471e5576cf02217f
        ;;
    *) echo 'Unknown trial profile' >&2; exit 1 ;;
esac

fail() { echo "ERROR: $*" >&2; exit 1; }
file_sha256() { sha256sum "$1" | cut -d ' ' -f 1; }
mounted() { grep -q " $TARGET " /proc/self/mounts; }
note() { echo "$(date '+%F %T') $*" >> "$LOG"; }

identity() {
    [ "$(id -u)" = 0 ] || fail 'Root required'
    [ "$(getprop ro.product.device)" = violin ] || fail 'Unsupported device'
    case "$(getprop ro.build.fingerprint)" in
        Xiaomi/violin/violin:17/*/OS4.0.6.0.XOTCNXM:user/release-keys) ;;
        *) fail 'Unsupported firmware' ;;
    esac
    [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ] || fail 'Not in init mount namespace'
}

check_restart_budget() {
    # Composer restarts reload Android too. Reserve two framework starts for
    # activation and rollback; never reset or disable the system's rescue state.
    case "$(getprop sys.boot.reason)" in
        *[Rr]escue[Pp]arty*) fail 'RescueParty reboot requires investigation before another display trial' ;;
    esac
    count=$(getprop crashrecovery.rescue_boot_count)
    [ -n "$count" ] || count=$(getprop sys.rescue_boot_count)
    case "$count" in
        0|1|2) ;;
        *) fail 'Framework restart budget unavailable or exhausted; inspect RescueParty state' ;;
    esac
    echo "RESTART_BUDGET_OK: rescue_boot_count=$count"
}

check() {
    identity
    check_restart_budget
    mounted && fail 'Library already has a mount; refusing to stack mounts'
    [ "$(file_sha256 "$TARGET")" = "$STOCK_HASH" ] || fail 'Stock hash mismatch'
    [ "$(file_sha256 "$PATCHED")" = "$TRIAL_HASH" ] || fail 'Trial hash mismatch'
    if [ "$PROFILE" = prefer120 ]; then
        [ -x /data/local/tmp/extbridge-drm-read-modes ] || fail 'Read-only DRM probe required'
        /data/local/tmp/extbridge-drm-read-modes | grep -q '^  1920x1080@120.00000 ' || fail 'Real 1080p120 kernel mode required'
    fi
    if [ "$PROFILE" = prefer144 ]; then
        [ "$(file_sha256 /sys/class/drm/card0-DP-1/edid)" = 1c334b8c809eb6325336d71aa8b52cde41ce55d1eabebcaf5b4105e3bc9fe1a1 ] || fail 'Unsupported DP receiver'
        [ -x /data/local/tmp/extbridge-drm-read-modes ] || fail 'Read-only DRM probe required'
        /data/local/tmp/extbridge-drm-read-modes | grep -q '^  2560x1440@143.98062 clock_kHz=580000 ' || fail 'Real 2K144 kernel mode required'
    fi
    if [ "$PROFILE" = prefer165 ]; then
        [ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ] || fail 'Unsupported kernel'
        [ "$(file_sha256 /sys/class/drm/card0-DP-1/edid)" = 1c334b8c809eb6325336d71aa8b52cde41ce55d1eabebcaf5b4105e3bc9fe1a1 ] || fail 'Unsupported DP receiver'
        [ "$(cat /sys/module/extbridge_618_mode_cap/parameters/enabled)" = Y ] || fail 'Exact-clock eligibility module required'
        [ "$(file_sha256 "$ROOT/extbridge_618_mode_cap.ko")" = 268e745ad21028e2a5a215171e3a3fd0da45f5fc82c7f3bbe755ded6de6003c1 ] || fail 'Eligibility module hash mismatch'
    fi
    pid=$(getprop init.svc_debug_pid.vendor.hwcomposer-3)
    [ -n "$pid" ] && [ -r "/proc/$pid/maps" ] || fail 'Composer not running'
    [ "$(readlink "/proc/$pid/ns/mnt")" = "$(readlink /proc/1/ns/mnt)" ] || fail 'Composer namespace differs'
    grep -q '/vendor/lib64/libxdmcore.so$' "/proc/$pid/maps" || fail 'Unexpected composer mapping'
    echo 'CHECK_OK: exact firmware, both hashes, stock mount and service namespace'
}

restore() {
    identity
    if mounted; then
        [ "$(file_sha256 "$TARGET")" = "$TRIAL_HASH" ] || fail 'Foreign mount; refusing to unmount'
        umount "$TARGET" || {
            # Composer can keep a mapped library busy. Detach this verified
            # trial mount; the restart below releases the old mapping.
            if mounted; then
                [ "$(file_sha256 "$TARGET")" = "$TRIAL_HASH" ] || fail 'Mount changed during restore'
                note 'Normal unmount busy; detaching verified trial mount'
                umount -l "$TARGET" || fail 'Lazy unmount failed'
            fi
        }
        [ "$(file_sha256 "$TARGET")" = "$STOCK_HASH" ] || fail 'Stock not restored'
        note 'STOCK_RESTORED; restarting vendor.hwcomposer-3 / SurfaceFlinger'
        setprop ctl.restart vendor.hwcomposer-3
    else
        [ "$(file_sha256 "$TARGET")" = "$STOCK_HASH" ] || fail 'Unexpected stock hash'
    fi
    rm -f "$ROOT/active/token"
    rmdir "$ROOT/active" 2>/dev/null || true
    echo RESTORED
}

case "${1:-status}" in
    restart-check) identity; check_restart_budget ;;
    check) check ;;
    start)
        check
        mkdir "$ROOT/active" || fail 'Another trial is active'
        chmod 0700 "$ROOT"
        chmod 0644 "$PATCHED"
        # Match the stock file type for the HAL; no global policy modification.
        chcon u:object_r:same_process_hal_file:s0 "$PATCHED" || {
            rmdir "$ROOT/active"
            fail 'Could not label temporary library'
        }
        # Arm device-side recovery before touching the mount, independent of ADB.
        token="$(date +%s)-$$"
        echo "$token" > "$ROOT/active/token"
        nohup /system/bin/sh "$ROOT/trial.sh" _watchdog "$token" "$PROFILE" >> "$LOG" 2>&1 </dev/null &
        note "WATCHDOG=$!; profile=$PROFILE; rollback in 45 seconds"
        if ! mount -o bind "$PATCHED" "$TARGET"; then
            rm -f "$ROOT/active/token"
            rmdir "$ROOT/active"
            fail 'Bind mount failed; service not restarted'
        fi
        note 'TRIAL_MOUNTED; restarting vendor.hwcomposer-3 / SurfaceFlinger'
        if ! setprop ctl.restart vendor.hwcomposer-3; then
            restore
            fail 'Service restart request failed; stock restored'
        fi
        echo 'TRIAL_STARTED: automatic stock restoration in 45 seconds'
        ;;
    _watchdog)
        sleep 45
        if [ "$(cat "$ROOT/active/token" 2>/dev/null || true)" = "${2:-missing}" ]; then
            restore
        fi
        ;;
    restore) restore ;;
    status)
        identity
        echo "library_sha256=$(file_sha256 "$TARGET")"
        echo "composer_pid=$(getprop init.svc_debug_pid.vendor.hwcomposer-3)"
        echo "surfaceflinger_pid=$(pidof surfaceflinger)"
        if mounted; then echo 'mount=present'; else echo 'mount=stock'; fi
        ;;
    *) fail 'Use restart-check, check, start, restore, or status' ;;
esac
