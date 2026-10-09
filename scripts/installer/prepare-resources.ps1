param(
    [Parameter(Mandatory)][string]$Destination,
    [Parameter(Mandatory)][string]$WixBin,
    [Parameter(Mandatory)][string]$BuildId
)
$ErrorActionPreference = 'Stop'
$Destination = [IO.Path]::GetFullPath($Destination)
$WixBin = (Resolve-Path -LiteralPath $WixBin).Path
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) { throw 'The existing MSVC build toolchain is required for native installer actions.' }
$vcvars = @(& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -find 'VC/Auxiliary/Build/vcvars64.bat') | Select-Object -First 1
if (-not $vcvars -or -not (Test-Path -LiteralPath $vcvars)) { throw 'The existing MSVC x64 build environment is missing.' }
if ($BuildId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') { throw 'Invalid installer build identity.' }
New-Item -ItemType Directory -Path $Destination | Out-Null
Copy-Item -Path (Join-Path $projectRoot 'release/installer/*') -Destination $Destination
$source = Join-Path $PSScriptRoot 'DirectoryChooser.cpp'
$native = Join-Path $Destination 'LumenInstallerActions.CA.dll'
$object = Join-Path $Destination 'DirectoryChooser.obj'
$importLibrary = Join-Path $Destination 'LumenInstallerActions.lib'
foreach ($path in @($vcvars, $source, $native, $object, $importLibrary)) { if ($path -match '["%\r\n]') { throw 'Unsupported native build path.' } }
# /MT embeds the CRT; the shipped action imports only Windows system libraries.
$command = 'call "' + $vcvars + '" >nul && cl.exe /nologo /utf-8 /std:c++17 /EHsc /O2 /MT /LD /DUNICODE /D_UNICODE /Fo"' + $object + '" /Fe"' + $native + '" "' + $source + '" /link /IMPLIB:"' + $importLibrary + '" msi.lib ole32.lib shell32.lib shlwapi.lib uuid.lib'
& $env:ComSpec /d /s /c $command
if ($LASTEXITCODE -ne 0) { throw 'Native installer action compilation failed.' }
# A distinct MSI ProductCode per build permits same-version maintenance fixes to upgrade safely.
# Keeping it deterministic still lets the same build repair its own installed package.
$sha = [Security.Cryptography.SHA256]::Create()
try { $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes('LumenMusic/MSI/' + $BuildId)) } finally { $sha.Dispose() }
$guidBytes = New-Object byte[] 16; [Array]::Copy($bytes, $guidBytes, 16)
$productCode = [Guid]::new($guidBytes).ToString('B').ToUpperInvariant()
$resourcePath = [Security.SecurityElement]::Escape([IO.Path]::GetFullPath($Destination))
$override = '<?xml version="1.0" encoding="utf-8"?><Include><?undef JpProductLanguage?><?define JpProductLanguage="2052"?><?undef JpProductCode?><?define JpProductCode="' + $productCode + '"?><?define LumenResourceDir="' + $resourcePath + '"?></Include>'
[IO.File]::WriteAllText((Join-Path $Destination 'overrides.wxi'), $override, [Text.UTF8Encoding]::new($false))
Write-Output "Installer resources: $Destination; ProductCode=$productCode; BuildId=$BuildId"
