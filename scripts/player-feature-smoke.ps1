param(
    [ValidateSet('Feature', 'Cache', 'Interaction', 'Lyrics')][string]$Smoke = 'Feature',
    [string]$InstalledHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$root = Join-Path $projectRoot ('build/player-feature-smoke/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root -Force | Out-Null
$launcher = switch ($Smoke) {
    'Cache' { 'com.lumenmusic.CachePlaybackSmokeLauncher' }
    'Interaction' { 'com.lumenmusic.UiInteractionSmokeLauncher' }
    'Lyrics' { 'com.lumenmusic.LyricPlaybackSmokeLauncher' }
    default { 'com.lumenmusic.FeatureDesktopSmokeLauncher' }
}
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
if (-not (Test-Path (Join-Path $testClasses ($launcher.Replace('.', '/') + '.class')))) {
    throw 'Compile the desktop tests before running this acceptance.'
}
$app = if ($InstalledHome) { Join-Path (Resolve-Path $InstalledHome).Path 'app' }
       else { (Get-ChildItem (Join-Path $projectRoot 'build/package-input') -Directory |
                    Sort-Object Name -Descending | Select-Object -First 1).FullName }
$jars = Get-ChildItem -LiteralPath $app -Filter '*.jar' -File
if (-not $jars) { throw "Missing desktop runtime dependencies: $app" }
$productionJar = Join-Path $app 'lumen-desktop-0.1.1.jar'
$entries = if ($InstalledHome) { @($jars.FullName) }
           else { @((Join-Path $projectRoot 'desktop/target/classes')) + @($jars.Where({ $_.Name -notlike 'lumen-desktop-*' }).FullName) }
$classpath = (@($entries) + @($testClasses)) -join ';'
$java = (Get-Command java -CommandType Application | Select-Object -First 1).Source
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
    if ($InstalledHome) { $arguments += ('-Dlumen.test.installedJar="' + $productionJar + '"') }
    $arguments += $launcher
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'desktop.stdout.log') `
        -RedirectStandardError (Join-Path $root 'desktop.stderr.log')
    if (-not $process.WaitForExit(120000)) { throw "$Smoke desktop acceptance timed out" }
    Get-Content -LiteralPath (Join-Path $root 'desktop.stdout.log')
    Get-Content -LiteralPath (Join-Path $root 'desktop.stderr.log')
    if ($process.ExitCode -ne 0) { throw "$Smoke desktop acceptance failed ($($process.ExitCode)); $root" }
    Write-Output "Acceptance artifacts: $root"
} finally {
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
