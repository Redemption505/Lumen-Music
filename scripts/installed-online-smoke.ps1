param(
    [Alias('AppHome')][string]$InstalledHome = (Join-Path $env:USERPROFILE 'AppData/Local/LumenMusic'),
    [string]$Python,
    [string]$PrimaryBv = 'BV1BZbSzZEGT',
    [string]$SecondaryBv = 'BV1hRNe6wEzV',
    [switch]$ReleaseChecks,
    [switch]$Normalization,
    [switch]$PublicSegments,
    [ValidateRange(300, 1800)][int]$LongPlaybackSeconds = 300,
    [ValidateSet('Default', 'DirectOnly', 'UnavailableProxy')][string]$NetworkScenario = 'Default'
)

$ErrorActionPreference = 'Stop'
if ($Normalization) {
    if ($PublicSegments) { throw '-Normalization applies to the continuous online release acceptance.' }
    $ReleaseChecks = $true
}
if ($ReleaseChecks -and $PublicSegments) { throw 'Select either -ReleaseChecks or -PublicSegments.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$installedRoot = (Resolve-Path -LiteralPath $InstalledHome).Path
$installedApp = Join-Path $installedRoot 'app'
$cfg = Join-Path $installedApp 'LumenMusic.cfg'
if (-not (Test-Path -LiteralPath $cfg -PathType Leaf)) { throw "Missing installed launcher configuration: $cfg" }
$configuration = Get-Content -LiteralPath $cfg
$mainEntry = @($configuration | Where-Object { $_ -match '^app\.mainjar=' })
if ($mainEntry.Count -eq 0) {
    $mainEntry = @($configuration | Where-Object { $_ -match '^app\.classpath=.*[\\/]lumen-desktop-[^\\/]+\.jar$' })
}
if ($mainEntry.Count -ne 1) { throw "Expected exactly one configured main JAR in $cfg" }
$mainName = ($mainEntry[0] -split '=', 2)[1].Trim() -replace '^\$APPDIR[\\/]', ''
$installedJar = Join-Path $installedApp $mainName
$installedVlc = Join-Path $installedApp 'vlc'
$serviceHome = Join-Path $installedApp 'service'
$proxyExe = Join-Path $installedApp 'stream-proxy/lumen-stream-proxy.exe'
$java = Join-Path $installedRoot 'runtime/bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    $java = (Get-Command java -CommandType Application | Select-Object -First 1).Source
}
if (-not $Python) {
    $bundledPython = Join-Path $installedApp 'python/python.exe'
    if (Test-Path -LiteralPath $bundledPython -PathType Leaf) { $Python = $bundledPython }
}
if (-not $Python) {
    $windowsApps = Join-Path $env:USERPROFILE 'AppData/Local/Microsoft/WindowsApps'
    if (Test-Path -LiteralPath (Join-Path $windowsApps 'python.exe') -PathType Leaf) {
        $Python = Join-Path $windowsApps 'python.exe'
    } else {
        $candidate = Get-ChildItem -Path (Join-Path $windowsApps 'PythonSoftwareFoundation.Python.*/python.exe') `
            -File -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        if ($candidate) { $Python = $candidate.FullName }
        else { throw 'Pass -Python with the actual WindowsApps Python executable path.' }
    }
}
$Python = (Resolve-Path -LiteralPath $Python).Path
foreach ($required in @($java, $Python, $installedJar, (Join-Path $installedVlc 'libvlc.dll'),
        (Join-Path $serviceHome 'lumen_service/__main__.py'), $proxyExe)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing installed acceptance input: $required" }
}
if (-not (Test-Path -LiteralPath (Join-Path $installedVlc 'plugins') -PathType Container)) {
    throw "Missing installed VLC plugins: $installedVlc"
}
foreach ($bvid in @($PrimaryBv, $SecondaryBv)) {
    if ($bvid -cnotmatch '^BV[0-9A-Za-z]{10}$') { throw "Invalid smoke BV identity: $bvid" }
}
if ($PrimaryBv -ceq $SecondaryBv) { throw 'The two smoke BV identities must differ.' }

$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$compileTests = $false
$smokeNames = if ($PublicSegments) { @('ReleasePublicSegmentSmoke', 'ReleasePublicSegmentSmokeLauncher') }
    elseif ($ReleaseChecks) { @('ReleaseOnlinePlaybackSmoke', 'ReleaseOnlinePlaybackSmokeLauncher') }
    else { @('OnlinePlaybackSmoke', 'OnlinePlaybackSmokeLauncher') }
foreach ($name in $smokeNames) {
    $compiled = Join-Path $testClasses "com/lumenmusic/$name.class"
    $source = Join-Path $projectRoot "desktop/src/test/java/com/lumenmusic/$name.java"
    if (-not (Test-Path -LiteralPath $compiled -PathType Leaf) -or
        (Get-Item -LiteralPath $source).LastWriteTimeUtc -gt (Get-Item -LiteralPath $compiled).LastWriteTimeUtc) {
        $compileTests = $true
    }
}
if ($compileTests) {
    $cachedMaven = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.11') `
        -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
    $wrapper = if ($cachedMaven) { $cachedMaven.FullName } else { Join-Path $projectRoot 'desktop/mvnw.cmd' }
    & $wrapper -f (Join-Path $projectRoot 'desktop/pom.xml') -o -B -ntp `
        "-Dmaven.repo.local=$env:USERPROFILE/.m2/repository" test-compile
    if ($LASTEXITCODE -ne 0) { throw "Offline smoke compilation failed ($LASTEXITCODE)." }
}

# The installed production JAR wins class lookup; development target/classes is excluded.
$dependencies = @(Get-ChildItem -LiteralPath $installedApp -Filter '*.jar' -File |
    Where-Object { $_.FullName -ne $installedJar } | Sort-Object Name | ForEach-Object { $_.FullName })
$classPath = (@($installedJar) + $dependencies + @($testClasses)) -join [IO.Path]::PathSeparator
$testRoot = Join-Path $projectRoot ('build/installed-online-smoke/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot 'isolated-online-smoke.marker') | Out-Null

function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally { $listener.Stop() }
}
function Wait-Service([string]$Url, [Diagnostics.Process]$Process, [DateTime]$Deadline) {
    while ([DateTime]::UtcNow -lt $Deadline) {
        if ($Process.HasExited) { throw "Service process exited before readiness: $Url" }
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 1 -UseBasicParsing
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 100
    }
    throw "Service readiness timed out: $Url"
}
function Stop-OwnedProcess([Diagnostics.Process]$Process) {
    if ($Process) {
        try {
            if (-not $Process.HasExited) {
                $Process.Kill()
                if (-not $Process.WaitForExit(5000)) {
                    throw "Owned process did not exit after termination: $($Process.Id)"
                }
            }
        } finally { $Process.Dispose() }
    }
}
function Test-LoopbackPortClosed([int]$Port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Server.ExclusiveAddressUse = $true
        try {
            $listener.Start()
            return $true
        } catch [System.Net.Sockets.SocketException] {
            $socketError = $_.Exception.GetBaseException()
            if ($socketError.SocketErrorCode -eq [System.Net.Sockets.SocketError]::AddressAlreadyInUse -or
                $socketError.SocketErrorCode -eq [System.Net.Sockets.SocketError]::AccessDenied) {
                return $false
            }
            throw
        }
    } finally { $listener.Stop() }
}
$pythonPort = Get-FreeLoopbackPort
$proxyPort = Get-FreeLoopbackPort
while ($proxyPort -eq $pythonPort) { $proxyPort = Get-FreeLoopbackPort }
$variables = @('LUMEN_SMOKE_ROOT', 'LUMEN_LIBRARY_PATH', 'LUMEN_HISTORY_PATH', 'LUMEN_PREFERENCES_PATH',
    'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME',
    'LUMEN_PROXY_ADDR', 'LUMEN_PYTHON_SERVICE_URL', 'LUMEN_SMOKE_BV_PRIMARY', 'LUMEN_SMOKE_BV_SECONDARY',
    'LUMEN_AI_API_KEY', 'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'PYTHONPATH', 'PYTHONUNBUFFERED',
    'PYTHONPYCACHEPREFIX', 'LOCALAPPDATA', 'OPENAI_API_KEY', 'LUMEN_AI_ENABLED')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$pythonProcess = $null
$proxyProcess = $null
$desktopProcess = $null
$stdout = Join-Path $testRoot 'desktop.stdout.log'
$stderr = Join-Path $testRoot 'desktop.stderr.log'
$classLog = Join-Path $testRoot 'class-load.log'
$acceptanceFailure = $null
try {
    $env:LUMEN_SMOKE_ROOT = $testRoot
    $env:LOCALAPPDATA = $testRoot
    $env:LUMEN_LIBRARY_PATH = Join-Path $testRoot 'library.json'
    $env:LUMEN_HISTORY_PATH = Join-Path $testRoot 'history.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $testRoot 'preferences.properties'
    if ($Normalization) {
        [IO.File]::WriteAllText($env:LUMEN_PREFERENCES_PATH, "audio.normalization=true`n", [Text.UTF8Encoding]::new($false))
    }
    $env:LUMEN_AI_SETTINGS_PATH = Join-Path $testRoot 'ai-settings.json'
    $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$pythonPort"
    $env:LUMEN_STREAM_URL = "http://127.0.0.1:$proxyPort"
    $env:LUMEN_VLC_HOME = $installedVlc
    $env:LUMEN_PROXY_ADDR = "127.0.0.1:$proxyPort"
    $env:LUMEN_PYTHON_SERVICE_URL = $env:LUMEN_SERVICE_URL
    $env:LUMEN_SMOKE_BV_PRIMARY = $PrimaryBv
    $env:LUMEN_SMOKE_BV_SECONDARY = $SecondaryBv
    $env:LUMEN_AI_API_KEY = ''
    $env:LUMEN_AI_CHAT_COMPLETIONS_URL = ''
    $env:LUMEN_AI_MODEL = ''
    $env:OPENAI_API_KEY = ''
    $env:LUMEN_AI_ENABLED = ''
    $env:PYTHONPATH = $serviceHome
    $env:PYTHONUNBUFFERED = '1'
    $env:PYTHONPYCACHEPREFIX = Join-Path $testRoot 'pycache'
    Write-Output "Installed production JAR: $installedJar"
    Write-Output "Installed production JAR SHA-256: $((Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash)"
    Write-Output "Installed service: $serviceHome"
    Write-Output "Installed proxy: $proxyExe"
    Write-Output "Actual Python executable: $Python"
    Write-Output "Network scenario: $NetworkScenario (process-local; system configuration unchanged)"
    if ($Normalization) { Write-Output 'Normalization acceptance enabled: dense initial clock sampling and actual VLC measurement.' }
    Write-Output "Acceptance artifacts: $testRoot"
    $runtimeSeconds = if ($PublicSegments) { 200 } elseif ($ReleaseChecks) { $LongPlaybackSeconds + 190 } else { 95 }
    $deadline = [DateTime]::UtcNow.AddSeconds($runtimeSeconds)
    $serviceArguments = @('-m', 'lumen_service', '--port', $pythonPort, '--provider', 'bilibili')
    if ($NetworkScenario -ne 'Default') {
        $scenarioEntry = Join-Path $PSScriptRoot 'online-smoke-service.py'
        $serviceArguments = @(('"' + $scenarioEntry + '"'), $NetworkScenario, '--port', $pythonPort,
            '--provider', 'bilibili')
    }
    $pythonProcess = Start-Process -FilePath $Python -ArgumentList $serviceArguments `
        -WorkingDirectory $testRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testRoot 'python.stdout.log') `
        -RedirectStandardError (Join-Path $testRoot 'python.stderr.log')
    Wait-Service "$env:LUMEN_SERVICE_URL/api/health" $pythonProcess ([DateTime]::UtcNow.AddSeconds(10))
    $proxyProcess = Start-Process -FilePath $proxyExe -WorkingDirectory $testRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testRoot 'proxy.stdout.log') `
        -RedirectStandardError (Join-Path $testRoot 'proxy.stderr.log')
    Wait-Service "$env:LUMEN_STREAM_URL/health" $proxyProcess ([DateTime]::UtcNow.AddSeconds(5))
    $arguments = @('-Xlog:class+load=info:file=class-load.log',
        ('"-Djavafx.cachedir=' + (Join-Path $testRoot 'javafx-cache') + '"'),
        ('"-Dlumen.smoke.installed.jar=' + $installedJar + '"'),
        ('-Dlumen.stream.base=' + $env:LUMEN_STREAM_URL), '-cp', ('"' + $classPath + '"'),
        $(if ($ReleaseChecks) { '-Dlumen.smoke.longPlaybackSeconds=' + $LongPlaybackSeconds }),
        $(if ($Normalization) { '-Dlumen.smoke.normalization=true' }),
        ('com.lumenmusic.' + $smokeNames[1])) | Where-Object { $_ }
    $desktopProcess = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $testRoot `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    while (-not $desktopProcess.WaitForExit(1000) -and [DateTime]::UtcNow -lt $deadline) {
        if ($pythonProcess.HasExited -or $proxyProcess.HasExited) { throw 'An owned online service exited during acceptance.' }
    }
    if (-not $desktopProcess.HasExited) { throw "Installed online acceptance exceeded its $runtimeSeconds-second runtime limit." }
    $desktopProcess.WaitForExit()
    $desktopProcess.Refresh()
    Get-Content -LiteralPath $stdout
    Get-Content -LiteralPath $stderr
    if ($desktopProcess.ExitCode -ne 0) { throw "Installed online acceptance failed ($($desktopProcess.ExitCode))." }
    $successMarker = if ($PublicSegments) { 'PASS: release public segment JavaFX' }
        elseif ($ReleaseChecks) { 'PASS: release online JavaFX' } else { 'PASS: installed online JavaFX' }
    if (-not (Select-String -LiteralPath $stdout -SimpleMatch $successMarker -Quiet)) {
        throw "Online playback success marker is missing: $stdout"
    }
    $origin = Select-String -LiteralPath $classLog -Pattern 'com\.lumenmusic\.LumenFxApp source: (.+)$' |
        Select-Object -First 1
    if (-not $origin) { throw "Installed class origin is missing: $classLog" }
    $loadedFrom = [Uri]::new($origin.Matches[0].Groups[1].Value)
    if (-not $loadedFrom.IsFile -or -not [string]::Equals([IO.Path]::GetFullPath($loadedFrom.LocalPath),
        [IO.Path]::GetFullPath($installedJar), [StringComparison]::OrdinalIgnoreCase)) {
        throw "Production class did not load from the installed JAR: $loadedFrom"
    }
    Write-Output "Verified installed production class origin: $($loadedFrom.LocalPath)"
} catch {
    $acceptanceFailure = $_
    throw
} finally {
    $cleanupFailures = [System.Collections.Generic.List[string]]::new()
    foreach ($ownedProcess in @($desktopProcess, $proxyProcess, $pythonProcess)) {
        try { Stop-OwnedProcess $ownedProcess }
        catch { $cleanupFailures.Add($_.Exception.Message) }
    }
    foreach ($name in $variables) {
        try { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        catch { $cleanupFailures.Add($_.Exception.Message) }
    }
    foreach ($port in @($pythonPort, $proxyPort)) {
        try {
            if (Test-LoopbackPortClosed $port) { Write-Output "Verified released service port: 127.0.0.1:$port" }
            else { $cleanupFailures.Add("Owned service port remains active: 127.0.0.1:$port") }
        } catch { $cleanupFailures.Add($_.Exception.Message) }
    }
    Write-Output "Acceptance artifacts retained at $testRoot"
    if ($cleanupFailures.Count -gt 0) {
        $cleanupMessage = 'Acceptance cleanup failed: ' + ($cleanupFailures -join '; ')
        if ($null -ne $acceptanceFailure) {
            Write-Warning $cleanupMessage
        } else {
            throw $cleanupMessage
        }
    }
}
