param([string]$JavaHome = 'C:\Users\zeooon3\AndroidStudioProjects\.jdks\jdk-21.0.11+10')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path (Split-Path $repo -Parent) 'outputs\refresh-capability-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& "$JavaHome\bin\javac.exe" --release 8 -encoding UTF-8 -d $out `
    "$repo\tools\xring-mode-unlock\lsposed\shared\com\extbridge\refreshunlock\RefreshUnlockContract.java" `
    "$repo\tools\xring-mode-unlock\lsposed\shared\com\extbridge\refreshunlock\WindowDecorationContract.java" `
    "$repo\helper\src\com\extbridge\helper\RefreshCapabilities.java" `
    "$PSScriptRoot\RefreshCapabilitiesTest.java"
if ($LASTEXITCODE) { throw 'Capability test compilation failed' }
& "$JavaHome\bin\java.exe" -cp $out RefreshCapabilitiesTest
if ($LASTEXITCODE) { throw 'Capability tests failed' }
