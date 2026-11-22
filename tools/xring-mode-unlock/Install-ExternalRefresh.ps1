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
$fingerprint = 'Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys'
if ((Invoke-Device shell 'getprop ro.build.fingerprint') -ne $fingerprint) {
    throw 'This bundle does not match the device firmware.'
}
if ((Invoke-Device shell "su -c 'id -u'") -ne '0') { throw 'ADB shell must have Root access.' }

$files = 'drm-read-current', 'live-mode-table', 'extbridge_618_mode_cap.ko', 'external-refresh.sh'
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

$installed = Invoke-Device shell "su -c 'if [ -d $root ]; then echo yes; fi'"
if ($installed -eq 'yes') {
    Confirm-DeviceBundle $root
    Write-Output 'Matching bundle is already installed.'
    return
}

$nonce = [Guid]::NewGuid().ToString('N')
$upload = "/data/local/tmp/extbridge-upload-$nonce"
$private = "/data/adb/extbridge-stage-$nonce"
Invoke-Device shell "mkdir -m 700 $upload"
try {
    foreach ($name in $files) {
        Invoke-Device push (Join-Path $Bundle $name) "$upload/$name"
    }
    Invoke-Device shell "su -c 'umask 077 && mkdir -m 700 $private && cp $upload/* $private/ && chmod 700 $private/drm-read-current $private/live-mode-table && sh -n $private/external-refresh.sh'"
    Confirm-DeviceBundle $private
    Invoke-Device shell "su -c 'test ! -e $root && mv -T $private $root'"
    Write-Output 'ExtBridge external refresh is installed.'
} finally {
    $uploadedFiles = ($files | ForEach-Object { "$upload/$_" }) -join ' '
    $privateFiles = ($files | ForEach-Object { "$private/$_" }) -join ' '
    & $Adb -s $Serial shell "rm -f $uploadedFiles; rmdir $upload; su -c 'if [ -d $private ]; then rm -f $privateFiles && rmdir $private; fi'" | Out-Null
}
