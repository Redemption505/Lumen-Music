param([string]$InstalledHome = (Join-Path $env:LOCALAPPDATA 'LumenMusic'))

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$install = (Resolve-Path -LiteralPath $InstalledHome).Path.TrimEnd('\')
if (-not (Test-Path -LiteralPath (Join-Path $install 'LumenMusic.exe') -PathType Leaf)) {
    throw 'The selected directory is not a Lumen Music installation.'
}
if (@(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($install + '\', [StringComparison]::OrdinalIgnoreCase)
    }).Count) { throw 'Close Lumen Music before making the installation backup.' }
$root = Join-Path $projectRoot ('build/phase3-release-backup/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$sources = [ordered]@{
    installation = $install
    local = (Join-Path $env:LOCALAPPDATA 'Lumen Music')
    roaming = (Join-Path $env:APPDATA 'Lumen Music')
}
$inventory = @()
foreach ($scope in $sources.Keys) {
    $source = $sources[$scope]
    if (-not (Test-Path -LiteralPath $source -PathType Container)) { continue }
    $source = (Resolve-Path -LiteralPath $source).Path.TrimEnd('\')
    if (@(Get-ChildItem -LiteralPath $source -Recurse -Force | Where-Object {
            $_.Attributes -band [IO.FileAttributes]::ReparsePoint
        }).Count) { throw 'A backup directory contains a junction or symbolic link.' }
    $files = @(Get-ChildItem -LiteralPath $source -Recurse -File -Force)
    $before = @{}
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($source.Length + 1)
        $before[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $destination = Join-Path $root $scope
    Copy-Item -LiteralPath $source -Destination $destination -Recurse
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($source.Length + 1)
        $copy = Join-Path $destination $relative
        if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant() -cne $before[$relative] -or
            (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $before[$relative]) {
            throw "Backup verification failed in $scope."
        }
        $inventory += [pscustomobject]@{ scope = $scope; path = $relative.Replace('\', '/');
            size = $file.Length; sha256 = $before[$relative] }
    }
    Write-Output ("Verified {0} backup: {1} files" -f $scope, $files.Count)
}
[IO.File]::WriteAllText((Join-Path $root 'inventory.json'), ($inventory | ConvertTo-Json -Depth 4), [Text.Encoding]::UTF8)
Write-Output "Backup: $root"
