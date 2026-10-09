$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$version = '3.13.15'
$archive = Join-Path $projectRoot "build/tools/python-$version-embed-amd64.zip"
$destination = Join-Path $projectRoot "build/tools/python-$version"
$sha256 = 'd1f04d990aee1253d8569e8e5104e30fa9f5fa830899f14843448872d936a2cf'
New-Item -ItemType Directory -Force -Path (Split-Path $archive -Parent) | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri "https://www.python.org/ftp/python/$version/python-$version-embed-amd64.zip" -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
    throw 'Python 官方运行时 SHA-256 校验失败。'
}
Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
Write-Output "Python embedded runtime: $destination"
