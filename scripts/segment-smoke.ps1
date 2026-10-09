param([Alias('InstalledHome')][string]$AppHome, [string]$AudioFixture, [string]$Java)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$root = Join-Path $projectRoot ('build/segment-smoke/' + [Guid]::NewGuid().ToString('N'))
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$launcher = 'com.lumenmusic.SegmentPlaybackSmokeLauncher'
if (-not (Test-Path (Join-Path $testClasses ($launcher.Replace('.', '/') + '.class')))) {
    throw 'Compile desktop tests before running segment acceptance.'
}
$app = if ($AppHome) { Join-Path (Resolve-Path -LiteralPath $AppHome).Path 'app' }
       else { (Get-ChildItem (Join-Path $projectRoot 'build/package-input') -Directory |
                    Sort-Object Name -Descending | Select-Object -First 1).FullName }
if (-not $app -or -not (Test-Path $app)) { throw 'Packaged desktop runtime was not found.' }
$jars = @(Get-ChildItem -LiteralPath $app -Filter '*.jar' -File)
if ($jars.Count -eq 0) { throw "Missing desktop runtime dependencies: $app" }
$productionJars = @($jars | Where-Object { $_.Name -like 'lumen-desktop-*.jar' })
if ($AppHome -and $productionJars.Count -ne 1) { throw "Expected one installed desktop JAR in $app; found $($productionJars.Count)." }
$productionJar = if ($AppHome) { $productionJars[0].FullName } else { Join-Path $app 'lumen-desktop-0.1.1.jar' }
$entries = if ($AppHome) { @($productionJar) + @($jars | Where-Object { $_.FullName -ne $productionJar } | ForEach-Object { $_.FullName }) }
           else { @((Join-Path $projectRoot 'desktop/target/classes')) +
                    @($jars.Where({ $_.Name -notlike 'lumen-desktop-*' }).FullName) }
$classpath = (@($entries) + @($testClasses)) -join ';'
if (-not $Java -and $AppHome) {
    $runtime = Split-Path $app -Parent
    $runtimeJava = Join-Path $runtime 'runtime/bin/java.exe'
    $release = Join-Path $runtime 'runtime/release'
    if (-not (Test-Path -LiteralPath $runtimeJava -PathType Leaf)) {
        throw "Installed Java test driver is missing: $runtimeJava. Pass -Java for an external JDK test driver."
    }
    $hasHttpServer = $false
    if (Test-Path -LiteralPath $release -PathType Leaf) {
        try {
            $modules = Get-Content -LiteralPath $release | Where-Object { $_ -match '^MODULES=' }
            $hasHttpServer = [bool]($modules -match '\bjdk\.httpserver\b')
        } catch {
            Write-Output "Could not inspect installed runtime modules: $($_.Exception.Message)"
        }
    }
    if ($hasHttpServer) {
        $Java = $runtimeJava
        Write-Output "Segment test driver: installed runtime $Java (jdk.httpserver present)"
    } else {
        Write-Output 'Segment test driver: installed runtime lacks jdk.httpserver; using external JDK. Installed runtime is not verified by this smoke.'
    }
}
if (-not $Java) { $Java = (Get-Command java -CommandType Application | Select-Object -First 1).Source }
$java = $Java
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Missing Java test driver: $java" }
if ($AppHome -and $PSBoundParameters.ContainsKey('Java')) { Write-Output "Segment test driver: external Java $java; installed runtime is not verified by this smoke." }
if ($AppHome) { Write-Output "Production JAR: $productionJar (SHA-256: $((Get-FileHash -LiteralPath $productionJar -Algorithm SHA256).Hash))" }
if ($AudioFixture) { $AudioFixture = (Resolve-Path -LiteralPath $AudioFixture).Path }
New-Item -ItemType Directory -Path $root -Force | Out-Null
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
finally { $listener.Stop() }
$variables = @('LOCALAPPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_SERVICE_URL',
    'LUMEN_STREAM_URL', 'LUMEN_HISTORY_PATH', 'LUMEN_AI_SETTINGS_PATH', 'LUMEN_VLC_HOME',
    'LUMEN_AI_API_KEY', 'OPENAI_API_KEY')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$process = $null
try {
    $env:LOCALAPPDATA = $root
    $env:LUMEN_LIBRARY_PATH = Join-Path $root 'library.json'
    $env:LUMEN_HISTORY_PATH = Join-Path $root 'history.json'
    $env:LUMEN_PREFERENCES_PATH = Join-Path $root 'preferences.properties'
    $env:LUMEN_AI_SETTINGS_PATH = Join-Path $root 'ai-settings.json'
    $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
    $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
    $env:LUMEN_VLC_HOME = Join-Path $app 'vlc'
    $env:LUMEN_AI_API_KEY = $null
    $env:OPENAI_API_KEY = $null
    $arguments = @('-Djavafx.cachedir="' + (Join-Path $root 'javafx-cache') + '"', '-cp', ('"' + $classpath + '"'))
    if ($AppHome) { $arguments += ('-Dlumen.test.installedJar="' + $productionJar + '"') }
    $arguments += $launcher
    if ($AudioFixture) { $arguments += ('"' + $AudioFixture + '"') }
    # Windows PowerShell 5.1 reports no ExitCode when Start-Process redirects the streams
    # itself, so a batch wrapper performs the redirection and returns the java exit code.
    $stdout = Join-Path $root 'desktop.stdout.log'
    $stderr = Join-Path $root 'desktop.stderr.log'
    $runner = Join-Path $root 'run-acceptance.cmd'
    Set-Content -LiteralPath $runner -Encoding OEM -Value @(
        '@echo off',
        ('"' + $java + '" ' + ($arguments -join ' ') + ' > "' + $stdout + '" 2> "' + $stderr + '"'),
        'exit /b %ERRORLEVEL%')
    $process = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\cmd.exe') `
        -ArgumentList '/c', ('"' + $runner + '"') -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru
    if (-not $process.WaitForExit(120000)) {
        & taskkill /F /T /PID $process.Id 2>$null | Out-Null
        throw "Segment desktop acceptance timed out; $root"
    }
    $process.WaitForExit()
    Get-Content -LiteralPath $stdout
    Get-Content -LiteralPath $stderr
    if ($process.ExitCode -ne 0) { throw "Segment desktop acceptance failed ($($process.ExitCode)); $root" }
    Write-Output "Segment acceptance artifacts: $root"
} finally {
    if ($process -and -not $process.HasExited) { & taskkill /F /T /PID $process.Id 2>$null | Out-Null }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
