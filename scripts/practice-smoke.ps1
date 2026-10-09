param([string]$Python, [string]$Java, [switch]$VisualOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$arguments = @{
    SmokeLauncher = 'com.lumenmusic.PracticePlaybackSmokeLauncher'
    ArtifactDirectory = 'practice-smoke'
    FixtureScript = Join-Path $projectRoot 'service/tests/practice_smoke_fixture.py'
}
if ($Python) { $arguments.Python = $Python }
if ($Java) { $arguments.Java = $Java }
if ($VisualOnly) { $arguments.VisualOnly = $true }
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
