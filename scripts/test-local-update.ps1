param(
    [string]$OldAppHome,
    [string]$NewAppHome,
    [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$FromVersion = '0.1.0',
    [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$ToVersion = '0.1.1',
    [string]$OldModules,
    [switch]$SkipProcessWait
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$projectRoot = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $projectRoot ('build/local-update-tests/' + [Guid]::NewGuid().ToString('N'))
$helper = Join-Path $PSScriptRoot 'update/apply-local-update.ps1'
$powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
if (-not $OldAppHome) { $OldAppHome = Join-Path $projectRoot 'build/dist/20260930-164842-644/LumenMusic' }
if (-not $NewAppHome) { $NewAppHome = Join-Path $projectRoot 'build/dist/20261001-112410-718/LumenMusic' }
$oldImage = (Resolve-Path -LiteralPath $OldAppHome).Path
$newImage = (Resolve-Path -LiteralPath $NewAppHome).Path
if ([version]$ToVersion -le [version]$FromVersion) { throw 'Fixture target must be newer than source' }
foreach ($identity in @(@($oldImage, $FromVersion), @($newImage, $ToVersion))) {
    if ([Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $identity[0] 'LumenMusic.exe')).ProductVersion -cne $identity[1]) {
        throw 'Fixture native input differs from declared version'
    }
}
$ownProcesses = New-Object System.Collections.ArrayList
$passed = New-Object System.Collections.ArrayList
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Put([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Path -Parent) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, [Text.Encoding]::ASCII)
}
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Pass([string]$Name) { [void]$passed.Add($Name); Write-Output "PASS $Name" }
function Jar([string]$Path, [string]$BuildId = 'fixture-build') {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Create)
    $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $writer = New-Object IO.StreamWriter($zip.CreateEntry('lumen-build.properties').Open(), [Text.Encoding]::ASCII)
        try { $writer.WriteLine('version=' + $ToVersion); $writer.WriteLine('build.id=' + $BuildId) } finally { $writer.Dispose() }
    } finally { $zip.Dispose(); $stream.Dispose() }
}
function Cfg([string]$Version) {
    return ('[Application]', ('app.classpath=$APPDIR\lumen-desktop-' + $Version + '.jar'),
        'app.mainclass=com.lumenmusic.LumenLauncher', '[JavaOptions]', ('java-options=-Djpackage.app-version=' + $Version)) -join [Environment]::NewLine
}
function Fixture([string]$Name, [switch]$ImageMarker, [switch]$RealModules) {
    $imageRoot = Join-Path $testRoot $Name
    $install = Join-Path $imageRoot 'install'; $package = Join-Path $imageRoot 'package'
    New-Item -ItemType Directory -Force -Path (Join-Path $install 'app'), (Join-Path $package 'payload/root') | Out-Null
    Copy-Item -LiteralPath (Join-Path $oldImage 'LumenMusic.exe') -Destination (Join-Path $install 'LumenMusic.exe')
    Copy-Item -LiteralPath (Join-Path $newImage 'LumenMusic.exe') -Destination (Join-Path $package 'payload/root/LumenMusic.exe')
    Put (Join-Path $install 'app/LumenMusic.cfg') (Cfg $FromVersion)
    Put (Join-Path $install "app/lumen-desktop-$FromVersion.jar") 'same-version locally modified old JAR'
    if ($ImageMarker) { Put (Join-Path $install 'app/.jpackage.xml') "<jpackage-state><app-version>$FromVersion</app-version></jpackage-state>" }
    else { Put (Join-Path $install 'app/.package') 'MSI marker remains unchanged' }
    Put (Join-Path $install 'app/service/lumen_service/__init__.py') 'old init variant'
    Put (Join-Path $install 'app/service/lumen_service/__main__.py') 'old main variant'
    Put (Join-Path $install 'app/service/lumen_service/__pycache__/old.pyc') 'old code cache'
    Put (Join-Path $install 'userdata/library.json') 'personal library must not change'
    Put (Join-Path $install 'app/unmanaged.txt') 'unmanaged personal file'
    Put (Join-Path $install 'runtime/release') 'fixture retained runtime'
    Put (Join-Path $install 'runtime/lib/modules') 'old independently linked module image'
    Put (Join-Path $install 'app/vlc/libvlc.dll') 'fixture retained VLC'
    Put (Join-Path $install 'app/dependency.jar') 'fixture retained dependency'
    New-Item -ItemType Directory -Force -Path (Join-Path $install 'app/python') | Out-Null
    Copy-Item -LiteralPath (Join-Path $env:SystemRoot 'System32/cmd.exe') -Destination (Join-Path $install 'app/python/python.exe')
    Put (Join-Path $install 'app/stream-proxy/lumen-stream-proxy.exe') 'old proxy variant'
    Jar (Join-Path $package "payload/lumen-desktop-$ToVersion.jar")
    Put (Join-Path $package 'payload/LumenMusic.cfg') (Cfg $ToVersion)
    Put (Join-Path $package 'payload/.jpackage.xml') "<jpackage-state><app-version>$ToVersion</app-version></jpackage-state>"
    Put (Join-Path $package 'payload/stream-proxy/lumen-stream-proxy.exe') 'new proxy'
    Put (Join-Path $package 'payload/service/lumen_service/__init__.py') ''
    Put (Join-Path $package 'payload/service/lumen_service/__main__.py') 'new main'
    Put (Join-Path $package 'payload/root/runtime/lib/modules') 'new canonical module image'
    if ($RealModules) {
        if (-not $OldModules) { $OldModules = Join-Path $oldImage 'runtime/lib/modules' }
        $oldModules = $OldModules
        Assert (Test-Path -LiteralPath $oldModules -PathType Leaf) 'Selected old module image is missing'
        Copy-Item -LiteralPath $oldModules -Destination (Join-Path $install 'runtime/lib/modules') -Force
        Copy-Item -LiteralPath (Join-Path $newImage 'runtime/lib/modules') -Destination (Join-Path $package 'payload/root/runtime/lib/modules') -Force
    }
    $doc = New-Object Xml.XmlDocument; $update = $doc.CreateElement('update'); [void]$doc.AppendChild($update)
    $attrs = @{ format='1'; app='LumenMusic'; platform='windows'; arch='x64'; fromVersion=$FromVersion; toVersion=$ToVersion;
        oldJar="lumen-desktop-$FromVersion.jar"; newJar="lumen-desktop-$ToVersion.jar"; buildId='fixture-build'; packageId=[Guid]::NewGuid().ToString('D') }
    foreach ($key in $attrs.Keys) { $update.SetAttribute($key, [string]$attrs[$key]) }
    $files = $doc.CreateElement('files'); [void]$update.AppendChild($files)
    foreach ($relative in @('root/LumenMusic.exe', "lumen-desktop-$ToVersion.jar", 'LumenMusic.cfg', '.jpackage.xml',
            'stream-proxy/lumen-stream-proxy.exe', 'service/lumen_service/__init__.py', 'service/lumen_service/__main__.py', 'root/runtime/lib/modules')) {
        $node = $doc.CreateElement('file'); $node.SetAttribute('scope', $(if ($relative.StartsWith('root/')) { 'root' } else { 'app' }))
        $node.SetAttribute('path', $relative.Replace('root/', '')); $source = Join-Path (Join-Path $package 'payload') $relative
        $node.SetAttribute('sha256', (Hash $source)); $node.SetAttribute('size', [string](Get-Item -LiteralPath $source).Length); [void]$files.AppendChild($node)
    }
    $requirements = $doc.CreateElement('requirements'); [void]$update.AppendChild($requirements)
    foreach ($relative in @('runtime/release', 'app/python/python.exe', 'app/vlc/libvlc.dll', 'app/dependency.jar')) {
        $node = $doc.CreateElement('file'); $node.SetAttribute('path', $relative); $source = Join-Path $install $relative
        $node.SetAttribute('sha256', (Hash $source)); $node.SetAttribute('size', [string](Get-Item -LiteralPath $source).Length); [void]$requirements.AppendChild($node)
    }
    $doc.Save((Join-Path $package 'manifest.xml'))
    return [pscustomobject]@{ home=$imageRoot; install=$install; package=$package; manifest=$doc }
}
function Run($Fixture, [int]$FailAfter = 0, [int]$Parent = 0, [switch]$Async) {
    $result = Join-Path $Fixture.home ('result-' + [Guid]::NewGuid().ToString('N') + '.xml')
    $arguments = @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $helper + '"'),
        '-InstallRoot', ('"' + $Fixture.install + '"'), '-PackageRoot', ('"' + $Fixture.package + '"'),
        '-ResultPath', ('"' + $result + '"'), '-ParentPid', [string]$Parent, '-TestFailAfter', [string]$FailAfter)
    $originalModules = $env:PSModulePath
    try {
        # Windows PowerShell must load its own bundled modules under a PowerShell 7 parent.
        $env:PSModulePath = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules'
        $process = Start-Process -FilePath $powershell -ArgumentList $arguments -WindowStyle Hidden -PassThru
    } finally { $env:PSModulePath = $originalModules }
    [void]$ownProcesses.Add($process)
    if ($Async) { return [pscustomobject]@{ process=$process; result=$result } }
    if (-not $process.WaitForExit(90000)) { throw 'Helper did not finish within 90 seconds' }
    Assert (Test-Path -LiteralPath $result) 'Missing result XML'
    $xml = [xml][IO.File]::ReadAllText($result)
    return $xml.result
}
function Refused($Fixture, [string]$Reason) {
    $old = Hash (Join-Path $Fixture.install "app/lumen-desktop-$FromVersion.jar")
    $oldModules = Hash (Join-Path $Fixture.install 'runtime/lib/modules')
    $result = Run $Fixture
    Assert ($result.status -eq 'failed_unchanged') "Expected unchanged refusal, got $($result.status): $($result.message)"
    Assert ($result.message -match $Reason) "Unexpected refusal: $($result.message)"
    Assert ((Hash (Join-Path $Fixture.install "app/lumen-desktop-$FromVersion.jar")) -eq $old) 'Refusal changed source JAR'
    Assert ((Hash (Join-Path $Fixture.install 'runtime/lib/modules')) -ceq $oldModules) 'Refusal changed source runtime module image'
    Assert (-not (Test-Path (Join-Path $Fixture.install "app/lumen-desktop-$ToVersion.jar"))) 'Refusal created target JAR'
}
function Package-Fixture([string]$Name, [switch]$ChangedRuntime) {
    $f = Fixture $Name
    $sandbox = Join-Path $f.home 'project'
    $scripts = Join-Path $sandbox 'scripts'
    New-Item -ItemType Directory -Force -Path (Join-Path $scripts 'update') | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'package-update.ps1') -Destination $scripts
    foreach ($name in @('apply-local-update.ps1', 'update-local.cmd')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot "update/$name") -Destination (Join-Path $scripts 'update') }
    $new = Join-Path $sandbox 'new-image'
    New-Item -ItemType Directory -Force -Path (Join-Path $new 'app') | Out-Null
    Copy-Item -LiteralPath (Join-Path $f.package 'payload/root/LumenMusic.exe') -Destination $new
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $f.package 'payload') -Force | Where-Object { $_.Name -ne 'root' }) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $new 'app') -Recurse
    }
    Copy-Item -LiteralPath (Join-Path $f.install 'runtime') -Destination $new -Recurse
    Copy-Item -LiteralPath (Join-Path $f.package 'payload/root/runtime/lib/modules') -Destination (Join-Path $new 'runtime/lib/modules') -Force
    foreach ($path in @('python', 'vlc', 'dependency.jar')) { Copy-Item -LiteralPath (Join-Path $f.install "app/$path") -Destination (Join-Path $new 'app') -Recurse }
    if ($ChangedRuntime) { Put (Join-Path $new 'runtime/release') 'different target runtime' }
    $release = Join-Path $sandbox "build/releases/Lumen-Music-v$ToVersion-Windows-x64"
    foreach ($name in @('LICENSE.txt', 'THIRD-PARTY-NOTICES.txt')) { Put (Join-Path $release $name) ('fixture ' + $name) }
    $source = Join-Path $sandbox 'QA_ONLY-source'
    Put (Join-Path $source 'BUILD-INFO.txt') ("Version=$ToVersion`nBuildId=fixture-build`nMainJarSha256=" + (Hash (Join-Path $new "app/lumen-desktop-$ToVersion.jar")))
    [IO.Compression.ZipFile]::CreateFromDirectory($source, (Join-Path $release 'SOURCE.zip'))
    Put (Join-Path $sandbox "release/v$ToVersion/UPDATE-README.txt") 'QA_ONLY fixture update instructions {{BUILD_ID}}'
    Put (Join-Path $sandbox 'desktop/pom.xml') "<project><version>$ToVersion</version></project>"
    return [pscustomobject]@{ old=$f.install; new=$new; script=(Join-Path $scripts 'package-update.ps1'); project=$sandbox }
}
function Run-Package($Fixture, [string]$SourceVersion = $FromVersion, [string]$TargetVersion = $ToVersion) {
    $args = @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $Fixture.script + '"'),
        '-NewAppHome', ('"' + $Fixture.new + '"'), '-OldAppHome', ('"' + $Fixture.old + '"'), '-FromVersion', $SourceVersion, '-ToVersion', $TargetVersion)
    $originalModules = $env:PSModulePath
    try {
        $env:PSModulePath = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules'
        $process = Start-Process $powershell -ArgumentList $args -WindowStyle Hidden -PassThru
    } finally { $env:PSModulePath = $originalModules }
    [void]$ownProcesses.Add($process)
    Assert ($process.WaitForExit(90000)) 'Packaging did not finish'
    return $process.ExitCode
}
try {
    $f = Fixture 'wrong-install'
    $f.install = Join-Path $f.home 'not-installed'; New-Item -ItemType Directory -Path $f.install | Out-Null
    $r = Run $f; Assert ($r.status -eq 'failed_unchanged') 'Wrong installation accepted'; Pass 'wrong installation'

    $f = Fixture 'wrong-package'; $f.manifest.update.SetAttribute('app', 'OtherApp'); $f.manifest.Save((Join-Path $f.package 'manifest.xml'))
    Refused $f 'manifest'; Pass 'wrong application manifest'

    $f = Fixture 'legacy-retained-modules'
    $node = $f.manifest.update.files.file | Where-Object { $_.scope -eq 'root' -and $_.path -eq 'runtime/lib/modules' }
    [void]$f.manifest.update.files.RemoveChild($node)
    $oldModulesHash = Hash (Join-Path $f.install 'runtime/lib/modules')
    $node = $f.manifest.CreateElement('file'); $node.SetAttribute('path', 'runtime/lib/modules')
    $node.SetAttribute('sha256', $oldModulesHash); $node.SetAttribute('size', [string](Get-Item -LiteralPath (Join-Path $f.install 'runtime/lib/modules')).Length)
    [void]$f.manifest.update.requirements.AppendChild($node); $f.manifest.Save((Join-Path $f.package 'manifest.xml'))
    $r = Run $f; Assert ($r.status -eq 'success') "Legacy retained modules update: $($r.message)"
    Assert ((Hash (Join-Path $f.install 'runtime/lib/modules')) -ceq $oldModulesHash) 'Legacy retained module image changed'
    Pass 'old format-1 contract still accepts strictly retained modules'

    $f = Fixture 'corrupt'; Put (Join-Path $f.package "payload/lumen-desktop-$ToVersion.jar") 'truncated payload'
    Refused $f 'SHA-256'; Pass 'payload corruption'
    $f = Fixture 'corrupt-modules'; Put (Join-Path $f.package 'payload/root/runtime/lib/modules') 'truncated module image'
    Refused $f 'SHA-256'; Pass 'runtime module payload corruption refuses before any replacement'

    $f = Fixture 'metadata'; Jar (Join-Path $f.package "payload/lumen-desktop-$ToVersion.jar") 'wrong-build'
    $node = $f.manifest.update.files.file | Where-Object { $_.path -eq "lumen-desktop-$ToVersion.jar" }
    $node.SetAttribute('sha256', (Hash (Join-Path $f.package "payload/lumen-desktop-$ToVersion.jar")))
    $node.SetAttribute('size', [string](Get-Item (Join-Path $f.package "payload/lumen-desktop-$ToVersion.jar")).Length)
    $f.manifest.Save((Join-Path $f.package 'manifest.xml')); Refused $f 'build metadata'; Pass 'JAR build identity'

    $f = Fixture 'launcher'; Copy-Item (Join-Path $oldImage 'LumenMusic.exe') (Join-Path $f.package 'payload/root/LumenMusic.exe') -Force
    $node = $f.manifest.update.files.file | Where-Object { $_.scope -eq 'root' -and $_.path -eq 'LumenMusic.exe' }
    $node.SetAttribute('sha256', (Hash (Join-Path $f.package 'payload/root/LumenMusic.exe')))
    $node.SetAttribute('size', [string](Get-Item (Join-Path $f.package 'payload/root/LumenMusic.exe')).Length)
    $f.manifest.Save((Join-Path $f.package 'manifest.xml')); Refused $f 'launcher version'; Pass 'native target version'

    $f = Fixture 'runtime'; Put (Join-Path $f.install 'runtime/release') 'changed retained runtime'
    Refused $f 'SHA-256'; Pass 'retained runtime mismatch'
    $f = Fixture 'modules-overlap'
    $node = $f.manifest.CreateElement('file'); $node.SetAttribute('path', 'runtime/lib/modules')
    $node.SetAttribute('sha256', (Hash (Join-Path $f.install 'runtime/lib/modules')))
    $node.SetAttribute('size', [string](Get-Item -LiteralPath (Join-Path $f.install 'runtime/lib/modules')).Length)
    [void]$f.manifest.update.requirements.AppendChild($node); $f.manifest.Save((Join-Path $f.package 'manifest.xml'))
    Refused $f 'retained requirement'; Pass 'module image cannot be both managed and retained'
    $f = Fixture 'root-whitelist'
    $node = $f.manifest.update.files.file | Where-Object { $_.path -eq 'runtime/lib/modules' }
    $node.SetAttribute('path', 'runtime/release'); $f.manifest.Save((Join-Path $f.package 'manifest.xml'))
    Refused $f 'managed file'; Pass 'root runtime whitelist permits modules only'

    $f = Fixture 'source-cfg'; Put (Join-Path $f.install 'app/LumenMusic.cfg') (Cfg $ToVersion)
    Refused $f 'configuration'; Pass 'source configuration mismatch'

    $f = Fixture 'unsafe'; $f.manifest.update.files.file[6].SetAttribute('path', '../personal.txt'); $f.manifest.Save((Join-Path $f.package 'manifest.xml'))
    Refused $f 'managed file'; Pass 'out-of-scope path'

    $f = Fixture 'rollback' -RealModules; $oldHashes = @{}
    foreach ($file in Get-ChildItem $f.install -File -Recurse -Force) { $oldHashes[$file.FullName] = Hash $file.FullName }
    $r = Run $f 4; Assert ($r.status -eq 'rolled_back') "Rollback result: $($r.status): $($r.message)"
    foreach ($path in $oldHashes.Keys) { Assert ((Hash $path) -eq $oldHashes[$path]) "Rollback changed $path" }
    Assert (-not (Test-Path (Join-Path $f.install "app/lumen-desktop-$ToVersion.jar"))) 'Rollback left target JAR'
    Assert (-not (Test-Path (Join-Path $f.install 'app/.jpackage.xml'))) 'Rollback left newly-created image marker'
    $backupXml = [xml][IO.File]::ReadAllText((Join-Path $r.backupPath 'backup.xml'))
    foreach ($entry in $backupXml.backup.file | Where-Object { $_.exists -eq 'True' }) {
        Assert ((Hash (Join-Path $r.backupPath ('files/' + $entry.path))) -eq $entry.sha256) 'Actual backup hash mismatch'
    }
    Pass 'injected rollback, source code variants, actual backup hashes and cache restoration'

    $r = Run $f -FailAfter (@($f.manifest.update.files.file).Count); Assert ($r.status -eq 'rolled_back') "Full-payload rollback: $($r.status): $($r.message)"
    foreach ($path in $oldHashes.Keys) { Assert ((Hash $path) -eq $oldHashes[$path]) "Full-payload rollback changed $path" }
    Assert ((Hash (Join-Path $f.install 'runtime/lib/modules')) -ceq $oldHashes[(Join-Path $f.install 'runtime/lib/modules')]) 'Final-write rollback did not restore real old module bytes'
    Pass 'rollback after final module-image replacement restores actual old module bytes'

    $differentModulesFixture = Fixture 'rollback-different-modules'
    $differentModulesPath = Join-Path $differentModulesFixture.install 'runtime/lib/modules'
    $differentModulesHash = Hash $differentModulesPath
    Assert ($differentModulesHash -cne (Hash (Join-Path $differentModulesFixture.package 'payload/root/runtime/lib/modules'))) 'Synthetic old and target module bytes must differ'
    $differentHashes = @{}
    foreach ($file in Get-ChildItem -LiteralPath $differentModulesFixture.install -File -Recurse -Force) {
        $differentHashes[$file.FullName] = Hash $file.FullName
    }
    Assert ($differentModulesFixture.manifest.update.files.file[-1].path -ceq 'runtime/lib/modules') 'Synthetic module image is not the final managed write'
    $differentResult = Run $differentModulesFixture -FailAfter (@($differentModulesFixture.manifest.update.files.file).Count)
    Assert ($differentResult.status -ceq 'rolled_back') "Different-module final-write rollback: $($differentResult.status): $($differentResult.message)"
    foreach ($path in $differentHashes.Keys) {
        Assert ((Hash $path) -ceq $differentHashes[$path]) "Different-module rollback changed $path"
    }
    Assert ((Hash $differentModulesPath) -ceq $differentModulesHash) 'Different-module rollback did not restore the original module bytes'
    Assert (-not (Test-Path -LiteralPath (Join-Path $differentModulesFixture.install "app/lumen-desktop-$ToVersion.jar"))) 'Different-module rollback left the target JAR'
    Assert (-not (Test-Path -LiteralPath (Join-Path $differentModulesFixture.install 'app/.jpackage.xml'))) 'Different-module rollback left the new image marker'
    Pass 'synthetic different module bytes and every source file restored after final-write failure'

    $r = Run $f; Assert ($r.status -eq 'success') "Update failed: $($r.message)"
    Assert (-not (Test-Path (Join-Path $f.install "app/lumen-desktop-$FromVersion.jar"))) 'Old JAR not retired'
    Assert (-not (Test-Path (Join-Path $f.install 'app/service/lumen_service/__pycache__'))) 'Code cache not retired'
    foreach ($entry in $f.manifest.update.files.file) {
        $target = if ($entry.scope -eq 'root') { Join-Path $f.install $entry.path } else { Join-Path $f.install ('app/' + $entry.path) }
        Assert ((Hash $target) -eq $entry.sha256) 'Installed managed file mismatch'
    }
    foreach ($path in @('userdata/library.json', 'app/unmanaged.txt', 'app/.package', 'runtime/release', 'app/python/python.exe', 'app/vlc/libvlc.dll', 'app/dependency.jar')) {
        Assert ((Hash (Join-Path $f.install $path)) -eq $oldHashes[(Join-Path $f.install $path)]) "Unmanaged or retained file changed: $path"
    }
    Pass 'complete update with MSI marker and personal files preserved'
    $count = @(Get-ChildItem $f.install -Directory -Filter 'update-backup-*').Count
    $r = Run $f; Assert ($r.status -eq 'already_current') "Repeat update: $($r.message)"
    Assert (@(Get-ChildItem $f.install -Directory -Filter 'update-backup-*').Count -eq $count) 'Repeat created a new backup'
    Pass 'idempotent repeat update'

    $f = Fixture 'image' -ImageMarker; $r = Run $f
    Assert ($r.status -eq 'success') "Image update: $($r.message)"; Pass 'app-image marker'

    if (-not $SkipProcessWait) {
    $f = Fixture 'wait'
    $parent = Start-Process $powershell -ArgumentList '-NoProfile', '-Command', 'Start-Sleep -Seconds 3' -WindowStyle Hidden -PassThru
    [void]$ownProcesses.Add($parent)
    $python = Start-Process (Join-Path $f.install 'app/python/python.exe') -ArgumentList '/c', 'ping -n 4 127.0.0.1 > nul' -WindowStyle Hidden -PassThru
    [void]$ownProcesses.Add($python)
    $watch = [Diagnostics.Stopwatch]::StartNew(); $run = Run $f 0 $parent.Id -Async
    Start-Sleep -Milliseconds 700
    Assert (Test-Path (Join-Path $f.install "app/lumen-desktop-$FromVersion.jar")) 'Helper replaced before process exit'
    Assert (-not (Test-Path $run.result)) 'Helper finished before parent exited'
    Assert ($run.process.WaitForExit(90000)) 'Waiting helper timed out'; $watch.Stop()
    $r = ([xml][IO.File]::ReadAllText($run.result)).result
    Assert ($r.status -eq 'success') "Wait update: $($r.message)"
    Assert ($watch.Elapsed.TotalSeconds -ge 2) 'Helper did not wait for normal exits'
    Assert ($parent.HasExited -and $python.HasExited) 'Owned process remained alive'
    Pass 'parent and installed Python normal-exit waiting'
    }

    $p = Package-Fixture 'packager'
    Assert ((Run-Package $p -SourceVersion '9.9.9') -ne 0) 'Packager accepted an undeclared source image version'
    Assert (-not (Test-Path (Join-Path $p.project "build/releases/Lumen-Music-v$FromVersion-to-v$ToVersion-Windows-x64-Update"))) 'Version mismatch created release output'
    Assert ((Run-Package $p -TargetVersion '9.9.9') -ne 0) 'Packager accepted a target version different from POM'
    Assert (-not (Test-Path (Join-Path $p.project 'build/releases/Lumen-Music-v9.9.9-Windows-x64'))) 'Target mismatch created release output'
    Pass 'packager rejects explicit source/target version mismatches before output'
    Assert ((Run-Package $p) -eq 0) 'Generic packager failed'
    $release = Join-Path $p.project "build/releases/Lumen-Music-v$FromVersion-to-v$ToVersion-Windows-x64-Update"
    $archive = $release + '.zip'
    Assert (Test-Path $archive) 'Update ZIP missing'
    $xml = [xml][IO.File]::ReadAllText((Join-Path $release 'manifest.xml'))
    Assert (@($xml.update.files.file).Count -eq 8 -and @($xml.update.requirements.file).Count -eq 4) 'Packaged inventory is not full managed payload plus retained requirements'
    Assert (@($xml.update.files.file | Where-Object { $_.scope -eq 'root' -and $_.path -eq 'runtime/lib/modules' }).Count -eq 1 -and
        @($xml.update.requirements.file | Where-Object { $_.path -eq 'runtime/lib/modules' }).Count -eq 0) 'Packager did not isolate modules from strict retained requirements'
    Assert (-not (Test-Path (Join-Path $release 'payload/runtime'))) 'Runtime included in payload'
    foreach ($path in @('SOURCE.zip', 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt', 'README.txt', 'apply-local-update.ps1', 'update.cmd')) {
        Assert (Test-Path (Join-Path $release $path)) "Package missing $path"
    }
    Assert (-not (Test-Path (Join-Path $release 'update-local.cmd'))) 'Package entry name differs from update documentation'
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try { Assert (@($zip.Entries | Where-Object { $_.FullName.EndsWith('/manifest.xml') }).Count -eq 1) 'ZIP manifest not readable' }
    finally { $zip.Dispose() }
    $before = Hash $archive
    Assert ((Run-Package $p) -ne 0) 'Existing package overwrite accepted'
    Assert ((Hash $archive) -eq $before) 'Existing package changed'
    Pass 'generic XML ZIP packaging, retained requirements, bundled notices and overwrite refusal'

    $p = Package-Fixture 'packager-runtime' -ChangedRuntime
    Assert ((Run-Package $p) -ne 0) 'Packager accepted changed retained runtime'
    Assert (-not (Test-Path (Join-Path $p.project "build/releases/Lumen-Music-v$FromVersion-to-v$ToVersion-Windows-x64-Update"))) 'Failed packaging left partial release'
    Pass 'packager rejects incompatible runtime before output'

    foreach ($path in @($helper, (Join-Path $PSScriptRoot 'package-update.ps1'), (Join-Path $PSScriptRoot 'update/update-local.cmd'))) {
        Assert (-not @([IO.File]::ReadAllBytes($path) | Where-Object { $_ -gt 127 }).Count) "Non-ASCII script: $path"
    }
    Pass 'ASCII scripts on Windows PowerShell 5.1'
    Write-Output "Local update tests passed: $($passed.Count); fixtures: $testRoot"
} finally {
    foreach ($process in $ownProcesses) {
        if (-not $process.HasExited) {
            if (-not $process.WaitForExit(5000)) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        }
        $process.Dispose()
    }
}
