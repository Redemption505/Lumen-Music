param([string]$Python, [string]$Java, [string]$AppHome, [string]$RuntimeHome)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$root = Join-Path $projectRoot ('build/radio-smoke/' + [Guid]::NewGuid().ToString('N'))
if ($AppHome -and $RuntimeHome) { throw 'Specify only -AppHome or -RuntimeHome.' }
if (-not $AppHome -and -not $RuntimeHome) { throw 'Specify -AppHome or -RuntimeHome; no historical build image is selected automatically.' }
$app = if ($AppHome) { Join-Path (Resolve-Path -LiteralPath $AppHome).Path 'app' }
    else {
        $runtime = (Resolve-Path -LiteralPath $RuntimeHome).Path
        if (Test-Path -LiteralPath (Join-Path $runtime 'app') -PathType Container) { Join-Path $runtime 'app' }
        else { $runtime }
    }
if ($AppHome -and $Python) { throw 'Use the installed Python when -AppHome is specified; omit -Python.' }
if ($AppHome) { $Python = Join-Path $app 'python/python.exe' }
if (-not $Python) { $Python = $env:LUMEN_PYTHON }
if (-not $Python) { throw 'Specify -Python or set LUMEN_PYTHON to an existing interpreter.' }
if (-not $Java -and $AppHome) { $Java = Join-Path (Split-Path $app -Parent) 'runtime/bin/java.exe' }
if (-not $Java) { throw 'Specify -Java for the source smoke driver.' }
$python = $Python
$java = $Java
if ($AppHome -and $PSBoundParameters.ContainsKey('Java')) { Write-Output "Radio test driver: external Java $java; installed runtime is not verified by this smoke." }
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$launcher = 'com.lumenmusic.RadioSmokeLauncher'
foreach ($required in @($app, $python, $java, (Join-Path $testClasses ($launcher.Replace('.', '/') + '.class')),
        (Join-Path $app 'vlc/libvlc.dll'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing radio smoke input: $required" }
}
$jars = @(Get-ChildItem -LiteralPath $app -Filter '*.jar' -File)
$productionJars = @($jars | Where-Object { $_.Name -like 'lumen-desktop-*.jar' })
if ($AppHome -and $productionJars.Count -ne 1) { throw "Expected one installed desktop JAR in $app; found $($productionJars.Count)." }
$productionJar = if ($AppHome) { $productionJars[0].FullName }
$dependencies = @($jars | Where-Object { $_.Name -notlike 'lumen-desktop-*' } | ForEach-Object { $_.FullName })
$productionClasses = if ($AppHome) { @($productionJar) } else { @((Join-Path $projectRoot 'desktop/target/classes')) }
$classpath = (@($productionClasses) + $dependencies + @($testClasses)) -join ';'
if ($AppHome) { Write-Output "Production JAR: $productionJar (SHA-256: $((Get-FileHash -LiteralPath $productionJar -Algorithm SHA256).Hash))" }
New-Item -ItemType Directory -Path $root -Force | Out-Null
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
finally { $listener.Stop() }

$variables = @('LOCALAPPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH',
    'LUMEN_HISTORY_PATH', 'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL',
    'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME', 'LUMEN_AI_API_KEY', 'OPENAI_API_KEY')
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

    $serviceHome = if ($AppHome) { Join-Path $app 'service' } else { Join-Path $projectRoot 'service' }
    if (-not (Test-Path -LiteralPath (Join-Path $serviceHome 'lumen_service') -PathType Container)) { throw "Missing service source: $serviceHome" }
    $code = "import sys,runpy; sys.path.insert(0, r'$serviceHome'); sys.argv=['lumen_service','--provider','demo','--port','$port']; runpy.run_module('lumen_service',run_name='__main__')"
    $serviceRunner = Join-Path $root 'run-service.cmd'
    Set-Content -LiteralPath $serviceRunner -Encoding OEM -Value @(
        '@echo off',
        ('"' + $python + '" -c "' + $code + '" > "' + (Join-Path $root 'service.stdout.log') +
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
        ('"' + $java + '" -Djavafx.cachedir="' + (Join-Path $root 'javafx-cache') +
            '" -cp "' + $classpath + '" ' + $launcher + ' > "' +
            (Join-Path $root 'desktop.stdout.log') + '" 2> "' +
            (Join-Path $root 'desktop.stderr.log') + '"'),
        'exit /b %ERRORLEVEL%')
    $desktopProcess = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32/cmd.exe') `
        -ArgumentList '/c', ('"' + $desktopRunner + '"') -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru
    if (-not $desktopProcess.WaitForExit(130000)) {
        & taskkill /F /T /PID $desktopProcess.Id 2>$null | Out-Null
        throw "Radio desktop smoke timed out: $root"
    }
    $desktopProcess.WaitForExit()
    Get-Content -LiteralPath (Join-Path $root 'desktop.stdout.log')
    Get-Content -LiteralPath (Join-Path $root 'desktop.stderr.log')
    if ($desktopProcess.ExitCode -ne 0) {
        throw "Radio desktop smoke failed ($($desktopProcess.ExitCode)): $root"
    }
    Write-Output "Radio smoke artifacts: $root"
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
