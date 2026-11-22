#!/bin/sh
# Build only against the already prepared official Android 17 / 6.18 DDK.
set -eu
[ "$#" = 2 ] || { echo 'Usage: build.sh PREPARED_DDK_ROOT NEW_OUTPUT_DIR' >&2; exit 1; }
ROOT=$(realpath "$1")
OUT=$(realpath -m "$2")
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
check_hash() {
    [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$2" ] || {
        echo "Unsupported prepared build input: $1" >&2
        exit 1
    }
}
check_hash "$ROOT/prepared/Module.symvers" 8ed8e33dd52d747365ed5bb52b78a0a7dca343609c9448a02b7684f41f68b122
check_hash "$ROOT/prepared/.config" be4310c05180a5e8d2fe4a645e21f3367cc1d0ebefed111b9f13e433a6c6a9f2
check_hash "$ROOT/common/Makefile" ca0c14974a2193a848f8c4169148ed395c4931e18b59843a80ee40d7349fdf74
grep -q '6.18.21-android17-5-g30c0eaeef78d-ab15549627-4k' "$ROOT/prepared/include/generated/utsrelease.h"
[ ! -e "$OUT/extbridge_618_observer.ko" ]
[ ! -e "$OUT/extbridge_618_lane_trial-v6.ko" ]
[ ! -e "$OUT/extbridge_618_mode_cap.ko" ]
mkdir -p "$OUT"
for part in observer lane_trial mode_cap; do
    mkdir -p "$ROOT/$part"
    cp "$SOURCE/$part/Makefile" "$SOURCE/$part/"*.c "$ROOT/$part/"
    if [ "$part" = lane_trial ]; then
        cp "$SOURCE/$part/vendor.symvers" "$ROOT/$part/"
        set -- "KBUILD_EXTRA_SYMBOLS=$ROOT/$part/vendor.symvers"
        artifact=extbridge_618_lane_trial
        destination=extbridge_618_lane_trial-v6
    else
        set --
        artifact=extbridge_618_$part
        destination=$artifact
    fi
    make -C "$ROOT/common" O="$ROOT/prepared" M="$ROOT/$part" "$@" \
        ARCH=arm64 LLVM=1 LLVM_IAS=1 \
        NM=aarch64-linux-gnu-nm AR=aarch64-linux-gnu-ar \
        OBJCOPY=aarch64-linux-gnu-objcopy OBJDUMP=aarch64-linux-gnu-objdump \
        READELF=aarch64-linux-gnu-readelf STRIP=aarch64-linux-gnu-strip modules
    cp "$ROOT/$part/$artifact.ko" "$OUT/$destination.ko"
    sha256sum "$OUT/$destination.ko"
done
