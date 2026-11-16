$ErrorActionPreference = "Stop"

# Auto-detect JDK (11+ required)
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $jdkCandidates = @(
        Get-ChildItem -Path "C:\Program Files\Microsoft\jdk-*" -ErrorAction SilentlyContinue
        Get-ChildItem -Path "C:\Program Files\Eclipse Adoptium\jdk-*" -ErrorAction SilentlyContinue
        Get-ChildItem -Path "C:\Program Files\Java\jdk-*" -ErrorAction SilentlyContinue
    ) | Sort-Object Name -Descending
    foreach ($cand in $jdkCandidates) {
        if (Test-Path (Join-Path $cand.FullName "bin\javac.exe")) {
            $env:JAVA_HOME = $cand.FullName
            break
        }
    }
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    throw "JDK not found. Please install JDK 17+ and set JAVA_HOME."
}

# Auto-detect Android SDK
$SDK = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $SDK) {
    throw "Android SDK not found. Please set ANDROID_HOME or ANDROID_SDK_ROOT."
}

$BT = Get-ChildItem -Path (Join-Path $SDK "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $BT -or -not (Test-Path (Join-Path $BT "aapt2.exe"))) {
    throw "Android SDK build-tools (with aapt2.exe) not found under $SDK\build-tools"
}

$ANDROID_JAR = Get-ChildItem -Path (Join-Path $SDK "platforms") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^android-(\d+)$' -and [int]$Matches[1] -ge 30 } |
    Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName "android.jar" } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
if (-not $ANDROID_JAR) {
    throw "Android SDK platform (API 30+) android.jar not found under $SDK\platforms"
}

$env:PATH = "$($env:JAVA_HOME)\bin;$BT;$env:PATH"
Write-Host "Using JDK: $env:JAVA_HOME"
Write-Host "Using Build-Tools: $BT"
Write-Host "Using android.jar: $ANDROID_JAR"

$V3 = Split-Path -Parent $MyInvocation.MyCommand.Path
$BUILD = Join-Path $V3 "build"
if (Test-Path $BUILD) { Remove-Item -Recurse -Force $BUILD }
New-Item -ItemType Directory -Path "$BUILD\bridge_classes" | Out-Null
New-Item -ItemType Directory -Path "$BUILD\helper_res_zip" | Out-Null
New-Item -ItemType Directory -Path "$BUILD\helper_gen" | Out-Null
New-Item -ItemType Directory -Path "$BUILD\helper_classes" | Out-Null
New-Item -ItemType Directory -Path "$BUILD\helper_dex" | Out-Null
New-Item -ItemType Directory -Path "$BUILD\out" | Out-Null

Write-Host "=== 1. Compiling ExtBridge daemon -> extbridge.jar ==="
& javac -encoding UTF-8 --release 11 -cp $ANDROID_JAR -d "$BUILD\bridge_classes" "$V3\bridge\src\com\extbridge\daemon\ExtBridge.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$bridgeClasses = Get-ChildItem -Recurse -Filter "*.class" "$BUILD\bridge_classes" | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --min-api 30 --output "$BUILD\out\extbridge.jar" @bridgeClasses
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== 2. Compiling Android resources with aapt2 ==="
& "$BT\aapt2.exe" compile --dir "$V3\helper\res" -o "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& "$BT\aapt2.exe" link `
    -o "$BUILD\unaligned.apk" `
    --manifest "$V3\helper\AndroidManifest.xml" `
    -I $ANDROID_JAR `
    --min-sdk-version 30 `
    --target-sdk-version 35 `
    --java "$BUILD\helper_gen" `
    "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== 3. Compiling Helper Java sources ==="
$javaFiles = @(Get-ChildItem -Recurse -Filter "*.java" "$V3\helper\src" | ForEach-Object { $_.FullName })
$javaFiles += @(Get-ChildItem -Recurse -Filter "*.java" "$BUILD\helper_gen" | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 11 -cp $ANDROID_JAR -d "$BUILD\helper_classes" @javaFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$helperClasses = Get-ChildItem -Recurse -Filter "*.class" "$BUILD\helper_classes" | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --min-api 30 --lib $ANDROID_JAR --output "$BUILD\helper_dex" @helperClasses
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== 4. Packaging, aligning, and signing exthelper.apk ==="
python -c "
import zipfile, os
b = r'$BUILD'
root = r'$V3'
src = os.path.join(b, 'unaligned.apk')
dst = os.path.join(b, 'withdex.apk')
dex = os.path.join(b, 'helper_dex', 'classes.dex')
jar = os.path.join(b, 'out', 'extbridge.jar')
libadb = os.path.join(root, 'helper', 'jniLibs', 'arm64-v8a', 'libadb.so')
if not os.path.exists(libadb):
    raise FileNotFoundError('Missing native adb binary: ' + libadb)
with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(dst, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename in ('classes.dex', 'assets/extbridge.jar', 'lib/arm64-v8a/libadb.so'): continue
        comp = zipfile.ZIP_STORED if item.filename.endswith(('.arsc', '.png')) else zipfile.ZIP_DEFLATED
        zout.writestr(item.filename, zin.read(item.filename), compress_type=comp)
    zout.write(dex, 'classes.dex', compress_type=zipfile.ZIP_STORED)
    zout.write(jar, 'assets/extbridge.jar', compress_type=zipfile.ZIP_DEFLATED)
    zout.write(libadb, 'lib/arm64-v8a/libadb.so', compress_type=zipfile.ZIP_DEFLATED)
print('Packed classes.dex + assets/extbridge.jar + libadb.so OK')
"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& "$BT\zipalign.exe" -f -p 4 "$BUILD\withdex.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$customKs = Join-Path $V3 "exthelper.p12"
if ($env:EXTBRIDGE_KEYSTORE -and (Test-Path $env:EXTBRIDGE_KEYSTORE)) {
    $ksPath = $env:EXTBRIDGE_KEYSTORE
    $ksPass = if ($env:EXTBRIDGE_KEYSTORE_PASS) { $env:EXTBRIDGE_KEYSTORE_PASS } else { "extbridge" }
} elseif (Test-Path $customKs) {
    $ksPath = $customKs
    $ksPass = "extbridge"
} else {
    $ksPath = Join-Path $BUILD "debug.keystore"
    $ksPass = "android"
    Write-Host "No local exthelper.p12 found; generating temporary debug keystore..."
    & keytool -genkeypair -v -keystore $ksPath -storetype PKCS12 -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $ksPass -keypass $ksPass -dname "CN=ExtBridge Debug,O=ExtBridge,C=US" | Out-Null
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

& "$BT\apksigner.bat" sign `
    --ks $ksPath `
    --ks-type PKCS12 `
    --ks-pass "pass:$ksPass" `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out "$BUILD\out\exthelper.apk" `
    "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== BUILD COMPLETE ==="
Get-ChildItem "$BUILD\out" | Select-Object Name, Length
