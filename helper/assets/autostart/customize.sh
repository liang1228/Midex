# KernelSU manager installer. Install the companion APK first.
[ "$API" -ge 33 ] || abort "Android 13 or newer is required"
APK=$(pm path com.extbridge.helper 2>/dev/null)
[ -n "$APK" ] || abort "Install the ExtBridge APK first"
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/extbridge.jar" 0 0 0644
touch "$MODPATH/skip_mount"
rm -f /data/adb/service.d/99-extbridge.sh
ui_print "ExtBridge plug-and-play module installed. Takes effect after reboot."
