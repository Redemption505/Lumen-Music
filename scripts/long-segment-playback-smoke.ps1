param(
    [string]$AppHome,
    [string]$RuntimeApp,
    [string]$Java,
    [string]$Python,
    [switch]$Normalization,
    [switch]$Preload,
    [ValidateRange(60, 900)][int]$TimeoutSeconds = 360
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $projectRoot ('build/long-segment-playback-smoke/' + [Guid]::NewGuid().ToString('N'))
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$launcher = 'com.lumenmusic.LongSegmentPlaybackSmokeLauncher'
if (-not (Test-Path -LiteralPath (Join-Path $testClasses ($launcher.Replace('.', '/') + '.class')))) {
    throw 'Compile desktop tests before running this smoke.'
}
if ($AppHome -and $RuntimeApp) { throw 'Use either -AppHome or -RuntimeApp.' }
$productionJar = $null
if ($AppHome) {
    $AppHome = (Resolve-Path -LiteralPath $AppHome).Path
    $RuntimeApp = Join-Path $AppHome 'app'
    $cfg = Join-Path $RuntimeApp 'LumenMusic.cfg'
    if (-not (Test-Path -LiteralPath $cfg -PathType Leaf)) { throw "Missing launcher configuration: $cfg" }
    $configuration = Get-Content -LiteralPath $cfg
    $mainEntry = @($configuration | Where-Object { $_ -match '^app\.mainjar=' })
    if ($mainEntry.Count -eq 0) {
        $mainEntry = @($configuration | Where-Object { $_ -match '^app\.classpath=.*[\\/]lumen-desktop-[^\\/]+\.jar$' })
    }
    if ($mainEntry.Count -ne 1) { throw "Expected exactly one configured main JAR in $cfg" }
    $mainName = ($mainEntry[0] -split '=', 2)[1].Trim() -replace '^\$APPDIR[\\/]', ''
    $productionJar = Join-Path $RuntimeApp $mainName
    $serviceHome = Join-Path $RuntimeApp 'service'
    $proxyExe = Join-Path $RuntimeApp 'stream-proxy/lumen-stream-proxy.exe'
    if (-not $Java) {
        $candidate = Join-Path $AppHome 'runtime/bin/java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $Java = $candidate }
    }
    if (-not $Python) {
        $candidate = Join-Path $RuntimeApp 'python/python.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $Python = $candidate }
    }
} else {
    if (-not $RuntimeApp) {
        $RuntimeApp = (Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/package-input') -Directory |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    }
    $serviceHome = Join-Path $projectRoot 'service'
    $proxyExe = Join-Path $projectRoot 'stream-proxy/target/release/lumen-stream-proxy.exe'
}
if (-not $RuntimeApp) { throw 'Provide an existing -RuntimeApp with JavaFX/VLC dependencies.' }
$RuntimeApp = (Resolve-Path -LiteralPath $RuntimeApp).Path
if (-not $Java) { $Java = (Get-Command java -CommandType Application | Select-Object -First 1).Source }
if (-not $Python) { $Python = $env:LUMEN_PYTHON }
if (-not $Python) { $Python = (Get-Command python -CommandType Application | Select-Object -First 1).Source }
$vlcHome = Join-Path $RuntimeApp 'vlc'
foreach ($required in @($Java, $Python, $proxyExe, (Join-Path $serviceHome 'lumen_service/__main__.py'),
        (Join-Path $vlcHome 'libvlc.dll'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing smoke input: $required" }
}
if ($AppHome -and -not (Test-Path -LiteralPath $productionJar -PathType Leaf)) { throw "Missing configured JAR: $productionJar" }
$dependencies = @(Get-ChildItem -LiteralPath $RuntimeApp -Filter '*.jar' -File |
    Where-Object { $_.Name -notlike 'lumen-desktop-*' } | Sort-Object Name | ForEach-Object { $_.FullName })
$productionEntries = if ($AppHome) { @($productionJar) } else { @(Join-Path $projectRoot 'desktop/target/classes') }
$classpath = (@($productionEntries) + $dependencies + @($testClasses)) -join ';'
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot 'isolated-long-segment.marker') | Out-Null

function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}
function Wait-Ready([string]$Url, [Diagnostics.Process]$Process) {
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw "Owned service exited: $Url" }
        try {
            if ((Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 100
    }
    throw "Service readiness timed out: $Url"
}
function Stop-Owned([Diagnostics.Process]$Process) {
    if ($Process) {
        if (-not $Process.HasExited) { $Process.Kill(); $Process.WaitForExit(5000) | Out-Null }
        $Process.Dispose()
    }
}
$pythonPort = Get-FreePort
$proxyPort = Get-FreePort
while ($proxyPort -eq $pythonPort) { $proxyPort = Get-FreePort }
$variables = @('LUMEN_SMOKE_ROOT', 'LOCALAPPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_HISTORY_PATH',
    'LUMEN_PREFERENCES_PATH', 'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL',
    'LUMEN_VLC_HOME', 'LUMEN_PROXY_ADDR', 'LUMEN_PYTHON_SERVICE_URL', 'PYTHONPATH', 'PYTHONUNBUFFERED',
    'PYTHONPYCACHEPREFIX', 'LUMEN_AI_API_KEY', 'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL',
    'LUMEN_AI_ENABLED', 'OPENAI_API_KEY')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$pythonProcess = $null
$proxyProcess = $null
$desktopProcess = $null
$stdout = Join-Path $testRoot 'desktop.stdout.log'
$stderr = Join-Path $testRoot 'desktop.stderr.log'
try {
    $env:LUMEN_SMOKE_ROOT = $testRoot
    $env:LOCALAPPDATA = $testRoot
    $env:LUMEN_LIBRARY_PATH = Join-Path $testRoot 'library.json'
    $env:LUMEN_HISTORY_PATH = Join-Path $testRoot 'history.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $testRoot 'preferences.properties'
    $env:LUMEN_AI_SETTINGS_PATH = Join-Path $testRoot 'ai-settings.json'
    $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$pythonPort"
    $env:LUMEN_STREAM_URL = "http://127.0.0.1:$proxyPort"
    $env:LUMEN_PROXY_ADDR = "127.0.0.1:$proxyPort"
    $env:LUMEN_PYTHON_SERVICE_URL = $env:LUMEN_SERVICE_URL
    $env:LUMEN_VLC_HOME = $vlcHome
    $env:PYTHONPATH = $serviceHome
    $env:PYTHONUNBUFFERED = '1'
    $env:PYTHONPYCACHEPREFIX = Join-Path $testRoot 'pycache'
    foreach ($name in @('LUMEN_AI_API_KEY', 'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'LUMEN_AI_ENABLED', 'OPENAI_API_KEY')) {
        [Environment]::SetEnvironmentVariable($name, '', 'Process')
    }
    # Explicit sys.path also works with an isolated embedded Python interpreter.
    $serviceRunner = Join-Path $testRoot 'run-service.py'
    $servicePathLiteral = $serviceHome.Replace('\', '\\').Replace("'", "\'")
    [IO.File]::WriteAllText($serviceRunner,
        "import sys, runpy`nsys.path.insert(0, '$servicePathLiteral')`nsys.argv=['lumen_service','--port','$pythonPort','--provider','bilibili']`nrunpy.run_module('lumen_service', run_name='__main__')`n",
        [Text.UTF8Encoding]::new($false))
    Write-Output "Acceptance artifacts: $testRoot"
    Write-Output "BV: BV1bE41127s6; native VLC; cache off; normalization=$Normalization; preload=$Preload; Java: $Java"
    Write-Output "Real provider: $serviceHome; Rust proxy: $proxyExe; VLC: $vlcHome"
    if ($AppHome) { Write-Output "Production JAR: $productionJar (SHA-256: $((Get-FileHash -LiteralPath $productionJar -Algorithm SHA256).Hash))" }
    $pythonProcess = Start-Process -FilePath $Python -ArgumentList ('"' + $serviceRunner + '"') `
        -WorkingDirectory $testRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testRoot 'python.stdout.log') -RedirectStandardError (Join-Path $testRoot 'python.stderr.log')
    Wait-Ready "$env:LUMEN_SERVICE_URL/api/health" $pythonProcess
    $proxyProcess = Start-Process -FilePath $proxyExe -WorkingDirectory $testRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testRoot 'proxy.stdout.log') -RedirectStandardError (Join-Path $testRoot 'proxy.stderr.log')
    Wait-Ready "$env:LUMEN_STREAM_URL/health" $proxyProcess
    $arguments = @('-Xlog:class+load=info:file=class-load.log',
        ('"-Djavafx.cachedir=' + (Join-Path $testRoot 'javafx-cache') + '"'),
        ('-Dlumen.smoke.normalization=' + $Normalization.IsPresent.ToString().ToLowerInvariant()),
        ('-Dlumen.smoke.preload=' + $Preload.IsPresent.ToString().ToLowerInvariant()),
        ('-Dlumen.stream.base=' + $env:LUMEN_STREAM_URL), '-cp', ('"' + $classpath + '"'))
    if ($AppHome) { $arguments += ('"-Dlumen.smoke.installed.jar=' + $productionJar + '"') }
    $arguments += $launcher
    $desktopProcess = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $testRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not $desktopProcess.WaitForExit(1000) -and [DateTime]::UtcNow -lt $deadline) {
        if ($pythonProcess.HasExited -or $proxyProcess.HasExited) { throw 'Owned provider/proxy exited during playback.' }
    }
    if (-not $desktopProcess.HasExited) { throw "Long segment acceptance timed out; owned Java PID=$($desktopProcess.Id)." }
    $desktopProcess.WaitForExit()
    $desktopProcess.Refresh()
    Get-Content -LiteralPath $stdout
    Get-Content -LiteralPath $stderr
    if ($desktopProcess.ExitCode -ne 0) { throw "Long segment acceptance failed ($($desktopProcess.ExitCode)); $testRoot" }
    if (-not (Select-String -LiteralPath $stdout -SimpleMatch 'PASS LongSegmentPlaybackSmoke:' -Quiet)) { throw 'Playback success marker missing.' }
    if (-not (Test-Path -LiteralPath (Join-Path $testRoot 'long-segment-native.csv'))) { throw 'Native playback samples missing.' }
    Write-Output 'PASS natural Java process exit after native playback cleanup.'
} finally {
    foreach ($owned in @($desktopProcess, $proxyProcess, $pythonProcess)) { Stop-Owned $owned }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Write-Output "Acceptance artifacts retained: $testRoot"
}
