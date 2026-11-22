#!/system/bin/sh

[ "$(getprop ro.product.device)" = xuanyuan ] || abort 'This preview requires Xiaomi 15 Ultra (xuanyuan).'
[ "$(getprop ro.build.version.incremental)" = OS4.0.3.0.XOACNXM ] || abort 'Unsupported firmware build.'
[ -f /data/adb/metamodule/metamount.sh ] && [ ! -f /data/adb/metamodule/disable ] \
    || abort 'Enable a KernelSU OverlayFS metamodule first.'
stock=$(sha256sum /system_ext/lib64/libsurfaceflinger.so) || abort 'Cannot read the stock library.'
[ "${stock%% *}" = fe89bbd525f33fb806d532135458d090c88798f50ddfc6368bd09212a1bd03d6 ] \
    || abort 'Stock SurfaceFlinger hash differs.'

library="$MODPATH/system/system_ext/lib64/libsurfaceflinger.so"
[ -s "$library" ] || abort 'Preview library is missing.'
set_perm "$library" 0 0 0644 u:object_r:system_lib_file:s0 || abort 'Could not set preview library permissions.'
# meta-overlayfs uses skip_mount at install time to decide whether to stage the payload.
rm -f "$MODPATH/skip_mount" && : > "$MODPATH/arm-once" || abort 'Could not arm the preview.'
ui_print 'Preview armed for the next eligible boot. The following boot uses stock.'
