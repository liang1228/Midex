#!/system/bin/sh
# Temporary 165 Hz enumeration on the already verified direct DP link.
set -eu
ROOT=/data/local/tmp/extbridge-live-cap-reprobe
KO=/data/local/tmp/extbridge-xring-mode-trial/extbridge_618_mode_cap.ko
DP=/sys/class/drm/card0-DP-1
[ "$(cat /proc/sys/kernel/random/boot_id)" = 5aa59f24-37a1-4780-aa75-0c7e2a22d547 ]
[ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ]
[ "$(sha256sum "$KO" | cut -d ' ' -f 1)" = 268e745ad21028e2a5a215171e3a3fd0da45f5fc82c7f3bbe755ded6de6003c1 ]
[ "$(sha256sum "$DP/edid" | cut -d ' ' -f 1)" = 1c334b8c809eb6325336d71aa8b52cde41ce55d1eabebcaf5b4105e3bc9fe1a1 ]
[ "$(cat "$DP/status")" = connected ]
[ ! -d /sys/module/extbridge_618_mode_cap ]
[ ! -d /sys/module/extbridge_618_lane_trial ]
mkdir "$ROOT"
echo WAITING_FOR_DISCONNECT > "$ROOT/status"
for step in $(seq 1 300); do
    if [ "$(cat "$DP/status")" = disconnected ]; then
        insmod "$KO"
        trap 'rmmod extbridge_618_mode_cap' EXIT
        echo CAP_LOADED
        echo CAP_LOADED > "$ROOT/status"
        sleep 60
        cat /sys/module/extbridge_618_mode_cap/parameters/enabled
        cat /sys/module/extbridge_618_mode_cap/parameters/overrides
        rmmod extbridge_618_mode_cap
        trap - EXIT
        echo CAP_UNLOADED
        echo CAP_UNLOADED > "$ROOT/status"
        exit 0
    fi
    sleep 1
done
echo DISCONNECT_TIMEOUT > "$ROOT/status"
exit 1
