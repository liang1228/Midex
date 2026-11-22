#!/system/bin/sh
# KernelSU/Magisk late_start service; the daemon's DisplayListener handles hotplug.
MODDIR=${0%/*}
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then exit 0; fi
attempt=0
until [ "$(getprop sys.boot_completed)" = 1 ]; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 90 ] || exit 1
    sleep 2
done
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then exit 0; fi
[ "$(getprop extbridge.lsposed)" = 1 ] && exit 0
APK=$(pm path com.extbridge.helper | head -n 1 | cut -d: -f2-)
[ -f "$APK" ] || exit 1
# Refresh from the installed APK on boot, so an app update cannot keep old daemon code.
if unzip -p "$APK" assets/extbridge.jar > "$MODDIR/extbridge.jar.tmp" 2>/dev/null; then
    [ -s "$MODDIR/extbridge.jar.tmp" ] || exit 1
    chmod 644 "$MODDIR/extbridge.jar.tmp"
    mv -f "$MODDIR/extbridge.jar.tmp" "$MODDIR/extbridge.jar" || exit 1
else
    rm -f "$MODDIR/extbridge.jar.tmp"
    exit 1
fi
PID=$(cat /data/local/tmp/extbridge.pid 2>/dev/null)
case "$PID" in
    ''|*[!0-9]*) ;;
    *) if [ -r "/proc/$PID/cmdline" ] && tr '\000' '\n' < "/proc/$PID/cmdline" |
            grep -qx 'com.extbridge.daemon.ExtBridge'; then exit 0; fi ;;
esac
# No shell watcher survives this launch. The socket singleton also covers concurrent starts.
cd /data/local/tmp || exit 1
CLASSPATH="$MODDIR/extbridge.jar" nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge \
    </dev/null >>/data/local/tmp/extbridge.log 2>&1 &
