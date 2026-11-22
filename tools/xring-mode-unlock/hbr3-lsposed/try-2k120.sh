#!/system/bin/sh
# Reads a verified live link. Only --trial can send the temporary framework request.
set -eu
case "${1:-status}" in status|--trial) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ]
[ "$(getprop ro.build.fingerprint)" = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys' ]
dp=/sys/class/drm/card0-DP-1
policy=/data/adb/extbridge-external-refresh/manual-v1/trace-dp-policy.sh
reader=/data/local/tmp/extbridge-drm-read-modes
current=/data/local/tmp/extbridge-drm-read-current
panel=70b007a56f70fe5955c3e30736e4599d40c5a300d8aa20c70674e304280b9ce7
sha() { sha256sum "$1" | cut -d ' ' -f 1; }
[ "$(sha "$policy")" = 9d726d08caa9161089d2628046ea3f955febe1410e658d93895609b7144f087b ]
[ "$(sha "$reader")" = 68f79bd4c0b3c946311357a001a694d7ca267cea0ebcee03cfd5d29fb594a900 ]
[ "$(sha "$current")" = aa85db6bdc514c059d9c8691ff7e7675871b10f00ce4e64b24a3da07854f2461 ]
[ "$(cat "$dp/status")" = connected ] && [ "$(sha "$dp/edid")" = "$panel" ] || { echo WRONG_PANEL; exit 1; }
sample=$(cut -d . -f 1 /proc/uptime)
trace=$(sh "$policy")
if ! printf '%s\n' "$trace" | awk '/connector_get:/ {
    ++count
    if ($0 !~ / actual_rate=3( |$)/ || $0 !~ / actual_lanes=2( |$)/ || $0 !~ / maxpclk=540000( |$)/) bad=1
} END { exit (!count || bad) }'; then
    echo BLOCKED_LINK_REQUIRES_HBR3_X2
    exit 3
fi
printf '%s\n' "$trace" | grep -q '^  2560x1440@119.99759 clock_kHz=497750 ' || { echo NO_DRM_2K120; exit 3; }
[ "$(sha "$dp/edid")" = "$panel" ]
if [ "${1:-status}" = status ]; then
    cmd display extbridge-hbr3-120 status
    exit
fi
cmd display extbridge-hbr3-120 trial HBR3x2-497750 "$sample"
trap 'cmd display extbridge-hbr3-120 stop >/dev/null' EXIT
trap 'exit 1' HUP INT TERM
for n in 1 2 3 4 5 6; do
    sleep 1
    [ "$(cat "$dp/status")" = connected ] && [ "$(sha "$dp/edid")" = "$panel" ] || exit 1
    "$current" | grep '^CRTC id=205 valid=1 '
done
sleep 6
