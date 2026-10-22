param(
    [Parameter(Mandatory)][string]$FullReleaseRoot,
    [Parameter(Mandatory)][string]$UpdateRoot,
    [Parameter(Mandatory)][string]$AppHome,
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$FromVersion,
    [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$ToVersion
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path $PSScriptRoot -Parent
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Entry-Hash($Entry) {
    $stream = $Entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
}
function Entry-Text($Entry) {
    Assert ($null -ne $Entry) 'Required archive text entry is missing'
    $reader = New-Object IO.StreamReader($Entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Checksums([string]$Text) {
    $result = @{}
    foreach ($line in ($Text -split '\r?\n') | Where-Object { $_ }) {
        Assert ($line -cmatch '^([0-9a-f]{64})  ([^\\]+)$') 'Invalid checksum line'
        $digest = $Matches[1]; $path = $Matches[2]
        Assert (-not $result.ContainsKey($path)) "Duplicate checksum entry: $path"
        $result[$path] = $digest
    }
    return $result
}
function Zip-Inventory($Zip) {
    $entries = @{}
    foreach ($entry in $Zip.Entries) {
        $path = $entry.FullName
        Assert ($path -and $path -notmatch '\\|^/|(^|/)\.\.?(/|$)' -and -not $path.EndsWith('/')) "Invalid or non-file ZIP entry: $path"
        Assert (-not $entries.ContainsKey($path)) "Duplicate ZIP entry: $path"
        $entries[$path] = $entry
    }
    return $entries
}
function Verify-ReleaseZip([string]$ReleaseRoot) {
    foreach ($name in @('README.txt', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt', 'SOURCE.zip', 'SHA256SUMS.txt')) {
        Assert (Test-Path -LiteralPath (Join-Path $ReleaseRoot $name) -PathType Leaf) "Required release artifact missing: $name"
    }
    $archive = $ReleaseRoot + '.zip'
    $sidecar = Checksums ([IO.File]::ReadAllText($archive + '.sha256'))
    $archiveName = Split-Path $archive -Leaf
    $digest = Hash $archive
    Assert ($sidecar.Count -eq 1 -and $sidecar[$archiveName] -ceq $digest) "Outer ZIP SHA differs: $archiveName"
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        $entries = Zip-Inventory $zip
        $files = @(Get-ChildItem -LiteralPath $ReleaseRoot -File -Recurse -Force)
        Assert ($entries.Count -eq $files.Count) "ZIP and release directory inventories differ: $archiveName"
        $prefix = (Split-Path $ReleaseRoot -Leaf) + '/'
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($ReleaseRoot.Length + 1).Replace('\', '/')
            $entry = $entries[$prefix + $relative]
            Assert ($null -ne $entry -and $entry.Length -eq $file.Length -and (Entry-Hash $entry) -ceq (Hash $file.FullName)) "ZIP content differs from release directory: $relative"
        }
    } finally { $zip.Dispose() }
    $checksums = Checksums ([IO.File]::ReadAllText((Join-Path $ReleaseRoot 'SHA256SUMS.txt')))
    $rootFiles = @(Get-ChildItem -LiteralPath $ReleaseRoot -File | Where-Object { $_.Name -cne 'SHA256SUMS.txt' })
    Assert ($checksums.Count -eq $rootFiles.Count) 'Root SHA256SUMS inventory is incomplete'
    foreach ($file in $rootFiles) { Assert ($checksums[$file.Name] -ceq (Hash $file.FullName)) "Release root checksum differs: $($file.Name)" }
    return [pscustomobject]@{ archive=$archiveName; sha256=$digest; entries=$files.Count; bytes=(Get-Item -LiteralPath $archive).Length }
}
$root = Join-Path $projectRoot ('build/phase3-release-verification/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$evidence = [ordered]@{ status='failed'; full=$null; update=$null; sourceFiles=0; workspaceFiles=0; managedFiles=0; retainedFiles=0 }
try {
    foreach ($key in @('FullReleaseRoot', 'UpdateRoot', 'AppHome', 'Installer')) {
        $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath (Get-Variable -Name $key -ValueOnly)).Path).TrimEnd('\')
        Assert ($resolved.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) 'Verification inputs must be isolated project-local release artifacts'
        Set-Variable -Name $key -Value $resolved
    }
    $version = [string]([xml][IO.File]::ReadAllText((Join-Path $projectRoot 'desktop/pom.xml'))).project.version
    if (-not $ToVersion) { $ToVersion = $version }
    Assert ($ToVersion -ceq $version -and [version]$ToVersion -gt [version]$FromVersion) 'Invalid declared release version pair'
    Assert ((Split-Path $FullReleaseRoot -Leaf) -ceq "Lumen-Music-v$version-Windows-x64" -and
        (Split-Path $UpdateRoot -Leaf) -ceq "Lumen-Music-v$FromVersion-to-v$version-Windows-x64-Update") 'Release directory names differ from declared versions'
    $evidence.full = Verify-ReleaseZip $FullReleaseRoot
    $evidence.update = Verify-ReleaseZip $UpdateRoot
    foreach ($name in @('SOURCE.zip', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt')) {
        Assert ((Hash (Join-Path $FullReleaseRoot $name)) -ceq (Hash (Join-Path $UpdateRoot $name))) "Full/update corresponding artifact differs: $name"
    }
    Assert ((Hash (Join-Path $FullReleaseRoot "LumenMusic-$version-Setup.exe")) -ceq (Hash $Installer)) 'Full release Setup differs from supplied installer'
    Assert ([Diagnostics.FileVersionInfo]::GetVersionInfo($Installer).ProductVersion -ceq $version -and
        [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $AppHome 'LumenMusic.exe')).ProductVersion -ceq $version) 'Installer/native version differs'
    $manifest = [xml][IO.File]::ReadAllText((Join-Path $UpdateRoot 'manifest.xml')); $update = $manifest.update
    Assert ($update.fromVersion -ceq $FromVersion -and $update.toVersion -ceq $version -and
        $update.oldJar -ceq "lumen-desktop-$FromVersion.jar" -and $update.newJar -ceq "lumen-desktop-$version.jar") 'Update manifest differs from declared release identity'
    $managed = @{}; $retained = @{}
    foreach ($item in $update.files.file) {
        Assert ($item.scope -ceq 'root' -or $item.scope -ceq 'app') 'Unknown managed scope'
        $relative = if ($item.scope -ceq 'root') { [string]$item.path } else { 'app/' + $item.path }
        $payload = if ($item.scope -ceq 'root') { 'payload/root/' + $item.path } else { 'payload/' + $item.path }
        Assert (-not $managed.ContainsKey($relative)) 'Duplicate managed manifest file'
        $managed[$relative] = [string]$item.sha256
        foreach ($path in @((Join-Path $AppHome $relative), (Join-Path $UpdateRoot $payload))) {
            Assert ((Get-Item -LiteralPath $path).Length -eq [long]$item.size -and (Hash $path) -ceq [string]$item.sha256) "Managed size/hash differs: $relative"
        }
    }
    Assert ($managed.ContainsKey('runtime/lib/modules')) 'Final update must include the managed runtime module image'
    foreach ($item in $update.requirements.file) {
        $relative = [string]$item.path; $path = Join-Path $AppHome $relative
        Assert (-not $retained.ContainsKey($relative) -and -not $managed.ContainsKey($relative)) 'Duplicate or managed/retained-overlap manifest file'
        $retained[$relative] = [string]$item.sha256
        Assert ((Get-Item -LiteralPath $path).Length -eq [long]$item.size -and (Hash $path) -ceq [string]$item.sha256) "Retained size/hash differs: $relative"
    }
    $payloadFiles = @(Get-ChildItem -LiteralPath (Join-Path $UpdateRoot 'payload') -File -Recurse -Force)
    Assert ($payloadFiles.Count -eq $managed.Count) 'Update payload has missing or unlisted files'
    $expectedManaged = @('LumenMusic.exe', "app/lumen-desktop-$version.jar", 'app/LumenMusic.cfg',
        'app/.jpackage.xml', 'app/stream-proxy/lumen-stream-proxy.exe', 'runtime/lib/modules')
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $AppHome 'app/service/lumen_service') -Filter '*.py' -File) {
        $expectedManaged += 'app/service/lumen_service/' + $file.Name
    }
    Assert ($managed.Count -eq $expectedManaged.Count) 'Managed inventory differs from the target app-image'
    foreach ($path in $expectedManaged) { Assert ($managed.ContainsKey($path)) "Managed target file missing: $path" }
    $expectedRetained = @(foreach ($tree in @('runtime', 'app/python', 'app/vlc')) {
        foreach ($file in Get-ChildItem -LiteralPath (Join-Path $AppHome $tree) -Recurse -File -Force) {
            if ($file.FullName -match '[\\/]__pycache__[\\/]') { continue }
            $relative = $file.FullName.Substring($AppHome.Length + 1).Replace('\', '/')
            if ($relative -cne 'runtime/lib/modules') { $relative }
        }
    })
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $AppHome 'app') -Filter '*.jar' -File) {
        if ($file.Name -cne [string]$update.oldJar -and $file.Name -cne [string]$update.newJar) { $expectedRetained += 'app/' + $file.Name }
    }
    Assert ($retained.Count -gt 0 -and $retained.Count -eq $expectedRetained.Count) 'Retained inventory differs from the target app-image'
    foreach ($path in $expectedRetained) { Assert ($retained.ContainsKey($path)) "Retained target file missing: $path" }
    $evidence.managedFiles = $managed.Count; $evidence.retainedFiles = $retained.Count
    $helperHash = Hash (Join-Path $PSScriptRoot 'update/apply-local-update.ps1')
    Assert ((Hash (Join-Path $UpdateRoot 'apply-local-update.ps1')) -ceq $helperHash -and
        (Hash (Join-Path $UpdateRoot 'update.cmd')) -ceq (Hash (Join-Path $PSScriptRoot 'update/update-local.cmd'))) 'Update helper/CMD differs from canonical source'
    $jarPath = Join-Path $AppHome ('app/' + $update.newJar)
    $jar = [IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $jarProperties = Entry-Text ($jar.GetEntry('lumen-build.properties'))
        Assert ($jarProperties -cmatch ('(?m)^version=' + [regex]::Escape($version) + '\r?$')) 'Main JAR version differs'
        Assert ($jarProperties -cmatch '(?m)^build\.id=([A-Za-z0-9][A-Za-z0-9._-]{0,127})\r?$') 'Main JAR BuildId is missing'
        $buildId = $Matches[1]
        Assert ($buildId -ceq [string]$update.buildId -and (Entry-Hash ($jar.GetEntry('update/apply-local-update.ps1'))) -ceq $helperHash) 'Main JAR/update/canonical helper identity differs'
    } finally { $jar.Dispose() }
    $sourcePath = Join-Path $FullReleaseRoot 'SOURCE.zip'
    $source = [IO.Compression.ZipFile]::OpenRead($sourcePath)
    try {
        $entries = Zip-Inventory $source
        $checksums = Checksums (Entry-Text $entries['SOURCE-SHA256SUMS.txt'])
        Assert ($checksums.Count + 1 -eq $entries.Count) 'SOURCE-SHA256SUMS inventory is incomplete'
        foreach ($name in $entries.Keys) {
            Assert ($name -notmatch '(^|/)(build|userdata|personal-data|__pycache__|\.pytest_cache|\.cache|\.git|\.codex|node_modules|target)(/|$)|\.pyc$') "Non-source/cache/personal artifact in SOURCE: $name"
            if ($name -ceq 'SOURCE-SHA256SUMS.txt') { continue }
            Assert ($checksums[$name] -ceq (Entry-Hash $entries[$name])) "SOURCE checksum differs: $name"
            if ($name -ceq 'BUILD-INFO.txt') { continue }
            if ($name.StartsWith('third-party/')) {
                $workspacePath = Join-Path $projectRoot ('build/release-sources/' + $name)
            } elseif ($name -ceq 'LICENSE.txt') {
                $workspacePath = Join-Path $projectRoot 'release/licenses/GPL-3.0.txt'
            } else { $workspacePath = Join-Path $projectRoot $name }
            Assert (Test-Path -LiteralPath $workspacePath -PathType Leaf) "Source has no corresponding workspace input: $name"
            Assert ((Hash $workspacePath) -ceq $checksums[$name]) "Corresponding workspace source changed: $name"
            $evidence.workspaceFiles++
        }
        $expectedSourcePaths = @('README.md', 'AGENTS.md', 'CONTRIBUTING.md', 'CHANGELOG.md', '.gitignore', '.editorconfig', '.github/ISSUE_TEMPLATE/bug_report.yml',
            '.github/ISSUE_TEMPLATE/feature_request.yml', '.github/ISSUE_TEMPLATE/config.yml',
            '.github/pull_request_template.md',
            '第四阶段.md', 'desktop/pom.xml', 'desktop/mvnw', 'desktop/mvnw.cmd', 'service/README.md', 'stream-proxy/Cargo.toml', 'stream-proxy/Cargo.lock')
        foreach ($tree in @('desktop/src', 'desktop/.mvn', 'service/lumen_service', 'service/tests', 'stream-proxy/src', 'scripts', 'release', 'docs', '.github/workflows')) {
            foreach ($file in Get-ChildItem -LiteralPath (Join-Path $projectRoot $tree) -File -Recurse |
                    Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]|\.pyc$' }) {
                $expectedSourcePaths += $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
            }
        }
        foreach ($name in $expectedSourcePaths) { Assert ($entries.ContainsKey($name)) "Workspace source missing from SOURCE: $name" }
        Assert ($entries.ContainsKey('scripts/verify-phase3-release.ps1')) 'Final verification tool is missing from corresponding source'
        $buildInfo = Entry-Text $entries['BUILD-INFO.txt']
        Assert (-not @($buildInfo.ToCharArray() | Where-Object { [int]$_ -gt 127 }).Count -and
            $buildInfo -notmatch '[A-Za-z]:[\\/]|\\\\') 'BUILD-INFO must be ASCII without private machine paths'
        $info = @{}
        foreach ($line in ($buildInfo -split '\r?\n')) {
            if ($line -cmatch '^([A-Za-z][A-Za-z0-9]*)=(.+)$') {
                Assert (-not $info.ContainsKey($Matches[1])) 'Duplicate BUILD-INFO field'
                $info[$Matches[1]] = $Matches[2]
            }
        }
        $expectedInfo = @{
            Version=$version; BuildId=$buildId; MainJarSha256=(Hash $jarPath); InstallerSha256=(Hash $Installer)
            LauncherSha256=(Hash (Join-Path $AppHome 'LumenMusic.exe'))
            StreamProxySha256=(Hash (Join-Path $AppHome 'app/stream-proxy/lumen-stream-proxy.exe'))
            CanonicalUpdateHelperSha256=$helperHash
        }
        foreach ($key in $expectedInfo.Keys) { Assert ($info[$key] -ceq $expectedInfo[$key]) "BUILD-INFO identity differs: $key" }
        $image = [xml][IO.File]::ReadAllText((Join-Path $AppHome 'app/.jpackage.xml'))
        Assert ($info.JpackageJdkVersion -ceq $image.'jpackage-state'.version) 'BUILD-INFO jpackage JDK differs from packaged metadata'
        $runtime = [IO.File]::ReadAllText((Join-Path $AppHome 'runtime/release'))
        Assert ($runtime -cmatch '(?m)^JAVA_VERSION=\"([^\"\r\n]+)\"\r?$' -and $info.BundledRuntimeJavaVersion -ceq $Matches[1]) 'BUILD-INFO runtime version differs from packaged runtime'
        Assert ($buildInfo.Contains("-Type app-image -BuildId $buildId") -and
            $buildInfo.Contains("-Type exe -BuildId $buildId -AppHome <built-LumenMusic>")) 'BUILD-INFO lacks actual BuildId/app-image reproduction commands'
        $evidence.sourceFiles = $entries.Count; $evidence.sourceSha256 = Hash $sourcePath
    } finally { $source.Dispose() }
    $evidence.fromVersion = $FromVersion; $evidence.version = $version; $evidence.buildId = $buildId; $evidence.helperSha256 = $helperHash
    $evidence.status = 'passed'
    Write-Output "PASS final release: full ZIP $($evidence.full.entries) entries/$($evidence.full.bytes) bytes; update ZIP $($evidence.update.entries) entries/$($evidence.update.bytes) bytes"
    Write-Output "PASS corresponding SOURCE: $($evidence.sourceFiles) files/$($evidence.workspaceFiles) workspace inputs; managed=$($evidence.managedFiles); retained=$($evidence.retainedFiles); build=$buildId"
} catch {
    $evidence.error = $_.Exception.Message
    throw
} finally {
    [IO.File]::WriteAllText((Join-Path $root 'evidence.json'), ($evidence | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8)
    Write-Output "Verification evidence: $root/evidence.json"
}
