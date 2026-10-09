param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [string]$PackageRoot = $PSScriptRoot,
    [int]$ParentPid = 0,
    [string]$ResultPath,
    [switch]$Restart,
    [int]$TestFailAfter = 0
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$manifest = $null
$backup = ''
$changes = New-Object System.Collections.ArrayList
$records = New-Object System.Collections.ArrayList
$cacheMoves = New-Object System.Collections.ArrayList
$status = 'failed_unchanged'
$message = ''
$resultAllowed = $true

function Hash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
}
function Safe-Path([string]$Root, [string]$Relative) {
    if ($Relative -cnotmatch '^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$' -or
        @($Relative.Split('/') | Where-Object { $_ -eq '.' -or $_ -eq '..' }).Count) {
        throw "Invalid relative path: $Relative"
    }
    $full = [IO.Path]::GetFullPath((Join-Path $Root $Relative))
    if (-not $full.StartsWith($Root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path outside boundary: $Relative"
    }
    $current = $full
    while ($current.Length -ge $Root.Length) {
        if ((Test-Path -LiteralPath $current) -and
            ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Reparse point in update path: $current"
        }
        if ($current -eq $Root) { break }
        $current = Split-Path $current -Parent
    }
    return $full
}
function Read-Xml([string]$Path, [long]$Limit = 1048576) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -gt $Limit) {
        throw "Missing or oversized XML: $Path"
    }
    $settings = New-Object Xml.XmlReaderSettings
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create($Path, $settings)
    try { $doc = New-Object Xml.XmlDocument; $doc.XmlResolver = $null; $doc.Load($reader); return ,$doc }
    finally { $reader.Dispose() }
}
function Assert-Hash($Item, [string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or
        (Get-Item -LiteralPath $Path).Length -ne [long]$Item.size -or (Hash $Path) -cne [string]$Item.sha256) {
        throw "Size or SHA-256 mismatch: $Path"
    }
}
function Assert-Entry($Item) {
    $size = 0L
    if ([string]$Item.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        [string]$Item.size -cnotmatch '^(0|[1-9][0-9]*)$' -or
        -not [long]::TryParse([string]$Item.size, [ref]$size) -or $size -gt 268435456) {
        throw "Invalid size or hash: $($Item.path)"
    }
}
function Assert-Attributes($Node, [string[]]$Names) {
    foreach ($attribute in $Node.Attributes) {
        if ($Names -cnotcontains $attribute.Name) { throw 'Unknown XML attribute' }
    }
    foreach ($name in $Names) { if (-not $Node.HasAttribute($name)) { throw "Missing XML attribute: $name" } }
}
function Assert-Children($Node, [string[]]$Names) {
    foreach ($child in $Node.ChildNodes) {
        if ($child.NodeType -eq [Xml.XmlNodeType]::Element -and $Names -cnotcontains $child.Name) { throw 'Unknown XML element' }
        if (($child.NodeType -eq [Xml.XmlNodeType]::Text -or $child.NodeType -eq [Xml.XmlNodeType]::CDATA) -and
            -not [string]::IsNullOrWhiteSpace($child.Value)) { throw 'Unexpected XML text' }
    }
}
function Assert-Native([string]$Path, [string]$Version) {
    if ([Diagnostics.FileVersionInfo]::GetVersionInfo($Path).ProductVersion -cne $Version) { throw "Native launcher version mismatch: $Version" }
    $stream = [IO.File]::OpenRead($Path); $reader = New-Object IO.BinaryReader($stream)
    try {
        if ($reader.ReadUInt16() -ne 0x5a4d) { throw 'Invalid native launcher' }
        $stream.Position = 60; $offset = $reader.ReadInt32()
        if ($offset -lt 64 -or $offset -gt $stream.Length - 6) { throw 'Invalid PE header' }
        $stream.Position = $offset
        if ($reader.ReadUInt32() -ne 0x00004550 -or $reader.ReadUInt16() -ne 0x8664) { throw 'Native launcher is not Windows x64' }
    } finally { $reader.Dispose(); $stream.Dispose() }
}
function Assert-Cfg([string]$Path, [string]$Jar, [string]$Version) {
    $lines = [IO.File]::ReadAllLines($Path)
    $mainJars = @($lines | Where-Object { $_ -cmatch '^app\.classpath=\$APPDIR\\lumen-desktop-' })
    if ($mainJars.Count -ne 1 -or $mainJars[0] -cne ('app.classpath=$APPDIR\' + $Jar) -or
        @($lines | Where-Object { $_ -ceq "java-options=-Djpackage.app-version=$Version" }).Count -ne 1 -or
        @($lines | Where-Object { $_ -cmatch '^java-options=-Djpackage\.app-version=' }).Count -ne 1 -or
        @($lines | Where-Object { $_ -ceq 'app.mainclass=com.lumenmusic.LumenLauncher' }).Count -ne 1) {
        throw "Launcher configuration mismatch: $Version"
    }
    foreach ($line in $lines) {
        if ($line -cmatch '^app\.classpath=' -and $line -cnotmatch '^app\.classpath=\$APPDIR\\') { throw 'Invalid classpath path' }
        if ($line -cmatch '^app\.classpath=\$APPDIR\\(.+)$') {
            $relative = $Matches[1].Replace('\', '/')
            if ($relative -ne $Jar -and -not $requirements.ContainsKey('app/' + $relative)) {
                throw "Classpath dependency missing from requirements: $relative"
            }
        }
    }
}
function Assert-Jar([string]$Path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entries = @($zip.Entries | Where-Object { $_.FullName -ceq 'lumen-build.properties' })
        if ($entries.Count -ne 1 -or $entries[0].Length -gt 8192) { throw 'Missing JAR build metadata' }
        $reader = New-Object IO.StreamReader($entries[0].Open())
        try { $properties = @{}; foreach ($line in ($reader.ReadToEnd() -split '\r?\n')) {
            if ($line -match '^\s*(version|build\.id)\s*=\s*(.*?)\s*$') {
                if ($properties.ContainsKey($Matches[1])) { throw 'Duplicate JAR build property' }
                $properties[$Matches[1]] = $Matches[2]
            }
        } } finally { $reader.Dispose() }
        if ($properties.version -cne [string]$manifest.toVersion -or
            $properties['build.id'] -cne [string]$manifest.buildId) { throw 'JAR build metadata mismatch' }
    } finally { $zip.Dispose() }
}
function Assert-Image([string]$Path, [string]$Version) {
    $xml = Read-Xml $Path
    if ($xml.DocumentElement.Name -cne 'jpackage-state' -or @($xml.DocumentElement.SelectNodes('app-version')).Count -ne 1 -or
        [string]$xml.'jpackage-state'.'app-version' -cne $Version) { throw 'Image version marker mismatch' }
}
function Target($Item) {
    if ([string]$Item.scope -ceq 'root') { return (Safe-Path $InstallRoot ([string]$Item.path)) }
    return (Safe-Path $InstallRoot ('app/' + [string]$Item.path))
}
function Payload($Item) {
    $relative = if ([string]$Item.scope -ceq 'root') { 'payload/root/' } else { 'payload/' }
    return (Safe-Path $PackageRoot ($relative + [string]$Item.path))
}
function Assert-Package {
    foreach ($item in $files) { Assert-Hash $item (Payload $item) }
    $exe = Safe-Path $PackageRoot 'payload/root/LumenMusic.exe'
    Assert-Native $exe $manifest.toVersion
    Assert-Cfg (Safe-Path $PackageRoot 'payload/LumenMusic.cfg') $manifest.newJar $manifest.toVersion
    Assert-Image (Safe-Path $PackageRoot 'payload/.jpackage.xml') $manifest.toVersion
    Assert-Jar (Safe-Path $PackageRoot ('payload/' + $manifest.newJar))
}
function Assert-Installed([bool]$Current) {
    foreach ($item in $requirements.Values) { Assert-Hash $item (Safe-Path $InstallRoot ([string]$item.path)) }
    $version = if ($Current) { [string]$manifest.toVersion } else { [string]$manifest.fromVersion }
    $jar = if ($Current) { [string]$manifest.newJar } else { [string]$manifest.oldJar }
    Assert-Native (Safe-Path $InstallRoot 'LumenMusic.exe') $version
    Assert-Cfg (Safe-Path $InstallRoot 'app/LumenMusic.cfg') $jar $version
    if (Test-Path -LiteralPath (Safe-Path $InstallRoot 'app/.jpackage.xml') -PathType Leaf) {
        Assert-Image (Safe-Path $InstallRoot 'app/.jpackage.xml') $version
    } elseif (-not (Test-Path -LiteralPath (Safe-Path $InstallRoot 'app/.package') -PathType Leaf)) {
        throw 'Not a Lumen Music installation (missing package marker)'
    }
    if (-not (Test-Path -LiteralPath (Safe-Path $InstallRoot ('app/' + $jar)) -PathType Leaf)) { throw 'Missing installed main JAR' }
    if ($Current) {
        foreach ($item in $files) { Assert-Hash $item (Target $item) }
        Assert-Jar (Safe-Path $InstallRoot ('app/' + $jar))
    } else {
        if (Test-Path -LiteralPath (Safe-Path $InstallRoot ('app/' + $manifest.newJar))) { throw 'Target main JAR already exists' }
        foreach ($file in Get-ChildItem -LiteralPath (Safe-Path $InstallRoot 'app/service/lumen_service') -Filter '*.py' -File) {
            if (-not $seen.ContainsKey('app/service/lumen_service/' + $file.Name)) { throw "Unmanaged old service module: $($file.Name)" }
        }
    }
}
function Wait-ForExit {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $running = @()
        if ($ParentPid -gt 0 -and $ParentPid -ne $PID) {
            $parent = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
            if ($parent) { $running += $parent }
        }
        foreach ($process in Get-Process) {
            if ($process.Id -eq $PID) { continue }
            $path = try { $process.Path } catch { $null }
            if ($path -and $path.StartsWith($InstallRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
                $running += $process
            }
        }
        if (-not $running.Count) { return }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Application or its bundled service did not exit within 60 seconds; no process was killed' }
        Start-Sleep -Milliseconds 100
    } while ($true)
}
function Record-Old([string]$Relative) {
    $target = Safe-Path $InstallRoot $Relative
    $exists = Test-Path -LiteralPath $target -PathType Leaf
    if ((Test-Path -LiteralPath $target) -and -not $exists) { throw "Target is not a file: $Relative" }
    $record = [pscustomobject]@{ path = $Relative; exists = $exists; sha256 = ''; size = 0L }
    if ($exists) {
        $record.sha256 = Hash $target; $record.size = (Get-Item -LiteralPath $target).Length
        $saved = Safe-Path $backup ('files/' + $Relative)
        New-Item -ItemType Directory -Force -Path (Split-Path $saved -Parent) | Out-Null
        Copy-Item -LiteralPath $target -Destination $saved
        Assert-Hash $record $saved
    }
    [void]$records.Add($record)
    return $record
}
function Write-Backup {
    $doc = New-Object Xml.XmlDocument
    $root = $doc.CreateElement('backup'); [void]$doc.AppendChild($root)
    foreach ($record in $records) {
        $node = $doc.CreateElement('file')
        foreach ($key in @('path', 'exists', 'sha256', 'size')) { $node.SetAttribute($key, [string]$record.$key) }
        [void]$root.AppendChild($node)
    }
    $doc.Save((Safe-Path $backup 'backup.xml'))
}
function Verify-Restored {
    foreach ($record in $records) {
        $target = Safe-Path $InstallRoot $record.path
        if ($record.exists) { Assert-Hash $record $target }
        elseif (Test-Path -LiteralPath $target) { throw "New file remained after rollback: $($record.path)" }
    }
}
function Rollback {
    $errors = New-Object System.Collections.ArrayList
    foreach ($record in $changes) {
        try {
            $target = Safe-Path $InstallRoot $record.path
            if ($record.exists -and (Test-Path -LiteralPath $target -PathType Leaf) -and
                (Get-Item -LiteralPath $target).Length -eq $record.size -and (Hash $target) -ceq $record.sha256) { continue }
            if (Test-Path -LiteralPath $target) {
                $failed = Safe-Path $backup ('failed/' + $record.path)
                New-Item -ItemType Directory -Force -Path (Split-Path $failed -Parent) | Out-Null
                Move-Item -LiteralPath $target -Destination $failed -Force
            }
            if ($record.exists) {
                Copy-Item -LiteralPath (Safe-Path $backup ('files/' + $record.path)) -Destination $target
                Assert-Hash $record $target
            }
        } catch { [void]$errors.Add($_.Exception.Message) }
    }
    foreach ($cache in $cacheMoves) {
        try { Move-Item -LiteralPath $cache.saved -Destination $cache.target } catch { [void]$errors.Add($_.Exception.Message) }
    }
    try { Verify-Restored } catch { [void]$errors.Add($_.Exception.Message) }
    if ($errors.Count) { throw ($errors -join '; ') }
}

try {
    $InstallRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $InstallRoot).Path).TrimEnd('\')
    $PackageRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PackageRoot).Path).TrimEnd('\')
    if (-not $ResultPath) { $ResultPath = Join-Path $PackageRoot 'stage/result.xml' }
    $ResultPath = [IO.Path]::GetFullPath($ResultPath)
    if ($ResultPath.StartsWith($InstallRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        $resultAllowed = $false
        throw 'Result file must not be inside the installation'
    }
    $doc = Read-Xml (Safe-Path $PackageRoot 'manifest.xml')
    $manifest = $doc.DocumentElement
    Assert-Attributes $manifest @('format', 'app', 'platform', 'arch', 'fromVersion', 'toVersion', 'oldJar', 'newJar', 'buildId', 'packageId')
    Assert-Children $manifest @('files', 'requirements')
    $uuid = [Guid]::Empty
    if ($manifest.Name -cne 'update' -or $manifest.format -cne '1' -or $manifest.app -cne 'LumenMusic' -or
        $manifest.platform -cne 'windows' -or $manifest.arch -cne 'x64' -or
        [string]$manifest.fromVersion -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' -or
        [string]$manifest.toVersion -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' -or
        [version]$manifest.toVersion -le [version]$manifest.fromVersion -or
        [string]$manifest.oldJar -cne ('lumen-desktop-' + $manifest.fromVersion + '.jar') -or
        [string]$manifest.newJar -cne ('lumen-desktop-' + $manifest.toVersion + '.jar') -or
        [string]$manifest.buildId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or
        -not [Guid]::TryParseExact([string]$manifest.packageId, 'D', [ref]$uuid) -or
        $uuid.ToString('D') -cne [string]$manifest.packageId -or
        @($manifest.SelectNodes('files')).Count -ne 1 -or @($manifest.SelectNodes('requirements')).Count -ne 1) {
        throw 'Invalid update manifest contract'
    }
    $files = @($manifest.SelectNodes('files/file'))
    Assert-Attributes $manifest.files @(); Assert-Children $manifest.files @('file')
    Assert-Attributes $manifest.requirements @(); Assert-Children $manifest.requirements @('file')
    if ($files.Count -gt 1024 -or @($manifest.SelectNodes('requirements/file')).Count -gt 8192) { throw 'Too many manifest entries' }
    $seen = @{}; $requirements = @{}; $total = 0L
    $allowed = '^(LumenMusic\.cfg|\.jpackage\.xml|stream-proxy/lumen-stream-proxy\.exe|service/lumen_service/[A-Za-z_][A-Za-z_0-9]*\.py)$'
    foreach ($item in $files) {
        Assert-Attributes $item @('scope', 'path', 'sha256', 'size'); Assert-Children $item @()
        Assert-Entry $item
        $key = [string]$item.scope + '/' + [string]$item.path
        if ($seen.ContainsKey($key) -or
            ([string]$item.scope -ceq 'root' -and [string]$item.path -cnotin @('LumenMusic.exe', 'runtime/lib/modules')) -or
            ([string]$item.scope -ceq 'app' -and [string]$item.path -cne [string]$manifest.newJar -and [string]$item.path -cnotmatch $allowed) -or
            ([string]$item.scope -cne 'root' -and [string]$item.scope -cne 'app')) { throw "Invalid managed file: $key" }
        $seen[$key] = $item; $total += [long]$item.size
        [void](Target $item); [void](Payload $item)
    }
    if ($total -gt 536870912) { throw 'Payload exceeds 512 MiB' }
    foreach ($mandatory in @('root/LumenMusic.exe', ('app/' + $manifest.newJar), 'app/LumenMusic.cfg', 'app/.jpackage.xml',
            'app/stream-proxy/lumen-stream-proxy.exe', 'app/service/lumen_service/__init__.py', 'app/service/lumen_service/__main__.py')) {
        if (-not $seen.ContainsKey($mandatory)) { throw "Missing managed file: $mandatory" }
    }
    foreach ($item in $manifest.SelectNodes('requirements/file')) {
        Assert-Attributes $item @('path', 'sha256', 'size'); Assert-Children $item @()
        Assert-Entry $item
        $relative = [string]$item.path
        if ($requirements.ContainsKey($relative) -or $relative -cnotmatch '^(runtime/.+|app/python/.+|app/vlc/.+|app/[^/]+\.jar)$' -or
            $relative -ceq ('app/' + $manifest.oldJar) -or $relative -ceq ('app/' + $manifest.newJar) -or
            $seen.ContainsKey($relative) -or $seen.ContainsKey('root/' + $relative)) {
            throw "Invalid retained requirement: $relative"
        }
        [void](Safe-Path $InstallRoot $relative)
        $requirements[$relative] = $item
    }
    if (-not $requirements.Count) { throw 'Missing retained runtime requirements' }
    Assert-Package
    $current = [Diagnostics.FileVersionInfo]::GetVersionInfo((Safe-Path $InstallRoot 'LumenMusic.exe')).ProductVersion -ceq [string]$manifest.toVersion
    Assert-Installed $current
    Wait-ForExit
    Assert-Package
    Assert-Installed $current
    if ($current) { $status = 'already_current'; $message = 'Installed files already match this update' }
    else {
        $backup = Safe-Path $InstallRoot ('update-backup-v' + $manifest.fromVersion + '-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $backup | Out-Null
        foreach ($item in $files) {
            $relative = if ([string]$item.scope -ceq 'root') { [string]$item.path } else { 'app/' + [string]$item.path }
            [void](Record-Old $relative)
        }
        $oldJarRecord = Record-Old ('app/' + $manifest.oldJar)
        $service = Safe-Path $InstallRoot 'app/service'
        $caches = @(Get-ChildItem -LiteralPath $service -Directory -Recurse -Force | Where-Object { $_.Name -ceq '__pycache__' })
        foreach ($cache in $caches) {
            $relative = $cache.FullName.Substring($InstallRoot.Length + 1).Replace('\', '/')
            [void](Safe-Path $InstallRoot $relative)
            foreach ($file in Get-ChildItem -LiteralPath $cache.FullName -File -Recurse -Force) {
                [void](Record-Old ($file.FullName.Substring($InstallRoot.Length + 1).Replace('\', '/')))
            }
        }
        Write-Backup
        [void]$changes.Add($oldJarRecord)
        $retired = Safe-Path $backup ('retired/app/' + $manifest.oldJar)
        New-Item -ItemType Directory -Force -Path (Split-Path $retired -Parent) | Out-Null
        Move-Item -LiteralPath (Safe-Path $InstallRoot $oldJarRecord.path) -Destination $retired
        foreach ($cache in $caches) {
            $relative = $cache.FullName.Substring($InstallRoot.Length + 1).Replace('\', '/')
            $saved = Safe-Path $backup ('retired/' + $relative)
            New-Item -ItemType Directory -Force -Path (Split-Path $saved -Parent) | Out-Null
            Move-Item -LiteralPath (Safe-Path $InstallRoot $relative) -Destination $saved
            [void]$cacheMoves.Add([pscustomobject]@{ target = (Safe-Path $InstallRoot $relative); saved = $saved })
        }
        $count = 0
        foreach ($item in $files) {
            $target = Target $item
            $relative = $target.Substring($InstallRoot.Length + 1).Replace('\', '/')
            $record = @($records | Where-Object { $_.path -ceq $relative })[0]
            New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
            $temporary = Safe-Path $InstallRoot ($relative + '.update-' + [Guid]::NewGuid().ToString('N'))
            try {
                Copy-Item -LiteralPath (Payload $item) -Destination $temporary
                Assert-Hash $item $temporary
                [void]$changes.Add($record)
                Move-Item -LiteralPath $temporary -Destination $target -Force
            } finally {
                if (Test-Path -LiteralPath $temporary) {
                    Move-Item -LiteralPath $temporary -Destination (Safe-Path $backup ('temporary-' + [Guid]::NewGuid().ToString('N')))
                }
            }
            $count++
            if ($TestFailAfter -gt 0 -and $count -ge $TestFailAfter) { throw 'Injected update failure' }
        }
        Assert-Installed $true
        $status = 'success'; $message = 'Updated and verified all managed application files'
    }
    if ($Restart) {
        Start-Process -FilePath (Safe-Path $InstallRoot 'LumenMusic.exe') -WorkingDirectory $InstallRoot -WindowStyle Hidden | Out-Null
        $message += '; launcher started'
    }
} catch {
    $message = $_.Exception.Message
    if ($changes.Count -gt 0) {
        try { Rollback; $status = 'rolled_back' }
        catch { $status = 'rollback_failed'; $message += '; rollback: ' + $_.Exception.Message }
    } else { $status = 'failed_unchanged' }
}

try {
    if (-not $resultAllowed) { throw 'Refusing result write inside installation' }
    if (-not $ResultPath) { $ResultPath = Join-Path $PackageRoot 'stage/result.xml' }
    $doc = New-Object Xml.XmlDocument
    $result = $doc.CreateElement('result'); [void]$doc.AppendChild($result)
    $result.SetAttribute('status', $status)
    foreach ($key in @('fromVersion', 'toVersion', 'buildId')) {
        $value = if ($manifest) { [string]$manifest.GetAttribute($key) } else { '' }
        $result.SetAttribute($key, $value)
    }
    $result.SetAttribute('backupPath', $backup); $result.SetAttribute('message', $message)
    New-Item -ItemType Directory -Force -Path (Split-Path $ResultPath -Parent) | Out-Null
    $temporaryResult = $ResultPath + '.' + [Guid]::NewGuid().ToString('N')
    $doc.Save($temporaryResult)
    Move-Item -LiteralPath $temporaryResult -Destination $ResultPath -Force
} catch { Write-Error "Cannot write update result: $($_.Exception.Message)"; exit 2 }
Write-Output "$status`: $message"
if ($status -eq 'success' -or $status -eq 'already_current') { exit 0 }
if ($status -eq 'rollback_failed') { exit 2 }
exit 1
