param(
    [Parameter(Mandatory)][string]$AppHome,
    [string]$Java,
    [ValidateSet('All', 'Normal', 'HiDpi')][string]$Profile = 'All',
    [ValidateRange(10, 120)][int]$TimeoutSeconds = 45,
    [switch]$CheckInputsOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$AppHome = (Resolve-Path -LiteralPath $AppHome).Path
$appDirectory = Join-Path $AppHome 'app'
$configurationPath = Join-Path $appDirectory 'LumenMusic.cfg'
if (-not (Test-Path -LiteralPath $configurationPath -PathType Leaf)) {
    throw "Missing launcher configuration: $configurationPath"
}
$configuration = @(Get-Content -LiteralPath $configurationPath)
$mainEntries = @($configuration | Where-Object { $_ -match '^app\.mainjar=' })
if ($mainEntries.Count -eq 0) {
    $mainEntries = @($configuration | Where-Object { $_ -match '^app\.classpath=.*[\\/]lumen-desktop-[^\\/;]+\.jar$' })
}
if ($mainEntries.Count -ne 1) { throw "Expected one configured production JAR in $configurationPath" }
$configuredName = ($mainEntries[0] -split '=', 2)[1].Trim() -replace '^\$APPDIR[\\/]', ''
$productionJar = (Resolve-Path -LiteralPath (Join-Path $appDirectory $configuredName)).Path
if (-not (Test-Path -LiteralPath $productionJar -PathType Leaf) -or
        -not $productionJar.StartsWith($appDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Configured production JAR must be inside AppHome/app: $productionJar"
}
$dependencies = @(Get-ChildItem -LiteralPath $appDirectory -Filter '*.jar' -File |
    Where-Object { $_.Name -notlike 'lumen-desktop-*' } | Sort-Object Name | ForEach-Object { $_.FullName })
if ($dependencies.Count -eq 0) { throw "Missing packaged dependency JARs: $appDirectory" }
$testClasses = Join-Path $projectRoot 'desktop/target/test-classes'
$cases = @(
    [pscustomobject]@{ Name = 'SmoothScrollingSmoke'; Required = @('SmoothListScroll', 'SmoothScrollPane', 'SmoothScrolling') },
    [pscustomobject]@{ Name = 'SmoothListScrollSmoke'; Required = @('SmoothListScroll', 'SmoothScrolling') }
)
foreach ($case in $cases) {
    $source = Join-Path $projectRoot ('desktop/src/test/java/com/lumenmusic/' + $case.Name + '.java')
    foreach ($suffix in @('.class', '$Harness.class')) {
        $compiled = Join-Path $testClasses ('com/lumenmusic/' + $case.Name + $suffix)
        if (-not (Test-Path -LiteralPath $compiled -PathType Leaf)) {
            throw "Compile the desktop tests before this smoke; missing $compiled"
        }
        if ((Get-Item -LiteralPath $compiled).LastWriteTimeUtc -lt (Get-Item -LiteralPath $source).LastWriteTimeUtc) {
            throw "Smoke source has changed since compilation: $source; recompile the existing desktop tests first."
        }
    }
}
if (-not $Java) { $Java = (Get-Command java -CommandType Application | Select-Object -First 1).Source }
$Java = (Resolve-Path -LiteralPath $Java).Path
if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { throw "Missing Java test driver: $Java" }

# Keep every component an array; a scalar string plus an array concatenates paths in PowerShell.
$classpathEntries = @($productionJar) + @($dependencies) + @($testClasses)
if ($classpathEntries.Count -ne ($dependencies.Count + 2) -or $classpathEntries[0] -cne $productionJar) {
    throw 'Production JAR must be the first standalone classpath entry.'
}
foreach ($entry in $classpathEntries) {
    if ($entry -isnot [string] -or $entry.Contains(';') -or $entry.Contains('"') -or
            -not (Test-Path -LiteralPath $entry)) { throw "Invalid classpath component: $entry" }
    if ([IO.Path]::GetFullPath($entry).Equals((Join-Path $projectRoot 'desktop/target/classes'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Production target/classes is forbidden in packaged acceptance.'
    }
}
$classpath = $classpathEntries -join [IO.Path]::PathSeparator
$productionHash = (Get-FileHash -LiteralPath $productionJar -Algorithm SHA256).Hash
Write-Output "Production JAR: $productionJar (SHA-256: $productionHash)"
Write-Output "Java test driver: $Java"
for ($index = 0; $index -lt $classpathEntries.Count; $index++) {
    Write-Output "Classpath[$index]: $($classpathEntries[$index])"
}
if ($CheckInputsOnly) { Write-Output 'PASS packaged scrolling smoke input and classpath validation.'; return }

function Invoke-ScrollingSmoke {
    param([string]$ScaleName, [string]$ScaleOption, [psobject]$Case)
    $caseRoot = Join-Path $projectRoot ('build/smooth-scrolling-smoke/' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $caseRoot -Force | Out-Null
    $stdout = Join-Path $caseRoot 'desktop.stdout.log'
    $stderr = Join-Path $caseRoot 'desktop.stderr.log'
    $classLog = Join-Path $caseRoot 'class-load.log'
    $launcher = 'com.lumenmusic.' + $Case.Name
    $cacheOption = '-Djavafx.cachedir=' + (Join-Path $caseRoot 'javafx-cache')
    # These exact semantic argument values are recorded separately from Windows quoting.
    $arguments = @('-Xlog:class+load=info:file=class-load.log', $ScaleOption, $cacheOption, '-cp', $classpath, $launcher)
    if ($arguments.Count -ne 6 -or $arguments[3] -cne '-cp' -or $arguments[4] -cne $classpath -or
            $arguments[5] -cne $launcher) { throw 'Unexpected JVM argument aggregation.' }
    foreach ($argument in $arguments) {
        if ($argument -isnot [string] -or [string]::IsNullOrWhiteSpace($argument) -or $argument.Contains('"')) {
            throw "Invalid JVM argument: $argument"
        }
    }
    # Start-Process joins ArgumentList; quote each individual argument after validating its value.
    $nativeArguments = @($arguments | ForEach-Object { '"' + $_ + '"' })
    $manifest = [ordered]@{
        profile = $ScaleName; case = $Case.Name; appHome = $AppHome; java = $Java
        productionJar = $productionJar; productionSha256 = $productionHash
        classpathEntries = @($classpathEntries); arguments = @($arguments); nativeArguments = @($nativeArguments)
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $caseRoot 'inputs.json') -Encoding UTF8
    Write-Output "Running $ScaleName/$($Case.Name); artifacts: $caseRoot"
    $process = $null
    try {
        $process = Start-Process -FilePath $Java -ArgumentList $nativeArguments -WorkingDirectory $caseRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            throw "Scrolling smoke timed out: $ScaleName/$($Case.Name); logs=$caseRoot"
        }
        $process.WaitForExit()
        $process.Refresh()
        Get-Content -LiteralPath $stdout
        Get-Content -LiteralPath $stderr
        if ($process.ExitCode -ne 0) { throw "Scrolling smoke failed ($($process.ExitCode)); logs=$caseRoot" }
        if (-not (Select-String -LiteralPath $stdout -SimpleMatch ($Case.Name + ' PASS:') -Quiet)) {
            throw "Missing smoke success marker; logs=$caseRoot"
        }
        if (-not (Test-Path -LiteralPath $classLog -PathType Leaf)) { throw "Missing class-load proof: $classLog" }
        $loadLines = @(Get-Content -LiteralPath $classLog)
        $proof = @()
        foreach ($name in @('SmoothListScroll', 'SmoothScrollPane', 'SmoothScrolling')) {
            $pattern = '\scom\.lumenmusic\.' + [regex]::Escape($name) + '\s+source:\s+(.+)$'
            $matchesForClass = @($loadLines | Where-Object { $_ -match $pattern })
            if ($matchesForClass.Count -eq 0) {
                if ($Case.Required -contains $name) { throw "Required production class did not load: $name; logs=$caseRoot" }
                continue
            }
            foreach ($line in $matchesForClass) {
                $record = [regex]::Match($line, $pattern)
                if (-not $record.Success) { throw "Invalid class-load record: $line" }
                $loadedSource = $record.Groups[1].Value.Trim()
                $sourceUri = [Uri]$loadedSource
                if (-not $sourceUri.IsFile -or -not ([IO.Path]::GetFullPath($sourceUri.LocalPath)).Equals(
                        $productionJar, [StringComparison]::OrdinalIgnoreCase)) {
                    throw "$name was loaded from $loadedSource instead of $productionJar; logs=$caseRoot"
                }
                $proof += [pscustomobject]@{ class = $name; source = $loadedSource }
            }
        }
        $proof | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $caseRoot 'class-origin.json') -Encoding UTF8
        Write-Output "PASS $ScaleName/$($Case.Name): all loaded scrolling helpers came from the configured AppHome JAR."
    } finally {
        if ($process) {
            if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit(5000) | Out-Null }
            $process.Dispose()
        }
    }
}

if ($Profile -in @('All', 'Normal')) {
    foreach ($case in $cases) { Invoke-ScrollingSmoke -ScaleName 'normal' -ScaleOption '-Dglass.win.uiScale=1.0' -Case $case }
}
if ($Profile -in @('All', 'HiDpi')) {
    foreach ($case in $cases) { Invoke-ScrollingSmoke -ScaleName 'hidpi-1.5' -ScaleOption '-Dglass.win.uiScale=1.5' -Case $case }
}
