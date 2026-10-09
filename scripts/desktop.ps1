param(
    [switch]$Smoke,
    [switch]$RealSmoke,
    [switch]$UiSmoke,
    [switch]$DemoSmoke,
    [switch]$OnlineSmoke,
    [switch]$LibrarySmoke,
    [switch]$JaySmoke,
    [switch]$Offline,
    [string]$ServiceUrl = 'http://127.0.0.1:8765',
    [string]$SamplePath,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$desktopDir = Join-Path $projectRoot 'desktop'
$wrapper = Join-Path $desktopDir 'mvnw.cmd'
$mavenArguments = @('-B', '-ntp')
if ($Offline) { $mavenArguments += '-o' }
if (-not $JavaHome) {
    $javaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCommand) { $JavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw '找不到完整 JDK，请通过 -JavaHome、JAVA_HOME 或 PATH 指定含 java.exe 和 javac.exe 的 JDK。'
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
if ($UiSmoke -and -not $DemoSmoke -and -not $OnlineSmoke -and -not $SamplePath) {
    throw '本地 UI 验收需要显式 -SamplePath，使用自产或允许使用的音频文件。'
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousServiceUrl = $env:LUMEN_SERVICE_URL
$previousSmokeSample = $env:LUMEN_SMOKE_SAMPLE
$previousSmokeDemo = $env:LUMEN_SMOKE_DEMO
$previousSmokeOnline = $env:LUMEN_SMOKE_ONLINE
$previousPreferencesPath = $env:LUMEN_PREFERENCES_PATH
$env:LUMEN_SERVICE_URL = $ServiceUrl
$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
Push-Location $desktopDir
try {
    if ($UiSmoke -or $DemoSmoke -or $OnlineSmoke) {
        if (-not $env:LUMEN_PREFERENCES_PATH) {
            $env:LUMEN_PREFERENCES_PATH = Join-Path $projectRoot 'build/smoke-preferences.properties'
        }
        if ($DemoSmoke) { $env:LUMEN_SMOKE_DEMO = '1' }
        elseif ($OnlineSmoke) { $env:LUMEN_SMOKE_ONLINE = '1' }
        else {
            $resolvedSample = (Resolve-Path -LiteralPath $SamplePath -ErrorAction Stop).Path
            $env:LUMEN_SMOKE_SAMPLE = $resolvedSample
        }
        & $wrapper @mavenArguments test-compile 'org.codehaus.mojo:exec-maven-plugin:3.6.2:exec' '-Dexec.executable=java' '-Dexec.classpathScope=test' '-Dexec.args=-cp %classpath com.lumenmusic.FxDesktopSmokeLauncher'
    } elseif ($Smoke -or $RealSmoke -or $LibrarySmoke -or $JaySmoke) {
        $smokeClass = if ($RealSmoke) { 'com.lumenmusic.RealSearchSmoke' }
            elseif ($JaySmoke) { 'com.lumenmusic.JayChouSmokeTest' }
            elseif ($LibrarySmoke) { 'com.lumenmusic.LibrarySmokeTest' }
            else { 'com.lumenmusic.SmokeTest' }
        & $wrapper @mavenArguments test-compile 'org.codehaus.mojo:exec-maven-plugin:3.6.2:exec' '-Dexec.executable=java' '-Dexec.classpathScope=test' "-Dexec.args=-cp %classpath $smokeClass"
    } else {
        & $wrapper @mavenArguments javafx:run
    }
    if ($LASTEXITCODE -ne 0) { throw '桌面运行或验证失败。' }
} finally {
    Pop-Location
    $env:LUMEN_SERVICE_URL = $previousServiceUrl
    $env:LUMEN_SMOKE_SAMPLE = $previousSmokeSample
    $env:LUMEN_SMOKE_DEMO = $previousSmokeDemo
    $env:LUMEN_SMOKE_ONLINE = $previousSmokeOnline
    $env:LUMEN_PREFERENCES_PATH = $previousPreferencesPath
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
