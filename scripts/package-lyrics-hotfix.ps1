param(
    [Parameter(Mandatory)][string]$NewAppHome,
    [string]$OldAppHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $OldAppHome) {
    $OldAppHome = Join-Path $projectRoot 'build/dist/20261001-112410-718/LumenMusic'
}
$oldHome = (Resolve-Path -LiteralPath $OldAppHome).Path
$newHome = (Resolve-Path -LiteralPath $NewAppHome).Path
$jarName = 'lumen-desktop-0.1.1.jar'
$oldJar = Join-Path $oldHome "app/$jarName"
$newJar = Join-Path $newHome "app/$jarName"
$oldCfg = Join-Path $oldHome 'app/LumenMusic.cfg'
$newCfg = Join-Path $newHome 'app/LumenMusic.cfg'
$oldLauncher = Join-Path $oldHome 'LumenMusic.exe'
$newLauncher = Join-Path $newHome 'LumenMusic.exe'
$expectedOldJar = 'b98e67e53c62196f66d5e2bb1f92e284db2fc345d95d87512b55d95d0cb2b3ba'
$expectedCfg = '881af68dd7cd2aa6c8a86220af1b65d2e4fcfd779020b151af69283e80651e29'
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
foreach ($file in @($oldJar, $newJar, $oldCfg, $newCfg, $oldLauncher, $newLauncher)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "缺少发行输入：$file" }
}
if ((Hash $oldJar) -ne $expectedOldJar -or (Hash $oldCfg) -ne $expectedCfg -or
    (Hash $newCfg) -ne $expectedCfg) {
    throw '原版 JAR 或启动配置不匹配，不能制作同版修复包。'
}
if ((Hash $newJar) -eq $expectedOldJar) { throw '新版 JAR 与原版相同。' }
foreach ($launcher in @($oldLauncher, $newLauncher)) {
    if (([Diagnostics.FileVersionInfo]::GetVersionInfo($launcher)).ProductVersion -ne '0.1.1') {
        throw "启动器不是 0.1.1：$launcher"
    }
}
$name = 'Lumen-Music-v0.1.1-Lyrics-Hotfix-Windows-x64'
$releaseDir = Join-Path $projectRoot "build/releases/$name"
$archive = "$releaseDir.zip"
$fullRelease = Join-Path $projectRoot 'build/releases/Lumen-Music-v0.1.1-Windows-x64'
foreach ($document in @('SOURCE.zip', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $fullRelease $document) -PathType Leaf)) {
        throw "请先生成新版完整发行包，缺少：$document"
    }
}
if ((Test-Path -LiteralPath $releaseDir) -or (Test-Path -LiteralPath $archive)) {
    throw "同版修复包已存在，请保留或另行归档：$releaseDir"
}
New-Item -ItemType Directory -Path (Join-Path $releaseDir 'payload') | Out-Null
Copy-Item -LiteralPath $newJar -Destination (Join-Path $releaseDir "payload/$jarName")
$manifest = [ordered]@{
    version = '0.1.1'
    jar = $jarName
    oldSha256 = $expectedOldJar
    newSha256 = Hash $newJar
    configSha256 = $expectedCfg
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $releaseDir 'manifest.json') -Encoding utf8
foreach ($file in @('apply-lyrics-hotfix.ps1', 'lyrics-hotfix.cmd')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "update/$file") -Destination $releaseDir
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'release/v0.1.1/LYRICS-HOTFIX-README.txt') -Destination (Join-Path $releaseDir 'README.txt')
foreach ($document in @('SOURCE.zip', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt')) {
    Copy-Item -LiteralPath (Join-Path $fullRelease $document) -Destination $releaseDir
}
$hashLines = @(Get-ChildItem -LiteralPath $releaseDir -File | Sort-Object Name | ForEach-Object {
    (Hash $_.FullName) + '  ' + $_.Name
})
$hashLines | Set-Content -LiteralPath (Join-Path $releaseDir 'SHA256SUMS.txt') -Encoding utf8
Compress-Archive -LiteralPath $releaseDir -DestinationPath $archive -CompressionLevel Optimal
(Hash $archive) + '  ' + (Split-Path $archive -Leaf) |
    Set-Content -LiteralPath "$archive.sha256" -Encoding utf8
Write-Output "Lyrics hotfix ZIP: $archive"
