param(
    [Parameter(Mandatory)][string]$InstalledHome,
    [string]$Java
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$installedRoot = (Resolve-Path -LiteralPath $InstalledHome -ErrorAction Stop).Path
$installedApp = Join-Path $installedRoot 'app'
$driver = 'Explicit JDK driver; installed application jars/VLC'
if (-not $Java) {
    $Java = Join-Path $installedRoot 'runtime/bin/java.exe'
    $driver = 'Installed runtime'
}
if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) {
    throw 'Installed command-line Java is unavailable; specify -Java for an existing JDK driver.'
}
$cfg = [IO.File]::ReadAllLines((Join-Path $installedApp 'LumenMusic.cfg'))
$mainEntries = @($cfg | Where-Object { $_ -cmatch '^app\.classpath=\$APPDIR\\lumen-desktop-[A-Za-z0-9._-]+\.jar$' })
if ($mainEntries.Count -ne 1) { throw 'Installed main JAR configuration is missing or ambiguous' }
$installedJar = Join-Path $installedApp $mainEntries[0].Substring('app.classpath=$APPDIR\'.Length)
$installedVlc = Join-Path $installedApp 'vlc'
foreach ($required in @($java, $installedJar, (Join-Path $installedVlc 'libvlc.dll'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Installed acceptance input is missing: $required"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $installedVlc 'plugins') -PathType Container)) {
    throw "Installed VLC plugins are missing: $installedVlc"
}

$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$testSources = Join-Path $projectRoot 'desktop/src/test/java/com/lumenmusic'
$compileTests = $false
foreach ($name in @('Phase3DesktopSmoke', 'Phase3DesktopSmokeLauncher')) {
    $compiled = Join-Path $testClasses "com/lumenmusic/$name.class"
    $source = Join-Path $testSources "$name.java"
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
    if ($LASTEXITCODE -ne 0) { throw "Offline smoke entry compilation failed ($LASTEXITCODE)." }
}

# Installed production jars precede the test entry; target/classes is never used.
$dependencies = @(Get-ChildItem -LiteralPath $installedApp -Filter '*.jar' -File |
    Where-Object { $_.FullName -ne $installedJar } | Sort-Object Name | ForEach-Object { $_.FullName })
$classPath = (@($installedJar) + $dependencies + @($testClasses)) -join [IO.Path]::PathSeparator
$testRoot = Join-Path $projectRoot ('build/phase3-installed-smoke/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$stdout = Join-Path $testRoot 'stdout.log'
$stderr = Join-Path $testRoot 'stderr.log'
$classLog = Join-Path $testRoot 'class-load.log'
$probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try {
    $probe.Start()
    $testPort = ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
} finally { $probe.Stop() }

$testVariables = @('LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_AI_SETTINGS_PATH',
    'LUMEN_SERVICE_URL', 'LUMEN_VLC_HOME', 'LOCALAPPDATA', 'APPDATA', 'LUMEN_AI_API_KEY', 'OPENAI_API_KEY')
$previous = @{}
foreach ($name in $testVariables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$process = $null
try {
    $env:LOCALAPPDATA = $testRoot
    $env:APPDATA = Join-Path $testRoot 'roaming'
    $env:LUMEN_AI_API_KEY = ''
    $env:OPENAI_API_KEY = ''
    $env:LUMEN_LIBRARY_PATH = Join-Path $testRoot 'library.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $testRoot 'preferences.properties'
    $env:LUMEN_AI_SETTINGS_PATH = Join-Path $testRoot 'ai-settings.json'
    $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$testPort"
    $env:LUMEN_VLC_HOME = $installedVlc
    Write-Output "Java execution mode: $driver"
    Write-Output "Java driver: $java"
    Write-Output "Installed production JAR: $installedJar"
    Write-Output "Installed VLC: $installedVlc"
    Write-Output "Smoke entry classes: $testClasses"
    Write-Output "Local service: $env:LUMEN_SERVICE_URL"
    Write-Output "Acceptance artifacts: $testRoot"
    Write-Output 'JavaFX smoke has a 45-second internal watchdog; process fallback timeout is 60 seconds.'
    $arguments = @('-Xlog:class+load=info:file=class-load.log', '-cp', ('"' + $classPath + '"'),
        'com.lumenmusic.Phase3DesktopSmokeLauncher')
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $testRoot `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $timedOut = -not $process.WaitForExit(60000)
    if ($timedOut) {
        $process.Kill()
    }
    $process.WaitForExit()
    $process.Refresh()
    $exitCode = $process.ExitCode
    Write-Output "Installed smoke exit code: $exitCode"
    Get-Content -LiteralPath $stdout
    Get-Content -LiteralPath $stderr
    if ($timedOut) { throw 'Installed desktop acceptance process exceeded 60 seconds.' }
    if ($exitCode -ne 0) { throw "Installed Phase 3 desktop acceptance failed ($exitCode)." }
    if (-not (Select-String -LiteralPath $stdout -SimpleMatch 'PASS: Phase 3 real JavaFX' -Quiet)) {
        throw "Smoke success marker is missing: $stdout"
    }

    $origin = Select-String -LiteralPath $classLog -Pattern 'com\.lumenmusic\.LumenFxApp source: (.+)$' |
        Select-Object -First 1
    if (-not $origin) { throw "Installed LumenFxApp class origin was not recorded: $classLog" }
    $loadedFrom = [Uri]::new($origin.Matches[0].Groups[1].Value)
    if (-not $loadedFrom.IsFile -or -not [string]::Equals(
            [IO.Path]::GetFullPath($loadedFrom.LocalPath), [IO.Path]::GetFullPath($installedJar),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "LumenFxApp did not load from the installed production JAR: $loadedFrom"
    }
    Write-Output "Verified production class origin: $($loadedFrom.LocalPath)"
    foreach ($name in @('phase3-home.png', 'phase3-settings-860x600.png')) {
        $screenshot = Join-Path $testRoot $name
        if (-not (Test-Path -LiteralPath $screenshot -PathType Leaf) -or
            (Get-Item -LiteralPath $screenshot).Length -le 1000) {
            throw "Acceptance screenshot is missing or empty: $screenshot"
        }
        Write-Output "Screenshot: $screenshot"
    }

    $connection = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $connection.ConnectAsync('127.0.0.1', $testPort)
        try { $connected = $connect.Wait(1000) -and $connection.Connected }
        catch [System.AggregateException] { $connected = $false }
        if ($connected) { throw "Local smoke service remains active on port $testPort." }
    } finally { $connection.Dispose() }
    Write-Output "Verified local smoke service stopped: 127.0.0.1:$testPort"
} finally {
    try {
        if ($process) {
            if (-not $process.HasExited) {
                $process.Kill()
                $process.WaitForExit()
            }
            $process.Dispose()
        }
    } finally {
        foreach ($name in $testVariables) {
            if ($null -eq $previous[$name]) {
                Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
            }
        }
    }
}
