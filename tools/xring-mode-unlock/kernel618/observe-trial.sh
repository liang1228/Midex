#!/system/bin/sh
# Only the read-only observer is loaded. This script never loads patched OEM modules.
set -eu
BASE=/data/local/tmp/extbridge-kernel618-observer
MODULE=extbridge_618_observer
EXPECTED=bd97069fbbd0e7fab2560a0f9d7eec5c022b87ba333307170384e9b6c508df6a

if [ "${1:-}" = watchdog ]; then
    sleep 20
    if [ -f "$BASE/run/owner" ] && [ "$(cat "$BASE/run/owner")" = "$2" ] &&
       [ -f "$BASE/run/loaded" ] && [ -d "/sys/module/$MODULE" ]; then
        rmmod "$MODULE"
    fi
    exit 0
fi

[ "$(id -u)" = 0 ]
[ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ]
[ "$(getprop ro.build.fingerprint)" = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys' ]
[ "$(sha256sum "$BASE/$MODULE.ko" | cut -d ' ' -f 1)" = "$EXPECTED" ]
[ "$(sha256sum /vendor/lib/modules/external_tcpc.ko | cut -d ' ' -f 1)" = 78a767af8dea2e4f6f3c3d7c6e9a93e0e3fb46eede1df1888ce533bf70e294b9 ]
[ ! -d "/sys/module/$MODULE" ]
mkdir "$BASE/run"
owner="$(cat /proc/sys/kernel/random/boot_id):$$"
printf '%s\n' "$owner" > "$BASE/run/owner"
cleanup() {
    if [ -f "$BASE/run/loaded" ] && [ -d "/sys/module/$MODULE" ]; then
        rmmod "$MODULE" || return 1
    fi
    rm -f "$BASE/run/loaded" "$BASE/run/owner"
    rmdir "$BASE/run"
}
trap cleanup EXIT HUP INT TERM
nohup sh "$BASE/observe-trial.sh" watchdog "$owner" </dev/null >"$BASE/watchdog.log" 2>&1 &
printf 'boot_id=%s\n' "$(cat /proc/sys/kernel/random/boot_id)"
printf 'taint_before=%s\n' "$(cat /proc/sys/kernel/tainted)"
insmod "$BASE/$MODULE.ko"
touch "$BASE/run/loaded"
cat /sys/class/tcpc/type_c_port0/caps_info >/dev/null
for field in observations local_status remote_status local_config remote_config; do
    printf '%s=%s\n' "$field" "$(cat "/sys/module/$MODULE/parameters/$field")"
done
rmmod "$MODULE"
rm "$BASE/run/loaded"
[ ! -d "/sys/module/$MODULE" ]
printf 'observer_unloaded=1\ntaint_after=%s\n' "$(cat /proc/sys/kernel/tainted)"
/data/local/tmp/extbridge-drm-read-current
