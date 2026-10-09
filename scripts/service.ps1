param([string]$Python = $env:LUMEN_PYTHON, [ValidateRange(1, 65535)][int]$Port = 8765,
      [ValidateSet('bilibili', 'demo')][string]$Provider = 'bilibili')
$ErrorActionPreference = 'Stop'
if (-not $Python) { $Python = 'python' }
$pythonCommand = Get-Command $Python -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $pythonCommand) { throw "找不到 Python：$Python。请通过 -Python、LUMEN_PYTHON 或 PATH 指定 Python 3.10+。" }
& $pythonCommand.Source -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'
if ($LASTEXITCODE -ne 0) { throw 'Python 无法运行或低于 3.10，请通过 -Python 指定有效解释器。' }
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'service')
try {
    & $pythonCommand.Source -m lumen_service --port $Port --provider $Provider
    if ($LASTEXITCODE -ne 0) { throw 'Python 服务退出失败。' }
} finally { Pop-Location }
