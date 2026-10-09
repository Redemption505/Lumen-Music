$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $projectRoot ('build/phase3-smoke/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try {
    $probe.Start()
    $testPort = ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
} finally { $probe.Stop() }
$testVariables = @('LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH', 'LUMEN_SERVICE_URL', 'LUMEN_AI_SETTINGS_PATH', 'LUMEN_VLC_HOME')
$previous = @{}
foreach ($name in $testVariables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:LUMEN_LIBRARY_PATH = Join-Path $testRoot 'library.json'
$env:LUMEN_PREFERENCES_PATH = Join-Path $testRoot 'preferences.properties'
$env:LUMEN_AI_SETTINGS_PATH = Join-Path $testRoot 'ai-settings.json'
$env:LUMEN_SERVICE_URL = "http://127.0.0.1:$testPort"
$bundledVlc = Join-Path $projectRoot 'build/tools/vlc-3.0.23/vlc-3.0.23'
if (-not $env:LUMEN_VLC_HOME -and (Test-Path -LiteralPath (Join-Path $bundledVlc 'libvlc.dll'))) {
    $env:LUMEN_VLC_HOME = $bundledVlc
}
$cachedMaven = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.11') -Recurse -Filter mvn.cmd | Select-Object -First 1
$wrapper = if ($cachedMaven) { $cachedMaven.FullName } else { Join-Path $projectRoot 'desktop/mvnw.cmd' }
Push-Location (Join-Path $projectRoot 'desktop')
try {
    & $wrapper -o -B -ntp "-Dmaven.repo.local=$env:USERPROFILE/.m2/repository" test-compile `
        'org.codehaus.mojo:exec-maven-plugin:3.6.2:exec' '-Dexec.executable=java' `
        '-Dexec.classpathScope=test' '-Dexec.args=-cp %classpath com.lumenmusic.Phase3DesktopSmokeLauncher'
    if ($LASTEXITCODE -ne 0) { throw "Phase 3 desktop acceptance failed ($LASTEXITCODE)." }
    Write-Output "Acceptance artifacts: $testRoot"
} finally {
    Pop-Location
    foreach ($name in $testVariables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
