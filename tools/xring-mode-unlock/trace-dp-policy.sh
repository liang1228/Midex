#!/system/bin/sh
# Temporary observation only: no return-value override and no DRM SET ioctl.
set -eu
BASE=/sys/kernel/tracing
INST=$BASE/instances/extbridge_dp_policy
EVENT=extbridge_dp_policy/connector_get
reader=${1:-/data/local/tmp/extbridge-drm-read-modes}
[ "$(id -u)" = 0 ] || exit 1
[ "$(getprop ro.build.fingerprint)" = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys' ] || exit 1
[ ! -e "$INST" ] || exit 1
[ ! -e "$BASE/events/$EVENT" ] || exit 1
cleanup() {
    if [ -e "$INST/events/$EVENT/enable" ]; then echo 0 > "$INST/events/$EVENT/enable"; fi
    if [ -d "$INST" ]; then rmdir "$INST"; fi
    if [ -e "$BASE/events/$EVENT" ]; then printf '%s\n' "-:$EVENT" >> "$BASE/kprobe_events"; fi
}
mkdir "$INST"
trap cleanup EXIT
trap 'exit 1' INT TERM
echo 64 > "$INST/buffer_size_kb"
printf '%s\n' 'p:extbridge_dp_policy/connector_get drm_mode_getconnector maxpclk=+88(+24(@s_display)):u64 dsc_maxpclk=+96(+24(@s_display)):u64 dsc_policy=+44(+24(@s_display)):s32 dsc_source=+40(+24(@s_display)):u8 sink_dsc=+8(+24(+24(@s_display))):u8 sink_max_lanes=+38(+128(@s_display)):u8 sink_max_rate=+39(+128(@s_display)):u8 init_lanes=+68(+128(@s_display)):u32 init_rate=+64(+128(@s_display)):u32 actual_lanes=+100(+128(@s_display)):u32 actual_rate=+96(+128(@s_display)):u32 pin_type=+224(@s_display):u32' >> "$BASE/kprobe_events"
echo 1 > "$INST/events/$EVENT/enable"
echo 1 > "$INST/tracing_on"
if [ "$#" -gt 0 ]; then "$reader" >/dev/null & else "$reader" & fi
reader_pid=$!
wait "$reader_pid"
if [ "$#" -gt 0 ]; then
    awk -v pid="$reader_pid" '
        $1 ~ ("-" pid "$") && /connector_get:/ {
            lanes=clock=0
            rate=-1
            for (i=1; i<=NF; i++) {
                if ($i ~ /^maxpclk=[0-9]+$/) clock=substr($i, 9)
                if ($i ~ /^actual_lanes=[0-9]+$/) lanes=substr($i, 14)
                if ($i ~ /^actual_rate=[0-9]+$/) rate=substr($i, 13)
            }
            per_lane=rate==0 ? 54000 : rate==1 ? 90000 : rate==2 ? 180000 : rate==3 ? 270000 : 0
            if ((lanes==1 || lanes==2 || lanes==4) && per_lane && clock > 0) {
                negotiated=lanes*per_lane
                budget=clock < negotiated ? clock : negotiated
            }
        }
        END { if (budget) print budget; else exit 1 }' "$INST/trace"
    exit
fi
cat "$INST/trace"
