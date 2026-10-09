$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $outputDir = Join-Path $projectRoot 'build/classes'
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $sources = @(
        (Join-Path $projectRoot 'desktop/src/main/java/com/lumenmusic/audio/ClipAudioPlayer.java'),
        (Join-Path $projectRoot 'desktop/src/test/java/com/lumenmusic/AudioEngineExperiment.java')
    )
    & javac -encoding UTF-8 --release 17 -d $outputDir @sources
    if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败。' }
    & java -cp $outputDir com.lumenmusic.AudioEngineExperiment
    if ($LASTEXITCODE -ne 0) { throw '音频实验未通过。' }
} finally { Pop-Location }
