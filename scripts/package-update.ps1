param(
    [Parameter(Mandatory)][string]$NewAppHome,
    [Parameter(Mandatory)][string]$OldAppHome,
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$FromVersion,
    [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$ToVersion
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$projectRoot = Split-Path $PSScriptRoot -Parent
$oldHome = (Resolve-Path -LiteralPath $OldAppHome).Path.TrimEnd('\')
$newHome = (Resolve-Path -LiteralPath $NewAppHome).Path.TrimEnd('\')
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Required([string]$Path) { if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing release input: $Path" } }
function Image-Version([string]$imageRoot) {
    $exe = Join-Path $imageRoot 'LumenMusic.exe'; Required $exe
    $v = [Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductVersion
    if ($v -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') { throw 'Invalid launcher version' }
    $cfgPath = Join-Path $imageRoot 'app/LumenMusic.cfg'; Required $cfgPath
    $cfg = [IO.File]::ReadAllLines($cfgPath); $jar = "lumen-desktop-$v.jar"
    if (@($cfg | Where-Object { $_ -cmatch '^app\.classpath=\$APPDIR\\lumen-desktop-' }).Count -ne 1 -or
        @($cfg | Where-Object { $_ -ceq ('app.classpath=$APPDIR\' + $jar) }).Count -ne 1 -or
        @($cfg | Where-Object { $_ -ceq "java-options=-Djpackage.app-version=$v" }).Count -ne 1 -or
        @($cfg | Where-Object { $_ -cmatch '^java-options=-Djpackage\.app-version=' }).Count -ne 1 -or
        @($cfg | Where-Object { $_ -ceq 'app.mainclass=com.lumenmusic.LumenLauncher' }).Count -ne 1) { throw 'Configuration and launcher version differ' }
    Required (Join-Path $imageRoot "app/$jar"); return $v
}
$projectVersion = [string]([xml][IO.File]::ReadAllText((Join-Path $projectRoot 'desktop/pom.xml'))).project.version
if (-not $ToVersion) { $ToVersion = $projectVersion }
if ($ToVersion -cne $projectVersion) { throw 'Target version differs from the declared project version' }
if ((Image-Version $oldHome) -cne $FromVersion -or (Image-Version $newHome) -cne $ToVersion) {
    throw 'Supplied source/target versions differ from the selected app-images'
}
if ([version]$toVersion -le [version]$fromVersion) { throw 'Target must be newer than source' }
$oldJar = "lumen-desktop-$fromVersion.jar"; $newJar = "lumen-desktop-$toVersion.jar"
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $newHome "app/$newJar"))
try {
    $entries = @($zip.Entries | Where-Object { $_.FullName -ceq 'lumen-build.properties' })
    if ($entries.Count -ne 1 -or $entries[0].Length -gt 8192) { throw 'New JAR lacks build metadata' }
    $reader = New-Object IO.StreamReader($entries[0].Open())
    try {
        $properties = @{}
        foreach ($line in ($reader.ReadToEnd() -split '\r?\n')) {
            if ($line -match '^\s*(version|build\.id)\s*=\s*(.*?)\s*$') {
                if ($properties.ContainsKey($Matches[1])) { throw 'Duplicate build property' }
                $properties[$Matches[1]] = $Matches[2]
            }
        }
    } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$buildId = $properties['build.id']
if ($properties.version -cne $toVersion -or $buildId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') { throw 'JAR metadata mismatch' }
$imagePath = Join-Path $newHome 'app/.jpackage.xml'; Required $imagePath
$settings = New-Object Xml.XmlReaderSettings; $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit; $settings.XmlResolver = $null
$reader = [Xml.XmlReader]::Create($imagePath, $settings)
try { $image = New-Object Xml.XmlDocument; $image.XmlResolver = $null; $image.Load($reader) } finally { $reader.Dispose() }
if ($image.DocumentElement.Name -cne 'jpackage-state' -or @($image.DocumentElement.SelectNodes('app-version')).Count -ne 1 -or
    [string]$image.'jpackage-state'.'app-version' -cne $toVersion) { throw 'Target image marker mismatch' }
$name = "Lumen-Music-v$fromVersion-to-v$toVersion-Windows-x64-Update"
$releaseDir = Join-Path $projectRoot "build/releases/$name"; $archive = "$releaseDir.zip"
$fullRelease = Join-Path $projectRoot "build/releases/Lumen-Music-v$toVersion-Windows-x64"
$readme = Join-Path $projectRoot "release/v$toVersion/UPDATE-README.txt"; Required $readme
foreach ($docName in @('SOURCE.zip', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt')) { Required (Join-Path $fullRelease $docName) }
$readmeText = [IO.File]::ReadAllText($readme).Replace('{{BUILD_ID}}', $buildId)
if (-not $readmeText.Contains($buildId) -or $readmeText.Contains('{{BUILD_ID}}')) { throw 'Update README lacks current build identity' }
foreach ($identity in [regex]::Matches($readmeText, 'lumen-[0-9]+\.[0-9]+\.[0-9]+-[A-Za-z0-9._-]+')) {
    if ($identity.Value -cne $buildId) { throw 'Update README contains a stale build identity' }
}
$sourceZip = [IO.Compression.ZipFile]::OpenRead((Join-Path $fullRelease 'SOURCE.zip'))
try {
    $entries = @($sourceZip.Entries | Where-Object { $_.FullName -ceq 'BUILD-INFO.txt' })
    if ($entries.Count -ne 1 -or $entries[0].Length -gt 65536) { throw 'Full release SOURCE lacks unique BUILD-INFO' }
    $reader = New-Object IO.StreamReader($entries[0].Open())
    try { $sourceInfoText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $sourceInfo = @{}
    foreach ($line in ($sourceInfoText -split '\r?\n')) {
        if ($line -cmatch '^([A-Za-z][A-Za-z0-9]*)=(.+)$') {
            if ($sourceInfo.ContainsKey($Matches[1])) { throw 'Duplicate SOURCE build field' }
            $sourceInfo[$Matches[1]] = $Matches[2]
        }
    }
    if ($sourceInfo.Version -cne $toVersion -or $sourceInfo.BuildId -cne $buildId -or
        $sourceInfo.MainJarSha256 -cne (Hash (Join-Path $newHome "app/$newJar"))) { throw 'Full release SOURCE identity differs from target app-image' }
} finally { $sourceZip.Dispose() }
if ((Test-Path -LiteralPath $releaseDir) -or (Test-Path -LiteralPath $archive) -or (Test-Path -LiteralPath "$archive.sha256")) { throw "Update already exists; nothing overwritten: $releaseDir" }
$candidates = @([pscustomobject]@{ scope = 'root'; path = 'LumenMusic.exe' })
foreach ($path in @($newJar, 'LumenMusic.cfg', '.jpackage.xml', 'stream-proxy/lumen-stream-proxy.exe')) { $candidates += [pscustomobject]@{ scope = 'app'; path = $path } }
$service = Join-Path $newHome 'app/service/lumen_service'
foreach ($file in Get-ChildItem -LiteralPath $service -Filter '*.py' -File | Sort-Object Name) {
    if ($file.Name -cnotmatch '^[A-Za-z_][A-Za-z_0-9]*\.py$') { throw 'Invalid service module name' }
    $candidates += [pscustomobject]@{ scope = 'app'; path = 'service/lumen_service/' + $file.Name }
}
$candidates += [pscustomobject]@{ scope = 'root'; path = 'runtime/lib/modules' }
foreach ($path in @('__init__.py', '__main__.py')) { Required (Join-Path $service $path) }
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $oldHome 'app/service/lumen_service') -Filter '*.py' -File) { Required (Join-Path $service $file.Name) }
if ($candidates.Count -gt 1024) { throw 'Too many managed files' }
function Retained([string]$imageRoot) {
    $result = @{}
    foreach ($path in @('runtime', 'app/python', 'app/vlc')) {
        $directory = Join-Path $imageRoot $path
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Missing retained directory: $path" }
        foreach ($file in Get-ChildItem -LiteralPath $directory -File -Recurse -Force) {
            if ($file.FullName -match '[\\/]__pycache__[\\/]') { continue }
            $relative = $file.FullName.Substring($imageRoot.Length + 1).Replace('\', '/')
            if ($relative -ceq 'runtime/lib/modules') { continue }
            $result[$relative] = $file.FullName
        }
    }
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $imageRoot 'app') -Filter '*.jar' -File) {
        if ($file.Name -ne $oldJar -and $file.Name -ne $newJar) { $result['app/' + $file.Name] = $file.FullName }
    }
    return $result
}
$oldRetained = Retained $oldHome; $newRetained = Retained $newHome
if (-not $newRetained.Count -or $newRetained.Count -gt 8192 -or $newRetained.Count -ne $oldRetained.Count) { throw 'Retained inventories differ or exceed limit' }
$doc = New-Object Xml.XmlDocument; $update = $doc.CreateElement('update'); [void]$doc.AppendChild($update)
$attributes = [ordered]@{ format = '1'; app = 'LumenMusic'; platform = 'windows'; arch = 'x64'; fromVersion = $fromVersion; toVersion = $toVersion;
    oldJar = $oldJar; newJar = $newJar; buildId = $buildId; packageId = [Guid]::NewGuid().ToString('D') }
foreach ($key in $attributes.Keys) { $update.SetAttribute($key, [string]$attributes[$key]) }
$files = $doc.CreateElement('files'); [void]$update.AppendChild($files)
$requirements = $doc.CreateElement('requirements'); [void]$update.AppendChild($requirements)
$total = 0L
foreach ($candidate in $candidates) {
    $source = if ($candidate.scope -eq 'root') { Join-Path $newHome $candidate.path } else { Join-Path (Join-Path $newHome 'app') $candidate.path }
    Required $source; $size = (Get-Item -LiteralPath $source).Length
    if ($size -gt 268435456) { throw 'Single payload exceeds 256 MiB' }; $total += $size
    $entry = $doc.CreateElement('file'); $entry.SetAttribute('scope', $candidate.scope); $entry.SetAttribute('path', $candidate.path)
    $entry.SetAttribute('sha256', (Hash $source)); $entry.SetAttribute('size', [string]$size); [void]$files.AppendChild($entry)
}
if ($total -gt 536870912) { throw 'Payload exceeds 512 MiB' }
foreach ($path in $newRetained.Keys | Sort-Object) {
    if (-not $oldRetained.ContainsKey($path) -or (Get-Item -LiteralPath $oldRetained[$path]).Length -ne (Get-Item -LiteralPath $newRetained[$path]).Length -or
        (Hash $oldRetained[$path]) -cne (Hash $newRetained[$path])) { throw "Retained binary changed; use a full release: $path" }
    $entry = $doc.CreateElement('file'); $entry.SetAttribute('path', $path); $entry.SetAttribute('sha256', (Hash $newRetained[$path]))
    $entry.SetAttribute('size', [string](Get-Item -LiteralPath $newRetained[$path]).Length); [void]$requirements.AppendChild($entry)
}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $newHome 'app/LumenMusic.cfg'))) {
    if ($line -cmatch '^app\.classpath=\$APPDIR\\(.+)$') {
        $path = $Matches[1].Replace('\', '/')
        if ($path -ne $newJar -and -not $newRetained.ContainsKey('app/' + $path)) { throw "Unlisted classpath dependency: $path" }
    }
}
if ([Text.Encoding]::UTF8.GetByteCount($doc.OuterXml) -gt 1048576) { throw 'Manifest exceeds 1 MiB' }
# All validation finishes before creating any release output.
New-Item -ItemType Directory -Path (Join-Path $releaseDir 'payload') | Out-Null
$doc.Save((Join-Path $releaseDir 'manifest.xml'))
foreach ($candidate in $candidates) {
    $source = if ($candidate.scope -eq 'root') { Join-Path $newHome $candidate.path } else { Join-Path (Join-Path $newHome 'app') $candidate.path }
    $path = if ($candidate.scope -eq 'root') { 'root/' + $candidate.path } else { $candidate.path }
    $target = Join-Path (Join-Path $releaseDir 'payload') $path
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath $source -Destination $target
    if ((Hash $source) -cne (Hash $target)) { throw "Payload copy failed: $path" }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'update/apply-local-update.ps1') -Destination $releaseDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'update/update-local.cmd') -Destination (Join-Path $releaseDir 'update.cmd')
[IO.File]::WriteAllText((Join-Path $releaseDir 'README.txt'), $readmeText, [Text.UTF8Encoding]::new($false))
foreach ($docName in @('SOURCE.zip', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt')) { Copy-Item -LiteralPath (Join-Path $fullRelease $docName) -Destination $releaseDir }
$hashLines = @(Get-ChildItem -LiteralPath $releaseDir -File | Sort-Object Name | ForEach-Object { (Hash $_.FullName) + '  ' + $_.Name })
[IO.File]::WriteAllLines((Join-Path $releaseDir 'SHA256SUMS.txt'), $hashLines, [Text.Encoding]::ASCII)
$stream = [IO.File]::Open($archive, [IO.FileMode]::CreateNew)
$zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $releaseDir -File -Recurse -Force | Sort-Object FullName) {
        $entry = $name + '/' + $file.FullName.Substring($releaseDir.Length + 1).Replace('\', '/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entry, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $zip.Dispose(); $stream.Dispose() }
if ((Get-Item -LiteralPath $archive).Length -gt 536870912) { throw 'Update ZIP exceeds 512 MiB' }
[IO.File]::WriteAllText("$archive.sha256", ((Hash $archive) + '  ' + (Split-Path $archive -Leaf) + [Environment]::NewLine), [Text.Encoding]::ASCII)
Write-Output "Update ZIP: $archive"
Write-Output "Managed payload files: $($candidates.Count); retained requirements: $($newRetained.Count)"
