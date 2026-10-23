param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$AppHome,
    [Parameter(Mandatory)][string]$BackupRoot,
    [string]$InstalledHome = (Join-Path $env:LOCALAPPDATA 'LumenMusic'),
    [switch]$Elevate,
    [switch]$SameVersionRepair,
    [switch]$SameVersionUpgrade,
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$FromVersion,
    [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$ToVersion
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$Installer = (Resolve-Path -LiteralPath $Installer).Path
$AppHome = (Resolve-Path -LiteralPath $AppHome).Path.TrimEnd('\')
$BackupRoot = (Resolve-Path -LiteralPath $BackupRoot).Path.TrimEnd('\')
$InstalledHome = (Resolve-Path -LiteralPath $InstalledHome).Path.TrimEnd('\')
$version = [Diagnostics.FileVersionInfo]::GetVersionInfo($Installer).ProductVersion
$projectVersion = [string]([xml][IO.File]::ReadAllText((Join-Path $projectRoot 'desktop/pom.xml'))).project.version
if (-not $ToVersion) { $ToVersion = $projectVersion }
if ($SameVersionRepair -and $SameVersionUpgrade) { throw 'Choose repair or major upgrade, not both.' }
if ($version -cne $ToVersion -or $ToVersion -cne $projectVersion -or [version]$ToVersion -lt [version]$FromVersion -or
    ([version]$ToVersion -eq [version]$FromVersion -and -not ($SameVersionRepair -or $SameVersionUpgrade)) -or
    [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $AppHome 'LumenMusic.exe')).ProductVersion -cne $ToVersion) {
    throw 'Installer, app-image and declared source/target versions differ'
}
$installedVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $InstalledHome 'LumenMusic.exe')).ProductVersion
if ($installedVersion -cne $FromVersion -and $installedVersion -cne $ToVersion) { throw 'Installed launcher is neither the declared source nor target version' }
$backupVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $BackupRoot 'installation/LumenMusic.exe')).ProductVersion
if ($backupVersion -cne $FromVersion) { throw 'Verified installation backup is not the declared source version' }
if (@(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstalledHome + '\', [StringComparison]::OrdinalIgnoreCase)
    }).Count) { throw 'Close Lumen Music before upgrading the installation' }
$inventory = @(foreach ($entry in (Get-Content -LiteralPath (Join-Path $BackupRoot 'inventory.json') -Raw | ConvertFrom-Json)) {
    $entry
})
$data = @($inventory | Where-Object { $_.scope -in @('local', 'roaming') })
if (-not $data.Count) { throw 'The verified personal-data backup is missing' }
function Check-Data {
    foreach ($entry in $data) {
        $base = if ($entry.scope -eq 'local') { Join-Path $env:LOCALAPPDATA 'Lumen Music' }
            else { Join-Path $env:APPDATA 'Lumen Music' }
        $path = Join-Path $base $entry.path
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            (Get-Item -LiteralPath $path).Length -ne $entry.size -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) {
            throw 'Personal data differs from the verified backup; no success claimed'
        }
    }
}
Check-Data
$root = Join-Path $projectRoot ('build/phase3-install-acceptance/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$log = Join-Path $root 'installer.log'
$arguments = @('/qn', '/norestart', '/L*v', ('"' + $log + '"'))
if ($SameVersionRepair) {
    $arguments += @('REINSTALL=ALL', 'REINSTALLMODE=vamus')
}
$start = @{ FilePath = $Installer; ArgumentList = $arguments; WindowStyle = 'Hidden'; PassThru = $true }
if ($Elevate) { $start.Verb = 'RunAs' }
$process = Start-Process @start
if (-not $process.WaitForExit(180000)) { throw "Installer still running; inspect $log" }
$process.Refresh()
if ($process.ExitCode -notin @(0, 3010)) { throw "Installer failed ($($process.ExitCode)); inspect $log" }
if ([Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $InstalledHome 'LumenMusic.exe')).ProductVersion -ne $version) {
    throw 'Installed native launcher version did not advance'
}
$checked = 0
foreach ($file in Get-ChildItem -LiteralPath $AppHome -Recurse -File -Force | Where-Object {
        $_.FullName -notmatch '[\\/]__pycache__[\\/]|\.pyc$|[\\/]\.jpackage\.xml$'
    }) {
    $relative = $file.FullName.Substring($AppHome.Length + 1)
    $installed = Join-Path $InstalledHome $relative
    if (-not (Test-Path -LiteralPath $installed -PathType Leaf) -or
        (Get-FileHash -LiteralPath $file.FullName).Hash -cne (Get-FileHash -LiteralPath $installed).Hash) {
        throw "Installed file differs from tested app-image: $relative"
    }
    $checked++
}
Check-Data
$registrations = @(foreach ($branch in @(
        'HKCU:/Software/Microsoft/Windows/CurrentVersion/Uninstall',
        'HKLM:/Software/Microsoft/Windows/CurrentVersion/Uninstall',
        'HKLM:/Software/WOW6432Node/Microsoft/Windows/CurrentVersion/Uninstall')) {
    Get-ChildItem $branch -ErrorAction SilentlyContinue | Get-ItemProperty -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'LumenMusic' -and $_.WindowsInstaller -eq 1 }
})
if ($registrations.Count -ne 1 -or $registrations[0].DisplayVersion -ne $version) {
    throw 'The installed application registration is missing, duplicated or still outdated'
}
if ($registrations[0].InstallLocation -and
    $registrations[0].InstallLocation.TrimEnd('\') -ne $InstalledHome) {
    throw 'The registered installation points to a different directory'
}
$msi = New-Object -ComObject WindowsInstaller.Installer
if ($msi.ProductState($registrations[0].PSChildName) -ne 5 -or
    $msi.ProductInfo($registrations[0].PSChildName, 'VersionString') -ne $version) {
    throw 'Windows Installer does not recognize the installed product and version'
}
$evidence = [ordered]@{
    sameVersionRepair = [bool]$SameVersionRepair
    sameVersionUpgrade = [bool]$SameVersionUpgrade
    fromVersion = $FromVersion; version = $version; installerExit = $process.ExitCode; installedFilesVerified = $checked
    personalFilesUnchanged = $data.Count
    mainJarSha256 = (Get-FileHash -LiteralPath (Join-Path $InstalledHome "app/lumen-desktop-$version.jar")).Hash.ToLowerInvariant()
    installerSha256 = (Get-FileHash -LiteralPath $Installer).Hash.ToLowerInvariant()
    backup = $BackupRoot; installedHome = $InstalledHome
    registeredProductCode = $registrations[0].PSChildName
}
[IO.File]::WriteAllText((Join-Path $root 'evidence.json'), ($evidence | ConvertTo-Json), [Text.Encoding]::UTF8)
Write-Output "PASS: installed v$version, $checked binary files match the tested app-image; $($data.Count) personal files unchanged"
Write-Output "Artifacts: $root"
