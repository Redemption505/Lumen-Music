param(
    [ValidateSet('All', 'Java', 'Python', 'Rust', 'Demo')][string]$Component = 'All',
    [string]$Python = $env:LUMEN_PYTHON,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Offline,
    [string]$ArtifactDirectory = 'build/check'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$components = if ($Component -eq 'All') { @('Python', 'Rust', 'Java', 'Demo') } else { @($Component) }
if ($components -contains 'Python' -or $components -contains 'Demo') {
    if (-not $Python) { $Python = 'python' }
    $pythonCommand = Get-Command $Python -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $pythonCommand) { throw "找不到 Python：$Python。请通过 -Python、LUMEN_PYTHON 或 PATH 指定 Python 3.10+。" }
    $Python = $pythonCommand.Source
    & $Python -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'
    if ($LASTEXITCODE -ne 0) { throw 'Python 无法运行或低于 3.10，请通过 -Python 指定有效解释器。' }
}
if ($components -contains 'Java' -or $components -contains 'Demo') {
    if (-not $JavaHome) {
        $javaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($javaCommand) { $JavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent }
    }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe')) -or
            -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
        throw '找不到完整 JDK，请通过 -JavaHome、JAVA_HOME 或 PATH 指定含 java.exe 和 javac.exe 的 JDK。'
    }
    $JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
}
if ($components -contains 'Rust') {
    $cargoCommand = Get-Command cargo -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $cargoCommand) { throw '找不到 Cargo，请使用已安装的 Rust 工具链并将 cargo 加入 PATH。' }
}
$artifactBase = if ([IO.Path]::IsPathRooted($ArtifactDirectory)) { $ArtifactDirectory }
    else { Join-Path $projectRoot $ArtifactDirectory }
$runRoot = Join-Path $artifactBase ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$variables = @('JAVA_HOME', 'PATH', 'LOCALAPPDATA', 'APPDATA', 'PYTHONPATH', 'PYTHONPYCACHEPREFIX',
    'LUMEN_LIBRARY_PATH', 'LUMEN_HISTORY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_AI_SETTINGS_PATH',
    'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL', 'LUMEN_PYTHON', 'LUMEN_PROVIDER',
    'LUMEN_AI_API_KEY', 'OPENAI_API_KEY', 'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'LUMEN_AI_ENABLED')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$serviceProcess = $null
try {
    if ($JavaHome) {
        $env:JAVA_HOME = $JavaHome
        $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
    }
    $env:LOCALAPPDATA = $runRoot
    $env:APPDATA = Join-Path $runRoot 'roaming'
    $env:PYTHONPATH = Join-Path $projectRoot 'service'
    $env:PYTHONPYCACHEPREFIX = Join-Path $runRoot 'pycache'
    $env:LUMEN_LIBRARY_PATH = Join-Path $runRoot 'library.json'
    $env:LUMEN_HISTORY_PATH = Join-Path $runRoot 'history.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $runRoot 'preferences.properties'
    $env:LUMEN_AI_SETTINGS_PATH = Join-Path $runRoot 'ai-settings.json'
    foreach ($name in @('LUMEN_AI_API_KEY', 'OPENAI_API_KEY', 'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'LUMEN_AI_ENABLED')) {
        Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
    }
    $wrapper = Join-Path $projectRoot 'desktop/mvnw.cmd'
    $mavenArguments = @('-B', '-ntp', '-f', (Join-Path $projectRoot 'desktop/pom.xml'))
    if ($Offline) { $mavenArguments += '-o' }
    if ($components -contains 'Python') {
        # Unit tests choose their own temporary library and test the adjacent default paths.
        # Keep the isolated Windows profile, but do not override those per-test paths.
        $unitPathValues = @{}
        foreach ($name in @('LUMEN_LIBRARY_PATH', 'LUMEN_HISTORY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_AI_SETTINGS_PATH')) {
            $unitPathValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
        }
        try {
            & $Python -m unittest discover -s (Join-Path $projectRoot 'service/tests') -v 2>&1 |
                Tee-Object -FilePath (Join-Path $runRoot 'python.log')
            if ($LASTEXITCODE -ne 0) { throw 'Python 基础测试失败。' }
        } finally {
            foreach ($name in $unitPathValues.Keys) {
                if ($null -eq $unitPathValues[$name]) {
                    Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
                } else {
                    [Environment]::SetEnvironmentVariable($name, $unitPathValues[$name], 'Process')
                }
            }
        }
    }
    if ($components -contains 'Rust') {
        $cargoArguments = @('test', '--manifest-path', (Join-Path $projectRoot 'stream-proxy/Cargo.toml'), '--locked')
        if ($Offline) { $cargoArguments += '--offline' }
        & $cargoCommand.Source @cargoArguments 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'rust.log')
        if ($LASTEXITCODE -ne 0) { throw 'Rust 基础测试失败。' }
    }
    if ($components -contains 'Java') {
        & $wrapper @mavenArguments verify 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'java.log')
        if ($LASTEXITCODE -ne 0) { throw 'Java 基础测试失败。' }
    }
    if ($components -contains 'Demo') {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
        finally { $listener.Stop() }
        $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
        $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
        $env:LUMEN_PROVIDER = 'demo'
        $env:LUMEN_PYTHON = $Python
        $serviceProcess = Start-Process -FilePath $Python -ArgumentList @('-m', 'lumen_service', '--provider', 'demo', '--port', $port) `
            -WorkingDirectory (Join-Path $projectRoot 'service') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runRoot 'service.stdout.log') -RedirectStandardError (Join-Path $runRoot 'service.stderr.log')
        $healthy = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($serviceProcess.HasExited) { throw "Demo 服务在就绪前退出：$runRoot" }
            try {
                $response = Invoke-WebRequest -Uri "$env:LUMEN_SERVICE_URL/api/health" -TimeoutSec 1 -UseBasicParsing
                if ($response.StatusCode -eq 200 -and $response.Content.Contains('provider="demo"')) {
                    $healthy = $true; break
                }
            } catch { }
            Start-Sleep -Milliseconds 100
        }
        if (-not $healthy) { throw "Demo 服务启动超时：$runRoot" }
        & (Join-Path $PSScriptRoot 'desktop.ps1') -Smoke -ServiceUrl $env:LUMEN_SERVICE_URL -JavaHome $JavaHome -Offline:$Offline 2>&1 |
            Tee-Object -FilePath (Join-Path $runRoot 'demo.log')
    }
    Write-Output "PASS: $($components -join ', '); 检查资料：$runRoot"
} finally {
    try {
        if ($serviceProcess -and -not $serviceProcess.HasExited) {
            Stop-Process -Id $serviceProcess.Id
            $serviceProcess.WaitForExit(5000) | Out-Null
        }
    } finally {
        foreach ($name in $variables) {
            if ($null -eq $previous[$name]) {
                Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
            }
        }
        Write-Output "检查资料：$runRoot"
    }
}
