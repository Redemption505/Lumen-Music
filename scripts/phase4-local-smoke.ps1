param(
    [string]$Java,
    [string]$RuntimeApp,
    [string]$FixtureSource,
    [ValidateSet('All', 'Normal', 'HiDpi')][string]$Profile = 'All'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$productionClasses = Join-Path $projectRoot 'desktop/target/classes'
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$launcher = 'com.lumenmusic.Phase4LocalSmokeLauncher'
$outputBase = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/phase4-core-b/local'))

# This runner uses current development classes and existing packaged dependency/native files.
foreach ($required in @(
    (Join-Path $productionClasses 'com/lumenmusic/LumenFxApp.class'),
    (Join-Path $testClasses 'com/lumenmusic/Phase4LocalSmoke.class'),
    (Join-Path $testClasses 'com/lumenmusic/Phase4LocalSmokeLauncher.class')
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Compile desktop tests before running phase4-local-smoke.ps1. Missing: $required"
    }
}
if (-not $Java) {
    $cachedJava = Join-Path $env:USERPROFILE '.jdks/openjdk-23.0.2/bin/java.exe'
    if (Test-Path -LiteralPath $cachedJava -PathType Leaf) { $Java = $cachedJava }
    else { $Java = (Get-Command java -CommandType Application | Select-Object -First 1).Source }
}
if (-not $Java -or -not (Test-Path -LiteralPath $Java -PathType Leaf)) { throw 'Provide an existing Java JDK executable with -Java.' }
$Java = (Resolve-Path -LiteralPath $Java).Path
if (-not $RuntimeApp) {
    $packageInput = Join-Path $projectRoot 'build/package-input'
    if (Test-Path -LiteralPath $packageInput -PathType Container) {
        $candidate = Get-ChildItem -LiteralPath $packageInput -Directory |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($candidate) { $RuntimeApp = $candidate.FullName }
    }
}
if (-not $RuntimeApp) { throw 'Provide -RuntimeApp with the existing directory containing dependency JARs and vlc/.' }
$RuntimeApp = (Resolve-Path -LiteralPath $RuntimeApp).Path
$vlc = Join-Path $RuntimeApp 'vlc'
foreach ($required in @((Join-Path $vlc 'libvlc.dll'), (Join-Path $vlc 'plugins'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing existing VLC input: $required" }
}
$dependencies = @(Get-ChildItem -LiteralPath $RuntimeApp -Filter '*.jar' -File |
    Where-Object { $_.Name -notlike 'lumen-desktop-*' } | Sort-Object Name | ForEach-Object FullName)
if ($dependencies.Count -eq 0) { throw "No existing dependency JARs in $RuntimeApp" }
$classpath = (@($productionClasses) + $dependencies + @($testClasses)) -join ';'

$variables = @(
    'LOCALAPPDATA', 'APPDATA', 'TEMP', 'TMP',
    'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_HISTORY_PATH', 'LUMEN_AI_SETTINGS_PATH',
    'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME', 'LUMEN_PROVIDER', 'LUMEN_PYTHON',
    'LUMEN_AI_API_KEY', 'OPENAI_API_KEY', 'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'LUMEN_AI_ENABLED'
)
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$profiles = if ($Profile -eq 'All') { @('Normal', 'HiDpi') } else { @($Profile) }
$ownedProcess = $null
Write-Output "Java test driver: $Java"
Write-Output "Production classes: $productionClasses"
Write-Output "Existing dependency/native directory: $RuntimeApp"
Write-Output 'Fixture service is owned by the Java smoke on a fresh loopback port; no Python process is started.'

try {
    foreach ($case in $profiles) {
        $root = [IO.Path]::GetFullPath((Join-Path $outputBase ($case.ToLowerInvariant() + '-' + [Guid]::NewGuid().ToString('N'))))
        if (-not $root.StartsWith($outputBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Isolated profile escapes allowed output directory: $root"
        }
        if (Test-Path -LiteralPath $root) { throw "Refusing an existing smoke profile: $root" }
        $temporary = Join-Path $root 'temp'
        $javafxCache = Join-Path $root 'javafx-cache'
        $roaming = Join-Path $root 'roaming'
        New-Item -ItemType Directory -Path $root, $temporary, $javafxCache, $roaming | Out-Null
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
        finally { $listener.Stop() }
        # The Java HttpServer binds this port. Binding failure fails the run instead of reusing another service.
        $env:LOCALAPPDATA = $root
        $env:APPDATA = $roaming
        $env:TEMP = $temporary
        $env:TMP = $temporary
        $env:LUMEN_LIBRARY_PATH = Join-Path $root 'library.json'
        $env:LUMEN_PREFERENCES_PATH = Join-Path $root 'preferences.properties'
        $env:LUMEN_HISTORY_PATH = Join-Path $root 'history.json'
        $env:LUMEN_AI_SETTINGS_PATH = Join-Path $root 'ai-settings.json'
        $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
        $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
        $env:LUMEN_VLC_HOME = $vlc
        $env:LUMEN_PROVIDER = 'demo'
        $env:LUMEN_PYTHON = $null
        $env:LUMEN_AI_API_KEY = $null
        $env:OPENAI_API_KEY = $null
        $env:LUMEN_AI_CHAT_COMPLETIONS_URL = $null
        $env:LUMEN_AI_MODEL = $null
        $env:LUMEN_AI_ENABLED = 'false'

        $stdout = Join-Path $root 'desktop.stdout.log'
        $stderr = Join-Path $root 'desktop.stderr.log'
        $arguments = @(
            ('-Djava.io.tmpdir="' + $temporary + '"'),
            ('-Djavafx.cachedir="' + $javafxCache + '"'),
            ('-Djna.library.path="' + $vlc + '"')
        )
        if ($case -eq 'HiDpi') { $arguments += '-Dglass.win.uiScale=1.5' }
        if ($FixtureSource) { $arguments += ('-Dlumen.phase4.local.fixtureSource="' + (Resolve-Path -LiteralPath $FixtureSource).Path + '"') }
        $arguments += @('-cp', ('"' + $classpath + '"'), $launcher)
        Write-Output "Starting $case; isolated artifacts: $root"
        $ownedProcess = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $projectRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $deadline = [DateTime]::UtcNow.AddSeconds(300)
        while (-not $ownedProcess.WaitForExit(1000)) {
            if ([DateTime]::UtcNow -ge $deadline) {
                throw "Phase4 local $case timed out; owned Java PID=$($ownedProcess.Id); logs=$root"
            }
        }
        $ownedProcess.WaitForExit()
        Get-Content -LiteralPath $stdout
        Get-Content -LiteralPath $stderr
        if ($ownedProcess.ExitCode -ne 0) { throw "Phase4 local $case failed ($($ownedProcess.ExitCode)); logs=$root" }
        $evidence = Join-Path $root 'phase4-local-evidence.txt'
        if (-not (Test-Path -LiteralPath $evidence -PathType Leaf)) {
            throw "Phase4 local $case exited without its completed evidence file; logs=$root"
        }
        Write-Output "PASS Phase4 local $case; evidence: $evidence"
        $ownedProcess = $null
    }
} finally {
    # No process-name search or external service cleanup: stop only this runner's Java process.
    if ($ownedProcess -and -not $ownedProcess.HasExited) {
        Stop-Process -Id $ownedProcess.Id -Force
        $ownedProcess.WaitForExit(5000) | Out-Null
    }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
