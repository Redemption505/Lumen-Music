param([ValidateSet('Backup','Verify')][string]$Mode = 'Backup', [string]$BackupRoot)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
if ($Mode -eq 'Backup') {
    if (-not $BackupRoot) { $BackupRoot = Join-Path $project ('build/local-lyrics-hotfix/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
    New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
    $records = @()
    foreach ($entry in @(@{Name='Local'; Path=(Join-Path $env:LOCALAPPDATA 'Lumen Music')}, @{Name='Roaming'; Path=(Join-Path $env:APPDATA 'Lumen Music')})) {
        if (-not (Test-Path -LiteralPath $entry.Path)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $entry.Path -File -Recurse) {
            $relative = $file.FullName.Substring($entry.Path.Length).TrimStart('\')
            if ($relative -match '(^|\\)(service|javafx-cache)(\\|$)' -or $relative -match '(instance|service).*\.(lock|log|pid)$') { continue }
            $records += [pscustomobject]@{Path=$file.FullName; Hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
            $copy = Join-Path (Join-Path $BackupRoot $entry.Name) $relative
            New-Item -ItemType Directory -Path (Split-Path $copy -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $copy
        }
    }
    $records | Export-Clixml -LiteralPath (Join-Path $BackupRoot 'personal-hashes.xml')
    Write-Output "Backed up $($records.Count) personal files: $BackupRoot"
} else {
    if (-not $BackupRoot) { throw 'BackupRoot is required for verification.' }
    $records = @(Import-Clixml -LiteralPath (Join-Path $BackupRoot 'personal-hashes.xml'))
    foreach ($record in $records) {
        if (-not (Test-Path -LiteralPath $record.Path) -or (Get-FileHash -LiteralPath $record.Path -Algorithm SHA256).Hash -ne $record.Hash) {
            throw "Personal file changed during update: $($record.Path)"
        }
    }
    Write-Output "PASS $($records.Count) personal files unchanged."
}
