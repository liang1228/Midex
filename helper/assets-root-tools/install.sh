# The APK passes its extracted assets as $bundle and the supported $fingerprint.
set -eu
[ "$(id -u)" = 0 ] || { echo '需要 Root 授权'; exit 1; }
[ "$(getprop ro.build.fingerprint)" = "$fingerprint" ] || { echo '固件不匹配'; exit 1; }
[ "$(uname -r)" = 6.18.21-android17-5-g284297031c53-abogki553682993-4k ] || { echo '内核不匹配'; exit 1; }

root=/data/adb/extbridge-external-refresh
main_files='external-refresh.sh drm-read-current live-mode-table extbridge_618_mode_cap.ko'
manual_files='manual-refresh.sh trace-dp-policy.sh manual-timing manual-selector drm-read-props'

verify_files() {
    expected=$1
    installed=$2
    shift 2
    [ -d "$installed" ] && [ ! -L "$installed" ] || exit 1
    for name do
        [ -f "$installed/$name" ] && [ ! -L "$installed/$name" ] && cmp -s "$expected/$name" "$installed/$name" || {
            echo "已有版本的 $name 与内置版本不同"
            exit 1
        }
    done
}

if [ -e "$root" ] || [ -L "$root" ]; then
    verify_files "$bundle" "$root" $main_files
    if [ -e "$root/manual-v1" ] || [ -L "$root/manual-v1" ]; then
        verify_files "$bundle/manual-v1" "$root/manual-v1" $manual_files
        exit 0
    fi
fi

umask 077
stage=$(mktemp -d /data/adb/extbridge-tools.XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
mkdir "$stage/manual-v1"
for name in $main_files; do cp "$bundle/$name" "$stage/$name"; done
for name in $manual_files; do cp "$bundle/manual-v1/$name" "$stage/manual-v1/$name"; done
verify_files "$bundle" "$stage" $main_files
verify_files "$bundle/manual-v1" "$stage/manual-v1" $manual_files
chmod 700 "$stage/drm-read-current" "$stage/live-mode-table" "$stage/manual-v1/manual-timing" \
    "$stage/manual-v1/manual-selector" "$stage/manual-v1/drm-read-props"
if [ -e "$root" ]; then
    mv -T "$stage/manual-v1" "$root/manual-v1"
else
    mv -T "$stage" "$root"
fi
