$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $projectRoot ("build/update-tests/" + [Guid]::NewGuid().ToString('N'))
$package = Join-Path $testRoot 'package'
$install = Join-Path $testRoot 'installed'
$app = Join-Path $install 'app'
New-Item -ItemType Directory -Force -Path $package, $app | Out-Null
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Put([string]$path, [string]$text) {
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    [IO.File]::WriteAllText($path, $text, [Text.Encoding]::UTF8)
}
function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
function Invoke-ExpectedFailure([string]$root, [string]$reason, [int]$failAfter = 0) {
    $failed = $false
    try {
        & (Join-Path $PSScriptRoot 'update/apply-update.ps1') -InstallRoot $root -PackageRoot $package -TestFailAfter $failAfter | Out-Null
    } catch {
        $failed = $true
        Assert ($_.Exception.Message -match $reason) "Unexpected refusal: $($_.Exception.Message)"
    }
    Assert $failed 'Expected update refusal'
}
$oldImage = Join-Path $projectRoot 'build/dist/20260930-164842-644/LumenMusic'
$newImage = Join-Path $projectRoot 'build/dist/20261001-112410-718/LumenMusic'
Copy-Item -LiteralPath (Join-Path $oldImage 'LumenMusic.exe') -Destination (Join-Path $install 'LumenMusic.exe')
$oldLauncherHash = Hash (Join-Path $install 'LumenMusic.exe')
Put (Join-Path $app '.package') 'MSI installation marker'
Put (Join-Path $app 'LumenMusic.cfg') 'app.classpath=$APPDIR\lumen-desktop-0.1.0.jar'
Put (Join-Path $app 'lumen-desktop-0.1.0.jar') 'old jar'
$entries = @()
$launcherPayload = Join-Path $package 'payload/root/LumenMusic.exe'
New-Item -ItemType Directory -Force -Path (Split-Path $launcherPayload -Parent) | Out-Null
Copy-Item -LiteralPath (Join-Path $newImage 'LumenMusic.exe') -Destination $launcherPayload
$entries += [ordered]@{ path = 'LumenMusic.exe'; scope = 'root'
    sha256 = Hash $launcherPayload; oldSha256 = $oldLauncherHash }
foreach ($item in @(
    @('lumen-desktop-0.1.1.jar', 'new jar'),
    @('LumenMusic.cfg', 'app.classpath=$APPDIR\lumen-desktop-0.1.1.jar'),
    @('.jpackage.xml', '<jpackage-state><app-version>0.1.1</app-version></jpackage-state>'))) {
    $relative = $item[0]
    $source = Join-Path (Join-Path $package 'payload') $relative
    Put $source $item[1]
    $previous = if ($relative -eq 'lumen-desktop-0.1.1.jar') { $null } else { Join-Path $app $relative }
    $oldHash = if ($relative -eq '.jpackage.xml') {
        Hash (Join-Path $oldImage 'app/.jpackage.xml')
    } elseif ($previous) { Hash $previous } else { $null }
    $entry = [ordered]@{ path = $relative; sha256 = Hash $source; oldSha256 = $oldHash }
    if ($relative -eq '.jpackage.xml') { $entry.optionalOld = $true }
    $entries += $entry
}
$manifest = [ordered]@{ fromVersion = '0.1.0'; toVersion = '0.1.1'
    oldJar = 'lumen-desktop-0.1.0.jar'; oldJarSha256 = Hash (Join-Path $app 'lumen-desktop-0.1.0.jar')
    files = $entries }
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $package 'manifest.json') -Encoding utf8

$badRoot = Join-Path $testRoot 'wrong'
New-Item -ItemType Directory -Path $badRoot | Out-Null
Invoke-ExpectedFailure $badRoot '不是有效的'
Assert (Test-Path (Join-Path $app 'lumen-desktop-0.1.0.jar')) 'Wrong path changed installation'

Put (Join-Path $package 'payload/lumen-desktop-0.1.1.jar') 'corrupted'
Invoke-ExpectedFailure $install 'SHA-256 校验失败'
Assert (Test-Path (Join-Path $app 'lumen-desktop-0.1.0.jar')) 'Corrupt payload changed installation'
Put (Join-Path $package 'payload/lumen-desktop-0.1.1.jar') 'new jar'

$runningExecutable = Join-Path $app 'python/python.exe'
New-Item -ItemType Directory -Force -Path (Split-Path $runningExecutable -Parent) | Out-Null
Copy-Item -LiteralPath (Join-Path $env:SystemRoot 'System32/cmd.exe') -Destination $runningExecutable
$process = Start-Process -FilePath $runningExecutable -ArgumentList '/k' -WindowStyle Hidden -PassThru
try {
    Start-Sleep -Milliseconds 500
    Invoke-ExpectedFailure $install '仍在运行'
    Assert (Test-Path (Join-Path $app 'lumen-desktop-0.1.0.jar')) 'Running app changed installation'
} finally { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }

Invoke-ExpectedFailure $install '测试注入的更新失败' 4
Assert (Test-Path (Join-Path $app 'lumen-desktop-0.1.0.jar')) 'Rollback omitted old JAR'
Assert (-not (Test-Path (Join-Path $app 'lumen-desktop-0.1.1.jar'))) 'Rollback retained new JAR'
Assert ((Hash (Join-Path $install 'LumenMusic.exe')) -eq $oldLauncherHash) 'Rollback changed launcher'
Assert ((Hash (Join-Path $app 'LumenMusic.cfg')) -eq $entries[2].oldSha256) 'Rollback changed cfg'
Assert (-not (Test-Path (Join-Path $app '.jpackage.xml'))) 'Rollback retained newly created version marker'
Assert (Test-Path (Join-Path $app '.package')) 'Rollback changed MSI marker'

& (Join-Path $PSScriptRoot 'update/apply-update.ps1') -InstallRoot $install -PackageRoot $package
Assert (-not (Test-Path (Join-Path $app 'lumen-desktop-0.1.0.jar'))) 'Old JAR remained in classpath folder'
Assert ((Hash (Join-Path $install 'LumenMusic.exe')) -eq $entries[0].sha256) 'New launcher differs'
Assert ((Hash (Join-Path $app 'lumen-desktop-0.1.1.jar')) -eq $entries[1].sha256) 'New JAR differs'
Assert (([xml](Get-Content (Join-Path $app '.jpackage.xml') -Raw)).'jpackage-state'.'app-version' -eq '0.1.1') 'Version state differs'
Write-Output "Update tests passed: $testRoot"
