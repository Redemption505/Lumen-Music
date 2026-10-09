param([Parameter(Mandatory)][string]$AppHome)
$ErrorActionPreference = 'Stop'
$AppHome = (Resolve-Path $AppHome).Path
$root = Join-Path (Split-Path $PSScriptRoot -Parent) ('build/release-runtime-smoke/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
function FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}
$service = "http://127.0.0.1:$(FreePort)"
$proxy = "http://127.0.0.1:$(FreePort)"
$values = @{
    PATH = "$env:SystemRoot/System32;$env:SystemRoot"
    JAVA_HOME = ''; PYTHONHOME = ''; PYTHONPATH = ''; LUMEN_PYTHON = ''
    JAVA_TOOL_OPTIONS = ''; JDK_JAVA_OPTIONS = ''; _JAVA_OPTIONS = ''
    APPDATA = "$root/roaming"
    LOCALAPPDATA = $root; LUMEN_PROVIDER = 'demo'; LUMEN_SERVICE_URL = $service
    LUMEN_STREAM_URL = $proxy; LUMEN_LIBRARY_PATH = "$root/library.json"
    LUMEN_HISTORY_PATH = "$root/history.json"; LUMEN_PREFERENCES_PATH = "$root/preferences.properties"
    LUMEN_AI_SETTINGS_PATH = "$root/ai-settings.json"; LUMEN_AI_API_KEY = ''; OPENAI_API_KEY = ''
}
$original = @{}
foreach ($key in $values.Keys) { $original[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$launcher = $null
$processes = @()
$active = @(Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.StartsWith($AppHome + '\', [StringComparison]::OrdinalIgnoreCase)
})
if ($active.Count) { throw 'Close the existing application before native runtime acceptance' }
try {
    foreach ($key in $values.Keys) { [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process') }
    $launcher = Start-Process -FilePath "$AppHome/LumenMusic.exe" -WorkingDirectory $root -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            [xml]$health = (Invoke-WebRequest "$service/api/health" -TimeoutSec 1).Content
            if ($health.health.provider -eq 'demo') { $healthy = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) { throw 'Bundled Python service did not start' }
    [xml]$tracks = (Invoke-WebRequest "$service/api/tracks" -TimeoutSec 5).Content
    if (@($tracks.tracks.track).Count -lt 1) { throw 'Bundled service search failed' }
    $proxyReady = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            Invoke-WebRequest "$proxy/health" -TimeoutSec 1 | Out-Null
            $proxyReady = $true; break
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $proxyReady) { throw 'Bundled proxy did not start' }
    # Demo audio is synthesized by the desktop; the service intentionally has no stream for it.
    $response = Invoke-WebRequest "$proxy/api/tracks/demo-1/stream" -SkipHttpErrorCheck -TimeoutSec 10
    if ($response.StatusCode -ne 501 -or $response.Content -notmatch 'NOT_IMPLEMENTED') {
        throw 'Bundled proxy did not forward the service response'
    }
    $packaged = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($AppHome, [StringComparison]::OrdinalIgnoreCase)
    })
    $ids = @([uint32]$launcher.Id)
    do {
        $previousCount = $ids.Count
        $ids = @($ids + @($packaged | Where-Object { $_.ParentProcessId -in $ids } |
            ForEach-Object { [uint32]$_.ProcessId }) | Select-Object -Unique)
    } while ($ids.Count -gt $previousCount)
    $processes = @($packaged | Where-Object { $_.ProcessId -in $ids })
    if (-not @($processes | Where-Object { $_.ExecutablePath -eq "$AppHome\app\python\python.exe" }).Count) {
        throw 'Service is not using the bundled Python interpreter'
    }
    $window = $null
    foreach ($item in $processes | Where-Object { $_.Name -eq 'LumenMusic.exe' }) {
        $candidate = Get-Process -Id $item.ProcessId
        if ($candidate.MainWindowHandle -ne 0) { $window = $candidate; break }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (-not $window -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 250
        $window = $processes | Where-Object { $_.Name -eq 'LumenMusic.exe' } |
            ForEach-Object { Get-Process -Id $_.ProcessId } | Where-Object MainWindowHandle -ne 0 | Select-Object -First 1
    }
    if (-not $window) { throw 'Native application window missing' }
    $jvm = @($window.Modules | Where-Object { $_.ModuleName -ieq 'jvm.dll' })
    if ($jvm.Count -ne 1 -or -not [string]::Equals($jvm[0].FileName,
            "$AppHome\runtime\bin\server\jvm.dll", [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Native launcher is not using the bundled JVM'
    }
    if (-not $window.CloseMainWindow() -or -not $window.WaitForExit(15000)) { throw 'Native window did not shut down normally' }
    if (-not $launcher.WaitForExit(10000)) { throw 'Native launcher did not shut down' }
    $owned = @($processes | Where-Object { $_.Name -ne 'LumenMusic.exe' } | ForEach-Object { $_.ProcessId })
    foreach ($id in $owned) { if (Get-Process -Id $id -ErrorAction SilentlyContinue) { throw "Owned process survived shutdown: $id" } }
    [IO.File]::WriteAllLines((Join-Path $root 'evidence.txt'), @(
        'PASS: native runtime and normal shutdown',
        "AppHome=$AppHome", "Jvm=$($jvm[0].FileName)",
        "LauncherSha256=$((Get-FileHash -LiteralPath "$AppHome/LumenMusic.exe").Hash.ToLowerInvariant())",
        'DeveloperPathRemoved=true', 'PersonalDataIsolated=true', 'OwnedProcessesStopped=true'
    ), [Text.Encoding]::ASCII)
    Write-Output 'PASS: no developer PATH; bundled Python service/search, proxy forwarding, native JVM window and owned process cleanup'
    Write-Output "Artifacts: $root"
} finally {
    foreach ($item in @($processes | Where-Object { $_.Name -eq 'LumenMusic.exe' })) {
        $running = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
        if ($running -and $running.MainWindowHandle -ne 0) { $running.CloseMainWindow() | Out-Null }
    }
    if ($launcher -and -not $launcher.HasExited) { [void]$launcher.WaitForExit(15000) }
    $remaining = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($AppHome + '\', [StringComparison]::OrdinalIgnoreCase)
    })
    foreach ($item in $remaining) { Stop-Process -Id $item.ProcessId -ErrorAction SilentlyContinue }
    foreach ($key in $original.Keys) { [Environment]::SetEnvironmentVariable($key, $original[$key], 'Process') }
}
