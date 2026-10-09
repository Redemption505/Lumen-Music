param([string]$Python, [string]$Java, [string]$AppHome,
    [ValidateSet('All', 'Normal', 'HiDpi')][string]$Profile = 'All')

$ErrorActionPreference = 'Stop'
# The fixture is synthetic; all service files, WAV, playback state and screenshots
# stay in the fresh disposable directory created by playback-scene-smoke.ps1.
$arguments = @{
    Profile = $Profile
    SmokeLauncher = 'com.lumenmusic.PersonalHomeSmokeLauncher'
    ArtifactDirectory = 'personal-home-smoke'
    FixtureScript = (Join-Path (Split-Path $PSScriptRoot -Parent) 'service/tests/personal_home_smoke_fixture.py')
}
if ($Python) { $arguments.Python = $Python }
if ($Java) { $arguments.Java = $Java }
if ($AppHome) { $arguments.AppHome = $AppHome }
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
