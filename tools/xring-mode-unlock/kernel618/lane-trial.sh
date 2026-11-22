#!/system/bin/sh
# probe: load/read/unload. run: C then D. recover: query status then restore D.
set -eu
BASE=/data/local/tmp/extbridge-kernel618-lane
MODULE=extbridge_618_lane_trial
PARAM=/sys/module/$MODULE/parameters
EXPECTED=3be3e17d9a243857a5135b41f6f3780ecdd1036e573a5a4e267afc18abec5288
TRACE=/sys/kernel/tracing/instances/extbridge_lane_trial
TRACE_BASE=/sys/kernel/tracing
traced=0
MODE=${1:-probe}
PROFILE=${2:-lanes}
[ "$MODE" = probe ] || [ "$MODE" = run ] || [ "$MODE" = recover ]
PIN_C=0
NOSSC=0
case "$PROFILE" in
    lanes) HBR3=0 ;;
    hbr3) HBR3=1 ;;
    combined) HBR3=1; PIN_C=1 ;;
    combined-nossc) HBR3=1; PIN_C=1; NOSSC=1 ;;
    *) exit 1 ;;
esac
[ "$(id -u)" = 0 ]
[ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ]
[ "$(getprop ro.build.fingerprint)" = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys' ]
grep -q '^androidboot.slot_suffix = "_a"$' /proc/bootconfig
[ "$(sha256sum "$BASE/$MODULE.ko" | cut -d ' ' -f 1)" = "$EXPECTED" ]
[ "$(sha256sum /vendor/lib/modules/external_tcpc.ko | cut -d ' ' -f 1)" = 78a767af8dea2e4f6f3c3d7c6e9a93e0e3fb46eede1df1888ce533bf70e294b9 ]
# xring-drm is loaded from vendor_boot; its ramdisk file is removed after boot.
[ "$(sha256sum /dev/block/by-name/vendor_boot_a | cut -d ' ' -f 1)" = 23d0ffc22a5e9d14586b35dad7e4de08953a3a0dec79a1b9220bc35b5628e63f ]
[ ! -d "/sys/module/$MODULE" ]
loaded=0
cleanup() {
    if [ "$traced" = 1 ]; then
        if [ -e "$TRACE/events/extbridge_lane_trial/enable" ]; then
            echo 0 > "$TRACE/events/extbridge_lane_trial/enable"
        fi
        rmdir "$TRACE"
        for name in config host hpd connector pattern clock clock_result reduce; do
            if [ -e "$TRACE_BASE/events/extbridge_lane_trial/$name" ]; then
                printf '%s\n' "-:extbridge_lane_trial/$name" >> "$TRACE_BASE/kprobe_events"
            fi
        done
    fi
    if [ "$loaded" = 1 ] && [ -d "/sys/module/$MODULE" ]; then
        phase=$(cat "$PARAM/phase")
        case "$phase" in
            0|3|4|6) rmmod "$MODULE" ;;
            *) printf 'module_retained_for_device_side_restore phase=%s\n' "$phase" ;;
        esac
    fi
}
trap cleanup EXIT HUP INT TERM
printf 'mode=%s boot_id=%s\ntaint_before=%s\n' "$MODE" "$(cat /proc/sys/kernel/random/boot_id)" "$(cat /proc/sys/kernel/tainted)"
insmod "$BASE/$MODULE.ko" hbr3="$HBR3" pin_c="$PIN_C" nossc="$NOSSC"
loaded=1
cat "$PARAM/status"
if [ "$MODE" != probe ]; then
    [ ! -e "$TRACE" ]
    [ ! -e "$TRACE_BASE/events/extbridge_lane_trial" ]
    mkdir "$TRACE"
    traced=1
    echo 512 > "$TRACE/buffer_size_kb"
    printf '%s\n' 'p:extbridge_lane_trial/config external_tcpc:tcpci_dp_notify_config_done local=%x1:u32 remote=%x2:u32 ack=%x3:u8' >> "$TRACE_BASE/kprobe_events"
    printf '%s\n' 'p:extbridge_lane_trial/host mca_xring_usb:mca_xr_usb_queue_host_on polarity=%x0:u8 mode=%x1:u32 hpd=%x2:u32' >> "$TRACE_BASE/kprobe_events"
    printf '%s\n' 'p:extbridge_lane_trial/hpd mca_xring_usb:mca_xr_usb_queue_dp_hpd hpd=%x0:u32' >> "$TRACE_BASE/kprobe_events"
    printf '%s\n' 'p:extbridge_lane_trial/connector drm_mode_getconnector maxpclk=+88(+24(@s_display)):u64 sink_max_lanes=+38(+128(@s_display)):u8 sink_max_rate=+39(+128(@s_display)):u8 init_lanes=+68(+128(@s_display)):u32 init_rate=+64(+128(@s_display)):u32 actual_lanes=+100(+128(@s_display)):u32 actual_rate=+96(+128(@s_display)):u32 ssc=+81(+128(@s_display)):u8 pin_type=+224(@s_display):u32 dpcd_rev=+14(+128(@s_display)):x8 dpcd_max_rate=+15(+128(@s_display)):x8 dpcd_lanes=+16(+128(@s_display)):x8' >> "$TRACE_BASE/kprobe_events"
    printf '%s\n' 'p:extbridge_lane_trial/pattern xring_drm:dp_link_training_set_pattern pattern=%x1:u32 rate=+96(%x0):u32 lanes=+100(%x0):u32' >> "$TRACE_BASE/kprobe_events"
    printf '%s\n' 'p:extbridge_lane_trial/clock drm_dp_clock_recovery_ok lanes=%x1:u32 lane01=+0(%x0):x8 lane23=+1(%x0):x8 align=+2(%x0):x8' >> "$TRACE_BASE/kprobe_events"
    printf '%s\n' 'r:extbridge_lane_trial/clock_result drm_dp_clock_recovery_ok ok=$retval:u8' >> "$TRACE_BASE/kprobe_events"
    printf '%s\n' 'p:extbridge_lane_trial/reduce xring_drm:dp_link_reduce_link_rate rate=+96(%x0):u32 lanes=+100(%x0):u32' >> "$TRACE_BASE/kprobe_events"
    echo 1 > "$TRACE/events/extbridge_lane_trial/enable"
    echo 1 > "$TRACE/tracing_on"
    if [ "$MODE" = recover ]; then
        echo 1 > "$PARAM/recover"
    else
        echo 1 > "$PARAM/trial"
        for second in 5 15 25 35; do
            if [ "$second" = 5 ]; then sleep 5; else sleep 10; fi
            printf 'during_trial_second=%s\n' "$second"
            cat "$PARAM/status"
            /data/local/tmp/extbridge-drm-read-current
            /data/local/tmp/extbridge-drm-read-modes | grep -E 'CONNECTOR|2560x1440' || true
        done
    fi
    # Kernel delayed work was armed before Configure; ADB is not the timer.
    i=0
    while [ "$i" -lt 30 ]; do
        phase=$(cat "$PARAM/phase")
        case "$phase" in 3|4|6) break ;; esac
        sleep 2
        i=$((i + 1))
    done
    printf 'after_deadline\n'
    cat "$PARAM/status"
    cat "$TRACE/trace"
    [ "$(cat "$PARAM/phase")" = 3 ]
fi
rmmod "$MODULE"
loaded=0
[ ! -d "/sys/module/$MODULE" ]
printf 'module_unloaded=1\ntaint_after=%s\n' "$(cat /proc/sys/kernel/tainted)"
sleep 2
/data/local/tmp/extbridge-drm-read-current
