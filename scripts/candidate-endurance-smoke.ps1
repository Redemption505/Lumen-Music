param(
    [Parameter(Mandatory=$true)][string]$AppHome,
    [string]$Python,
    [string]$Java,
    [ValidateSet('Local','Online')][string]$Mode = 'Local',
    [ValidateRange(0,7200)][int]$Seconds = 3600,
    [string]$PrimaryBv = 'BV1BZbSzZEGT',
    [string]$SecondaryBv = 'BV1hRNe6wEzV',
    [switch]$PublicSegments
)
$ErrorActionPreference = 'Stop'
if ($PublicSegments -and ($Mode -ne 'Online' -or $Seconds -ne 0)) { throw 'PublicSegments requires Online mode and Seconds 0.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$appRoot = (Resolve-Path -LiteralPath $AppHome).Path
$app = Join-Path $appRoot 'app'
$cfg = Join-Path $app 'LumenMusic.cfg'
$mainEntry = @(Get-Content -LiteralPath $cfg | Where-Object { $_ -match '^app\.mainjar=' })
if ($mainEntry.Count -eq 0) { $mainEntry = @(Get-Content -LiteralPath $cfg | Where-Object { $_ -match '^app\.classpath=.*[\\/]lumen-desktop-[^\\/]+\.jar$' }) }
if ($mainEntry.Count -ne 1) { throw 'Expected one configured production JAR.' }
$mainName = ($mainEntry[0] -split '=',2)[1].Trim() -replace '^\$APPDIR[\\/]', ''
$jar = Join-Path $app $mainName
if (-not $Java) {
    if ($env:JAVA_HOME) { $Java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    else { $Java = 'java' }
}
$javaCommand = Get-Command $Java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javaCommand) { throw 'Provide -Java pointing to java.exe in an existing full JDK.' }
$Java = $javaCommand.Source
$javac = Join-Path (Split-Path $Java -Parent) 'javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'Candidate test driver compilation requires a full JDK. Supply -Java from that JDK; the packaged runtime alone is insufficient.' }
if (-not $Python) { $Python = Join-Path $app 'python/python.exe' }
$smokeNames = if ($PublicSegments) { @('ReleasePublicSegmentSmoke','ReleasePublicSegmentSmokeLauncher') } else { @('CandidateEnduranceSmoke','CandidateEnduranceSmokeLauncher') }
$driverSources = @($smokeNames | ForEach-Object { Join-Path $projectRoot ('desktop/src/test/java/com/lumenmusic/' + $_ + '.java') })
$proxy = Join-Path $app 'stream-proxy/lumen-stream-proxy.exe'
$service = Join-Path $app 'service'
foreach ($required in (@($Java,$javac,$Python,$jar,(Join-Path $app 'vlc/libvlc.dll'),(Join-Path $service 'lumen_service/__main__.py')) + $driverSources)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing candidate acceptance input: $required" }
}
if ($Mode -eq 'Online' -and -not (Test-Path -LiteralPath $proxy -PathType Leaf)) { throw "Missing candidate proxy: $proxy" }
$jars = @(Get-ChildItem -LiteralPath $app -Filter '*.jar' -File | Where-Object FullName -ne $jar | Sort-Object Name | ForEach-Object FullName)
$root = Join-Path $projectRoot ('build/phase4-release-d/runtime/' + $Mode.ToLowerInvariant() + '-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root,(Join-Path $root 'temp'),(Join-Path $root 'roaming'),(Join-Path $root 'javafx-cache') | Out-Null
$driver = Join-Path $root 'driver-classes'
New-Item -ItemType Directory -Path $driver | Out-Null
# Compile only this test driver, against the explicitly selected candidate and its existing dependencies.
# Per-run output prevents concurrent runs or Maven builds from changing each other's driver classes.
$compileClasspath = (@($jar) + $jars) -join ';'
& $javac -encoding UTF-8 --release 17 -cp $compileClasspath -d $driver @driverSources 2>&1 | Set-Content -LiteralPath (Join-Path $root 'driver-compile.log') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw "Standalone candidate test compilation failed: $root" }
@{javac=$javac;targetRelease=17;productionJar=$jar;sources=@($driverSources | ForEach-Object { @{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} });classes=@(Get-ChildItem -LiteralPath (Join-Path $driver 'com/lumenmusic') -Filter '*.class' | ForEach-Object { @{file=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'driver-build.json') -Encoding UTF8
$classpath = (@($jar) + $jars + @($driver)) -join ';'
New-Item -ItemType File -Path (Join-Path $root 'isolated-candidate.marker') | Out-Null
if ($PublicSegments) { New-Item -ItemType File -Path (Join-Path $root 'isolated-online-smoke.marker') | Out-Null }
$variables = @('LOCALAPPDATA','APPDATA','TEMP','TMP','LUMEN_SMOKE_ROOT','LUMEN_LIBRARY_PATH','LUMEN_HISTORY_PATH','LUMEN_PREFERENCES_PATH','LUMEN_AI_SETTINGS_PATH','LUMEN_SERVICE_URL','LUMEN_STREAM_URL','LUMEN_VLC_HOME','LUMEN_AI_API_KEY','OPENAI_API_KEY','LUMEN_AI_CHAT_COMPLETIONS_URL','LUMEN_AI_MODEL','LUMEN_AI_ENABLED','LUMEN_SMOKE_BV_PRIMARY','LUMEN_SMOKE_BV_SECONDARY','PYTHONPATH','PYTHONPYCACHEPREFIX','PYTHONUNBUFFERED','LUMEN_PROXY_ADDR','LUMEN_PYTHON_SERVICE_URL','LUMEN_PROVIDER','LUMEN_PYTHON')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
function Free-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}
function Wait-Ready($url,$owned) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($owned.HasExited) { throw "Owned service exited: $url" }
        try { if ((Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 1).StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Milliseconds 100
    }
    throw "Readiness timeout: $url"
}
$serviceProcess = $null; $proxyProcess = $null; $desktopProcess = $null
try {
    $env:LOCALAPPDATA = $root; $env:APPDATA = Join-Path $root 'roaming'; $env:TEMP = Join-Path $root 'temp'; $env:TMP = $env:TEMP
    $env:LUMEN_SMOKE_ROOT = $root
    $env:LUMEN_LIBRARY_PATH = Join-Path $root 'library.json'; $env:LUMEN_HISTORY_PATH = Join-Path $root 'history.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $root 'preferences.properties'; $env:LUMEN_AI_SETTINGS_PATH = Join-Path $root 'ai-settings.json'
    $port = Free-Port; $proxyPort = Free-Port
    while ($proxyPort -eq $port) { $proxyPort = Free-Port }
    $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"; $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
    $env:LUMEN_VLC_HOME = Join-Path $app 'vlc'
    foreach ($name in @('LUMEN_AI_API_KEY','OPENAI_API_KEY','LUMEN_AI_CHAT_COMPLETIONS_URL','LUMEN_AI_MODEL','LUMEN_PYTHON')) { [Environment]::SetEnvironmentVariable($name,$null,'Process') }
    $env:LUMEN_AI_ENABLED = 'false'; $env:LUMEN_PROVIDER = $(if ($Mode -eq 'Online') {'bilibili'} else {'demo'})
    $env:LUMEN_SMOKE_BV_PRIMARY = $PrimaryBv; $env:LUMEN_SMOKE_BV_SECONDARY = $SecondaryBv
    $env:PYTHONPATH = $null; $env:PYTHONPYCACHEPREFIX = Join-Path $root 'pycache'; $env:PYTHONUNBUFFERED = '1'
    $bootstrap = Join-Path $root 'service-bootstrap.py'
    [IO.File]::WriteAllText($bootstrap, "import sys,runpy`nsys.path.insert(0,sys.argv.pop(1))`nsys.argv[0]='lumen_service'`nrunpy.run_module('lumen_service',run_name='__main__')`n",[Text.UTF8Encoding]::new($false))
    $serviceArgs = @(('"' + $bootstrap + '"'),('"' + $service + '"'),'--provider',$env:LUMEN_PROVIDER,'--port',$port)
    $serviceProcess = Start-Process -FilePath $Python -ArgumentList $serviceArgs -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'service.stdout.log') -RedirectStandardError (Join-Path $root 'service.stderr.log')
    Wait-Ready ($env:LUMEN_SERVICE_URL + '/api/health') $serviceProcess
    if ($Mode -eq 'Online') {
        $env:LUMEN_STREAM_URL = "http://127.0.0.1:$proxyPort"; $env:LUMEN_PROXY_ADDR = "127.0.0.1:$proxyPort"; $env:LUMEN_PYTHON_SERVICE_URL = $env:LUMEN_SERVICE_URL
        $proxyProcess = Start-Process -FilePath $proxy -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'proxy.stdout.log') -RedirectStandardError (Join-Path $root 'proxy.stderr.log')
        Wait-Ready ($env:LUMEN_STREAM_URL + '/health') $proxyProcess
    }
    $metadata = @{mode=$Mode;seconds=$Seconds;appHome=$appRoot;jar=$jar;jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;java=$Java;python=$Python;service=$service;startedUtc=[DateTime]::UtcNow.ToString('o');network=$(if($Mode -eq 'Online'){'Actual public Bilibili network, inherited process network configuration'}else{'Synthetic local PCM; only isolated loopback demo API'});scope='No personal state, acoustic/headphone or independent-machine coverage'}
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'run-input.json') -Encoding UTF8
    $launcher = if ($PublicSegments) { 'com.lumenmusic.ReleasePublicSegmentSmokeLauncher' } else { 'com.lumenmusic.CandidateEnduranceSmokeLauncher' }
    $arguments = @('-Xlog:class+load=info:file=class-load.log',('"-Djava.io.tmpdir=' + (Join-Path $root 'temp') + '"'),('"-Djavafx.cachedir=' + (Join-Path $root 'javafx-cache') + '"'),('"-Dlumen.smoke.installed.jar=' + $jar + '"'),('"-Djna.library.path=' + $env:LUMEN_VLC_HOME + '"'),('-Dlumen.stream.base=' + $env:LUMEN_STREAM_URL),('-Dlumen.candidate.seconds=' + $Seconds),('-Dlumen.candidate.online=' + ($Mode -eq 'Online').ToString().ToLowerInvariant()),'-cp',('"' + $classpath + '"'),$launcher)
    $desktopProcess = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'desktop.stdout.log') -RedirectStandardError (Join-Path $root 'desktop.stderr.log')
    Write-Output "Candidate $Mode artifacts: $root ; Java PID=$($desktopProcess.Id) ; seconds=$Seconds ; SHA256=$($metadata.jarSha256)"
    'utc,wallSeconds,cpuSeconds,workingSetBytes,privateBytes,handles' | Set-Content -LiteralPath (Join-Path $root 'process-resources.csv') -Encoding ASCII
    $started = [DateTime]::UtcNow; $deadline = $started.AddSeconds($Seconds + 600)
    while (-not $desktopProcess.WaitForExit(1000)) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Candidate runtime deadline exceeded.' }
        if ($serviceProcess.HasExited -or ($proxyProcess -and $proxyProcess.HasExited)) { throw 'Owned service exited during candidate playback.' }
        $desktopProcess.Refresh()
        ('{0},{1},{2},{3},{4},{5}' -f [DateTime]::UtcNow.ToString('o'),([DateTime]::UtcNow-$started).TotalSeconds,$desktopProcess.TotalProcessorTime.TotalSeconds,$desktopProcess.WorkingSet64,$desktopProcess.PrivateMemorySize64,$desktopProcess.HandleCount) | Add-Content -LiteralPath (Join-Path $root 'process-resources.csv') -Encoding ASCII
    }
    $desktopProcess.WaitForExit()
    Get-Content -LiteralPath (Join-Path $root 'desktop.stdout.log')
    Get-Content -LiteralPath (Join-Path $root 'desktop.stderr.log')
    if ($desktopProcess.ExitCode -ne 0) { throw "Candidate runtime failed $($desktopProcess.ExitCode): $root" }
    if ($PublicSegments) {
        if (-not (Select-String -LiteralPath (Join-Path $root 'desktop.stdout.log') -SimpleMatch 'PASS: release public segment JavaFX' -Quiet)) { throw 'Public segment completion marker missing.' }
    } elseif (-not (Test-Path -LiteralPath (Join-Path $root 'candidate-result.json'))) { throw 'Candidate completed result missing.' }
    $origin = Select-String -LiteralPath (Join-Path $root 'class-load.log') -Pattern 'com\.lumenmusic\.LumenFxApp source: (.+)$' | Select-Object -First 1
    if (-not $origin) { throw 'Class origin missing.' }
    $loaded = [Uri]::new($origin.Matches[0].Groups[1].Value)
    if (-not $loaded.IsFile -or -not [string]::Equals([IO.Path]::GetFullPath($loaded.LocalPath),[IO.Path]::GetFullPath($jar),[StringComparison]::OrdinalIgnoreCase)) { throw "Wrong production origin: $loaded" }
    Write-Output "Verified candidate JAR class origin; completed artifacts: $root"
} finally {
    foreach ($owned in @($desktopProcess,$proxyProcess,$serviceProcess)) {
        if ($owned -and -not $owned.HasExited) { $owned.Kill(); $owned.WaitForExit(5000) | Out-Null }
    }
    foreach ($name in $variables) {
        if ($null -eq $previous[$name]) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    }
}
