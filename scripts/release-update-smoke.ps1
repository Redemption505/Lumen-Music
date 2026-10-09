param(
    [Parameter(Mandatory)][string]$OldAppHome,
    [Parameter(Mandatory)][string]$PackageRoot,
    [switch]$MsiLayout
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$root = Join-Path $projectRoot ('build/release-update-smoke/' + [Guid]::NewGuid().ToString('N'))
$install = Join-Path $root 'LumenMusic'
$profile = Join-Path $root 'personal-data'
New-Item -ItemType Directory -Path $root, $profile | Out-Null
Copy-Item -LiteralPath (Resolve-Path -LiteralPath $OldAppHome).Path -Destination $install -Recurse
if ($MsiLayout) {
    Move-Item -LiteralPath (Join-Path $install 'app/.jpackage.xml') -Destination (Join-Path $root 'old-image-state.xml')
    [IO.File]::WriteAllText((Join-Path $install 'app/.package'), 'LumenMusic', [Text.Encoding]::UTF8)
}
foreach ($name in @('library.json', 'history.json', 'preferences.properties', 'playback-state.xml', 'background.png')) {
    [IO.File]::WriteAllBytes((Join-Path $profile $name), [Text.Encoding]::UTF8.GetBytes("preserve-$name"))
}
function Snapshot([string]$path) {
    $result = @{}
    foreach ($file in Get-ChildItem -LiteralPath $path -Recurse -File) {
        $relative = $file.FullName.Substring($path.Length).TrimStart('\')
        $result[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    return $result
}
$before = Snapshot $install
$personal = Snapshot $profile
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PackageRoot 'apply-update.ps1') `
    -InstallRoot $install -PackageRoot $PackageRoot
if ($LASTEXITCODE -ne 0) { throw 'Real 0.1.0 image update failed' }
$manifest = Get-Content -LiteralPath (Join-Path $PackageRoot 'manifest.json') -Raw | ConvertFrom-Json
$changed = @($manifest.files | ForEach-Object {
    if ($_.scope -eq 'root') { [string]$_.path }
    else { 'app\' + ([string]$_.path).Replace('/', '\') }
})
$after = Snapshot $install
foreach ($relative in $before.Keys) {
    if ($relative -eq 'app\lumen-desktop-0.1.0.jar' -or $relative -in $changed) { continue }
    if ($after[$relative] -ne $before[$relative]) { throw "Unrelated installation file changed: $relative" }
}
$personalAfter = Snapshot $profile
foreach ($relative in $personal.Keys) {
    if ($personalAfter[$relative] -ne $personal[$relative]) { throw "Personal fixture changed: $relative" }
}
if (Test-Path -LiteralPath (Join-Path $install 'app/lumen-desktop-0.1.0.jar')) { throw 'Old JAR remains on classpath' }
[xml]$state = Get-Content -LiteralPath (Join-Path $install 'app/.jpackage.xml') -Raw
if ($state.'jpackage-state'.'app-version' -ne '0.1.1') { throw 'Image version not updated' }
if ((Get-Item -LiteralPath (Join-Path $install 'LumenMusic.exe')).VersionInfo.ProductVersion -ne '0.1.1') {
    throw 'Native launcher product version not updated'
}
& (Join-Path $PSScriptRoot 'release-runtime-smoke.ps1') -AppHome $install
Write-Output "PASS: real old image updated, unrelated files and personal fixtures preserved, native updated launcher started"
Write-Output "Updated image: $install"
