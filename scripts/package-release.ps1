param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$AppHome
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$projectRoot = Split-Path $PSScriptRoot -Parent
function Required([string]$Path) { if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing release input: $Path" } }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Source-File([string]$Path) {
    return $Path -notmatch '(?i)(^|/)(userdata|personal-data|signing|certificates|__pycache__|\.venv|\.pytest_cache|\.cache|target|node_modules)(/|$)|\.pyc$|\.log$' -and
        $Path -notmatch '(?i)(^|/)(ai-secret\.json.*|ai-settings\.json.*|\.ai-settings-recovery\.json.*|credentials\.json.*|\.env[^/]*|cookies?\.(txt|json))$|\.(pfx|p12|key|pem)$'
}
function Zip-Bytes($Zip, [string]$Name) {
    $entries = @($Zip.Entries | Where-Object { $_.FullName -ceq $Name })
    Assert ($entries.Count -eq 1) "Missing or duplicate JAR resource: $Name"
    $input = $entries[0].Open(); $output = New-Object IO.MemoryStream
    try { $input.CopyTo($output); return ,$output.ToArray() } finally { $input.Dispose(); $output.Dispose() }
}
function Bytes-Hash([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
}
function Properties([string]$Text) {
    $result = @{}
    foreach ($line in ($Text -split '\r?\n')) {
        if ($line -match '^\s*(version|build\.id)\s*=\s*(.*?)\s*$') {
            Assert (-not $result.ContainsKey($Matches[1])) 'Duplicate build property'
            $result[$Matches[1]] = $Matches[2]
        }
    }
    return $result
}
function Assert-Native([string]$Path, [string]$Version) {
    Required $Path
    Assert ([Diagnostics.FileVersionInfo]::GetVersionInfo($Path).ProductVersion -ceq $Version) 'Installer or launcher version differs from project version'
    $stream = [IO.File]::OpenRead($Path); $reader = New-Object IO.BinaryReader($stream)
    try {
        Assert ($reader.ReadUInt16() -eq 0x5a4d) 'Invalid native executable'
        $stream.Position = 60; $offset = $reader.ReadInt32()
        Assert ($offset -ge 64 -and $offset -le $stream.Length - 6) 'Invalid PE offset'
        $stream.Position = $offset
        Assert ($reader.ReadUInt32() -eq 0x00004550 -and $reader.ReadUInt16() -eq 0x8664) 'Release executable is not Windows x64'
    } finally { $reader.Dispose(); $stream.Dispose() }
}
$pom = [xml](Get-Content -LiteralPath (Join-Path $projectRoot 'desktop/pom.xml') -Raw)
$version = [string]$pom.project.version
$name = "Lumen-Music-v$version-Windows-x64"
$destination = Join-Path $projectRoot "build/releases/$name"
$archive = "$destination.zip"
Assert (-not ((Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath $archive) -or
    (Test-Path -LiteralPath "$archive.sha256"))) "Release already exists; nothing overwritten: $destination"
$Installer = (Resolve-Path -LiteralPath $Installer).Path
$AppHome = (Resolve-Path -LiteralPath $AppHome).Path.TrimEnd('\')
Assert-Native $Installer $version
Assert-Native (Join-Path $AppHome 'LumenMusic.exe') $version
$jarName = "lumen-desktop-$version.jar"
$mainJar = Join-Path $AppHome "app/$jarName"
$targetJar = Join-Path $projectRoot "desktop/target/$jarName"
$filteredProperties = Join-Path $projectRoot 'desktop/target/classes/lumen-build.properties'
foreach ($path in @($mainJar, $targetJar, $filteredProperties)) { Required $path }
Assert ((Hash $mainJar) -ceq (Hash $targetJar)) 'AppHome main JAR differs from current target JAR'
$zip = [IO.Compression.ZipFile]::OpenRead($mainJar)
try {
    $jarProperties = Properties ([Text.Encoding]::UTF8.GetString((Zip-Bytes $zip 'lumen-build.properties')))
    $build = Properties ([IO.File]::ReadAllText($filteredProperties))
    $buildId = [string]$build['build.id']
    Assert ($build.version -ceq $version -and $buildId -cmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -and
        $jarProperties.version -ceq $version -and $jarProperties['build.id'] -ceq $buildId) 'Current build properties and AppHome JAR identity differ'
    $helper = Join-Path $PSScriptRoot 'update/apply-local-update.ps1'; Required $helper
    $trustedHelperHash = Hash $helper
    Assert ((Bytes-Hash (Zip-Bytes $zip 'update/apply-local-update.ps1')) -ceq $trustedHelperHash) 'Bundled update helper does not match the trusted canonical helper'
} finally { $zip.Dispose() }
$cfgPath = Join-Path $AppHome 'app/LumenMusic.cfg'; Required $cfgPath
$cfg = [IO.File]::ReadAllLines($cfgPath)
Assert (@($cfg | Where-Object { $_ -ceq ('app.classpath=$APPDIR\' + $jarName) }).Count -eq 1 -and
    @($cfg | Where-Object { $_ -cmatch '^app\.classpath=\$APPDIR\\lumen-desktop-' }).Count -eq 1 -and
    @($cfg | Where-Object { $_ -ceq "java-options=-Djpackage.app-version=$version" }).Count -eq 1 -and
    @($cfg | Where-Object { $_ -cmatch '^java-options=-Djpackage\.app-version=' }).Count -eq 1 -and
    @($cfg | Where-Object { $_ -ceq 'app.mainclass=com.lumenmusic.LumenLauncher' }).Count -eq 1) 'AppHome launcher configuration differs from release identity'
$serviceSource = Join-Path $projectRoot 'service/lumen_service'
$serviceImage = Join-Path $AppHome 'app/service/lumen_service'
$sourceModules = @(Get-ChildItem -LiteralPath $serviceSource -Filter '*.py' -File -Recurse | Sort-Object FullName)
$imageModules = @(Get-ChildItem -LiteralPath $serviceImage -Filter '*.py' -File -Recurse | Sort-Object FullName)
Assert ($sourceModules.Count -gt 0 -and $sourceModules.Count -eq $imageModules.Count) 'Service Python inventories differ'
foreach ($file in $sourceModules) {
    $relative = $file.FullName.Substring($serviceSource.Length + 1)
    $target = Join-Path $serviceImage $relative; Required $target
    Assert ((Hash $file.FullName) -ceq (Hash $target)) "Packaged service differs from source: $relative"
}
$proxySource = Join-Path $projectRoot 'stream-proxy/target/release/lumen-stream-proxy.exe'
$proxyImage = Join-Path $AppHome 'app/stream-proxy/lumen-stream-proxy.exe'
foreach ($path in @($proxySource, $proxyImage)) { Required $path }
Assert ((Hash $proxySource) -ceq (Hash $proxyImage)) 'Packaged stream proxy differs from current release build'
$imageMetadata = Join-Path $AppHome 'app/.jpackage.xml'; Required $imageMetadata
$settings = New-Object Xml.XmlReaderSettings; $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit; $settings.XmlResolver = $null
$reader = [Xml.XmlReader]::Create($imageMetadata, $settings)
try { $image = New-Object Xml.XmlDocument; $image.XmlResolver = $null; $image.Load($reader) } finally { $reader.Dispose() }
Assert ($image.DocumentElement.Name -ceq 'jpackage-state' -and
    [string]$image.'jpackage-state'.'app-version' -ceq $version) 'AppHome image marker differs from release version'
$jdkVersion = $image.DocumentElement.GetAttribute('version')
$runtimeRelease = Join-Path $AppHome 'runtime/release'; Required $runtimeRelease
$runtimeVersionLines = @([IO.File]::ReadAllLines($runtimeRelease) | Where-Object { $_ -cmatch '^JAVA_VERSION="([^"\r\n]+)"$' })
Assert ($jdkVersion -cmatch '^[0-9][A-Za-z0-9.+_-]*$' -and $runtimeVersionLines.Count -eq 1) 'Missing packaged JDK/runtime version metadata'
[void]($runtimeVersionLines[0] -cmatch '^JAVA_VERSION="([^"\r\n]+)"$'); $runtimeVersion = $Matches[1]
$readme = Join-Path $projectRoot "release/v$version/README.txt"; Required $readme
$readmeText = [IO.File]::ReadAllText($readme).Replace('{{BUILD_ID}}', $buildId)
Assert ($readmeText.Contains($buildId) -and -not $readmeText.Contains('{{BUILD_ID}}')) 'Release README lacks current build identity'
foreach ($identity in [regex]::Matches($readmeText, 'lumen-[0-9]+\.[0-9]+\.[0-9]+-[A-Za-z0-9._-]+')) {
    Assert ($identity.Value -ceq $buildId) 'Release README contains a stale build identity'
}
$noticeSource = Join-Path $projectRoot 'release/v0.1.0/THIRD-PARTY-NOTICES.txt'; Required $noticeSource
$license = Join-Path $projectRoot 'release/licenses/GPL-3.0.txt'; Required $license
$thirdPartyNames = @('Python-3.13.15.tgz', 'vlcj-4.11.0-sources.jar', 'vlcj-natives-4.8.3-sources.jar')
foreach ($file in $thirdPartyNames) { Required (Join-Path $projectRoot "build/release-sources/third-party/$file") }

# Validate the complete binary identity before creating release output.
New-Item -ItemType Directory -Path $destination | Out-Null
Copy-Item -LiteralPath $Installer -Destination (Join-Path $destination "LumenMusic-$version-Setup.exe")
[IO.File]::WriteAllText((Join-Path $destination 'README.txt'), $readmeText, [Text.UTF8Encoding]::new($false))
$notices = [IO.File]::ReadAllText($noticeSource).Replace('Lumen Music 0.1.0 |', "Lumen Music $version |")
[IO.File]::WriteAllText((Join-Path $destination 'THIRD-PARTY-NOTICES.txt'), $notices, [Text.Encoding]::UTF8)
Copy-Item -LiteralPath $license -Destination (Join-Path $destination 'LICENSE.txt')
$sourceRoot = Join-Path $projectRoot ("build/release-sources/$version-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $sourceRoot | Out-Null
foreach ($tree in @('desktop/src', 'desktop/.mvn', 'service/lumen_service', 'service/tests',
        'stream-proxy/src', 'scripts', 'release', 'docs', '.github/workflows')) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $projectRoot $tree) -Recurse -File |
            Where-Object { Source-File ($_.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')) }) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1)
        $to = Join-Path $sourceRoot $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $to
    }
}
foreach ($file in @('README.md', 'AGENTS.md', 'CONTRIBUTING.md', 'CHANGELOG.md', '.gitignore', '.editorconfig', '.github/ISSUE_TEMPLATE/bug_report.yml',
        '.github/ISSUE_TEMPLATE/feature_request.yml', '.github/ISSUE_TEMPLATE/config.yml',
        '.github/pull_request_template.md',
        '第四阶段.md', 'desktop/pom.xml', 'desktop/mvnw', 'desktop/mvnw.cmd',
        'stream-proxy/Cargo.toml', 'stream-proxy/Cargo.lock')) {
    $to = Join-Path $sourceRoot $file
    New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $to
}
$thirdParty = Join-Path $sourceRoot 'third-party'
New-Item -ItemType Directory -Path $thirdParty | Out-Null
foreach ($file in $thirdPartyNames) { Copy-Item -LiteralPath (Join-Path $projectRoot "build/release-sources/third-party/$file") -Destination $thirdParty }
Copy-Item -LiteralPath $license -Destination (Join-Path $sourceRoot 'LICENSE.txt')
$buildInfo = @(
    'Lumen Music corresponding source build information',
    "Version=$version", "BuildId=$buildId", 'Platform=Windows-x64',
    "JpackageJdkVersion=$jdkVersion", "BundledRuntimeJavaVersion=$runtimeVersion",
    "MainJarSha256=$(Hash $mainJar)", "InstallerSha256=$(Hash $Installer)",
    "LauncherSha256=$(Hash (Join-Path $AppHome 'LumenMusic.exe'))",
    "StreamProxySha256=$(Hash $proxyImage)", "CanonicalUpdateHelperSha256=$trustedHelperHash", '',
    'Source hashes: SOURCE-SHA256SUMS.txt (relative source paths, excludes that checksum file).',
    'desktop/src/main/resources/lumen-build.properties is the Maven resource template.',
    "Its filtered output in the shipped JAR is version=$version and build.id=$buildId.",
    'Run from the extracted source root with the declared toolchain and existing dependencies.',
    "Use JDK $jdkVersion for jpackage and a Windows x64 runtime; install WiX 3.14.1 for EXE output.",
    'EXE installer native actions use MSVC x64 C++17 and Windows SDK with /MT; no additional user runtime is required.',
    'Restore the Python 3.13.15 embedded runtime and VLC 3.0.23 runtime to the package-windows defaults.',
    'The build identifier must be preserved with these commands:',
    "powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-windows.ps1 -Type app-image -BuildId $buildId",
    "powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-windows.ps1 -Type exe -BuildId $buildId -AppHome <built-LumenMusic>",
    'The commands reproduce build identity; archive timestamps and native tool outputs may differ.',
    'To assemble the full release, pass the resulting installer and app-image explicitly:',
    'powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-release.ps1 -Installer <built-Setup.exe> -AppHome <built-LumenMusic>',
    'The included third-party source archives must be available at build/release-sources/third-party.'
)
[IO.File]::WriteAllLines((Join-Path $sourceRoot 'BUILD-INFO.txt'), $buildInfo, [Text.Encoding]::ASCII)
$sourceHashes = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
    (Hash $_.FullName) + '  ' + $_.FullName.Substring($sourceRoot.Length + 1).Replace('\', '/')
})
[IO.File]::WriteAllLines((Join-Path $sourceRoot 'SOURCE-SHA256SUMS.txt'), $sourceHashes, [Text.UTF8Encoding]::new($false))
$sourceStream = [IO.File]::Open((Join-Path $destination 'SOURCE.zip'), [IO.FileMode]::CreateNew)
$sourceZip = New-Object IO.Compression.ZipArchive($sourceStream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Force | Sort-Object FullName) {
        $entry = $file.FullName.Substring($sourceRoot.Length + 1).Replace('\', '/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($sourceZip, $file.FullName, $entry, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $sourceZip.Dispose(); $sourceStream.Dispose() }
$hashLines = @(Get-ChildItem -LiteralPath $destination -File | Sort-Object Name | ForEach-Object { (Hash $_.FullName) + '  ' + $_.Name })
[IO.File]::WriteAllLines((Join-Path $destination 'SHA256SUMS.txt'), $hashLines, [Text.Encoding]::ASCII)
$stream = [IO.File]::Open($archive, [IO.FileMode]::CreateNew)
$zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $destination -Recurse -File -Force | Sort-Object FullName) {
        $entry = $name + '/' + $file.FullName.Substring($destination.Length + 1).Replace('\', '/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entry, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $zip.Dispose(); $stream.Dispose() }
[IO.File]::WriteAllText("$archive.sha256", ((Hash $archive) + '  ' + (Split-Path $archive -Leaf) + [Environment]::NewLine), [Text.Encoding]::ASCII)
Write-Output "Release ZIP: $archive"
Write-Output "Release build: $version / $buildId; JDK: $jdkVersion; runtime: $runtimeVersion"
Write-Output "Release size: $([Math]::Round((Get-Item -LiteralPath $archive).Length / 1MB, 1)) MiB"
