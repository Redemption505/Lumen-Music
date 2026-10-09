param([string]$Python, [string]$Java, [string]$AppHome,
    [ValidateSet('All', 'Normal', 'HiDpi')][string]$Profile = 'All')

$ErrorActionPreference = 'Stop'
# Reuse the existing disposable profile, loopback demo service and packaged native libraries.
# AppHome loads the shipped production JAR; omission tests desktop/target/classes.
$arguments = @{
    Profile = $Profile
    SmokeLauncher = 'com.lumenmusic.VisualLanguageSmokeLauncher'
    ArtifactDirectory = 'visual-language-smoke'
}
if ($Python) { $arguments.Python = $Python }
if ($Java) { $arguments.Java = $Java }
if ($AppHome) { $arguments.AppHome = $AppHome }
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
