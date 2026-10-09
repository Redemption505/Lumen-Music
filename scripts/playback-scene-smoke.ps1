param([string]$Python, [string]$Java, [switch]$VisualOnly, [string]$AppHome, [string]$RuntimeHome,
    [ValidateSet('All', 'Normal', 'HiDpi')][string]$Profile = 'All',
    [string]$SmokeLauncher = 'com.lumenmusic.PlaybackSceneSmokeLauncher',
    [string]$ArtifactDirectory = 'playback-scene-smoke', [string]$FixtureScript)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($AppHome -and $RuntimeHome) { throw '请只指定 -AppHome（目标安装镜像）或 -RuntimeHome（源码验收运行时目录）。' }
if (-not $AppHome -and -not $RuntimeHome) {
    throw '请显式指定 -AppHome，或以 -RuntimeHome 指定含依赖 JAR 与 vlc/ 的目录；不会自动选择历史 build 镜像。'
}
$app = if ($AppHome) { Join-Path (Resolve-Path -LiteralPath $AppHome).Path 'app' }
    else {
        $runtime = (Resolve-Path -LiteralPath $RuntimeHome).Path
        if (Test-Path -LiteralPath (Join-Path $runtime 'app') -PathType Container) { Join-Path $runtime 'app' }
        else { $runtime }
    }
