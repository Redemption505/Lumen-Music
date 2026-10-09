param(
    [string]$InstallRoot,
    [string]$PackageRoot = $PSScriptRoot,
    [int]$TestFailAfter = 0
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
$oldConfig = Join-Path $app 'LumenMusic.cfg'
$imageMarker = Join-Path $app '.jpackage.xml'
$installerMarker = Join-Path $app '.package'
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf) -or
    -not (Test-Path -LiteralPath $oldConfig -PathType Leaf) -or
    -not ((Test-Path -LiteralPath $imageMarker -PathType Leaf) -or
           (Test-Path -LiteralPath $installerMarker -PathType Leaf))) {
    throw "不是有效的 Lumen Music 安装目录：$InstallRoot"
}
if ((Get-Item -LiteralPath $app).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'app 目录不能是重解析点。'
}
if ((Get-Item -LiteralPath $InstallRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw '安装目录不能是重解析点。'
}
function Assert-NoReparse([string]$target, [string]$boundary) {
    $current = $target
    while ($current.Length -gt $boundary.Length) {
        if ((Test-Path -LiteralPath $current) -and
            ((Get-Item -LiteralPath $current).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "更新路径不能经过重解析点：$target"
        }
        $current = Split-Path $current -Parent
    }
}
if (([Diagnostics.FileVersionInfo]::GetVersionInfo($launcher)).ProductVersion -ne '0.1.0') {
    throw '原生启动器版本不是 0.1.0。'
}
if (Test-Path -LiteralPath $imageMarker -PathType Leaf) {
    $state = [xml](Get-Content -LiteralPath $imageMarker -Raw)
    if ([string]$state.'jpackage-state'.'app-version' -ne '0.1.0') {
        throw '安装镜像版本不是 0.1.0。'
    }
}
$manifest = Get-Content -LiteralPath (Join-Path $PackageRoot 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.fromVersion -ne '0.1.0' -or $manifest.toVersion -ne '0.1.1' -or
    $manifest.oldJar -ne 'lumen-desktop-0.1.0.jar') { throw '更新清单版本不匹配。' }
$oldJar = Join-Path $app $manifest.oldJar
$newJar = Join-Path $app 'lumen-desktop-0.1.1.jar'
if (-not (Test-Path -LiteralPath $oldJar -PathType Leaf) -or (Test-Path -LiteralPath $newJar)) {
    throw '未找到准确的 0.1.0 安装状态，或 0.1.1 已存在。'
}
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
if ((Hash $oldJar) -ne $manifest.oldJarSha256) { throw '旧版 JAR 校验失败。' }
$cfg = Get-Content -LiteralPath $oldConfig -Raw
if ($cfg -notmatch 'lumen-desktop-0\.1\.0\.jar' -or $cfg -match 'lumen-desktop-0\.1\.1\.jar') {
    throw '现有启动配置不是 0.1.0。'
}
$allowed = '^(lumen-desktop-0\.1\.1\.jar|LumenMusic\.cfg|\.jpackage\.xml|stream-proxy/lumen-stream-proxy\.exe|service/lumen_service/[A-Za-z_][A-Za-z_0-9]*\.py)$'
function GetScope($item) {
    if (-not $item.scope) { return 'app' }
    return [string]$item.scope
}
function GetTarget($item) {
    if ((GetScope $item) -eq 'root') { return (Join-Path $InstallRoot 'LumenMusic.exe') }
    return (Join-Path $app $item.path)
}
function GetPayload($item) {
    $relative = if ((GetScope $item) -eq 'root') { 'root/LumenMusic.exe' } else { [string]$item.path }
    return (Join-Path (Join-Path $PackageRoot 'payload') $relative)
}
function GetBackup($item) {
    $relative = if ((GetScope $item) -eq 'root') { 'root/LumenMusic.exe' } else { 'app/' + [string]$item.path }
    return (Join-Path $backup $relative)
}
$seen = @{}
$oldExists = @{}
foreach ($item in $manifest.files) {
    $relative = [string]$item.path
    $scope = GetScope $item
    $key = "$scope/$relative"
    $optionalOld = ($item.optionalOld -eq $true)
    if (($scope -eq 'root' -and $relative -cne 'LumenMusic.exe') -or
        ($scope -eq 'app' -and $relative -cnotmatch $allowed) -or
        ($scope -ne 'root' -and $scope -ne 'app') -or
        ($optionalOld -and ($scope -ne 'app' -or $relative -cne '.jpackage.xml' -or -not $item.oldSha256)) -or
        $seen.ContainsKey($key) -or
        [string]$item.sha256 -notmatch '^[0-9a-f]{64}$' -or
        ($item.oldSha256 -and [string]$item.oldSha256 -notmatch '^[0-9a-f]{64}$')) {
        throw "非法更新清单条目：$relative"
    }
    $seen[$key] = $true
    $source = GetPayload $item
    $target = GetTarget $item
    $boundary = if ($scope -eq 'root') { $InstallRoot } else { $app }
    Assert-NoReparse $target $boundary
    if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (Hash $source) -ne $item.sha256) {
        throw "更新文件 SHA-256 校验失败：$relative"
    }
    $exists = Test-Path -LiteralPath $target -PathType Leaf
    $oldExists[$key] = $exists
    if ($item.oldSha256) {
        if (($exists -and (Hash $target) -ne $item.oldSha256) -or
            (-not $exists -and -not $optionalOld)) {
            throw "安装文件版本或 SHA-256 不匹配：$relative"
        }
    } elseif ($exists) { throw "更新目标已存在：$relative" }
}
if (-not $seen.ContainsKey('root/LumenMusic.exe') -or
    -not $seen.ContainsKey('app/lumen-desktop-0.1.1.jar') -or
    -not $seen.ContainsKey('app/LumenMusic.cfg') -or
    -not $seen.ContainsKey('app/.jpackage.xml')) {
    throw '更新清单缺少原生启动器、新版 JAR、启动配置或版本状态。'
}
$running = @(Get-Process | Where-Object {
    $executable = try { $_.Path } catch { $null }
    $executable -and $executable.StartsWith($InstallRoot + '\', [StringComparison]::OrdinalIgnoreCase)
})
if ($running.Count) { throw 'Lumen Music 或其内置服务仍在运行，请退出后重试。' }

$backup = Join-Path $InstallRoot ("update-backup-v0.1.0-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $backup | Out-Null
$backupApp = Join-Path $backup 'app'
New-Item -ItemType Directory -Path $backupApp | Out-Null
foreach ($item in $manifest.files) {
    if (-not $oldExists["$(GetScope $item)/$($item.path)"]) { continue }
    $target = GetTarget $item
    $saved = GetBackup $item
    New-Item -ItemType Directory -Force -Path (Split-Path $saved -Parent) | Out-Null
    Copy-Item -LiteralPath $target -Destination $saved
    if ((Hash $saved) -ne $item.oldSha256) { throw "备份校验失败：$($item.path)" }
}
$modified = @()
$jarMoved = $false
try {
    Move-Item -LiteralPath $oldJar -Destination (Join-Path $backupApp $manifest.oldJar)
    $jarMoved = $true
    foreach ($item in $manifest.files) {
        $target = GetTarget $item
        $source = GetPayload $item
        $temporary = "$target.update-$([Guid]::NewGuid().ToString('N'))"
        $modified += $item
        try {
            Copy-Item -LiteralPath $source -Destination $temporary
            if ((Hash $temporary) -ne $item.sha256) { throw "临时文件校验失败：$($item.path)" }
            Move-Item -LiteralPath $temporary -Destination $target -Force
        } finally {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        }
        if ($TestFailAfter -gt 0 -and $modified.Count -ge $TestFailAfter) {
            throw '测试注入的更新失败。'
        }
    }
    foreach ($item in $manifest.files) {
        if ((Hash (GetTarget $item)) -ne $item.sha256) { throw "安装后校验失败：$($item.path)" }
    }
    Write-Output "更新完成：$InstallRoot"
    Write-Output "旧版文件备份：$backup"
} catch {
    $failure = $_
    foreach ($item in $modified) {
        $target = GetTarget $item
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
        if ($oldExists["$(GetScope $item)/$($item.path)"]) {
            Copy-Item -LiteralPath (GetBackup $item) -Destination $target
        }
    }
    if ($jarMoved) { Copy-Item -LiteralPath (Join-Path $backupApp $manifest.oldJar) -Destination $oldJar }
    throw "更新失败，已尝试恢复原文件；备份位于 $backup。原因：$failure"
}
