param(
    [string]$JavaHome = 'C:\Users\zeooon3\AndroidStudioProjects\.jdks\jdk-21.0.11+10'
)
$ErrorActionPreference = 'Stop'
$module = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = (Resolve-Path (Join-Path $module '..\..\..\..')).Path
$out = Join-Path $project 'outputs\touch-mode-guard-20261118\offline'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& "$JavaHome\bin\javac.exe" --release 8 -encoding UTF-8 -d $out `
    "$module\shared\com\extbridge\refreshunlock\ModeOverrideGuard.java" `
    "$PSScriptRoot\com\extbridge\refreshunlock\ModeOverrideGuardTest.java"
if ($LASTEXITCODE) { throw 'Mode guard test compilation failed' }
& "$JavaHome\bin\java.exe" -cp $out com.extbridge.refreshunlock.ModeOverrideGuardTest
if ($LASTEXITCODE) { throw 'Mode guard tests failed' }
