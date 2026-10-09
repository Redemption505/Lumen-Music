$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$root = Join-Path $projectRoot ('build/local-update-command-tests/' + [Guid]::NewGuid().ToString('N'))
$fixture = Join-Path $root 'command with spaces'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'update/update-local.cmd') -Destination $fixture
# This stub only records CMD arguments. It never invokes the updater or reads an installation.
$stub = @'
param([string]$InstallRoot, [string]$PackageRoot, [string]$ResultPath)
$doc = New-Object Xml.XmlDocument
$node = $doc.CreateElement('result'); [void]$doc.AppendChild($node)
$node.SetAttribute('installRoot', $InstallRoot)
$node.SetAttribute('packageRoot', $PackageRoot)
$valid = Test-Path -LiteralPath $InstallRoot -PathType Container
$node.SetAttribute('status', $(if ($valid) { 'success' } else { 'failed_unchanged' }))
New-Item -ItemType Directory -Force -Path (Split-Path $ResultPath -Parent) | Out-Null
$doc.Save($ResultPath)
if (-not $valid) { exit 1 }
'@
[IO.File]::WriteAllText((Join-Path $fixture 'apply-local-update.ps1'), $stub, [Text.Encoding]::ASCII)
$local = Join-Path $root 'fake local app data'
$default = Join-Path $local 'LumenMusic'
$spaces = Join-Path $root 'custom install with spaces'
New-Item -ItemType Directory -Force -Path $default, $spaces | Out-Null
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Run([string]$InputLine, [string]$Argument = '') {
    $result = Join-Path $fixture 'stage/result.xml'
    if (Test-Path -LiteralPath $result) {
        Move-Item -LiteralPath $result -Destination (Join-Path $root ('previous-' + [Guid]::NewGuid().ToString('N') + '.xml'))
    }
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = Join-Path $env:SystemRoot 'System32/cmd.exe'
    $command = '"' + (Join-Path $fixture 'update-local.cmd') + '"'
    if ($Argument) { $command += ' "' + $Argument + '"' }
    $start.Arguments = '/d /c "' + $command + '"'
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.EnvironmentVariables['LOCALAPPDATA'] = $local
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $process.StandardInput.WriteLine($InputLine); $process.StandardInput.WriteLine(''); $process.StandardInput.Close()
        $output = $process.StandardOutput.ReadToEnd(); $errorText = $process.StandardError.ReadToEnd()
        Assert ($process.WaitForExit(15000)) 'CMD fixture did not exit'
        $xml = if (Test-Path -LiteralPath $result) { ([xml][IO.File]::ReadAllText($result)).result } else { $null }
        return [pscustomobject]@{ code=$process.ExitCode; output=$output; errors=$errorText; result=$xml }
    } finally {
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}
$r = Run ''
Assert ($r.code -eq 0 -and $r.result.installRoot -eq $default) "Default directory failed: $($r.errors)"
Assert ($r.output.Contains('Install directory')) 'No directory prompt'
Write-Output 'PASS Enter selects isolated default'
$r = Run ('"' + $spaces + '"')
Assert ($r.code -eq 0 -and $r.result.installRoot -eq $spaces) "Quoted path failed: $($r.errors)"
Write-Output 'PASS pasted quoted absolute path with spaces'
$r = Run 'Q'
Assert ($r.code -eq 1 -and $null -eq $r.result) 'Cancel invoked helper'
Write-Output 'PASS cancel does not invoke helper'
$missing = Join-Path $root 'nonexistent installation'
$r = Run $missing
Assert ($r.code -eq 1 -and $r.result.installRoot -eq $missing -and $r.result.status -eq 'failed_unchanged') 'Error directory not preserved/reported'
Write-Output 'PASS erroneous directory reaches helper without replacement'
$r = Run '' $spaces
Assert ($r.code -eq 0 -and $r.result.installRoot -eq $spaces -and -not $r.output.Contains('Install directory')) 'Explicit argument still prompts'
Write-Output 'PASS explicit argument skips prompt'
Write-Output "Local update command tests passed: 5; fixtures: $root"
