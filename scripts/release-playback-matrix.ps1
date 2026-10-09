param(
    [Parameter(Mandatory)][string]$AppHome,
    [Parameter(Mandatory)][string]$Java,
    [ValidateSet('All', 'Smart', 'Queue', 'Scene', 'Diary', 'Practice', 'Continuous', 'HiDpiScene')][string]$Smoke = 'All'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$cases = @(
    @{ Name = 'Smart'; Launcher = 'SmartPlaylistSmokeLauncher'; Artifact = 'release-smart-playlist-smoke' },
    @{ Name = 'Queue'; Launcher = 'QueueNavigationSmokeLauncher'; Artifact = 'release-queue-navigation-smoke' },
    @{ Name = 'Scene'; Launcher = 'PlaybackSceneSmokeLauncher'; Artifact = 'release-playback-scene-smoke' },
    @{ Name = 'Diary'; Launcher = 'MusicDiarySmokeLauncher'; Artifact = 'release-music-diary-smoke'; Fixture = 'diary_smoke_fixture.py' },
    @{ Name = 'Practice'; Launcher = 'PracticePlaybackSmokeLauncher'; Artifact = 'release-practice-smoke'; Fixture = 'practice_smoke_fixture.py' },
    @{ Name = 'Continuous'; Launcher = 'ContinuousPlaybackSmokeLauncher'; Artifact = 'release-continuous-playback-smoke'; Fixture = 'continuous_smoke_fixture.py' },
    @{ Name = 'HiDpiScene'; Launcher = 'PlaybackSceneSmokeLauncher'; Artifact = 'release-playback-scene-smoke'; Profile = 'HiDpi' }
)
foreach ($case in $cases) {
    if ($Smoke -ne 'All' -and $Smoke -ne $case.Name) { continue }
    $arguments = @{
        AppHome = $AppHome
        Java = $Java
        SmokeLauncher = 'com.lumenmusic.' + $case.Launcher
        ArtifactDirectory = $case.Artifact
        Profile = $(if ($case.Profile) { $case.Profile } else { 'Normal' })
    }
    if ($case.Fixture) { $arguments.FixtureScript = Join-Path $projectRoot ('service/tests/' + $case.Fixture) }
    Write-Output "Release native case: $($case.Name)"
    & (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
}
