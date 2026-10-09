param(
    [ValidateSet('bilibili', 'demo')][string]$Provider = 'bilibili',
    [ValidateRange(1, 65535)][int]$Port = 8765,
    [string]$Python = $env:LUMEN_PYTHON,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $Python) { $Python = 'python' }
$pythonCommand = Get-Command $Python -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $pythonCommand) {
    throw "找不到 Python：$Python。请安装 Python 3.10+，或通过 -Python / LUMEN_PYTHON 指定解释器。"
}
& $pythonCommand.Source -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'
if ($LASTEXITCODE -ne 0) { throw 'Python 无法运行或低于 3.10，请通过 -Python 指定有效解释器。' }

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try { $listener.Start() }
catch {
    $compatible = $false
    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2 -UseBasicParsing
        $compatible = $response.StatusCode -eq 200 -and
            $response.Content.Contains('provider="' + $Provider + '"')
    } catch { }
    if (-not $compatible) {
        throw "端口 $Port 已被占用，且不是 $Provider 模式的 Lumen 服务；请关闭占用程序或指定其他 -Port。"
    }
}
finally { $listener.Stop() }

$previousPython = $env:LUMEN_PYTHON
$previousProvider = $env:LUMEN_PROVIDER
$previousServiceUrl = $env:LUMEN_SERVICE_URL
try {
    $env:LUMEN_PYTHON = $pythonCommand.Source
    $env:LUMEN_PROVIDER = $Provider
    $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$Port"
    & (Join-Path $PSScriptRoot 'desktop.ps1') -ServiceUrl $env:LUMEN_SERVICE_URL -JavaHome $JavaHome
    if ($LASTEXITCODE -ne 0) { throw "桌面程序运行失败（退出码 $LASTEXITCODE）。" }
} finally {
    $env:LUMEN_PYTHON = $previousPython
    $env:LUMEN_PROVIDER = $previousProvider
    $env:LUMEN_SERVICE_URL = $previousServiceUrl
}
