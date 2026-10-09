param([Parameter(Mandatory)][string]$InstalledHome, [Parameter(Mandatory)][string]$BuildId)
$ErrorActionPreference = 'Stop'
$InstalledHome = (Resolve-Path -LiteralPath $InstalledHome).Path.TrimEnd('\')
$root = Join-Path (Split-Path $PSScriptRoot -Parent) ('build/installed-personal-state/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$data = Join-Path $env:LOCALAPPDATA 'Lumen Music'
$roaming = Join-Path $env:APPDATA 'Lumen Music'
foreach ($name in @('LUMEN_LIBRARY_PATH', 'LUMEN_HISTORY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_SERVICE_URL')) {
    if ([Environment]::GetEnvironmentVariable($name, 'Process')) { throw 'Default-profile acceptance requires the actual default launch environment.' }
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Properties([string]$Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^([^#!][^=]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}
function Queue-State {
    [xml]$xml = [IO.File]::ReadAllText((Join-Path $data 'playback-state.xml'))
    $entries = @{}; foreach ($entry in $xml.properties.entry) { $entries[$entry.key] = $entry.InnerText }
    return $entries
}
function Assert([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
$jar = Join-Path $InstalledHome 'app/lumen-desktop-0.2.2.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $reader = [IO.StreamReader]::new($zip.GetEntry('lumen-build.properties').Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
Assert ($metadata -match ('(?m)^build\.id=' + [regex]::Escape($BuildId) + '\r?$')) 'Installed build does not match.'
$libraryPath = Join-Path $data 'library.json'
$historyPath = Join-Path $data 'history.json'
$beforeHashes = @{ library = Hash $libraryPath; history = Hash $historyPath }
$library = [IO.File]::ReadAllText($libraryPath) | ConvertFrom-Json
$preferencesPath = Join-Path $roaming 'preferences.properties'
$beforePreferences = Properties $preferencesPath
$beforeQueue = Queue-State
$executable = Join-Path $InstalledHome 'LumenMusic.exe'
$existing = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstalledHome + '\', [StringComparison]::OrdinalIgnoreCase) })
Assert ($existing.Count -eq 0) 'Close the installed player before acceptance.'
foreach ($port in @(8765,8766)) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
    try { $listener.Start() } finally { $listener.Stop() }
}
$process = $null
$window = $null
$watch = [Diagnostics.Stopwatch]::StartNew()
try {
    $process = Start-Process -FilePath $executable -WorkingDirectory $InstalledHome -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    $windowMs = $null; $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        Assert (-not $process.HasExited) 'The installed player exited during startup.'
        if ($null -eq $window) {
            $bundled = @(Get-CimInstance Win32_Process | Where-Object {
                $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstalledHome + '\', [StringComparison]::OrdinalIgnoreCase)
            })
            $ownedIds = @([uint32]$process.Id)
            do {
                $oldCount = $ownedIds.Count
                $ownedIds = @($ownedIds + @($bundled | Where-Object { $_.ParentProcessId -in $ownedIds } |
                    ForEach-Object { [uint32]$_.ProcessId }) | Select-Object -Unique)
            } while ($ownedIds.Count -gt $oldCount)
            $window = $bundled | Where-Object { $_.ProcessId -in $ownedIds -and $_.Name -eq 'LumenMusic.exe' } |
                ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue } |
                Where-Object MainWindowHandle -ne 0 | Select-Object -First 1
            if ($null -ne $window) {
                $windowMs = $watch.ElapsedMilliseconds
                # Retain a handle before shutdown: Get-Process is attached to an existing
                # child, unlike Start-Process, and otherwise its exit code may be unavailable.
                [void]$window.Handle
            }
        }
        try {
            [xml]$health = (Invoke-WebRequest 'http://127.0.0.1:8765/api/health' -TimeoutSec 1).Content
            if ($health.health.provider -eq 'bilibili' -and $null -ne $windowMs) { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 150
    }
    Assert $ready 'The default installed profile did not become ready.'
    $jvm = @($window.Modules | Where-Object { $_.ModuleName -ieq 'jvm.dll' })
    Assert ($jvm.Count -eq 1 -and $jvm[0].FileName -ieq (Join-Path $InstalledHome 'runtime/bin/server/jvm.dll')) 'The actual native window is not using the bundled JVM.'
    [xml]$response = (Invoke-WebRequest 'http://127.0.0.1:8765/api/library' -TimeoutSec 5).Content
    Assert (@($response.library.favorites.track).Where({$null -ne $_}).Count -eq @($library.favorites).Count) 'Original favorites were not loaded.'
    Assert (@($response.library.playlists.playlist).Where({$null -ne $_}).Count -eq @($library.playlists).Count) 'Original playlists were not loaded.'
    Assert ($window.CloseMainWindow() -and $window.WaitForExit(15000) -and $process.WaitForExit(10000)) 'The actual installed window did not close normally.'
    Assert ($window.ExitCode -eq 0 -and $process.ExitCode -eq 0) "Installed native exit codes: window=$($window.ExitCode), launcher=$($process.ExitCode)."
    Assert ((Hash $libraryPath) -ceq $beforeHashes.library -and (Hash $historyPath) -ceq $beforeHashes.history) 'Original library or listening history changed during read-only startup.'
    $afterPreferences = Properties $preferencesPath
    foreach ($key in $beforePreferences.Keys) {
        Assert ($afterPreferences.ContainsKey($key) -and $afterPreferences[$key] -ceq $beforePreferences[$key]) 'A saved preference changed during default-profile startup.'
    }
    $afterQueue = Queue-State
    foreach ($key in $beforeQueue.Keys) { Assert ($afterQueue[$key] -ceq $beforeQueue[$key]) 'The saved queue or position changed during paused startup.' }
    $remaining = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstalledHome + '\',[StringComparison]::OrdinalIgnoreCase) })
    Assert ($remaining.Count -eq 0) 'A bundled child process remained after normal shutdown.'
    $result = [ordered]@{ Passed=$true; InstalledHome=$InstalledHome; BuildId=$BuildId; JarSha256=Hash $jar;
        ActualLocalDataDirectory=$data; ActualRoamingDataDirectory=$roaming; ProfileRedirected=$false;
        WindowObservedMilliseconds=$windowMs; BundledJvm=$jvm[0].FileName; OriginalFavoritesLoaded=@($library.favorites).Count;
        OriginalPlaylistsLoaded=@($library.playlists).Count; OriginalLibraryAndHistoryUnchanged=$true;
        PreferencesPreserved=$true; SavedQueueAndPositionPreserved=$true; NormalExit=$true; ChildProcessesStopped=$true }
    [IO.File]::WriteAllText((Join-Path $root 'evidence.json'),($result | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "PASS actual installed default profile, original personal library/preferences/paused queue and normal shutdown: $root"
} finally {
    if ($window -and -not $window.HasExited) {
        [void]$window.CloseMainWindow(); [void]$window.WaitForExit(15000)
    }
    if ($process -and -not $process.HasExited) {
        [void]$process.WaitForExit(10000)
    }
}
