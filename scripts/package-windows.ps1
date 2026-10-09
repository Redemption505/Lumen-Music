param(
    [ValidateSet('app-image', 'exe')][string]$Type = 'app-image',
    [string]$WixBin,
    [string]$VlcHome,
    [string]$PythonHome,
    [string]$BuildId,
    [string]$AppHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$desktopDir = Join-Path $projectRoot 'desktop'
$packageRunId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
if ($Type -eq 'exe' -and -not $AppHome) { throw 'Build an app-image first, then pass its directory with -AppHome for the installer.' }
if ($Type -eq 'app-image' -and $AppHome) { throw '-AppHome is only used to assemble an installer.' }
if (-not $BuildId -and $Type -eq 'app-image') { $BuildId = Get-Date -Format 'yyyyMMdd-HHmmss' }
if ($BuildId -and $BuildId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') { throw 'Invalid build identifier.' }
$stage = Join-Path $projectRoot "build/package-input/$packageRunId"
$destination = Join-Path $projectRoot "build/dist/$packageRunId"
$wrapper = Join-Path $desktopDir 'mvnw.cmd'
$cachedMaven = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.11') -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
if ($cachedMaven) { $wrapper = $cachedMaven.FullName }
$jpackage = Get-Command jpackage -ErrorAction Stop
$pom = [xml](Get-Content -LiteralPath (Join-Path $desktopDir 'pom.xml') -Raw)
$javafxVersion = $pom.project.properties.'javafx.version'
$artifact = $pom.project.artifactId
$version = $pom.project.version
function Invoke-Packager([string[]]$PackageArguments) {
    $originalPath = $env:PATH
    try {
        if ($Type -eq 'exe') {
            if (-not $WixBin) {
                $localWix = Join-Path $projectRoot 'build/tools/wix-3.14.1/bin'
                if (Test-Path -LiteralPath (Join-Path $localWix 'candle.exe')) { $WixBin = $localWix }
            }
            if ($WixBin) {
                if (-not (Test-Path -LiteralPath (Join-Path $WixBin 'candle.exe')) -or
                    -not (Test-Path -LiteralPath (Join-Path $WixBin 'light.exe'))) { throw "WiX path is invalid: $WixBin" }
                $env:PATH = "$WixBin;$originalPath"
            }
        }
        & $jpackage.Source @PackageArguments
        if ($LASTEXITCODE -ne 0) { throw 'jpackage packaging failed.' }
    } finally { $env:PATH = $originalPath }
}
if ($Type -eq 'exe') {
    $AppHome = (Resolve-Path -LiteralPath $AppHome).Path
    $imageJar = Join-Path $AppHome "app/$artifact-$version.jar"
    $targetJar = Join-Path $desktopDir "target/$artifact-$version.jar"
    if ([Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $AppHome 'LumenMusic.exe')).ProductVersion -ne $version -or
        (Get-FileHash -LiteralPath $imageJar).Hash -cne (Get-FileHash -LiteralPath $targetJar).Hash) {
        throw 'The tested app-image does not match the current version and production JAR.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($imageJar)
    try {
        $reader = [IO.StreamReader]::new($zip.GetEntry('lumen-build.properties').Open())
        try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($metadata -notmatch '(?m)^build\.id=([A-Za-z0-9][A-Za-z0-9._-]{0,127})\r?$') { throw 'App-image build identity is missing.' }
        $imageBuildId = $Matches[1]
        if ($BuildId -and $BuildId -cne $imageBuildId) { throw 'App-image BuildId differs from the requested installer.' }
    } finally { $zip.Dispose() }
    if (-not $WixBin) { $WixBin = Join-Path $projectRoot 'build/tools/wix-3.14.1/bin' }
    $installerResources = Join-Path $projectRoot "build/installer-resources/$packageRunId"
    & (Join-Path $PSScriptRoot 'installer/prepare-resources.ps1') -Destination $installerResources -WixBin $WixBin -BuildId $imageBuildId
    New-Item -ItemType Directory -Path $destination | Out-Null
    Invoke-Packager @('-J-Duser.language=zh', '-J-Duser.country=CN', '--verbose', '--type', 'exe', '--name', 'LumenMusic', '--app-version', $version, '--app-image', $AppHome,
        '--dest', $destination, '--vendor', 'Lumen Music', '--description', 'Lumen Music desktop music player',
        '--icon', (Join-Path $desktopDir 'src/main/resources/icons/lumen.ico'),
        '--resource-dir', $installerResources, '--temp', (Join-Path $projectRoot "build/installer-work/$packageRunId"),
        '--win-per-user-install', '--win-dir-chooser', '--win-menu', '--win-shortcut',
        '--win-menu-group', 'Lumen Music', '--win-upgrade-uuid', '6F3FA127-20B0-3D74-BD13-D27F8BF9770B')
    if ([Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $destination "LumenMusic-$version.exe")).ProductVersion -ne $version) {
        throw 'Installer version differs from the tested app-image.'
    }
    Write-Host "Installer assembled from tested app-image: $AppHome ($imageBuildId)"
    Write-Host "Package output: $destination"
    return
}
$mavenRepository = Join-Path $env:USERPROFILE '.m2/repository/org/openjfx'
$cargo = Get-Command cargo -ErrorAction Stop

& $wrapper -f (Join-Path $desktopDir 'pom.xml') -B -ntp "-Dmaven.repo.local=$env:USERPROFILE/.m2/repository" "-Dlumen.build.id=$BuildId" '-Dmaven.test.skip=true' package
if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败。' }
& $cargo.Source build --manifest-path (Join-Path $projectRoot 'stream-proxy/Cargo.toml') --release --locked
if ($LASTEXITCODE -ne 0) { throw 'Axum 音频侧车构建失败。' }

New-Item -ItemType Directory -Force -Path $stage, $destination | Out-Null
Copy-Item -LiteralPath (Join-Path $desktopDir "target/$artifact-$version.jar") -Destination $stage
foreach ($module in @('base', 'graphics', 'controls', 'media')) {
    $jar = Join-Path $mavenRepository "javafx-$module/$javafxVersion/javafx-$module-$javafxVersion-win.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "缺少 Maven 已声明的 JavaFX 运行时依赖：$jar" }
    Copy-Item -LiteralPath $jar -Destination $stage
}
foreach ($nativeArtifact in @('jna', 'jna-platform')) {
    $dependency = $pom.project.dependencies.dependency |
        Where-Object { $_.groupId -eq 'net.java.dev.jna' -and $_.artifactId -eq $nativeArtifact } |
        Select-Object -First 1
    if (-not $dependency) { throw "Maven 未声明任务栏运行时依赖：$nativeArtifact" }
    $nativeVersion = [string]$dependency.version
    $jar = Join-Path $env:USERPROFILE ".m2/repository/net/java/dev/jna/$nativeArtifact/$nativeVersion/$nativeArtifact-$nativeVersion.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "缺少 Maven 已声明的任务栏运行时依赖：$jar" }
    Copy-Item -LiteralPath $jar -Destination $stage
}
foreach ($runtimeJar in @(
    'uk/co/caprica/vlcj/4.11.0/vlcj-4.11.0.jar',
    'uk/co/caprica/vlcj-natives/4.8.3/vlcj-natives-4.8.3.jar',
    'net/java/dev/jna/jna-jpms/5.14.0/jna-jpms-5.14.0.jar',
    'net/java/dev/jna/jna-platform-jpms/5.14.0/jna-platform-jpms-5.14.0.jar')) {
    $jar = Join-Path $env:USERPROFILE ".m2/repository/$runtimeJar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "缺少 VLC 播放依赖：$jar" }
    Copy-Item -LiteralPath $jar -Destination $stage
}
if (-not $VlcHome) {
    $VlcHome = Join-Path $projectRoot 'build/tools/vlc-3.0.23/vlc-3.0.23'
}
if (-not (Test-Path -LiteralPath (Join-Path $VlcHome 'libvlc.dll'))) {
    $archive = Join-Path $projectRoot 'build/tools/vlc-3.0.23-win64.zip'
    New-Item -ItemType Directory -Force -Path (Split-Path $archive -Parent) | Out-Null
    Invoke-WebRequest -Uri 'https://download.videolan.org/pub/videolan/vlc/3.0.23/win64/vlc-3.0.23-win64.zip' -OutFile $archive
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne
        '992d19dbd0b8a7cde9167d2f7780b1ef6f92acc8a71acfa736101a21f35181e1') {
        throw 'VLC 官方运行时 SHA-256 校验失败。'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath (Split-Path $VlcHome -Parent) -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $VlcHome 'plugins'))) {
    throw "VLC 运行时缺少插件目录：$VlcHome"
}
$vlcTarget = Join-Path $stage 'vlc'
New-Item -ItemType Directory -Force -Path $vlcTarget | Out-Null
foreach ($name in @('libvlc.dll', 'libvlccore.dll', 'COPYING.txt')) {
    Copy-Item -LiteralPath (Join-Path $VlcHome $name) -Destination $vlcTarget
}
Copy-Item -LiteralPath (Join-Path $VlcHome 'plugins') -Destination $vlcTarget -Recurse
$serviceTarget = Join-Path $stage 'service/lumen_service'
New-Item -ItemType Directory -Force -Path $serviceTarget | Out-Null
Copy-Item -Path (Join-Path $projectRoot 'service/lumen_service/*.py') -Destination $serviceTarget
if (-not $PythonHome) { $PythonHome = Join-Path $projectRoot 'build/tools/python-3.13.15' }
if (-not (Test-Path -LiteralPath (Join-Path $PythonHome 'python.exe'))) {
    throw '缺少 Python 嵌入式运行时，请先运行 scripts/prepare-python-runtime.ps1。'
}
$pythonTarget = Join-Path $stage 'python'
Copy-Item -LiteralPath $PythonHome -Destination $pythonTarget -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'release/python313._pth') -Destination $pythonTarget
$proxyTarget = Join-Path $stage 'stream-proxy'
New-Item -ItemType Directory -Force -Path $proxyTarget | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'stream-proxy/target/release/lumen-stream-proxy.exe') -Destination $proxyTarget

$arguments = @('--verbose', '--type', $Type,
    '--name', 'LumenMusic',
    '--app-version', $version,
    '--input', $stage,
    '--dest', $destination,
    '--main-jar', "$artifact-$version.jar",
    '--main-class', 'com.lumenmusic.LumenLauncher',
    '--vendor', 'Lumen Music',
    '--description', 'Lumen Music 桌面音乐播放器')
$icon = Join-Path $desktopDir 'src/main/resources/icons/lumen.ico'
if (-not (Test-Path -LiteralPath $icon)) { throw "找不到 Lumen 应用图标：$icon" }
$arguments += @('--icon', $icon)
Invoke-Packager $arguments
if ($Type -eq 'app-image') {
    $image = Join-Path $destination 'LumenMusic'
    $archive = Join-Path $destination "LumenMusic-$version-windows.zip"
    Compress-Archive -LiteralPath $image -DestinationPath $archive -CompressionLevel Optimal
    Write-Host "便携压缩包：$archive"
}
Write-Host "打包完成：$destination"
