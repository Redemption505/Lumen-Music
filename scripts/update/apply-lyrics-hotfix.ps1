param(
    [string]$InstallRoot,
    [string]$PackageRoot = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
if (-not $InstallRoot) {
    $suggested = Join-Path $env:LOCALAPPDATA 'LumenMusic'
    $answer = Read-Host "Lumen Music 安装目录（回车使用 $suggested）"
    $InstallRoot = if ($answer) { $answer.Trim('"') } else { $suggested }
}
$InstallRoot = (Resolve-Path -LiteralPath $InstallRoot -ErrorAction Stop).Path.TrimEnd('\')
$PackageRoot = (Resolve-Path -LiteralPath $PackageRoot -ErrorAction Stop).Path
$app = Join-Path $InstallRoot 'app'
$launcher = Join-Path $InstallRoot 'LumenMusic.exe'
$config = Join-Path $app 'LumenMusic.cfg'
$jarName = 'lumen-desktop-0.1.1.jar'
$target = Join-Path $app $jarName
$payload = Join-Path $PackageRoot "payload/$jarName"
$manifestPath = Join-Path $PackageRoot 'manifest.json'
$expectedOldJar = 'b98e67e53c62196f66d5e2bb1f92e284db2fc345d95d87512b55d95d0cb2b3ba'
$expectedConfig = '881af68dd7cd2aa6c8a86220af1b65d2e4fcfd779020b151af69283e80651e29'
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
foreach ($path in @($InstallRoot, $app, $target)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "不是有效的 0.1.1 安装目录：$InstallRoot" }
    if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "安装路径不能经过重解析点：$path"
    }
}
foreach ($path in @($launcher, $config)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "不是有效的 0.1.1 安装目录：$InstallRoot" }
}
if (-not ((Test-Path -LiteralPath (Join-Path $app '.package') -PathType Leaf) -or
           (Test-Path -LiteralPath (Join-Path $app '.jpackage.xml') -PathType Leaf))) {
    throw "不是有效的 0.1.1 安装目录：$InstallRoot"
}
if (([Diagnostics.FileVersionInfo]::GetVersionInfo($launcher)).ProductVersion -ne '0.1.1' -or
    (Hash $config) -ne $expectedConfig) {
    throw '启动器版本或启动配置不是原版 0.1.1。'
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw '修复包缺少清单。' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.version -ne '0.1.1' -or $manifest.jar -cne $jarName -or
    $manifest.oldSha256 -cne $expectedOldJar -or $manifest.configSha256 -cne $expectedConfig -or
    [string]$manifest.newSha256 -cnotmatch '^[0-9a-f]{64}$') {
    throw '修复包清单不匹配。'
}
if (-not (Test-Path -LiteralPath $payload -PathType Leaf) -or
    (Hash $payload) -ne $manifest.newSha256) { throw '修复包 JAR SHA-256 校验失败。' }
$running = @(Get-Process | Where-Object {
    $executable = try { $_.Path } catch { $null }
    $executable -and $executable.StartsWith($InstallRoot + '\', [StringComparison]::OrdinalIgnoreCase)
})
if ($running.Count) { throw 'Lumen Music 或其内置服务仍在运行，请退出后重试。' }
$currentHash = Hash $target
if ($currentHash -eq $manifest.newSha256) {
    Write-Output "已安装此歌词修复，无需重复更新：$InstallRoot"
    return
}
if ($currentHash -ne $expectedOldJar) { throw '现有 0.1.1 JAR 与原版不匹配，拒绝覆盖。' }
$backupDir = Join-Path $InstallRoot ("update-backup-v0.1.1-lyrics-" +
    (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $backupDir | Out-Null
$backup = Join-Path $backupDir $jarName
Copy-Item -LiteralPath $target -Destination $backup
if ((Hash $backup) -ne $expectedOldJar) { throw "旧版 JAR 备份校验失败：$backup" }
$temporary = Join-Path $app ("$jarName.hotfix-" + [Guid]::NewGuid().ToString('N'))
$replaced = $false
try {
    Copy-Item -LiteralPath $payload -Destination $temporary
    if ((Hash $temporary) -ne $manifest.newSha256) { throw '临时 JAR 校验失败。' }
    [IO.File]::Replace($temporary, $target, (Join-Path $backupDir 'replaced-original.jar'))
    $replaced = $true
    if ((Hash $target) -ne $manifest.newSha256) { throw '安装后 JAR 校验失败。' }
    Write-Output "歌词修复完成：$InstallRoot"
    Write-Output "原版 JAR 备份：$backup"
} catch {
    $failure = $_
    if ($replaced) {
        $rollback = Join-Path $app ("$jarName.rollback-" + [Guid]::NewGuid().ToString('N'))
        try {
            Copy-Item -LiteralPath $backup -Destination $rollback
            [IO.File]::Replace($rollback, $target, (Join-Path $backupDir 'failed-hotfix.jar'))
        } finally {
            if (Test-Path -LiteralPath $rollback) { Remove-Item -LiteralPath $rollback -Force }
        }
    }
    throw "歌词修复失败；原版 JAR 备份位于 $backup。原因：$failure"
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
