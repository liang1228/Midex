#!/system/bin/sh

# SurfaceFlinger may already be running by the late-load stage.
MODDIR=${0%/*}
: > "$MODDIR/skip_mount" || exit 1
rm -f "$MODDIR/arm-once"
