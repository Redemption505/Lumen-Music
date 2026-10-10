param(
    [string]$AppHome,
    [string]$RuntimeHome,
    [Parameter(Mandatory)][string]$Java,
    [string]$Python,
    [ValidateRange(1, 10)][int]$Samples = 5,
    [ValidateRange(0, 60)][int]$IntervalSeconds = 30,
    [string]$CacheFixture
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ([bool]$AppHome -eq [bool]$RuntimeHome) { throw 'Specify either AppHome or RuntimeHome.' }
$runtime = (Resolve-Path -LiteralPath $(if ($AppHome) { $AppHome } else { $RuntimeHome })).Path
$app = if (Test-Path -LiteralPath (Join-Path $runtime 'app') -PathType Container) { Join-Path $runtime 'app' } else { $runtime }
$Java = (Get-Command $Java -CommandType Application -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath (Join-Path (Split-Path $Java -Parent) 'javac.exe'))) { throw 'A full JDK is required for this engineering probe.' }
if (-not $Python) { $Python = Join-Path $app 'python/python.exe' }
$Python = (Get-Command $Python -CommandType Application -ErrorAction Stop).Source
$driver = Join-Path $projectRoot 'desktop/target/test-classes/com/lumenmusic/StartupPerformanceProbe.class'
$driverSource = Join-Path $projectRoot 'desktop/src/test/java/com/lumenmusic/StartupPerformanceProbe.java'
if (-not (Test-Path -LiteralPath $driver) -or (Get-Item -LiteralPath $driver).LastWriteTimeUtc -lt (Get-Item -LiteralPath $driverSource).LastWriteTimeUtc) {
    throw 'Compile the selected probe with Maven test-compile before running.'
}
$jars = @(Get-ChildItem -LiteralPath $app -Filter '*.jar' -File | Sort-Object Name)
$dependencies = @($jars | Where-Object Name -notlike 'lumen-desktop-*' | ForEach-Object FullName)
if ($dependencies.Count -eq 0) { throw 'Runtime dependency JARs are required.' }
$production = if ($AppHome) {
    $cfg = Get-Content -LiteralPath (Join-Path $app 'LumenMusic.cfg')
    $line = @($cfg | Where-Object { $_ -match '^app\.classpath=.*[\\/]lumen-desktop-[^\\/]+\.jar$' })
    if ($line.Count -ne 1) { throw 'Expected one configured production JAR.' }
    Join-Path $app (($line[0] -split '=', 2)[1] -replace '^\$APPDIR[\\/]', '')
} else { Join-Path $projectRoot 'desktop/target/classes' }
if (-not (Test-Path -LiteralPath $production)) { throw 'Production input does not exist.' }
$classpath = (@($production) + $dependencies + @(Join-Path $projectRoot 'desktop/target/test-classes')) -join ';'
$root = Join-Path $projectRoot ('build/startup-probe/' + [Guid]::NewGuid().ToString('N'))
$profile = Join-Path $root 'profile'
New-Item -ItemType Directory -Path $profile -Force | Out-Null
if ($CacheFixture) {
    $fixture = (Resolve-Path -LiteralPath $CacheFixture).Path
    $buildRoot = (Join-Path $projectRoot 'build') + [IO.Path]::DirectorySeparatorChar
    if (-not $fixture.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use a synthetic cache fixture under build.' }
    $cache = Join-Path $profile 'audio-cache'
    New-Item -ItemType Directory -Path $cache | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $fixture -File) {
        if ($file.Name -eq 'settings.properties' -or $file.Name -match '^[0-9a-f]{64}\.(m4a|properties)$') {
            Copy-Item -LiteralPath $file.FullName -Destination $cache
        }
    }
}
$variables = @('LOCALAPPDATA', 'APPDATA', 'TEMP', 'TMP', 'LUMEN_LIBRARY_PATH', 'LUMEN_HISTORY_PATH',
    'LUMEN_PREFERENCES_PATH', 'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL',
    'LUMEN_PYTHON', 'LUMEN_PROVIDER', 'LUMEN_VLC_HOME', 'PYTHONPATH', 'PYTHONPYCACHEPREFIX',
    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'LUMEN_AI_API_KEY', 'OPENAI_API_KEY',
    'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'LUMEN_AI_ENABLED')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$process = $null
$results = @()
function Free-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}
try {
    $env:LOCALAPPDATA = $profile
    $env:APPDATA = Join-Path $profile 'roaming'
    $env:TEMP = Join-Path $profile 'temp'
    $env:TMP = $env:TEMP
    New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
    $env:LUMEN_LIBRARY_PATH = Join-Path $profile 'library.json'
    $env:LUMEN_HISTORY_PATH = Join-Path $profile 'history.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $profile 'preferences.properties'
    $env:LUMEN_AI_SETTINGS_PATH = Join-Path $profile 'ai-settings.json'
    $env:LUMEN_SERVICE_URL = 'http://127.0.0.1:' + (Free-Port)
    do { $proxyPort = Free-Port } while ($env:LUMEN_SERVICE_URL.EndsWith(':' + $proxyPort))
    $env:LUMEN_STREAM_URL = 'http://127.0.0.1:' + $proxyPort
    $env:LUMEN_PYTHON = $Python
    $env:LUMEN_PROVIDER = 'demo'
    $env:LUMEN_VLC_HOME = Join-Path $app 'vlc'
    $env:PYTHONPYCACHEPREFIX = Join-Path $profile 'pycache'
    foreach ($name in @('PYTHONPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
        'LUMEN_AI_API_KEY', 'OPENAI_API_KEY', 'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'LUMEN_AI_ENABLED')) {
        Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
    }
    for ($index = 1; $index -le $Samples; $index++) {
        $report = Join-Path $profile ('trial-' + $index + '.json')
        $stdout = Join-Path $root ('trial-' + $index + '.stdout.log')
        $stderr = Join-Path $root ('trial-' + $index + '.stderr.log')
        $arguments = @('-Dglass.win.uiScale=1.0', ('"-Djavafx.cachedir=' + (Join-Path $profile 'javafx-cache') + '"'),
            ('"-Djna.library.path=' + (Join-Path $app 'vlc') + '"'), '-cp', ('"' + $classpath + '"'),
            'com.lumenmusic.StartupPerformanceProbe', ('"' + $report + '"'))
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $process = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $projectRoot `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        if (-not $process.WaitForExit(60000)) { throw "Probe timeout; evidence retained: $root" }
        $process.WaitForExit()
        $watch.Stop()
        if ($process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $report)) { throw "Probe failed; inspect $stdout and $stderr" }
        $observation = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
        if (-not $observation.passed) { throw 'Probe did not confirm the scenario.' }
        # The console host of the test JVM can exit only after the JVM. Preserve every
        # descendant PID and check the final process boundary here, including that host.
        $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(8)
        do {
            $remaining = @($observation.observedChildPids | ForEach-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
            if ($remaining.Count -eq 0) { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $cleanupDeadline)
        if ($remaining.Count -gt 0) { throw "A captured test descendant remained after JVM exit; retain evidence: $root" }
        $observation | Add-Member -NotePropertyName ownedChildrenStopped -NotePropertyValue $true
        $observation | Add-Member -NotePropertyName engineeringProcessWallMilliseconds -NotePropertyValue $watch.ElapsedMilliseconds
        [IO.File]::WriteAllText($report, ($observation | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
        $results += $observation
        Write-Output "Trial $index/$Samples passed; window=$($observation.windowObservedMs) ms; artifacts=$root"
        if ($index -lt $Samples -and $IntervalSeconds -gt 0) { Start-Sleep -Seconds $IntervalSeconds }
    }
    $summary = [ordered]@{
        passed = $true; samples = $Samples; intervalSeconds = $IntervalSeconds; repeatedSyntheticProfile = $true
        engineeringJavaLauncher = $true; nativeExecutableLaunch = $false; osRebootTrial = $false
        productionInput = $production; driverSha256 = (Get-FileHash -LiteralPath $driver -Algorithm SHA256).Hash.ToLowerInvariant()
        cacheFixturePresent = [bool]$CacheFixture; observations = $results
    }
    if ($AppHome) { $summary.productionJarSha256 = (Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash.ToLowerInvariant() }
    [IO.File]::WriteAllText((Join-Path $root 'summary.json'), ($summary | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Write-Output "PASS engineering launcher/service/UI/normal-exit: $root"
} finally {
    if ($process -and -not $process.HasExited) {
        # This is the exact process created above; never kill by application name or port.
        & taskkill /F /T /PID $process.Id 2>$null | Out-Null
        [void]$process.WaitForExit(5000)
    }
    foreach ($name in $variables) {
        if ($null -eq $previous[$name]) { Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
}
