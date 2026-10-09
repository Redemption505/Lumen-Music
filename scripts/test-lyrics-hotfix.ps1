$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$fixtureRoot = Join-Path $projectRoot ("build/lyrics-hotfix-tests/" + [Guid]::NewGuid().ToString('N'))
$install = Join-Path $fixtureRoot 'installed'
$app = Join-Path $install 'app'
$package = Join-Path $fixtureRoot 'package'
$payloadDir = Join-Path $package 'payload'
$oldImage = Join-Path $projectRoot 'build/dist/20261001-112410-718/LumenMusic'
$jarName = 'lumen-desktop-0.1.1.jar'
New-Item -ItemType Directory -Force -Path $app, $payloadDir | Out-Null
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
function Invoke-Hotfix([string]$expected) {
    $ErrorActionPreference = 'Continue'
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot 'update/apply-lyrics-hotfix.ps1') `
        -InstallRoot $install -PackageRoot $package 2>&1
    $message = $output -join "`n"
    if ($expected) {
        Assert ($LASTEXITCODE -ne 0) "Expected refusal: $message"
        Assert ($message -match $expected) "Unexpected refusal: $message"
    } else {
        Assert ($LASTEXITCODE -eq 0) "Hotfix failed: $message"
    }
}
Copy-Item -LiteralPath (Join-Path $oldImage 'LumenMusic.exe') -Destination $install
Copy-Item -LiteralPath (Join-Path $oldImage 'app/LumenMusic.cfg') -Destination $app
Copy-Item -LiteralPath (Join-Path $oldImage "app/$jarName") -Destination $app
[IO.File]::WriteAllText((Join-Path $app '.package'), 'LumenMusic', [Text.Encoding]::UTF8)
$target = Join-Path $app $jarName
$payload = Join-Path $payloadDir $jarName
Copy-Item -LiteralPath $target -Destination $payload
[IO.File]::AppendAllText($payload, 'test-hotfix', [Text.Encoding]::ASCII)
$manifest = [ordered]@{
    version = '0.1.1'; jar = $jarName
    oldSha256 = Hash $target; newSha256 = Hash $payload
    configSha256 = Hash (Join-Path $app 'LumenMusic.cfg')
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $package 'manifest.json') -Encoding utf8
$oldHash = Hash $target
Assert ($oldHash -eq 'b98e67e53c62196f66d5e2bb1f92e284db2fc345d95d87512b55d95d0cb2b3ba') 'Baseline JAR changed'

[IO.File]::AppendAllText($payload, 'damaged', [Text.Encoding]::ASCII)
Invoke-Hotfix 'SHA-256'
Assert ((Hash $target) -eq $oldHash) 'Damaged package changed installation'
Copy-Item -LiteralPath $target -Destination $payload -Force
[IO.File]::AppendAllText($payload, 'test-hotfix', [Text.Encoding]::ASCII)

$runningExecutable = Join-Path $app 'python/python.exe'
New-Item -ItemType Directory -Force -Path (Split-Path $runningExecutable -Parent) | Out-Null
Copy-Item -LiteralPath (Join-Path $env:SystemRoot 'System32/cmd.exe') -Destination $runningExecutable
$process = Start-Process -FilePath $runningExecutable -ArgumentList '/k' -WindowStyle Hidden -PassThru
try {
    Start-Sleep -Milliseconds 500
    Invoke-Hotfix '仍在运行'
    Assert ((Hash $target) -eq $oldHash) 'Running process changed installation'
} finally { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }

[IO.File]::AppendAllText($target, 'other-build', [Text.Encoding]::ASCII)
Invoke-Hotfix '拒绝覆盖'
Copy-Item -LiteralPath (Join-Path $oldImage "app/$jarName") -Destination $target -Force

$config = Join-Path $app 'LumenMusic.cfg'
[IO.File]::AppendAllText($config, 'other-config', [Text.Encoding]::ASCII)
Invoke-Hotfix '启动配置'
Copy-Item -LiteralPath (Join-Path $oldImage 'app/LumenMusic.cfg') -Destination $config -Force

Invoke-Hotfix ''
Assert ((Hash $target) -eq $manifest.newSha256) 'New JAR differs'
$backups = @(Get-ChildItem -LiteralPath $install -Directory -Filter 'update-backup-v0.1.1-lyrics-*')
Assert ($backups.Count -eq 1) 'Expected one backup directory'
Assert ((Hash (Join-Path $backups[0].FullName $jarName)) -eq $oldHash) 'Backup differs'
Invoke-Hotfix ''
Assert (@(Get-ChildItem -LiteralPath $install -Directory -Filter 'update-backup-v0.1.1-lyrics-*').Count -eq 1) 'Idempotent run added backup'
Write-Output "Lyrics hotfix tests passed: $fixtureRoot"
