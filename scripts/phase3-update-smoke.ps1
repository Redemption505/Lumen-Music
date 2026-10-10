param(
    [Parameter(Mandatory)][string]$OldAppHome,
    [string]$PackageRoot,
    [Parameter(Mandatory)][string]$NewAppHome,
    [string]$HotfixAppHome,
    [string]$StageJar,
    [string]$InstalledAppHome,
    [ValidateSet('all', 'original', 'hotfix', 'local-jar', 'installed-backup')]
    [string]$VariantName = 'all',
    [switch]$RunRuntime,
    [switch]$RestartRuntime,
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$FromVersion,
    [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$ToVersion
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path $PSScriptRoot -Parent
$projectVersion = [string]([xml][IO.File]::ReadAllText((Join-Path $projectRoot 'desktop/pom.xml'))).project.version
if (-not $ToVersion) { $ToVersion = $projectVersion }
if ($ToVersion -cne $projectVersion -or [version]$ToVersion -le [version]$FromVersion) { throw 'Invalid declared source/target versions' }
if ($RestartRuntime -and [string]::IsNullOrWhiteSpace($PackageRoot)) {
    throw 'RestartRuntime requires an explicit final PackageRoot; QA-only package generation is not accepted'
}
$OldAppHome = (Resolve-Path -LiteralPath $OldAppHome).Path.TrimEnd('\')
$NewAppHome = (Resolve-Path -LiteralPath $NewAppHome).Path.TrimEnd('\')
if ($HotfixAppHome) { $HotfixAppHome = (Resolve-Path -LiteralPath $HotfixAppHome).Path.TrimEnd('\') }
if ($StageJar) {
    $StageJar = (Resolve-Path -LiteralPath $StageJar).Path
    if (-not $StageJar.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'StageJar must be an isolated project-local copy, not the personal installation'
    }
}
if ($InstalledAppHome) {
    $InstalledAppHome = (Resolve-Path -LiteralPath $InstalledAppHome).Path.TrimEnd('\')
    if (-not $InstalledAppHome.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'InstalledAppHome must be a project-local backup, not the personal installation'
    }
}
$root = Join-Path $projectRoot ('build/phase3-update-smoke/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
if (-not $PackageRoot) {
    # A real binary payload with explicitly QA-only license/source fixtures breaks
    # the dependency on final release documentation. It is never a release package.
    $qaProject = Join-Path $root 'QA_ONLY-project'
    $qaScripts = Join-Path $qaProject 'scripts'
    New-Item -ItemType Directory -Path (Join-Path $qaScripts 'update') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'package-update.ps1') -Destination $qaScripts
    foreach ($file in @('apply-local-update.ps1', 'update-local.cmd')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('update/' + $file)) -Destination (Join-Path $qaScripts 'update')
    }
    $qaRelease = Join-Path $qaProject "build/releases/Lumen-Music-v$ToVersion-Windows-x64"
    New-Item -ItemType Directory -Path $qaRelease -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'release/licenses/GPL-3.0.txt') -Destination (Join-Path $qaRelease 'LICENSE.txt')
    [IO.File]::WriteAllText((Join-Path $qaRelease 'THIRD-PARTY-NOTICES.txt'), 'QA_ONLY fixture; not a corresponding-source release.', [Text.Encoding]::ASCII)
    $qaSource = Join-Path $qaProject 'QA_ONLY-source'
    New-Item -ItemType Directory -Path $qaSource | Out-Null
    [IO.File]::WriteAllText((Join-Path $qaSource 'QA_ONLY.txt'), 'QA_ONLY file-update acceptance placeholder. Never distribute this package.', [Text.Encoding]::ASCII)
    [IO.Compression.ZipFile]::CreateFromDirectory($qaSource, (Join-Path $qaRelease 'SOURCE.zip'))
    $qaDocs = Join-Path $qaProject "release/v$ToVersion"
    New-Item -ItemType Directory -Path $qaDocs -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot "release/v$ToVersion/UPDATE-README.txt") -Destination $qaDocs
    New-Item -ItemType Directory -Path (Join-Path $qaProject 'desktop') | Out-Null
    [IO.File]::WriteAllText((Join-Path $qaProject 'desktop/pom.xml'), "<project><version>$ToVersion</version></project>", [Text.Encoding]::ASCII)
    & (Join-Path $qaScripts 'package-update.ps1') -OldAppHome $OldAppHome -NewAppHome $NewAppHome -FromVersion $FromVersion -ToVersion $ToVersion
    $PackageRoot = Join-Path $qaProject "build/releases/Lumen-Music-v$FromVersion-to-v$ToVersion-Windows-x64-Update"
    [IO.File]::WriteAllText((Join-Path $PackageRoot 'QA_ONLY.txt'), 'QA_ONLY: real binaries, placeholder source archive; never distribute.', [Text.Encoding]::ASCII)
}
$PackageRoot = (Resolve-Path -LiteralPath $PackageRoot).Path.TrimEnd('\')
if ($RestartRuntime -and (Test-Path -LiteralPath (Join-Path $PackageRoot 'QA_ONLY.txt'))) {
    throw 'RestartRuntime requires the final release package, not QA_ONLY payload fixtures'
}
$helper = Join-Path $PackageRoot 'apply-local-update.ps1'
$powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Snapshot([string]$ImageRoot, [switch]$IgnoreBackups, $BackupBaseline = @{}) {
    $result = @{}
    $existingBackups = @{}
    foreach ($path in $BackupBaseline.Keys) {
        if ($path -cmatch '^(update-backup-[^/]+)/') { $existingBackups[$Matches[1]] = $true }
    }
    foreach ($file in Get-ChildItem -LiteralPath $ImageRoot -Recurse -Force -File) {
        $relative = $file.FullName.Substring($ImageRoot.Length + 1).Replace('\', '/')
        if ($IgnoreBackups -and $relative -cmatch '^(update-backup-[^/]+)/' -and
            -not $existingBackups.ContainsKey($Matches[1])) { continue }
        $result[$relative] = Hash $file.FullName
    }
    return $result
}
function Unchanged($Before, [string]$ImageRoot, [switch]$IgnoreBackups) {
    $after = Snapshot $ImageRoot -IgnoreBackups:$IgnoreBackups -BackupBaseline $Before
    Assert ($after.Count -eq $Before.Count) "Restored or source inventory changed: $ImageRoot"
    foreach ($path in $Before.Keys) { Assert ($after[$path] -ceq $Before[$path]) "Restored or source SHA changed: $path" }
}
function Put([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Path (Split-Path $Path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Text, [Text.Encoding]::ASCII)
}
$trustedHelperHash = Hash (Join-Path $PSScriptRoot 'update/apply-local-update.ps1')
$manifest = [xml][IO.File]::ReadAllText((Join-Path $PackageRoot 'manifest.xml'))
$update = $manifest.update
Assert ([string]$update.fromVersion -ceq $FromVersion -and [string]$update.toVersion -ceq $ToVersion -and
    [string]$update.oldJar -ceq "lumen-desktop-$FromVersion.jar" -and [string]$update.newJar -ceq "lumen-desktop-$ToVersion.jar") 'Manifest differs from declared source/target versions'
Assert (@($update.files.file).Count -gt 0 -and @($update.requirements.file).Count -gt 0) 'Missing managed or strictly retained inventory'
Assert ((Hash $helper) -ceq $trustedHelperHash -and
    (Hash (Join-Path $PSScriptRoot 'update/apply-local-update.ps1')) -ceq $trustedHelperHash) 'Release helper is not trusted canonical helper'
Assert ([Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $NewAppHome 'LumenMusic.exe')).ProductVersion -ceq $ToVersion) 'New native launcher differs from declared target version'
$managed = @{}
foreach ($item in $update.files.file) {
    Assert ($item.scope -ceq 'root' -or $item.scope -ceq 'app') 'Unknown managed scope'
    $relative = if ($item.scope -ceq 'root') { [string]$item.path } else { 'app/' + [string]$item.path }
    $payload = if ($item.scope -ceq 'root') { 'payload/root/' + $item.path } else { 'payload/' + $item.path }
    Assert (-not $managed.ContainsKey($relative)) 'Duplicate managed manifest file'
    Assert ((Hash (Join-Path $NewAppHome $relative)) -ceq [string]$item.sha256 -and
        (Hash (Join-Path $PackageRoot $payload)) -ceq [string]$item.sha256) "Real app-image, payload and manifest differ: $relative"
    $managed[$relative] = [string]$item.sha256
}
$expectedManaged = @('LumenMusic.exe', "app/lumen-desktop-$ToVersion.jar", 'app/LumenMusic.cfg',
    'app/.jpackage.xml', 'app/stream-proxy/lumen-stream-proxy.exe', 'runtime/lib/modules')
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $NewAppHome 'app/service/lumen_service') -Filter '*.py' -File) {
    $expectedManaged += 'app/service/lumen_service/' + $file.Name
}
Assert ($managed.Count -eq $expectedManaged.Count) 'Managed inventory differs from target image'
foreach ($path in $expectedManaged) { Assert ($managed.ContainsKey($path)) "Managed target file missing: $path" }
Assert (@(Get-ChildItem -LiteralPath (Join-Path $PackageRoot 'payload') -File -Recurse -Force).Count -eq $managed.Count) 'Payload inventory differs from manifest'
$expectedRetained = @{}
foreach ($tree in @('runtime', 'app/python', 'app/vlc')) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $NewAppHome $tree) -File -Recurse -Force) {
        if ($file.FullName -match '[\\/]__pycache__[\\/]') { continue }
        $relative = $file.FullName.Substring($NewAppHome.Length + 1).Replace('\', '/')
        if ($relative -cne 'runtime/lib/modules') { $expectedRetained[$relative] = Hash $file.FullName }
    }
}
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $NewAppHome 'app') -Filter '*.jar' -File) {
    if ($file.Name -cne [string]$update.oldJar -and $file.Name -cne [string]$update.newJar) {
        $expectedRetained['app/' + $file.Name] = Hash $file.FullName
    }
}
$retained = @{}
foreach ($item in $update.requirements.file) {
    $path = [string]$item.path
    Assert (-not $retained.ContainsKey($path) -and -not $managed.ContainsKey($path) -and
        $expectedRetained[$path] -ceq [string]$item.sha256) "Retained target identity differs: $path"
    $retained[$path] = [string]$item.sha256
}
Assert ($retained.Count -eq $expectedRetained.Count) 'Retained inventory differs from target image'
Assert ($managed.ContainsKey('runtime/lib/modules')) 'Real Phase 3 payload must carry the canonical runtime module image'
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $NewAppHome ('app/' + $update.newJar)))
try {
    $entry = $zip.GetEntry('update/apply-local-update.ps1')
    Assert ($null -ne $entry) 'Real target JAR has no trusted embedded update helper'
    $reader = $entry.Open(); $bytes = New-Object IO.MemoryStream
    try {
        $reader.CopyTo($bytes); $sha = [Security.Cryptography.SHA256]::Create()
        try { $embeddedHash = ([BitConverter]::ToString($sha.ComputeHash($bytes.ToArray()))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        Assert ($embeddedHash -ceq $trustedHelperHash) 'Target JAR embedded helper differs from canonical helper'
    } finally { $reader.Dispose(); $bytes.Dispose() }
} finally { $zip.Dispose() }
function Run-Helper([string]$Install, [string]$Label, [int]$FailAfter = 0, [switch]$Restart) {
    $result = Join-Path $root ($Label + '-result.xml')
    $stdout = Join-Path $root ($Label + '-stdout.log'); $stderr = Join-Path $root ($Label + '-stderr.log')
    $arguments = @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $helper + '"'),
        '-InstallRoot', ('"' + $Install + '"'), '-PackageRoot', ('"' + $PackageRoot + '"'),
        '-ResultPath', ('"' + $result + '"'), '-TestFailAfter', [string]$FailAfter)
    if ($Restart) { $arguments += '-Restart' }
    $originalModules = $env:PSModulePath
    try {
        # A PowerShell 7 parent must not leak its module path into Windows PowerShell.
        $env:PSModulePath = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules'
        $process = Start-Process -FilePath $powershell -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        # Windows PowerShell 5.1 needs the handle cached before redirected exit.
        $null = $process.Handle
    } finally { $env:PSModulePath = $originalModules }
    try {
        if ($Restart) { $script:restartHelperPid = $process.Id }
        Assert ($process.WaitForExit(90000)) "Real update helper timed out: $Label"
        Assert (Test-Path -LiteralPath $result -PathType Leaf) "Real update result missing: $Label"
        $xml = [xml][IO.File]::ReadAllText($result)
        $expectedCode = if ($FailAfter -gt 0) { 1 } else { 0 }
        Assert ($process.ExitCode -eq $expectedCode) "Unexpected helper exit code: $($process.ExitCode); $($xml.result.message)"
        return $xml.result
    } finally {
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}
function Verify-Backup($Result, [string]$OldJarHash) {
    $backup = [string]$Result.backupPath
    Assert (Test-Path -LiteralPath $backup -PathType Container) 'Missing actual update backup'
    $records = ([xml][IO.File]::ReadAllText((Join-Path $backup 'backup.xml'))).backup.file
    foreach ($record in $records | Where-Object { $_.exists -ceq 'True' }) {
        Assert ((Hash (Join-Path $backup ('files/' + $record.path))) -ceq [string]$record.sha256) "Actual backup differs from recorded SHA: $($record.path)"
    }
    $jarRecord = @($records | Where-Object { $_.path -ceq ('app/' + $update.oldJar) })
    Assert ($jarRecord.Count -eq 1 -and $jarRecord[0].sha256 -ceq $OldJarHash) 'Actual old JAR backup identity changed'
    Assert ((Hash (Join-Path $backup ('retired/app/' + $update.oldJar))) -ceq $OldJarHash) 'Retired old JAR differs from pre-update bytes'
}
function Free-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}
function Isolated-Processes([string]$Install) {
    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($Install + '\', [StringComparison]::OrdinalIgnoreCase)
    })
}
function Run-RestartRuntime($Variant, [string]$CaseRoot) {
    $runtimeRoot = Join-Path $CaseRoot 'helper-restart'
    New-Item -ItemType Directory -Path $runtimeRoot | Out-Null
    $restartInstall = Join-Path $runtimeRoot 'LumenMusic'
    Copy-Item -LiteralPath $Variant.home -Destination $restartInstall -Recurse
    if ($Variant.jar) { Copy-Item -LiteralPath $Variant.jar -Destination (Join-Path $restartInstall ('app/' + $update.oldJar)) -Force }
    $localData = Join-Path $runtimeRoot 'local-appdata'; $roamingData = Join-Path $runtimeRoot 'roaming-appdata'
    $temporaryData = Join-Path $runtimeRoot 'temp'
    New-Item -ItemType Directory -Path $localData, $roamingData, $temporaryData | Out-Null
    $canaries = @{}
    foreach ($path in @((Join-Path $localData 'fixed-canary.bin'), (Join-Path $roamingData 'fixed-canary.bin'),
            (Join-Path $restartInstall 'userdata/fixed-canary.bin'), (Join-Path $restartInstall 'app/phase3-unmanaged.txt'))) {
        Put $path 'phase3-fixed-helper-restart-personal-canary-20261006'
        $canaries[$path] = Hash $path
    }
    $oldJarHash = Hash (Join-Path $restartInstall ('app/' + $update.oldJar))
    $service = "http://127.0.0.1:$(Free-Port)"
    do { $proxy = "http://127.0.0.1:$(Free-Port)" } while ($proxy -eq $service)
    # Load the current host's CIM module before restricting the inherited module path.
    Import-Module CimCmdlets -ErrorAction Stop
    $values = @{
        PATH = "$env:SystemRoot/System32;$env:SystemRoot"
        PSModulePath = (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules')
        JAVA_HOME = ''; JAVA_TOOL_OPTIONS = ''; JDK_JAVA_OPTIONS = ''; _JAVA_OPTIONS = ''
        PYTHONHOME = ''; PYTHONPATH = ''; LUMEN_PYTHON = ''; LUMEN_VLC_HOME = ''
        LOCALAPPDATA = $localData; APPDATA = $roamingData; LUMEN_PROVIDER = 'demo'
        TEMP = $temporaryData; TMP = $temporaryData
        LUMEN_SERVICE_URL = $service; LUMEN_STREAM_URL = $proxy
        LUMEN_LIBRARY_PATH = (Join-Path $localData 'library.json')
        LUMEN_HISTORY_PATH = (Join-Path $localData 'history.json')
        LUMEN_PREFERENCES_PATH = (Join-Path $roamingData 'preferences.properties')
        LUMEN_AI_SETTINGS_PATH = (Join-Path $roamingData 'ai-settings.json')
        LUMEN_AI_API_KEY = ''; OPENAI_API_KEY = ''
    }
    $originalEnvironment = @{}
    foreach ($key in $values.Keys) { $originalEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
    $receipt = $null; $nativeWindow = $null; $observed = @()
    try {
        foreach ($key in $values.Keys) { [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process') }
        Assert (@(Isolated-Processes $restartInstall).Count -eq 0) 'Restart fixture already has running processes'
        $result = Run-Helper $restartInstall ($Variant.name + '-helper-restart') -Restart
        Assert ($result.status -ceq 'success' -and $result.message -match '; launcher started') 'Canonical helper did not perform the real Restart path'
        Verify-Backup $result $oldJarHash
        Assert (-not (Test-Path -LiteralPath (Join-Path $restartInstall ('app/' + $update.oldJar)))) 'Restart fixture still contains the retired old JAR'
        foreach ($path in $managed.Keys) {
            Assert ((Hash (Join-Path $restartInstall $path)) -ceq $managed[$path]) "Restart installed managed SHA differs: $path"
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(45); $healthy = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            try {
                $health = [xml](Invoke-WebRequest "$service/api/health" -UseBasicParsing -TimeoutSec 1).Content
                if ($health.health.provider -ceq 'demo') { $healthy = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 250
        }
        Assert $healthy 'Helper-restarted bundled Python service did not start'
        $tracks = [xml](Invoke-WebRequest "$service/api/tracks" -UseBasicParsing -TimeoutSec 5).Content
        Assert (@($tracks.tracks.track).Count -gt 0) 'Helper-restarted bundled service search failed'
        $deadline = [DateTime]::UtcNow.AddSeconds(15); $proxyReady = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            try { Invoke-WebRequest "$proxy/health" -UseBasicParsing -TimeoutSec 1 | Out-Null; $proxyReady = $true; break }
            catch { Start-Sleep -Milliseconds 250 }
        }
        Assert $proxyReady 'Helper-restarted bundled stream proxy did not start'
        $deadline = [DateTime]::UtcNow.AddSeconds(45); $jvmPath = ''
        while ([DateTime]::UtcNow -lt $deadline) {
            $observed = @(Isolated-Processes $restartInstall)
            foreach ($item in $observed | Where-Object { $_.Name -ceq 'LumenMusic.exe' }) {
                $candidate = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
                if (-not $candidate) { continue }
                if ($candidate.MainWindowHandle -ne 0) { $nativeWindow = $candidate }
                foreach ($module in $candidate.Modules | Where-Object { $_.ModuleName -ieq 'jvm.dll' }) {
                    Assert ([string]::Equals($module.FileName, (Join-Path $restartInstall 'runtime/bin/server/jvm.dll'),
                        [StringComparison]::OrdinalIgnoreCase)) 'Restart loaded a JVM outside the bundled runtime'
                    $jvmPath = $module.FileName
                }
            }
            if ($nativeWindow -and $jvmPath) { break }
            Start-Sleep -Milliseconds 250
        }
        Assert ($null -ne $nativeWindow -and $jvmPath) 'Helper Restart did not create a real native window with the bundled JVM'
        $pythonPath = Join-Path $restartInstall 'app/python/python.exe'
        $proxyPath = Join-Path $restartInstall 'app/stream-proxy/lumen-stream-proxy.exe'
        Assert (@($observed | Where-Object { $_.ExecutablePath -ieq $pythonPath }).Count -gt 0) 'Restart service is not using bundled Python'
        Assert (@($observed | Where-Object { $_.ExecutablePath -ieq $proxyPath }).Count -gt 0) 'Restart proxy is not using the bundled executable'
        Assert ([Diagnostics.FileVersionInfo]::GetVersionInfo($nativeWindow.Path).ProductVersion -ceq $ToVersion) 'Helper Restart launched the wrong native version'
        Assert ($nativeWindow.CloseMainWindow() -and $nativeWindow.WaitForExit(15000)) 'Helper-restarted native window did not exit normally'
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $remaining = @(Isolated-Processes $restartInstall)
            if (-not $remaining.Count) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
        Assert ($remaining.Count -eq 0) 'Owned helper-restarted launcher/Python/proxy survived normal shutdown'
        foreach ($path in $canaries.Keys) { Assert ((Hash $path) -ceq $canaries[$path]) 'Helper Restart changed a fixed personal or unmanaged canary' }
        $receipt = [pscustomobject]@{
            mode='canonical-helper-restart-real-final-package'; status=[string]$result.status
            helperPid=$script:restartHelperPid
            buildId=[string]$result.buildId; install=$restartInstall; bundledJvm=$jvmPath
            bundledPython=$pythonPath; bundledProxy=$proxyPath; normalExit=$true; canariesUnchanged=$true
            processes=@($observed | Select-Object ProcessId, ParentProcessId, Name, ExecutablePath)
        }
        [IO.File]::WriteAllText((Join-Path $runtimeRoot 'restart-evidence.json'), ($receipt | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8)
        Write-Host "PASS actual canonical helper -Restart: $($Variant.name); native $ToVersion, bundled JVM/Python/proxy, normal exit, isolated canaries"
        return $receipt
    } finally {
        # Failure cleanup is restricted to the owned isolated image; a forced exit
        # cannot satisfy the normal-shutdown assertions above.
        try {
        $remaining = @(Isolated-Processes $restartInstall)
        foreach ($item in $remaining | Where-Object { $_.Name -ceq 'LumenMusic.exe' }) {
            $candidate = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
            if ($candidate -and $candidate.MainWindowHandle -ne 0) { [void]$candidate.CloseMainWindow() }
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            $remaining = @(Isolated-Processes $restartInstall)
            if (-not $remaining.Count) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
        foreach ($item in $remaining) {
            $candidate = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
            if ($candidate -and $candidate.Path -and $candidate.Path.StartsWith($restartInstall + '\', [StringComparison]::OrdinalIgnoreCase)) {
                $candidate.Kill(); [void]$candidate.WaitForExit(5000)
            }
        }
        } finally {
            foreach ($key in $originalEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $originalEnvironment[$key], 'Process') }
        }
    }
}
$variants = @([pscustomobject]@{ name='original'; home=$OldAppHome; jar='' })
if ($HotfixAppHome) { $variants += [pscustomobject]@{ name='hotfix'; home=$HotfixAppHome; jar='' } }
if ($StageJar) { $variants += [pscustomobject]@{ name='local-jar'; home=$OldAppHome; jar=$StageJar } }
if ($InstalledAppHome) { $variants += [pscustomobject]@{ name='installed-backup'; home=$InstalledAppHome; jar='' } }
if ($VariantName -cne 'all') {
    $variants = @($variants | Where-Object { $_.name -ceq $VariantName })
    Assert ($variants.Count -eq 1) "Requested variant has no supplied source: $VariantName"
}
$evidence = New-Object System.Collections.ArrayList
foreach ($variant in $variants) {
    $original = Snapshot $variant.home
    $caseRoot = Join-Path $root $variant.name
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $install = Join-Path $caseRoot 'LumenMusic'
    Copy-Item -LiteralPath $variant.home -Destination $install -Recurse
    if ($variant.jar) { Copy-Item -LiteralPath $variant.jar -Destination (Join-Path $install ('app/' + $update.oldJar)) -Force }
    Put (Join-Path $install 'userdata/phase3-canary.txt') 'phase3-fixed-personal-canary-20261006'
    Put (Join-Path $install 'app/phase3-unmanaged.txt') 'phase3-fixed-unmanaged-canary-20261006'
    Put (Join-Path $install 'app/service/lumen_service/__pycache__/phase3-old.pyc') 'phase3-fixed-old-code-cache'
    $profile = Join-Path $caseRoot 'personal-data'
    foreach ($name in @('library.json', 'history.json', 'preferences.properties', 'playback-state.xml', 'background.png', 'ai-settings.json')) {
        Put (Join-Path $profile $name) ('phase3-fixed-personal-canary-' + $name)
    }
    $personal = Snapshot $profile; $before = Snapshot $install
    $oldJarHash = $before['app/' + $update.oldJar]
    Assert ([Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $install 'LumenMusic.exe')).ProductVersion -ceq $FromVersion) 'Copied old native image differs from declared source version'
    foreach ($requirement in $update.requirements.file) {
        Assert ($before[[string]$requirement.path] -ceq [string]$requirement.sha256) "Real retained requirement differs: $($requirement.path)"
    }
    # Inject after the last real managed file so retirement and every replacement are exercised.
    $rollback = Run-Helper $install ($variant.name + '-rollback') @($update.files.file).Count
    Assert ($rollback.status -ceq 'rolled_back') "Injected real rollback failed: $($rollback.message)"
    Unchanged $before $install -IgnoreBackups
    Unchanged $personal $profile
    Verify-Backup $rollback $oldJarHash
    $success = Run-Helper $install ($variant.name + '-upgrade')
    Assert ($success.status -ceq 'success') "Real old image update failed: $($success.message)"
    Assert (-not (Test-Path -LiteralPath (Join-Path $install ('app/' + $update.oldJar)))) 'Actual old JAR remains after update'
    Assert (-not (Test-Path -LiteralPath (Join-Path $install 'app/service/lumen_service/__pycache__'))) 'Old code cache remains after update'
    foreach ($path in $managed.Keys) { Assert ((Hash (Join-Path $install $path)) -ceq $managed[$path]) "Installed managed SHA differs: $path" }
    foreach ($path in $before.Keys) {
        if ($managed.ContainsKey($path) -or $path -ceq ('app/' + $update.oldJar) -or $path -cmatch '^app/service/(?:.*/)?__pycache__/') { continue }
        Assert ((Hash (Join-Path $install $path)) -ceq $before[$path]) "Retained or unmanaged file changed: $path"
    }
    Verify-Backup $success $oldJarHash
    Unchanged $personal $profile
    $after = Snapshot $install -IgnoreBackups -BackupBaseline $before
    $expected = @{}
    foreach ($path in $before.Keys) {
        if ($path -ceq ('app/' + $update.oldJar) -or $path -cmatch '^app/service/(?:.*/)?__pycache__/') { continue }
        $expected[$path] = $before[$path]
    }
    foreach ($path in $managed.Keys) { $expected[$path] = $managed[$path] }
    Assert ($after.Count -eq $expected.Count) 'Unexpected files remained or appeared after the real update'
    foreach ($path in $expected.Keys) { Assert ($after[$path] -ceq $expected[$path]) "Post-update inventory SHA differs: $path" }
    $backupCount = @(Get-ChildItem -LiteralPath $install -Directory -Filter 'update-backup-*').Count
    $repeat = Run-Helper $install ($variant.name + '-repeat')
    Assert ($repeat.status -ceq 'already_current') "Real repeated update failed: $($repeat.message)"
    Assert (@(Get-ChildItem -LiteralPath $install -Directory -Filter 'update-backup-*').Count -eq $backupCount) 'Repeated update created a backup'
    Unchanged $after $install -IgnoreBackups
    Unchanged $original $variant.home
    Unchanged $personal $profile
    if ($RunRuntime) { & (Join-Path $PSScriptRoot 'release-runtime-smoke.ps1') -AppHome $install }
    $restartReceipt = $null
    if ($RestartRuntime) {
        $restartReceipt = Run-RestartRuntime $variant $caseRoot
        Unchanged $original $variant.home
        Unchanged $personal $profile
    }
    [void]$evidence.Add([pscustomobject]@{ variant=$variant.name; install=$install; oldJarSha256=$oldJarHash;
        newJarSha256=$managed['app/' + $update.newJar]; oldRuntimeModulesSha256=$before['runtime/lib/modules'];
        newRuntimeModulesSha256=$managed['runtime/lib/modules']; rollback=[string]$rollback.status; upgrade=[string]$success.status;
        repeat=[string]$repeat.status; managedFiles=$managed.Count; retainedFiles=@($update.requirements.file).Count;
        nativeRuntimeExecuted=([bool]$RunRuntime -or [bool]$RestartRuntime); helperRestartExecuted=[bool]$RestartRuntime;
        restartEvidence=$restartReceipt; backup=[string]$success.backupPath })
    Write-Output "PASS real-image file upgrade: $($variant.name); rollback, all managed SHA, retired JAR/backup SHA, retained/unmanaged canaries, repeat"
}
$report = [pscustomobject]@{ mode='real-native-images-and-real-payload-helper'; fromVersion=$FromVersion; toVersion=$ToVersion; buildId=[string]$update.buildId;
    helperSha256=$trustedHelperHash; nativeRuntimeExecuted=([bool]$RunRuntime -or [bool]$RestartRuntime);
    helperRestartExecuted=[bool]$RestartRuntime; variants=@($evidence) }
[IO.File]::WriteAllText((Join-Path $root 'evidence.json'), ($report | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8)
Write-Output "Real-image update artifacts: $root"
if (-not $RunRuntime -and -not $RestartRuntime) { Write-Output 'Native EXE execution was not requested; file-update acceptance does not prove native launch.' }
