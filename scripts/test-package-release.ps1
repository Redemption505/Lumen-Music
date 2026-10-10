param([Parameter(Mandatory)][string]$AppHome)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$projectRoot = Split-Path $PSScriptRoot -Parent
$AppHome = (Resolve-Path -LiteralPath $AppHome).Path
$root = Join-Path $projectRoot ('build/package-release-tests/' + [Guid]::NewGuid().ToString('N'))
$project = Join-Path $root 'QA_ONLY-project'
function Put([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Path (Split-Path $Path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Text, [Text.Encoding]::ASCII)
}
function Copy-Input([string]$From, [string]$To) {
    New-Item -ItemType Directory -Path (Split-Path $To -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $From -Destination $To
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$version = ([xml][IO.File]::ReadAllText((Join-Path $projectRoot 'desktop/pom.xml'))).project.version
$jarName = "lumen-desktop-$version.jar"
$fixtureHome = Join-Path $project 'image'
foreach ($path in @('LumenMusic.exe', "app/$jarName", 'app/LumenMusic.cfg', 'app/.jpackage.xml', 'runtime/release', 'app/stream-proxy/lumen-stream-proxy.exe')) {
    Copy-Input (Join-Path $AppHome $path) (Join-Path $fixtureHome $path)
}
Copy-Input (Join-Path $projectRoot 'scripts/package-release.ps1') (Join-Path $project 'scripts/package-release.ps1')
Copy-Input (Join-Path $projectRoot 'scripts/update/apply-local-update.ps1') (Join-Path $project 'scripts/update/apply-local-update.ps1')
# Only the isolated metadata fixture receives the current helper resource.
# This keeps these checks independent of the next real Maven/JAR build.
$fixtureJar = Join-Path $fixtureHome "app/$jarName"
$fixtureZip = [IO.Compression.ZipFile]::Open($fixtureJar, [IO.Compression.ZipArchiveMode]::Update)
try {
    $oldEntry = $fixtureZip.GetEntry('update/apply-local-update.ps1')
    if ($oldEntry) { $oldEntry.Delete() }
    $input = [IO.File]::OpenRead((Join-Path $projectRoot 'scripts/update/apply-local-update.ps1'))
    $output = $fixtureZip.CreateEntry('update/apply-local-update.ps1').Open()
    try { $input.CopyTo($output) } finally { $input.Dispose(); $output.Dispose() }
} finally { $fixtureZip.Dispose() }
Copy-Input $fixtureJar (Join-Path $project "desktop/target/$jarName")
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $AppHome "app/$jarName"))
try {
    $reader = New-Object IO.StreamReader($zip.GetEntry('lumen-build.properties').Open())
    try { $buildText = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
Put (Join-Path $project 'desktop/target/classes/lumen-build.properties') $buildText
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $AppHome 'app/service/lumen_service') -Filter '*.py' -File) {
    Copy-Input $file.FullName (Join-Path $fixtureHome ('app/service/lumen_service/' + $file.Name))
    Copy-Input $file.FullName (Join-Path $project ('service/lumen_service/' + $file.Name))
}
Copy-Input (Join-Path $AppHome 'app/stream-proxy/lumen-stream-proxy.exe') (Join-Path $project 'stream-proxy/target/release/lumen-stream-proxy.exe')
Put (Join-Path $project 'desktop/pom.xml') ("<project><version>$version</version></project>")
foreach ($path in @('desktop/src/main/resources/lumen-build.properties', 'desktop/.mvn/fixture.txt', 'service/tests/fixture.txt',
        'stream-proxy/src/fixture.txt', "release/v$version/README.txt", 'release/v0.1.0/THIRD-PARTY-NOTICES.txt',
        'release/licenses/GPL-3.0.txt', 'docs/fixture.txt', 'README.md', 'AGENTS.md', 'CONTRIBUTING.md',
        'CHANGELOG.md', '.gitignore', '.github/workflows/ci.yml', '第四阶段.md', 'desktop/mvnw', 'desktop/mvnw.cmd',
        'stream-proxy/Cargo.toml', 'stream-proxy/Cargo.lock')) { Put (Join-Path $project $path) 'QA_ONLY corresponding-source metadata fixture' }
Put (Join-Path $project "release/v$version/README.txt") 'QA_ONLY current build {{BUILD_ID}}'
foreach ($path in @('docs/assets/home-lumen.png', 'docs/assets/home-light.png')) { Put (Join-Path $project $path) 'QA_ONLY screenshot stand-in' }
foreach ($path in @('service/lumen_service/ai-secret.json', 'service/tests/.ai-settings-recovery.json',
        'desktop/src/main/resources/.env', 'desktop/src/main/resources/private-canary.pem',
        'docs/userdata/library.xml', 'scripts/.cache/private-canary.json', '.github/workflows/.env',
        '.github/private-canary.json')) { Put (Join-Path $project $path) 'QA_ONLY private canary must not enter SOURCE' }
foreach ($path in @('Python-3.13.15.tgz', 'vlcj-4.11.0-sources.jar', 'vlcj-natives-4.8.3-sources.jar')) {
    Put (Join-Path $project ('build/release-sources/third-party/' + $path)) 'QA_ONLY third-party source placeholder'
}
$script = Join-Path $project 'scripts/package-release.ps1'
$installer = Join-Path $fixtureHome 'LumenMusic.exe'
$release = Join-Path $project "build/releases/Lumen-Music-v$version-Windows-x64"
$powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$passed = New-Object System.Collections.ArrayList
function Run([string]$Label, [string]$Expected = '') {
    $stdout = Join-Path $root ($Label + '-stdout.log'); $stderr = Join-Path $root ($Label + '-stderr.log')
    $originalModules = $env:PSModulePath
    try {
        $env:PSModulePath = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules'
        $process = Start-Process -FilePath $powershell -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            ('"' + $script + '"'), '-Installer', ('"' + $installer + '"'), '-AppHome', ('"' + $fixtureHome + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    } finally { $env:PSModulePath = $originalModules }
    try {
        [void]$process.Handle
        Assert ($process.WaitForExit(90000)) 'Release fixture timed out'
        $process.WaitForExit(); $process.Refresh()
        $output = [IO.File]::ReadAllText($stderr)
        if ($Expected) {
            Assert ($process.ExitCode -ne 0 -and $output -match $Expected) "Wrong refusal for $Label : $output"
            if ($Label -ne 'overwrite') { Assert (-not (Test-Path -LiteralPath $release)) "Validation created release output: $Label" }
        } else { Assert ($process.ExitCode -eq 0) "Fixture release failed: $output" }
        [void]$passed.Add($Label); Write-Output "PASS metadata fixture: $Label"
    } finally { $process.Dispose() }
}
$targetJar = Join-Path $project "desktop/target/$jarName"
$oldLauncher = Join-Path $projectRoot 'build/dist/20261001-112410-718/LumenMusic/LumenMusic.exe'
if (Test-Path -LiteralPath $oldLauncher -PathType Leaf) {
    $matchingInstaller = $installer; $installer = $oldLauncher
    Run 'native-version' 'version differs'
    $installer = $matchingInstaller
}
Put $targetJar 'modified target jar'; Run 'target-jar' 'main JAR differs'
Copy-Input (Join-Path $fixtureHome "app/$jarName") $targetJar
Put (Join-Path $project 'desktop/target/classes/lumen-build.properties') "version=$version`nbuild.id=wrong-build"
Run 'build-properties' 'identity differ'
Put (Join-Path $project 'desktop/target/classes/lumen-build.properties') $buildText
$servicePath = Join-Path $project 'service/lumen_service/__main__.py'
Put $servicePath 'modified service'; Run 'service-source' 'service differs'
Copy-Input (Join-Path $fixtureHome 'app/service/lumen_service/__main__.py') $servicePath
$proxyPath = Join-Path $project 'stream-proxy/target/release/lumen-stream-proxy.exe'
Put $proxyPath 'modified proxy'; Run 'proxy-build' 'proxy differs'
Copy-Input (Join-Path $fixtureHome 'app/stream-proxy/lumen-stream-proxy.exe') $proxyPath
$helperPath = Join-Path $project 'scripts/update/apply-local-update.ps1'
Put $helperPath 'modified helper'; Run 'trusted-helper' 'trusted canonical helper'
Copy-Input (Join-Path $projectRoot 'scripts/update/apply-local-update.ps1') $helperPath
New-Item -ItemType Directory -Path $release -Force | Out-Null
Put (Join-Path $release 'keep.txt') 'existing release directory must survive'
Run 'overwrite' 'already exists'
Assert ([IO.File]::ReadAllText((Join-Path $release 'keep.txt')) -ceq 'existing release directory must survive') 'Existing release directory changed'
# Move the deliberately-created fixture aside; no deletion is necessary.
Move-Item -LiteralPath $release -Destination (Join-Path $root 'existing-release-preserved')
Put ($release + '.zip') 'existing release ZIP must survive'
Run 'overwrite-zip' 'already exists'
Assert ([IO.File]::ReadAllText($release + '.zip') -ceq 'existing release ZIP must survive') 'Existing release ZIP changed'
Move-Item -LiteralPath ($release + '.zip') -Destination (Join-Path $root 'existing-release.zip')
Put ($release + '.zip.sha256') 'existing release sidecar must survive'
Run 'overwrite-sidecar' 'already exists'
Assert ([IO.File]::ReadAllText($release + '.zip.sha256') -ceq 'existing release sidecar must survive') 'Existing release sidecar changed'
Move-Item -LiteralPath ($release + '.zip.sha256') -Destination (Join-Path $root 'existing-release.zip.sha256')
Put (Join-Path $project "release/v$version/README.txt") 'QA_ONLY stale build lumen-0.0.0-wrong'
Run 'stale-readme' 'lacks current build identity|stale build identity'
Put (Join-Path $project "release/v$version/README.txt") 'QA_ONLY current build {{BUILD_ID}}'
Run 'matching-identity'
$properties = @{}
foreach ($line in ($buildText -split '\r?\n')) { if ($line -match '^build\.id=(.+)$') { $properties.buildId = $Matches[1] } }
Assert ([IO.File]::ReadAllText((Join-Path $release 'README.txt')).Contains($properties.buildId)) 'Packaged README lacks rendered build identity'
foreach ($name in @("LumenMusic-$version-Setup.exe", 'README.txt', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt', 'SOURCE.zip', 'SHA256SUMS.txt')) {
    Assert (Test-Path -LiteralPath (Join-Path $release $name) -PathType Leaf) "Fixture release lacks $name"
}
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $release 'SOURCE.zip'))
try {
    foreach ($path in @('docs/assets/home-lumen.png', 'docs/assets/home-light.png')) { Assert ($null -ne $zip.GetEntry($path)) "Screenshot missing from SOURCE: $path" }
    foreach ($path in @('AGENTS.md', 'CONTRIBUTING.md', 'CHANGELOG.md', '.gitignore', '.github/workflows/ci.yml')) {
        $entry = $zip.GetEntry($path); Assert ($null -ne $entry) "Repository documentation or workflow missing from SOURCE: $path"
        $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
        try { $actual = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose(); $stream.Dispose() }
        Assert ($actual -ceq (Hash (Join-Path $project $path))) "Repository documentation or workflow differs from source: $path"
    }
    foreach ($path in @('service/lumen_service/ai-secret.json', 'service/tests/.ai-settings-recovery.json',
            'desktop/src/main/resources/.env', 'desktop/src/main/resources/private-canary.pem',
            'docs/userdata/library.xml', 'scripts/.cache/private-canary.json', '.github/workflows/.env',
            '.github/private-canary.json')) { Assert ($null -eq $zip.GetEntry($path)) "Private fixture included in SOURCE: $path" }
    $reader = New-Object IO.StreamReader($zip.GetEntry('BUILD-INFO.txt').Open())
    try { $info = $reader.ReadToEnd() } finally { $reader.Dispose() }
    Assert ($info -notmatch [regex]::Escape($projectRoot) -and $info -notmatch 'C:[\\/]|D:[\\/]|Eternity') 'BUILD-INFO leaks a private path'
    Assert ($info -match 'JpackageJdkVersion=' -and $info -match 'BundledRuntimeJavaVersion=' -and $info -match '-BuildId ') 'Missing toolchain/build instructions'
    $reader = New-Object IO.StreamReader($zip.GetEntry('SOURCE-SHA256SUMS.txt').Open())
    try { $lines = $reader.ReadToEnd() -split '\r?\n' } finally { $reader.Dispose() }
    foreach ($line in $lines | Where-Object { $_ }) {
        Assert ($line -cmatch '^([0-9a-f]{64})  (.+)$') 'Invalid source checksum syntax'
        $expectedHash = $Matches[1]; $name = $Matches[2]
        $entry = $zip.GetEntry($name); Assert ($null -ne $entry) "Source hash entry missing: $name"
        $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
        try { $actual = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose(); $stream.Dispose() }
        Assert ($actual -ceq $expectedHash) "Source checksum failed: $name"
    }
    Assert (@($zip.Entries).Count -eq @($lines | Where-Object { $_ }).Count + 1) 'Source hash inventory is incomplete'
} finally { $zip.Dispose() }
Write-Output "PASS source ZIP inventory and hashes; cases=$($passed.Count); artifacts=$root"
Write-Output 'QA_ONLY: the installer fixture uses a real launcher PE as a versioned stand-in; this does not test real installer execution.'
