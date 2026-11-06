param(
    [Parameter(Mandatory)][string]$RuntimeHome,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$BuildId
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$RuntimeHome = (Resolve-Path -LiteralPath $RuntimeHome).Path
foreach ($relative in @('runtime/release', 'app/python/python.exe', 'app/vlc/libvlc.dll',
        'app/stream-proxy/lumen-stream-proxy.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $RuntimeHome $relative) -PathType Leaf)) {
        throw "Missing runtime input: $relative"
    }
}
$java = Get-Command javac -CommandType Application -ErrorAction Stop
$javaHome = Split-Path (Split-Path $java.Source -Parent) -Parent
$jpackage = Join-Path $javaHome 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw 'A full existing JDK with jpackage is required.' }
$runRoot = Join-Path $projectRoot ('build/local-preview/' + $BuildId)
if (Test-Path -LiteralPath $runRoot) { throw 'BuildId already has local-preview output; select a new identity.' }
$inputDirectory = Join-Path $runRoot 'input'
$outputDirectory = Join-Path $runRoot 'image'
New-Item -ItemType Directory -Path $inputDirectory, $outputDirectory -Force | Out-Null
$previousJava = $env:JAVA_HOME
$previousOptions = $env:MAVEN_OPTS
try {
    $env:JAVA_HOME = $javaHome
    $env:MAVEN_OPTS = '-Xmx384m'
    & (Join-Path $projectRoot 'desktop/mvnw.cmd') -B -ntp -o -f (Join-Path $projectRoot 'desktop/pom.xml') `
        "-Dlumen.build.id=$BuildId" '-Dlumen.release.profile=local-first' '-Dmaven.test.skip=true' package
    if ($LASTEXITCODE -ne 0) { throw 'Local preview Java package failed.' }
} finally {
    if ($null -eq $previousJava) { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue }
    else { $env:JAVA_HOME = $previousJava }
    if ($null -eq $previousOptions) { Remove-Item Env:MAVEN_OPTS -ErrorAction SilentlyContinue }
    else { $env:MAVEN_OPTS = $previousOptions }
}
$mainJar = Join-Path $projectRoot 'desktop/target/lumen-desktop-0.2.2.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::OpenRead($mainJar)
try {
    $reader = [IO.StreamReader]::new($jar.GetEntry('lumen-build.properties').Open())
    try { $properties = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $jar.Dispose() }
if ($properties -notmatch '(?m)^release\.profile=local-first\r?$' -or
        $properties -notmatch ('(?m)^build\.id=' + [regex]::Escape($BuildId) + '\r?$')) {
    throw 'Refuse packaging a full or mismatched JAR as a local preview.'
}
Copy-Item -LiteralPath $mainJar -Destination $inputDirectory
foreach ($dependency in Get-ChildItem -LiteralPath (Join-Path $RuntimeHome 'app') -Filter '*.jar' -File) {
    if ($dependency.Name -notlike 'lumen-desktop-*') {
        Copy-Item -LiteralPath $dependency.FullName -Destination $inputDirectory
    }
}
foreach ($directory in @('python', 'vlc', 'stream-proxy')) {
    Copy-Item -LiteralPath (Join-Path $RuntimeHome ('app/' + $directory)) -Destination $inputDirectory -Recurse
}
$serviceTarget = Join-Path $inputDirectory 'service/lumen_service'
New-Item -ItemType Directory -Path $serviceTarget -Force | Out-Null
foreach ($module in Get-ChildItem -LiteralPath (Join-Path $projectRoot 'service/lumen_service') -Filter '*.py' -File) {
    Copy-Item -LiteralPath $module.FullName -Destination $serviceTarget
}
# The existing, unchanged runtime is reused. No jlink, Rust build, installer,
# registry write or upgrade of the daily LumenMusic installation is performed.
& $jpackage '-J-Xmx256m' --type app-image --name LumenMusicPreview --app-version 0.2.2 `
    --runtime-image (Join-Path $RuntimeHome 'runtime') --input $inputDirectory --dest $outputDirectory `
    --main-jar lumen-desktop-0.2.2.jar --main-class com.lumenmusic.LumenLauncher `
    --vendor 'Lumen Music' --description 'Lumen Music local-first private test candidate' `
    --icon (Join-Path $projectRoot 'desktop/src/main/resources/icons/lumen.ico')
if ($LASTEXITCODE -ne 0) { throw 'Local preview app-image assembly failed.' }
$image = Join-Path $outputDirectory 'LumenMusicPreview'
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
$inventory = @(Get-ChildItem -LiteralPath $image -Recurse -File | Sort-Object FullName | ForEach-Object {
    @{ path = $_.FullName.Substring($image.Length + 1).Replace('\', '/'); sha256 = Hash $_.FullName; bytes = $_.Length }
})
$metadata = [ordered]@{
    status = 'private-image-built-not-window-accepted'; buildId = $BuildId; releaseProfile = 'local-first'
    appHome = $image; executableName = 'LumenMusicPreview.exe'; sourceMainJarSha256 = Hash $mainJar
    published = $false; installerCreated = $false; dailyInstallationChanged = $false; files = $inventory
}
[IO.File]::WriteAllText((Join-Path $runRoot 'image-manifest.json'), ($metadata | ConvertTo-Json -Depth 6),
    [Text.UTF8Encoding]::new($false))
Write-Output "Private local preview: $image; BuildId=$BuildId. Window/boot/independent-PC acceptance remains separate."
