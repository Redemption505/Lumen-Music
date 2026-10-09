param([string]$Python, [string]$Java, [string]$AppHome,
    [ValidateSet('All', 'Normal', 'HiDpi')][string]$Profile = 'All')

$ErrorActionPreference = 'Stop'
$arguments = @{
    Profile = $Profile
    SmokeLauncher = 'com.lumenmusic.CollectionMemorySmokeLauncher'
    ArtifactDirectory = 'collection-memory-smoke'
    FixtureScript = (Join-Path (Split-Path $PSScriptRoot -Parent) 'service/tests/collection_memory_smoke_fixture.py')
}
if ($Python) { $arguments.Python = $Python }
if ($Java) { $arguments.Java = $Java }
if ($AppHome) { $arguments.AppHome = $AppHome }
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') @arguments
