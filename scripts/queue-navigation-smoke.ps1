param([Parameter(Mandatory)][string]$RuntimeHome,
    [Parameter(Mandatory)][string]$Java, [string]$Python)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$root = Join-Path $projectRoot ('build/queue-navigation-smoke/' + [Guid]::NewGuid().ToString('N'))
$runtime = (Resolve-Path -LiteralPath $RuntimeHome).Path
$app = if (Test-Path -LiteralPath (Join-Path $runtime 'app') -PathType Container) {
    Join-Path $runtime 'app'
} else { $runtime }
if (-not $Python) { $Python = $env:LUMEN_PYTHON }
if (-not $Python) { throw 'Specify -Python or set LUMEN_PYTHON to an existing interpreter.' }
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$launcher = 'com.lumenmusic.QueueNavigationSmokeLauncher'
foreach ($required in @($app, $Python, $Java, (Join-Path $testClasses ($launcher.Replace('.', '/') + '.class')),
        (Join-Path $app 'vlc/libvlc.dll'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing queue navigation smoke input: $required" }
}
$jars = @(Get-ChildItem -LiteralPath $app -Filter '*.jar' -File)
$dependencies = @($jars | Where-Object { $_.Name -notlike 'lumen-desktop-*' } | ForEach-Object { $_.FullName })
$classpath = (@((Join-Path $projectRoot 'desktop/target/classes')) + $dependencies + @($testClasses)) -join ';'
New-Item -ItemType Directory -Path $root -Force | Out-Null
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
finally { $listener.Stop() }

$variables = @('LOCALAPPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH',
    'LUMEN_HISTORY_PATH', 'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL',
    'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME', 'LUMEN_AI_API_KEY', 'OPENAI_API_KEY',
    'PYTHONPATH')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$serviceProcess = $null
$desktopProcess = $null
try {
    $env:LOCALAPPDATA = $root
    $env:LUMEN_LIBRARY_PATH = Join-Path $root 'library.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $root 'preferences.properties'
    $env:LUMEN_HISTORY_PATH = Join-Path $root 'history.json'
    $env:LUMEN_AI_SETTINGS_PATH = Join-Path $root 'ai-settings.json'
    $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
    $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
    $env:LUMEN_VLC_HOME = Join-Path $app 'vlc'
    Remove-Item -LiteralPath 'Env:\LUMEN_AI_API_KEY' -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath 'Env:\OPENAI_API_KEY' -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath 'Env:\PYTHONPATH' -ErrorAction SilentlyContinue

    $serviceHome = Join-Path $projectRoot 'service'
    if (-not (Test-Path -LiteralPath (Join-Path $serviceHome 'lumen_service') -PathType Container)) {
        throw "Missing service source: $serviceHome"
    }
    $code = "import sys,runpy; sys.path.insert(0, r'$serviceHome'); sys.argv=['lumen_service','--provider','demo','--port','$port']; runpy.run_module('lumen_service',run_name='__main__')"
    $serviceRunner = Join-Path $root 'run-service.cmd'
    Set-Content -LiteralPath $serviceRunner -Encoding OEM -Value @(
        '@echo off',
        ('"' + $Python + '" -c "' + $code + '" > "' + (Join-Path $root 'service.stdout.log') +
            '" 2> "' + (Join-Path $root 'service.stderr.log') + '"'),
        'exit /b %ERRORLEVEL%')
    $serviceProcess = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32/cmd.exe') `
        -ArgumentList '/c', ('"' + $serviceRunner + '"') -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($serviceProcess.HasExited) { throw "Demo service exited before readiness: $root" }
        try {
            $response = Invoke-WebRequest -Uri "$env:LUMEN_SERVICE_URL/api/health" `
                -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $healthy = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 100
    }
    if (-not $healthy) { throw "Demo service readiness timed out: $root" }

    $desktopRunner = Join-Path $root 'run-desktop.cmd'
    Set-Content -LiteralPath $desktopRunner -Encoding OEM -Value @(
        '@echo off',
        ('"' + $Java + '" -Djavafx.cachedir="' + (Join-Path $root 'javafx-cache') +
            '" -cp "' + $classpath + '" ' + $launcher + ' > "' +
            (Join-Path $root 'desktop.stdout.log') + '" 2> "' +
            (Join-Path $root 'desktop.stderr.log') + '"'),
        'exit /b %ERRORLEVEL%')
    $desktopProcess = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32/cmd.exe') `
        -ArgumentList '/c', ('"' + $desktopRunner + '"') -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru
    if (-not $desktopProcess.WaitForExit(300000)) {
        & taskkill /F /T /PID $desktopProcess.Id 2>$null | Out-Null
        throw "Queue navigation desktop smoke timed out: $root"
    }
    $desktopProcess.WaitForExit()
    Get-Content -LiteralPath (Join-Path $root 'desktop.stdout.log')
    Get-Content -LiteralPath (Join-Path $root 'desktop.stderr.log')
    if ($desktopProcess.ExitCode -ne 0) {
        throw "Queue navigation desktop smoke failed ($($desktopProcess.ExitCode)): $root"
    }
    Write-Output "Queue navigation smoke exit code: $($desktopProcess.ExitCode)"
    Write-Output "Queue navigation smoke artifacts: $root"
} finally {
    foreach ($owned in @($desktopProcess, $serviceProcess)) {
        if ($owned -and -not $owned.HasExited) {
            & taskkill /F /T /PID $owned.Id 2>$null | Out-Null
            $owned.WaitForExit(5000) | Out-Null
        }
    }
    foreach ($name in $variables) {
        if ($null -eq $previous[$name]) {
            Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
    }
}
