param([string]$Python, [Parameter(Mandatory)][string]$Java,
    [string]$RuntimeHome, [string]$AppHome, [switch]$VisualOnly, [switch]$RadioOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($RadioOnly) {
    if ($AppHome -or -not $RuntimeHome) { throw 'RadioOnly requires -RuntimeHome for source smoke dependencies; omit -AppHome.' }
    if (-not $Python) { $Python = $env:LUMEN_PYTHON }
    if (-not $Python) { throw 'Specify -Python or set LUMEN_PYTHON to an existing interpreter.' }
    $runtime = (Resolve-Path -LiteralPath $RuntimeHome).Path
    $app = if (Test-Path -LiteralPath (Join-Path $runtime 'app') -PathType Container) { Join-Path $runtime 'app' }
        else { $runtime }
    foreach ($required in @($Python, $Java, (Join-Path $app 'vlc/libvlc.dll'))) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing segment radio smoke input: $required" }
    }
    $classpath = (@((Join-Path $projectRoot 'desktop/target/classes'), (Join-Path $projectRoot 'desktop/target/test-classes')) +
        @(Get-ChildItem -LiteralPath $app -Filter '*.jar' | Where-Object { $_.Name -notlike 'lumen-desktop-*' } | ForEach-Object FullName)) -join ';'
    $root = Join-Path $projectRoot ('build/segment-batch-smoke/radio-normal-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root | Out-Null
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
    $variables = @('LOCALAPPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_HISTORY_PATH',
        'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME',
        'OPENAI_API_KEY', 'LUMEN_AI_API_KEY', 'PYTHONPATH', 'LUMEN_SEGMENT_BATCH_RADIO_ONLY')
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
        Remove-Item -LiteralPath 'Env:\OPENAI_API_KEY' -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath 'Env:\LUMEN_AI_API_KEY' -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath 'Env:\PYTHONPATH' -ErrorAction SilentlyContinue
        $env:LUMEN_SEGMENT_BATCH_RADIO_ONLY = '1'
        & $Python (Join-Path $projectRoot 'service/tests/segment_batch_smoke_fixture.py') $root
        if ($LASTEXITCODE -ne 0) { throw 'Radio fixture preparation failed' }
        $serviceHome = Join-Path $projectRoot 'service'
        $serviceCode = "import sys,runpy; sys.path.insert(0, r'$serviceHome'); sys.argv=['lumen_service','--provider','demo','--port','$port']; runpy.run_module('lumen_service',run_name='__main__')"
        $serviceProcess = Start-Process -FilePath $Python -ArgumentList ('-c "' + $serviceCode + '"') `
            -WorkingDirectory (Join-Path $projectRoot 'service') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $root 'service.stdout.log') -RedirectStandardError (Join-Path $root 'service.stderr.log')
        $deadline = [DateTime]::UtcNow.AddSeconds(12)
        $healthy = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($serviceProcess.HasExited) { throw 'Radio service exited before readiness' }
            try { $healthy = (Invoke-WebRequest "$env:LUMEN_SERVICE_URL/api/health" -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200 } catch { }
            if ($healthy) { break }
            Start-Sleep -Milliseconds 100
        }
        if (-not $healthy) { throw 'Radio service readiness timed out' }
        $desktopProcess = Start-Process -FilePath $Java `
            -ArgumentList ('-Djavafx.cachedir="' + (Join-Path $root 'javafx-cache') + '" -cp "' + $classpath + '" com.lumenmusic.SegmentBatchSmokeLauncher') `
            -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $root 'desktop.stdout.log') -RedirectStandardError (Join-Path $root 'desktop.stderr.log')
        if (-not $desktopProcess.WaitForExit(90000)) { throw 'Radio native acceptance timed out' }
        $desktopProcess.WaitForExit()
        Get-Content -LiteralPath (Join-Path $root 'desktop.stdout.log')
        Get-Content -LiteralPath (Join-Path $root 'desktop.stderr.log')
        if ($desktopProcess.ExitCode -ne 0) { throw "Radio native acceptance failed: $root" }
        Write-Output "Radio normal exit code: 0; artifacts: $root"
    } finally {
        foreach ($owned in @($desktopProcess, $serviceProcess)) {
            if ($owned -and -not $owned.HasExited) { Stop-Process -Id $owned.Id -Force; $owned.WaitForExit(5000) | Out-Null }
        }
        foreach ($name in $variables) {
            if ($null -eq $previous[$name]) {
                Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
            }
        }
    }
    return
}
if ($AppHome -and $RuntimeHome) { throw 'Specify only -AppHome or -RuntimeHome.' }
if (-not $AppHome -and -not $RuntimeHome) { throw 'Specify -AppHome or -RuntimeHome; no historical build image is selected automatically.' }
$arguments = @{
    SmokeLauncher = 'com.lumenmusic.SegmentBatchSmokeLauncher'
    ArtifactDirectory = 'segment-batch-smoke'
    FixtureScript = Join-Path $projectRoot 'service/tests/segment_batch_smoke_fixture.py'
}
if ($AppHome) { $arguments.AppHome = $AppHome }
if ($RuntimeHome) { $arguments.RuntimeHome = $RuntimeHome }
if ($Python) { $arguments.Python = $Python }
if ($Java) { $arguments.Java = $Java }
if ($VisualOnly) { $arguments.VisualOnly = $true }
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
