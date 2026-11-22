#!/system/bin/sh

MODDIR=${0%/*}
: > "$MODDIR/skip_mount" || exit 1
[ -f "$MODDIR/arm-once" ] || exit 0
rm "$MODDIR/arm-once" || exit 1
[ "$(getprop ro.product.device)" = xuanyuan ] || exit 0
[ "$(getprop ro.build.version.incremental)" = OS4.0.3.0.XOACNXM ] || exit 0
[ -f /data/adb/metamodule/metamount.sh ] && [ ! -f /data/adb/metamodule/disable ] || exit 0
if pidof surfaceflinger >/dev/null 2>&1; then exit 0; fi
stock=$(sha256sum /system_ext/lib64/libsurfaceflinger.so) || exit 1
[ "${stock%% *}" = fe89bbd525f33fb806d532135458d090c88798f50ddfc6368bd09212a1bd03d6 ] || exit 0
rm "$MODDIR/skip_mount"
