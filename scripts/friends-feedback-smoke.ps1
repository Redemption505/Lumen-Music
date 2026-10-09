param([string]$Python, [string]$Java, [string]$AppHome, [switch]$VisualOnly,
    [ValidateSet('All', 'Normal', 'HiDpi')][string]$Profile = 'All')

$ErrorActionPreference = 'Stop'
$arguments = @{
    Profile = $Profile
    SmokeLauncher = 'com.lumenmusic.FriendsFeedbackSmokeLauncher'
    ArtifactDirectory = 'friends-feedback-smoke'
}
if ($Python) { $arguments.Python = $Python }
if ($Java) { $arguments.Java = $Java }
if ($AppHome) { $arguments.AppHome = $AppHome }
if ($VisualOnly) { $arguments.VisualOnly = $true }
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
