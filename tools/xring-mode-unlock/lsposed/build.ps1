param(
    [string]$JavaHome = 'C:\Users\zeooon3\AndroidStudioProjects\.jdks\jdk-21.0.11+10',
    [string]$Sdk = 'C:\Users\zeooon3\AppData\Local\Android\Sdk',
    [switch]$Hbr3Trial
)
$ErrorActionPreference = 'Stop'
$module = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $module '..\..\..')).Path
$project = Split-Path $repo -Parent
$out = Join-Path $project 'outputs\refresh-unlock-build'
$apk = Join-Path $project 'outputs\ExtBridge-DisplayWindowExtension-0.4.0.apk'
if ($Hbr3Trial) {
    $module = Join-Path (Split-Path $module -Parent) 'hbr3-lsposed'
    $out = Join-Path $project 'outputs\hbr3-120-build'
    $apk = Join-Path $project 'outputs\ExtBridge-HBR3-2K120-trial.apk'
}
$toolsDir = Join-Path $Sdk 'build-tools\36.1.0'
$android = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$api = Join-Path $repo 'lsposed\libs\api-82.jar'
$keystore = Join-Path $repo '.extbridge-debug.keystore'
if (-not (Test-Path $keystore)) { throw 'Existing ExtBridge debug keystore required.' }
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
New-Item -ItemType Directory -Force -Path "$out\classes", "$out\dex" | Out-Null
$sourceDirs = @("$module\src")
if (Test-Path "$module\shared") { $sourceDirs += "$module\shared" }
$sources = @(Get-ChildItem -LiteralPath $sourceDirs -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$JavaHome\bin\javac.exe" --release 8 -encoding UTF-8 -cp "$android;$api" -d "$out\classes" @sources
if ($LASTEXITCODE) { throw 'javac failed' }
& "$JavaHome\bin\jar.exe" --create --file "$out\classes.jar" -C "$out\classes" .
if ($LASTEXITCODE) { throw 'jar failed' }
& "$toolsDir\d8.bat" --min-api 33 --lib $android --classpath $api --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE) { throw 'd8 failed' }
& "$toolsDir\aapt.exe" package -f --min-sdk-version 33 --target-sdk-version 36 -M "$module\AndroidManifest.xml" -S "$module\res" -A "$module\assets" -I $android -F "$out\unsigned.apk"
if ($LASTEXITCODE) { throw 'aapt failed' }
Push-Location "$out\dex"
try { & "$toolsDir\aapt.exe" add "$out\unsigned.apk" classes.dex } finally { Pop-Location }
if ($LASTEXITCODE) { throw 'dex packaging failed' }
& "$toolsDir\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }
& "$toolsDir\apksigner.bat" sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'APK signing failed' }
& "$toolsDir\apksigner.bat" verify $apk
if ($LASTEXITCODE) { throw 'APK verification failed' }
Write-Output $apk
