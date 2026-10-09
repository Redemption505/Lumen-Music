param(
    [string]$Java,
    [string]$RuntimeApp,
    [string]$AppHome,
    [ValidateSet('all', 'button', 'native', 'minimized', 'immersive', 'mini', 'leaving', 'returning')]
    [string]$Scenario = 'all'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$outputRoot = Join-Path $projectRoot ('build/pet-exit-smoke/' + [Guid]::NewGuid().ToString('N'))
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$productionClasses = Join-Path $projectRoot 'desktop/target/classes'
$launcher = 'com.lumenmusic.PetExitSmokeLauncher'
if (-not (Test-Path -LiteralPath (Join-Path $testClasses ($launcher.Replace('.', '/') + '.class')))) {
    throw 'Compile desktop tests before running pet-exit-smoke.ps1.'
}
if ($AppHome -and $RuntimeApp) { throw 'Use either -AppHome or -RuntimeApp.' }
$productionJar = $null
if ($AppHome) {
    $AppHome = (Resolve-Path -LiteralPath $AppHome).Path
    $RuntimeApp = Join-Path $AppHome 'app'
    $cfg = Join-Path $RuntimeApp 'LumenMusic.cfg'
    if (-not (Test-Path -LiteralPath $cfg -PathType Leaf)) { throw "Missing launcher configuration: $cfg" }
    $configuration = Get-Content -LiteralPath $cfg
    $mainEntry = @($configuration | Where-Object { $_ -match '^app\.mainjar=' })
    if ($mainEntry.Count -eq 0) {
        $mainEntry = @($configuration | Where-Object { $_ -match '^app\.classpath=.*[\\/]lumen-desktop-[^\\/]+\.jar$' })
    }
    if ($mainEntry.Count -ne 1) { throw "Expected exactly one configured main JAR in $cfg" }
    $mainName = ($mainEntry[0] -split '=', 2)[1].Trim() -replace '^\$APPDIR[\\/]', ''
    $productionJar = Join-Path $RuntimeApp $mainName
    foreach ($required in @($productionJar, (Join-Path $RuntimeApp 'vlc/libvlc.dll'))) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing installed smoke input: $required" }
    }
    if (-not $Java) {
        $packageJava = Join-Path $AppHome 'runtime/bin/java.exe'
        if (Test-Path -LiteralPath $packageJava -PathType Leaf) {
            $modules = @(& $packageJava --list-modules 2>&1)
            if ($LASTEXITCODE -eq 0 -and @($modules | Where-Object { $_ -match '^jdk\.httpserver@' }).Count -gt 0) {
                $Java = $packageJava
            } else {
                Write-Output 'Packaged Java lacks the test fixture jdk.httpserver module; using JDK test driver.'
            }
        }
    }
    Write-Output "Production JAR: $productionJar (SHA-256: $((Get-FileHash -LiteralPath $productionJar -Algorithm SHA256).Hash))"
}
if (-not $Java) { $Java = (Get-Command java -CommandType Application | Select-Object -First 1).Source }
if (-not $RuntimeApp) {
    $RuntimeApp = (Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/package-input') -Directory |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not $RuntimeApp) { throw 'Provide -RuntimeApp with the existing packaged dependency directory.' }
$RuntimeApp = (Resolve-Path -LiteralPath $RuntimeApp).Path
$dependencies = @(Get-ChildItem -LiteralPath $RuntimeApp -Filter '*.jar' -File |
    Where-Object { $_.Name -notlike 'lumen-desktop-*' } | ForEach-Object { $_.FullName })
if (-not $dependencies) { throw "No existing runtime dependency jars in $RuntimeApp" }
$productionEntries = if ($AppHome) { @($productionJar) } else { @($productionClasses) }
$classpath = (@($productionEntries) + $dependencies + @($testClasses)) -join ';'
Write-Output "Java test driver: $Java"
if ($AppHome -and -not [IO.Path]::GetFullPath($Java).Equals(
        [IO.Path]::GetFullPath((Join-Path $AppHome 'runtime/bin/java.exe')), [StringComparison]::OrdinalIgnoreCase)) {
    Write-Output 'Installed production classes and VLC are tested with a JDK driver; packaged Java runtime is not verified by this smoke.'
}
$scenarios = if ($Scenario -eq 'all') {
    @('button', 'native', 'minimized', 'immersive', 'mini', 'leaving', 'returning')
} else { @($Scenario) }
$variables = @('LOCALAPPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_HISTORY_PATH',
    'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL', 'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME',
    'LUMEN_AI_API_KEY', 'OPENAI_API_KEY')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$process = $null
try {
    foreach ($case in $scenarios) {
        $caseRoot = Join-Path $outputRoot $case
        New-Item -ItemType Directory -Path $caseRoot -Force | Out-Null
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
        finally { $listener.Stop() }
        $env:LOCALAPPDATA = $caseRoot
        $env:LUMEN_LIBRARY_PATH = Join-Path $caseRoot 'library.json'
        $env:LUMEN_PREFERENCES_PATH = Join-Path $caseRoot 'preferences.properties'
        $env:LUMEN_HISTORY_PATH = Join-Path $caseRoot 'history.json'
        $env:LUMEN_AI_SETTINGS_PATH = Join-Path $caseRoot 'ai-settings.json'
        $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
        $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
        $env:LUMEN_VLC_HOME = Join-Path $RuntimeApp 'vlc'
        $env:LUMEN_AI_API_KEY = $null
        $env:OPENAI_API_KEY = $null
        $stdout = Join-Path $caseRoot 'desktop.stdout.log'
        $stderr = Join-Path $caseRoot 'desktop.stderr.log'
        $arguments = @(('-Djavafx.cachedir="' + (Join-Path $caseRoot 'javafx-cache') + '"'),
            '-cp', ('"' + $classpath + '"'))
        if ($AppHome) { $arguments += ('-Dlumen.smoke.installed.jar="' + $productionJar + '"') }
        $arguments += @($launcher, $case)
        $process = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $projectRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        if (-not $process.WaitForExit(45000)) {
            throw "Pet exit scenario '$case' left its Java process running; owned PID=$($process.Id); logs=$caseRoot"
        }
        $process.WaitForExit()
        Get-Content -LiteralPath $stdout
        Get-Content -LiteralPath $stderr
        if ($process.ExitCode -ne 0) { throw "Pet exit scenario '$case' failed ($($process.ExitCode)); logs=$caseRoot" }
        if (-not (Test-Path -LiteralPath (Join-Path $caseRoot 'passed.txt'))) {
            throw "Pet exit scenario '$case' exited without lifecycle verification; logs=$caseRoot"
        }
        Write-Output "PASS natural Java process exit: $case; PID=$($process.Id)"
        $process = $null
    }
    Write-Output "Pet exit acceptance artifacts: $outputRoot"
} finally {
    # Cleanup is restricted to the exact Java process this driver launched.
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
