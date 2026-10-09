param(
    [Parameter(Mandatory)][string]$AppHome,
    [Parameter(Mandatory)][string]$Java,
    [ValidateSet('1.25', '1.5')][string]$Scale = '1.25',
    [Parameter(Mandatory)][string]$FrozenService,
    [string]$ExistingProfile
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$AppHome = (Resolve-Path -LiteralPath $AppHome).Path
$app = Join-Path $AppHome 'app'
$python = Join-Path $app 'python/python.exe'
$serviceHome = Join-Path $app 'service'
$FrozenService = (Resolve-Path -LiteralPath $FrozenService).Path
$jar = @(Get-ChildItem -LiteralPath $app -Filter 'lumen-desktop-*.jar')
if ($jar.Count -ne 1) { throw 'AppHome must contain exactly one release desktop JAR' }
foreach ($path in @($Java, $python, (Join-Path $serviceHome 'lumen_service/__main__.py'),
        (Join-Path $app 'vlc/libvlc.dll'), (Join-Path $projectRoot 'desktop/target/test-classes/com/lumenmusic/Phase3ReleaseSmoke.class'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required release or test input missing: $path" }
}
# Production JAR and packaged dependencies precede test classes; target/classes is deliberately absent.
$classpath = (@($jar[0].FullName) + @(Get-ChildItem -LiteralPath $app -Filter '*.jar' |
    Where-Object Name -NotLike 'lumen-desktop-*' | ForEach-Object FullName) +
    @((Join-Path $projectRoot 'desktop/target/test-classes'))) -join ';'
if ($ExistingProfile) {
    $root = (Resolve-Path -LiteralPath $ExistingProfile).Path
    $allowed = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/phase3-release-smoke')) + [IO.Path]::DirectorySeparatorChar
    if (-not $root.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath "$root/restart-expected.properties")) { throw 'ExistingProfile must be a completed isolated release-smoke profile' }
} else {
    $root = Join-Path $projectRoot ('build/phase3-release-smoke/dpi-' + $Scale + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root | Out-Null
}
$values = @{
    LOCALAPPDATA = $root; LUMEN_LIBRARY_PATH = "$root/library.json"
    LUMEN_HISTORY_PATH = "$root/history.json"; LUMEN_PREFERENCES_PATH = "$root/preferences.properties"
    LUMEN_AI_SETTINGS_PATH = "$root/ai-settings.json"; LUMEN_AI_API_KEY = $null; OPENAI_API_KEY = $null
    LUMEN_VLC_HOME = (Join-Path $app 'vlc'); LUMEN_RELEASE_JAR = $jar[0].FullName
    LUMEN_RELEASE_SCALE = $Scale; LUMEN_RELEASE_MODE = 'workflow'
    LUMEN_SERVICE_URL = ''; LUMEN_STREAM_URL = ''; LUMEN_PROVIDER = 'demo'
    PYTHONPATH = ''; PYTHONHOME = ''; LUMEN_PYTHON = $python; LUMEN_SERVICE_HOME = $serviceHome
}
$previous = @{}
foreach ($key in $values.Keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$serviceProcess = $null
$desktopProcess = $null
function FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}
try {
    foreach ($key in $values.Keys) {
        if ($null -eq $values[$key]) {
            Remove-Item -LiteralPath ('Env:\' + $key) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
        }
    }
    if (-not $ExistingProfile) {
        & $python (Join-Path $projectRoot 'service/tests/phase3_release_smoke_fixture.py') $root $FrozenService
        if ($LASTEXITCODE -ne 0) { throw 'Frozen legacy fixture preparation failed' }
    }
    $evidence = [ordered]@{
        appHome = $AppHome; productionJar = $jar[0].FullName
        productionJarSha256 = (Get-FileHash -LiteralPath $jar[0].FullName -Algorithm SHA256).Hash
        serviceHome = $serviceHome; python = $python; vlcHome = $env:LUMEN_VLC_HOME
        javaHarnessHost = $Java; scale = $Scale; classpath = $classpath
        note = 'The test harness uses an explicit JDK; production classes, service, Python and VLC come from AppHome.'
        processes = @()
    }
    $modes = if ($ExistingProfile) { @('performance') } else { @('workflow', 'restart') }
    foreach ($mode in $modes) {
        $port = FreePort
        $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
        $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
        $env:LUMEN_RELEASE_MODE = $mode
        $serviceProcess = Start-Process -FilePath $python -ArgumentList @('-m', 'lumen_service', '--provider', 'demo', '--port', $port) `
            -WorkingDirectory $serviceHome -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput "$root/$mode-service.stdout.log" -RedirectStandardError "$root/$mode-service.stderr.log"
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        $healthy = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($serviceProcess.HasExited) { throw "Release service exited before readiness: $mode" }
            try { $healthy = (Invoke-WebRequest "$env:LUMEN_SERVICE_URL/api/health" -TimeoutSec 1).StatusCode -eq 200 } catch { }
            if ($healthy) { break }
            Start-Sleep -Milliseconds 100
        }
        if (-not $healthy) { throw 'Release service readiness timed out' }
        $arguments = '-Dglass.win.uiScale=' + $Scale + ' -Djavafx.cachedir="' + "$root/javafx-cache" + '" -cp "' + $classpath + '" com.lumenmusic.Phase3ReleaseSmokeLauncher'
        $desktopProcess = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $AppHome `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root/$mode-desktop.stdout.log" -RedirectStandardError "$root/$mode-desktop.stderr.log"
        $evidence.processes += [ordered]@{ mode = $mode; servicePid = $serviceProcess.Id; desktopPid = $desktopProcess.Id; servicePort = $port }
        $evidenceFile = if ($ExistingProfile) { "$root/performance-release-input-evidence.json" } else { "$root/release-input-evidence.json" }
        $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidenceFile -Encoding utf8
        if (-not $desktopProcess.WaitForExit(220000)) { throw "Release native acceptance timed out: $mode" }
        $desktopProcess.WaitForExit()
        Get-Content -LiteralPath "$root/$mode-desktop.stdout.log"
        Get-Content -LiteralPath "$root/$mode-desktop.stderr.log"
        if ($desktopProcess.ExitCode -ne 0) { throw "Release native acceptance failed: $mode; artifacts: $root" }
        Stop-Process -Id $serviceProcess.Id
        if (-not $serviceProcess.WaitForExit(10000)) { throw 'Owned release service failed to stop' }
        $serviceProcess = $null
        $desktopProcess = $null
    }
    if (@(Import-Csv -LiteralPath "$root/playlist-scroll.csv").Count -ne 9) { throw 'Expected nine measured 2000-track viewport samples' }
    if (-not (Test-Path -LiteralPath "$root/restart-pass.txt")) { throw 'Independent restart verification missing' }
    $cleanupFile = if ($ExistingProfile) { "$root/performance-process-cleanup.txt" } else { "$root/process-cleanup.txt" }
    "PASS $($modes.Count) fresh desktop/service process lifecycles; owned processes exited" | Set-Content -LiteralPath $cleanupFile
    $result = if ($ExistingProfile) { 'visible 2000-track samples and persisted-state verification' } else { 'integrated workflow, independent restart and 2000-track samples' }
    Write-Output "PASS release JAR $result at $Scale DPI; artifacts: $root"
} finally {
    foreach ($owned in @($desktopProcess, $serviceProcess)) {
        if ($owned -and -not $owned.HasExited) { Stop-Process -Id $owned.Id; $owned.WaitForExit(5000) | Out-Null }
    }
    foreach ($key in $previous.Keys) {
        if ($null -eq $previous[$key]) {
            Remove-Item -LiteralPath ('Env:\' + $key) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
        }
    }
}
