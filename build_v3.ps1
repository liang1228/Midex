$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
    $candidates = @(
        Get-ChildItem "$env:USERPROFILE/AndroidStudioProjects/.jdks/jdk-*" -Directory -ErrorAction SilentlyContinue
        Get-ChildItem "C:/Program Files/Microsoft/jdk-*" -Directory -ErrorAction SilentlyContinue
        Get-ChildItem "C:/Program Files/Java/jdk-*" -Directory -ErrorAction SilentlyContinue
    ) | Sort-Object Name -Descending
    $jdk = $candidates | Where-Object { Test-Path "$($_.FullName)/bin/javac.exe" } | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME to JDK 21+." }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk" }
$keystore = Join-Path $repo ".extbridge-debug.keystore"
if (-not (Test-Path -LiteralPath $keystore)) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $keystore -storetype PKCS12 -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=ExtBridge Debug,O=ExtBridge,C=US"
    if ($LASTEXITCODE) { throw "Debug keystore generation failed." }
}
Push-Location $repo
try {
    & ./gradlew.bat :helper:exportDebug @args
    if ($LASTEXITCODE) { throw "Gradle build failed ($LASTEXITCODE)." }
} finally { Pop-Location }
Write-Host "APK: $repo/build/out/exthelper.apk"
Write-Host "Daemon: $repo/build/out/extbridge.jar"
