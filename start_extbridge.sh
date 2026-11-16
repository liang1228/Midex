#!/system/bin/sh
am broadcast -a com.extbridge.daemon.SHUTDOWN >/dev/null 2>&1
sleep 1
for p in $(pgrep -f "com\.extbridge\.daemon\.ExtBridge|ExtBridge"); do
    kill "$p" 2>/dev/null
done
sleep 1

appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null
appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null
appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null
pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null
dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1

export CLASSPATH=/data/local/tmp/extbridge.jar
nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >/data/local/tmp/extbridge.log 2>&1 </dev/null &
sleep 2
cat /data/local/tmp/extbridge.log
