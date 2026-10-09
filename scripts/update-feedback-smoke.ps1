param([Parameter(Mandatory)][string]$AppJar, [string]$Python,
    [Parameter(Mandatory)][string]$Java, [Parameter(Mandatory)][string]$Launcher,
    [Parameter(Mandatory)][string]$RuntimeHome,
    [ValidateSet('normal','hidpi-1.5','both')][string]$Scale = 'both', [switch]$VisualOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$AppJar = (Resolve-Path -LiteralPath $AppJar).Path
if (-not $Python) { $Python = $env:LUMEN_PYTHON }
if (-not $Python) { throw 'Specify -Python or set LUMEN_PYTHON to an existing interpreter.' }
$runtime = (Resolve-Path -LiteralPath $RuntimeHome).Path
$packageInput = if (Test-Path -LiteralPath (Join-Path $runtime 'app') -PathType Container) {
    Join-Path $runtime 'app'
} else { $runtime }
$dependencies = @(Get-ChildItem -LiteralPath $packageInput -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'lumen-desktop-*' } | ForEach-Object FullName)
foreach ($required in @($AppJar, $Python, $Java, $Launcher,
        (Join-Path $projectRoot 'desktop/target/test-classes/com/lumenmusic/UpdateFeedbackSmokeLauncher.class'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing update feedback smoke input: $required" }
}
$variables = @('LOCALAPPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_HISTORY_PATH',
    'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME', 'LUMEN_AI_API_KEY', 'OPENAI_API_KEY', 'PYTHONPATH')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Invoke-UpdateFeedbackSmoke([string]$scaleName, [string]$scaleOption) {
    $root = Join-Path $projectRoot ('build/update-feedback-smoke/' + $scaleName + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root | Out-Null
    $serviceProcess = $null
    $desktopProcess = $null
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
    try {
        $env:LOCALAPPDATA = $root
        $env:LUMEN_LIBRARY_PATH = Join-Path $root 'library.json'
        $env:LUMEN_PREFERENCES_PATH = Join-Path $root 'preferences.properties'
        $env:LUMEN_HISTORY_PATH = Join-Path $root 'history.json'
        $env:LUMEN_AI_SETTINGS_PATH = Join-Path $root 'ai-settings.json'
        $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
        $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
        $env:LUMEN_VLC_HOME = Join-Path $packageInput 'vlc'
        Remove-Item -LiteralPath 'Env:\LUMEN_AI_API_KEY' -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath 'Env:\OPENAI_API_KEY' -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath 'Env:\PYTHONPATH' -ErrorAction SilentlyContinue
        & $Python (Join-Path $projectRoot 'service/tests/update_feedback_smoke_fixture.py') $root $AppJar $Launcher
        if ($LASTEXITCODE -ne 0) { throw "Update feedback fixture failed: $root" }
        $serviceHome = Join-Path $projectRoot 'service'
        $serviceCode = "import sys,runpy; sys.path.insert(0, r'$serviceHome'); sys.argv=['lumen_service','--provider','demo','--port','$port']; runpy.run_module('lumen_service',run_name='__main__')"
        $serviceProcess = Start-Process -FilePath $Python -ArgumentList ('-c "' + $serviceCode + '"') `
            -WorkingDirectory $serviceHome -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $root 'service.stdout.log') -RedirectStandardError (Join-Path $root 'service.stderr.log')
        $deadline = [DateTime]::UtcNow.AddSeconds(12)
        $healthy = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($serviceProcess.HasExited) { throw "Update feedback service exited: $root" }
            try { $healthy = (Invoke-WebRequest "$env:LUMEN_SERVICE_URL/api/health" -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200 } catch { }
            if ($healthy) { break }
            Start-Sleep -Milliseconds 100
        }
        if (-not $healthy) { throw "Update feedback service readiness timed out: $root" }
        $classpath = (@((Join-Path $root 'install/app/lumen-desktop-0.2.0.jar')) + $dependencies +
            @((Join-Path $projectRoot 'desktop/target/test-classes'))) -join ';'
        $arguments = $scaleOption + $(if ($VisualOnly) { ' -Dlumen.update.smoke.visualOnly=true' } else { '' }) + ' -Djavafx.cachedir="' + (Join-Path $root 'javafx-cache') + '" -cp "' + $classpath + '" com.lumenmusic.UpdateFeedbackSmokeLauncher'
        $desktopProcess = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $projectRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'desktop.stdout.log') `
            -RedirectStandardError (Join-Path $root 'desktop.stderr.log')
        Write-Output "NATIVE INTERACTION PROFILE: $root"
        if (-not $desktopProcess.WaitForExit(1820000)) { throw "Update feedback smoke timed out: $root" }
        $desktopProcess.WaitForExit()
        Get-Content -LiteralPath (Join-Path $root 'desktop.stdout.log')
        Get-Content -LiteralPath (Join-Path $root 'desktop.stderr.log')
        if ($desktopProcess.ExitCode -ne 0) { throw "Update feedback smoke failed: $root" }
        Write-Output "Update feedback $scaleName exit code: 0; artifacts: $root"
    } finally {
        foreach ($owned in @($desktopProcess, $serviceProcess)) {
            if ($owned -and -not $owned.HasExited) { Stop-Process -Id $owned.Id -Force; $owned.WaitForExit(5000) | Out-Null }
        }
    }
}

try {
    if ($Scale -in @('normal','both')) { Invoke-UpdateFeedbackSmoke 'normal' '' }
    if ($Scale -in @('hidpi-1.5','both')) { Invoke-UpdateFeedbackSmoke 'hidpi-1.5' '-Dglass.win.uiScale=1.5' }
} finally {
    foreach ($name in $variables) {
        if ($null -eq $previous[$name]) {
            Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
    }
}
