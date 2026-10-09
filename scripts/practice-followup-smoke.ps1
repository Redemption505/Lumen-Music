param([string]$Python, [string]$Java)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$arguments = @{
    SmokeLauncher = 'com.lumenmusic.PracticePlaybackSmokeFollowupLauncher'
    ArtifactDirectory = 'practice-smoke-followup'
    FixtureScript = Join-Path $projectRoot 'service/tests/practice_smoke_fixture.py'
}
if ($Python) { $arguments.Python = $Python }
if ($Java) { $arguments.Java = $Java }
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
