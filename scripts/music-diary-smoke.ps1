param([string]$Python, [string]$Java)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'playback-scene-smoke.ps1') -Python $Python -Java $Java `
    -SmokeLauncher 'com.lumenmusic.MusicDiarySmokeLauncher' -ArtifactDirectory 'music-diary-smoke' `
    -FixtureScript (Join-Path (Split-Path $PSScriptRoot -Parent) 'service/tests/diary_smoke_fixture.py')