if (-not $Python -and $AppHome) { $Python = Join-Path $app 'python/python.exe' }
if (-not $Python) { $Python = $env:LUMEN_PYTHON }
if (-not $Python) { $Python = 'python' }
$pythonCommand = Get-Command $Python -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $pythonCommand) { throw "找不到 Python：$Python。请通过 -Python、LUMEN_PYTHON 或 PATH 指定 Python 3.10+。" }
$Python = $pythonCommand.Source
& $Python -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'
if ($LASTEXITCODE -ne 0) { throw 'Python 无法运行或低于 3.10，请通过 -Python 指定有效解释器。' }
if (-not $Java) {
    if ($env:JAVA_HOME) { $Java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    else { $Java = 'java' }
}
$javaCommand = Get-Command $Java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javaCommand) { throw "找不到 Java：$Java。请通过 -Java、JAVA_HOME 或 PATH 指定 Java。" }
$Java = $javaCommand.Source
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$launcher = $SmokeLauncher
$driverNames = @($launcher, ($launcher -replace 'Launcher$', '')) | Select-Object -Unique
foreach ($driverName in $driverNames) {
    $relative = $driverName.Replace('.', '/')
    $source = Join-Path $projectRoot ('desktop/src/test/java/' + $relative + '.java')
    $compiled = Join-Path $testClasses ($relative + '.class')
    if (Test-Path -LiteralPath $source -PathType Leaf) {
        if (-not (Test-Path -LiteralPath $compiled -PathType Leaf) -or
            (Get-Item -LiteralPath $compiled).LastWriteTimeUtc -lt (Get-Item -LiteralPath $source).LastWriteTimeUtc) {
            throw "Selected scene driver is missing or older than its source: $driverName. Run desktop/mvnw.cmd test-compile before acceptance."
        }
        Write-Output "Driver class: $compiled (SHA-256: $((Get-FileHash -LiteralPath $compiled -Algorithm SHA256).Hash))"
    }
}
foreach ($required in @($app, $Python, $Java, (Join-Path $testClasses ($launcher.Replace('.', '/') + '.class')),
        (Join-Path $app 'vlc/libvlc.dll'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing playback scene smoke input: $required" }
}
$jars = @(Get-ChildItem -LiteralPath $app -Filter '*.jar' -File)
$dependencies = @($jars | Where-Object { $_.Name -notlike 'lumen-desktop-*' } | ForEach-Object { $_.FullName })
if ($dependencies.Count -eq 0) { throw "缺少场景验收依赖 JAR：$app" }
$productionJar = $null
if ($AppHome) {
    $cfg = Join-Path $app 'LumenMusic.cfg'
    if (-not (Test-Path -LiteralPath $cfg -PathType Leaf)) { throw "Missing app launcher configuration: $cfg" }
    $configuration = Get-Content -LiteralPath $cfg
    $mainEntry = @($configuration | Where-Object { $_ -match '^app\.mainjar=' })
    if ($mainEntry.Count -eq 0) {
        $mainEntry = @($configuration | Where-Object { $_ -match '^app\.classpath=.*[\\/]lumen-desktop-[^\\/]+\.jar$' })
    }
    if ($mainEntry.Count -ne 1) { throw "Expected exactly one configured main JAR in $cfg" }
    $mainName = ($mainEntry[0] -split '=', 2)[1].Trim() -replace '^\$APPDIR[\\/]', ''
    $productionJar = Join-Path $app $mainName
    if (-not (Test-Path -LiteralPath $productionJar -PathType Leaf)) { throw "Missing configured production JAR: $productionJar" }
    $dependencies = @($jars | Where-Object { $_.FullName -ne $productionJar } | Sort-Object Name | ForEach-Object FullName)
    $entries = @($productionJar) + $dependencies
    Write-Output "Production JAR: $productionJar (SHA-256: $((Get-FileHash -LiteralPath $productionJar -Algorithm SHA256).Hash))"
} else {
    $productionClasses = Join-Path $projectRoot 'desktop/target/classes'
    if (-not (Test-Path -LiteralPath (Join-Path $productionClasses 'com/lumenmusic/LumenFxApp.class'))) {
        throw '缺少当前源码 classes，请先运行 desktop/mvnw.cmd test-compile。'
    }
    $entries = @($productionClasses) + $dependencies
}
$classpath = ($entries + @($testClasses)) -join ';'
$serviceHome = if ($AppHome) { Join-Path $app 'service' } else { Join-Path $projectRoot 'service' }
if (-not (Test-Path -LiteralPath (Join-Path $serviceHome 'lumen_service') -PathType Container)) {
    throw "Missing service source: $serviceHome"
}

$variables = @('LOCALAPPDATA', 'APPDATA', 'LUMEN_LIBRARY_PATH', 'LUMEN_PREFERENCES_PATH',
    'LUMEN_HISTORY_PATH', 'LUMEN_AI_SETTINGS_PATH', 'LUMEN_SERVICE_URL',
    'LUMEN_STREAM_URL', 'LUMEN_VLC_HOME', 'LUMEN_AI_API_KEY', 'OPENAI_API_KEY',
    'LUMEN_AI_CHAT_COMPLETIONS_URL', 'LUMEN_AI_MODEL', 'LUMEN_AI_ENABLED',
    'PYTHONPATH', 'PYTHONPYCACHEPREFIX')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Invoke-PlaybackSceneSmoke([string]$scaleName, [string]$scaleOption) {
    $root = Join-Path $projectRoot ('build/' + $ArtifactDirectory + '/' + $scaleName + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
    $serviceProcess = $null
    $desktopProcess = $null
    try {
        $env:LOCALAPPDATA = $root
        $env:APPDATA = Join-Path $root 'roaming'
        $env:LUMEN_LIBRARY_PATH = Join-Path $root 'library.json'
        $env:LUMEN_PREFERENCES_PATH = Join-Path $root 'preferences.properties'
        $env:LUMEN_HISTORY_PATH = Join-Path $root 'history.json'
        $env:LUMEN_AI_SETTINGS_PATH = Join-Path $root 'ai-settings.json'
        $env:LUMEN_SERVICE_URL = "http://127.0.0.1:$port"
        $env:LUMEN_STREAM_URL = $env:LUMEN_SERVICE_URL
        $env:LUMEN_VLC_HOME = Join-Path $app 'vlc'
        $env:LUMEN_AI_API_KEY = $null
        $env:OPENAI_API_KEY = $null
        $env:LUMEN_AI_CHAT_COMPLETIONS_URL = $null
        $env:LUMEN_AI_MODEL = $null
        $env:LUMEN_AI_ENABLED = $null
        $env:PYTHONPATH = $null
        $env:PYTHONPYCACHEPREFIX = Join-Path $root 'pycache'
        if ($FixtureScript) {
            & $Python -X ("pycache_prefix=" + $env:PYTHONPYCACHEPREFIX) $FixtureScript $root
            if ($LASTEXITCODE -ne 0) { throw "Fixture preparation failed: $root" }
        }

        $code = "import sys,runpy; sys.path.insert(0, r'$serviceHome'); sys.argv=['lumen_service','--provider','demo','--port','$port']; runpy.run_module('lumen_service',run_name='__main__')"
        $serviceRunner = Join-Path $root 'run-service.cmd'
        Set-Content -LiteralPath $serviceRunner -Encoding OEM -Value @(
            '@echo off',
            ('"' + $Python + '" -X "pycache_prefix=' + $env:PYTHONPYCACHEPREFIX + '" -c "' + $code + '" > "' + (Join-Path $root 'service.stdout.log') +
                '" 2> "' + (Join-Path $root 'service.stderr.log') + '"'),
            'exit /b %ERRORLEVEL%')
        $serviceProcess = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32/cmd.exe') `
            -ArgumentList '/c', ('"' + $serviceRunner + '"') -WorkingDirectory $projectRoot `
            -WindowStyle Hidden -PassThru
        $deadline = [DateTime]::UtcNow.AddSeconds(12)
        $healthy = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($serviceProcess.HasExited) { throw "Demo service exited before readiness: $root" }
            try {
                $response = Invoke-WebRequest -Uri "$env:LUMEN_SERVICE_URL/api/health" `
                    -UseBasicParsing -TimeoutSec 1
                if ($response.StatusCode -eq 200) { $healthy = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 100
        }
        if (-not $healthy) { throw "Demo service readiness timed out: $root" }

        $desktopRunner = Join-Path $root 'run-desktop.cmd'
        Set-Content -LiteralPath $desktopRunner -Encoding OEM -Value @(
            '@echo off',
            ('"' + $Java + '" ' + $scaleOption + $(if ($VisualOnly) { ' -Dlumen.scene.smoke.visualOnly=true' } else { '' }) +
                $(if ($AppHome) { ' -Xlog:class+load=info:file=class-load.log "-Dlumen.test.installedJar=' + $productionJar +
                    '" "-Djna.library.path=' + (Join-Path $app 'vlc') + '"' } else { '' }) + ' -Djavafx.cachedir="' +
                (Join-Path $root 'javafx-cache') + '" -cp "' + $classpath + '" ' +
                $launcher + ' > "' + (Join-Path $root 'desktop.stdout.log') +
                '" 2> "' + (Join-Path $root 'desktop.stderr.log') + '"'),
            'exit /b %ERRORLEVEL%')
        $desktopProcess = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32/cmd.exe') `
            -ArgumentList '/c', ('"' + $desktopRunner + '"') -WorkingDirectory $(if ($AppHome) { $root } else { $projectRoot }) `
            -WindowStyle Hidden -PassThru
        if (-not $desktopProcess.WaitForExit(300000)) {
            & taskkill /F /T /PID $desktopProcess.Id 2>$null | Out-Null
            throw "Playback scene desktop smoke timed out: $root"
        }
        $desktopProcess.WaitForExit()
        Get-Content -LiteralPath (Join-Path $root 'desktop.stdout.log')
        Get-Content -LiteralPath (Join-Path $root 'desktop.stderr.log')
        if ($desktopProcess.ExitCode -ne 0) {
            throw "Playback scene desktop smoke failed ($($desktopProcess.ExitCode)): $root"
        }
        if ($AppHome) {
            $origin = Select-String -LiteralPath (Join-Path $root 'class-load.log') -Pattern 'com\.lumenmusic\.LumenFxApp source: (.+)$' | Select-Object -First 1
            if (-not $origin) { throw "Production class origin is missing: $root" }
            $loadedFrom = [Uri]::new($origin.Matches[0].Groups[1].Value)
            if (-not $loadedFrom.IsFile -or -not [string]::Equals([IO.Path]::GetFullPath($loadedFrom.LocalPath),
                    [IO.Path]::GetFullPath($productionJar), [StringComparison]::OrdinalIgnoreCase)) {
                throw "Production class did not load from the app JAR: $loadedFrom"
            }
            Write-Output "Verified production class origin: $($loadedFrom.LocalPath)"
        }
        Write-Output "Playback scene $scaleName exit code: $($desktopProcess.ExitCode)"
        Write-Output "Playback scene $scaleName artifacts: $root"
    } finally {
        foreach ($owned in @($desktopProcess, $serviceProcess)) {
            if ($owned -and -not $owned.HasExited) {
                & taskkill /F /T /PID $owned.Id 2>$null | Out-Null
                $owned.WaitForExit(5000) | Out-Null
            }
        }
    }
}

try {
    if ($Profile -in @('All', 'Normal')) { Invoke-PlaybackSceneSmoke 'normal' '' }
    if ($Profile -in @('All', 'HiDpi')) { Invoke-PlaybackSceneSmoke 'hidpi-1.5' '-Dglass.win.uiScale=1.5' }
} finally {
    foreach ($name in $variables) {
        if ($null -eq $previous[$name]) {
            Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
    }
}
