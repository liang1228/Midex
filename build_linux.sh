#!/usr/bin/env bash
# ExtBridge Linux/ARM64 本机构建脚本（无需 Gradle）
#
# 依赖:
#   - JDK 17+ (javac/java/keytool)
#   - Android SDK build-tools（需要 aapt2 / d8 / apksigner / zipalign）
#   - ARM64 主机上运行官方 x86_64 aapt2 时，需要 qemu-x86_64-static + x86_64 sysroot
#
# 用法:
#   ./build_linux.sh
#   ANDROID_HOME=/path/to/sdk AAPT2_SYSROOT=/path/to/x86_64-root ./build_linux.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/root/android-sdk}}"
AJ="$SDK/platforms/android-36/android.jar"
BT="$SDK/build-tools/35.0.1"
if [ ! -f "$AJ" ]; then
    AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ ! -x "$BT/aapt2" ]; then
    BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
    BT="${BT%/}"
fi
if [ -z "${AJ:-}" ] || [ ! -f "$AJ" ]; then
    echo "找不到 android.jar，请设置 ANDROID_HOME (当前: $SDK)" >&2
    exit 1
fi
if [ -z "${BT:-}" ] || [ ! -f "$BT/d8" ]; then
    echo "找不到 build-tools (aapt2/d8)，请设置 ANDROID_HOME (当前: $SDK)" >&2
    exit 1
fi

BUILD="$ROOT/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/bridge_classes" "$BUILD/helper_classes" "$BUILD/gen" "$BUILD/dex" "$BUILD/out"

# aapt2: 优先本机可执行；否则用 qemu + x86_64 sysroot 运行官方 x86_64 版
if "$BT/aapt2" version >/dev/null 2>&1; then
    AAPT2=("$BT/aapt2")
elif command -v qemu-x86_64-static >/dev/null 2>&1; then
    SYSROOT="${AAPT2_SYSROOT:-/root/x86_64-root}"
    if [ ! -d "$SYSROOT" ]; then
        echo "官方 aapt2 是 x86_64，需要 qemu sysroot: $SYSROOT" >&2
        echo "可设置 AAPT2_SYSROOT 指向一个 x86_64 Ubuntu base 根目录" >&2
        exit 1
    fi
    AAPT2=(qemu-x86_64-static -L "$SYSROOT" "$BT/aapt2")
else
    echo "aapt2 无法执行，且未安装 qemu-x86_64-static" >&2
    exit 1
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
"${JAVA_BIN}javac" -version >/dev/null

echo "=== 1/6 编译 daemon + DriverProbe -> extbridge.jar ==="
"${JAVA_BIN}javac" -encoding UTF-8 --release 11 -cp "$AJ" \
    -d "$BUILD/bridge_classes" \
    "$ROOT/bridge/src/com/extbridge/daemon/ExtBridge.java" \
    "$ROOT/bridge/src/com/extbridge/probe/DriverProbe.java"
find "$BUILD/bridge_classes" -name '*.class' > "$BUILD/bridge.list"
"$BT/d8" --min-api 30 --output "$BUILD/out/extbridge.jar" @"$BUILD/bridge.list"

echo "=== 2/6 编译资源 (aapt2) ==="
"${AAPT2[@]}" compile --dir "$ROOT/helper/res" -o "$BUILD/res.zip"
"${AAPT2[@]}" link -o "$BUILD/unaligned.apk" \
    --manifest "$ROOT/helper/AndroidManifest.xml" \
    -I "$AJ" --min-sdk-version 30 --target-sdk-version 36 \
    --java "$BUILD/gen" "$BUILD/res.zip"

echo "=== 3/6 编译 helper + LSPosed 模块 ==="
XPAPI="$ROOT/lsposed/libs/api-82.jar"
if [ ! -f "$XPAPI" ]; then
    echo "缺少 Xposed API: $XPAPI" >&2
    exit 1
fi
HELPER_SRC=("$ROOT"/helper/src/com/extbridge/helper/*.java)
"${JAVA_BIN}javac" -encoding UTF-8 --release 11 -cp "$AJ:$XPAPI:$BUILD/bridge_classes" \
    -d "$BUILD/helper_classes" \
    "${HELPER_SRC[@]}" \
    "$ROOT/lsposed/src/com/extbridge/lsposed/ExtBridgeModule.java" \
    "$BUILD/gen/com/extbridge/helper/R.java"
# 把 daemon/probe 类一并放进 classes.dex，供 LSPosed 注入 system_server 时直接加载
find "$BUILD/helper_classes" "$BUILD/bridge_classes" -name '*.class' > "$BUILD/helper.list"
"$BT/d8" --min-api 30 --lib "$AJ" --output "$BUILD/dex" @"$BUILD/helper.list"

echo "=== 4/6 打包 APK ==="
python3 - "$BUILD" "$ROOT" <<'PY'
import os, sys, zipfile
build, root = sys.argv[1], sys.argv[2]
src = os.path.join(build, 'unaligned.apk')
dst = os.path.join(build, 'withdex.apk')
dex = os.path.join(build, 'dex', 'classes.dex')
jar = os.path.join(build, 'out', 'extbridge.jar')
libadb = os.path.join(root, 'helper', 'jniLibs', 'arm64-v8a', 'libadb.so')
xposed_init = os.path.join(root, 'helper', 'assets', 'xposed_init')
with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(dst, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename in ('classes.dex', 'assets/extbridge.jar', 'lib/arm64-v8a/libadb.so', 'assets/xposed_init'):
            continue
        comp = zipfile.ZIP_STORED if item.filename.endswith(('.arsc', '.png')) else zipfile.ZIP_DEFLATED
        zout.writestr(item.filename, zin.read(item.filename), compress_type=comp)
    zout.write(dex, 'classes.dex', compress_type=zipfile.ZIP_STORED)
    zout.write(jar, 'assets/extbridge.jar', compress_type=zipfile.ZIP_DEFLATED)
    zout.write(libadb, 'lib/arm64-v8a/libadb.so', compress_type=zipfile.ZIP_DEFLATED)
    with open(xposed_init, 'rb') as f:
        data = f.read()
    info = zipfile.ZipInfo('assets/xposed_init')
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zout.writestr(info, data)
print('packed classes.dex + assets/extbridge.jar + assets/xposed_init + libadb.so')
PY

zipalign -f -p 4 "$BUILD/withdex.apk" "$BUILD/aligned.apk"

echo "=== 5/6 签名 ==="
KS="$ROOT/exthelper.p12"
KSPASS="extbridge"
if [ ! -f "$KS" ]; then
    # 不要放在 build/ 下：build 每次都会重建，换签名会导致覆盖安装失败
    KS="$ROOT/.extbridge-debug.keystore"
    KSPASS="android"
    if [ ! -f "$KS" ]; then
        "${JAVA_BIN}keytool" -genkeypair -v -keystore "$KS" -storetype PKCS12 -alias androiddebugkey \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass "$KSPASS" -keypass "$KSPASS" \
            -dname "CN=ExtBridge Debug,O=ExtBridge,C=US" >/dev/null
    fi
fi
"${JAVA_BIN}java" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-type PKCS12 --ks-pass "pass:$KSPASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$BUILD/out/exthelper.apk" "$BUILD/aligned.apk"

echo "=== 6/6 完成 ==="
ls -lh "$BUILD/out/exthelper.apk" "$BUILD/out/extbridge.jar"
echo
echo "APK: $BUILD/out/exthelper.apk"
echo "守护进程 jar: $BUILD/out/extbridge.jar"
