param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_.:\-]+$')][string]$Serial,
    [string]$Bundle = $PSScriptRoot,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
function Invoke-Device {
    $output = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $output" }
    $output
}

$root = '/data/adb/extbridge-external-refresh'
$target = "$root/manual-v1"
$fingerprint = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys'
if ((Invoke-Device shell 'getprop ro.build.fingerprint') -ne $fingerprint) {
    throw 'This bundle does not match the device firmware.'
}
Invoke-Device shell "su -c 'test -x $root/drm-read-current'"
$files = 'manual-refresh.sh', 'trace-dp-policy.sh', 'manual-timing', 'manual-selector', 'drm-read-props'
$hashes = @{}
foreach ($name in $files) {
    $hashes[$name] = (Get-FileHash -LiteralPath (Join-Path $Bundle $name)).Hash.ToLowerInvariant()
}

function Confirm-DeviceBundle([string]$Directory) {
    foreach ($name in $files) {
        $line = Invoke-Device shell "su -c 'sha256sum $Directory/$name'"
        if (($line -split ' ')[0] -ne $hashes[$name]) { throw "Digest mismatch for $name at $Directory" }
    }
}

if ((Invoke-Device shell "su -c 'if [ -d $target ]; then echo yes; fi'") -eq 'yes') {
    Confirm-DeviceBundle $target
    Write-Output 'Matching bundle is already installed.'
    return
}

$nonce = [Guid]::NewGuid().ToString('N')
$upload = "/data/local/tmp/extbridge-manual-$nonce"
$private = "$root/stage-$nonce"
Invoke-Device shell "mkdir -m 700 $upload"
try {
    foreach ($name in $files) {
        Invoke-Device push (Join-Path $Bundle $name) "$upload/$name"
    }
    Invoke-Device shell "su -c 'umask 077 && mkdir -m 700 $private && cp $upload/* $private/ && chmod 700 $private/manual-timing $private/manual-selector $private/drm-read-props && sh -n $private/manual-refresh.sh && sh -n $private/trace-dp-policy.sh'"
    Confirm-DeviceBundle $private
    Invoke-Device shell "su -c 'test ! -e $target && mv -T $private $target'"
    Write-Output 'ExtBridge custom refresh is installed.'
} finally {
    $uploadedFiles = ($files | ForEach-Object { "$upload/$_" }) -join ' '
    $privateFiles = ($files | ForEach-Object { "$private/$_" }) -join ' '
    & $Adb -s $Serial shell "rm -f $uploadedFiles; rmdir $upload; su -c 'if [ -d $private ]; then rm -f $privateFiles && rmdir $private; fi'" | Out-Null
}
