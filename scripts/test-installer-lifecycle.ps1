param(
    [string]$Jpackage,
    [string]$WixBin,
    [string]$FixtureImage
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $Jpackage) { $Jpackage = (Get-Command jpackage -ErrorAction Stop).Source }
if (-not $WixBin) { $WixBin = Join-Path $projectRoot 'build/tools/wix-3.14.1/bin' }
if (-not $FixtureImage) { $FixtureImage = Join-Path $projectRoot 'build/installer-probe/image' }
$Jpackage = (Resolve-Path -LiteralPath $Jpackage).Path
$WixBin = (Resolve-Path -LiteralPath $WixBin).Path
$FixtureImage = (Resolve-Path -LiteralPath $FixtureImage).Path
$testId = [Guid]::NewGuid().ToString('N')
$root = Join-Path $projectRoot ('build/installer-lifecycle/' + $testId)
$installDir = Join-Path $root 'app'
$qaName = 'LumenInstallerQA-' + $testId
$qaDisplay = 'Lumen Installer QA ' + $testId
$qaFamily = [Guid]::NewGuid().ToString('B').ToUpperInvariant()
$formalFamily = '{6F3FA127-20B0-3D74-BD13-D27F8BF9770B}'
$formalCodes = @('{5C37F840-8D8C-3727-AC06-67650564FF9E}', '{A503C7B2-C60E-43BD-98B5-DDEB31DD29A4}')
$menuDir = Join-Path ([Environment]::GetFolderPath('Programs')) $qaDisplay
$qaRegistry = 'HKCU:/Software/' + $qaDisplay
$codes = New-Object Collections.Generic.List[string]
$msiRuns = New-Object Collections.Generic.List[object]
$builds = New-Object Collections.Generic.List[object]
$result = [ordered]@{ TestId=$testId; Root=$root; Name=$qaName; UpgradeCode=$qaFamily; InstallDir=$installDir;
    MenuDir=$menuDir; RegistryRoot=$qaRegistry; Scope='Real silent QA MSI lifecycle; synthetic payload; never starts the player or accesses personal data.' }
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
Assert ($qaFamily -cne $formalFamily -and $installDir.StartsWith((Join-Path $projectRoot 'build/installer-lifecycle/'), [StringComparison]::OrdinalIgnoreCase)) 'QA root/family guard failed.'
Assert (-not (Test-Path -LiteralPath $root) -and -not (Test-Path -LiteralPath $menuDir) -and -not (Test-Path $qaRegistry)) 'QA target already exists.'
New-Item -ItemType Directory -Path $root | Out-Null
function Formal-Registration {
    $entries = @()
    foreach ($hive in @('HKCU:', 'HKLM:')) {
        foreach ($code in $formalCodes) {
            $path = "$hive/Software/Microsoft/Windows/CurrentVersion/Uninstall/$code"
            if (Test-Path $path) {
                $key = Get-Item $path
                $values = [ordered]@{}
                foreach ($name in ($key.GetValueNames() | Sort-Object)) { $values[$name] = $key.GetValue($name) }
                $entries += [ordered]@{ Path=$path; Values=$values }
            }
        }
    }
    ConvertTo-Json -InputObject $entries -Depth 5 -Compress
}
$formalBefore = Formal-Registration
[IO.File]::WriteAllText((Join-Path $root 'formal-registration-before.json'), $formalBefore, [Text.UTF8Encoding]::new($false))
$installer = New-Object -ComObject WindowsInstaller.Installer
function Query([string]$Msi, [string]$Sql) {
    $db = $installer.OpenDatabase($Msi, 0)
    try {
        $view = $db.OpenView($Sql)
        try {
            [void]$view.Execute()
            while ($record=$view.Fetch()) {
                try {
                    $count=$record.GetType().InvokeMember('FieldCount',[Reflection.BindingFlags]::GetProperty,$null,$record,$null)
                    $row=@(); for($i=1;$i -le $count;$i++) { $row += $record.StringData($i) }
                    Write-Output -NoEnumerate $row
                } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) }
            }
        } finally { [void]$view.Close(); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) }
    } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($db) }
}
function Guard-Msi([string]$Path) {
    Assert ($Path.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) 'MSI is outside this QA run.'
    $props=@{}; foreach($row in (Query $Path 'SELECT `Property`, `Value` FROM `Property`')) { $props[$row[0]]=$row[1] }
    Assert ($props.ProductName -ceq $qaName -and $props.UpgradeCode -ceq $qaFamily -and $props.UpgradeCode -cne $formalFamily -and
        $props.ProductCode -notin $formalCodes -and $props.ProductVersion -ceq '0.2.2') 'MSI identity guard rejected a non-QA product.'
    foreach($row in (Query $Path 'SELECT `UpgradeCode` FROM `Upgrade`')) { Assert ($row[0] -ceq $qaFamily) 'MSI references an external upgrade family.' }
    foreach($row in (Query $Path 'SELECT `Key` FROM `Registry`')) { Assert ($row[0].StartsWith('Software\' + $qaDisplay + '\', [StringComparison]::OrdinalIgnoreCase)) 'MSI has a registry entry outside the owned QA namespace.' }
    Assert (@(Query $Path 'SELECT `Shortcut`, `Target`, `Arguments` FROM `Shortcut`' | Where-Object { $_[0] -ceq 'LumenInstallUninstall' -and $_[1] -ceq '[SystemFolder]msiexec.exe' -and $_[2] -ceq '/x [ProductCode]' }).Count -eq 1) 'MSI lacks durable uninstall shortcut.'
    $props
}
function Product-State([string]$Code) { $installer.GetType().InvokeMember('ProductState',[Reflection.BindingFlags]::GetProperty,$null,$installer,@($Code)) }
function Run-Msi([string]$Label, [string[]]$Arguments, [string]$Executable = (Join-Path $env:SystemRoot 'System32/msiexec.exe')) {
    $log = Join-Path $root ($Label + '.log')
    $process = Start-Process -FilePath $Executable -ArgumentList (@($Arguments) + @('/qn','/norestart','/l*v', ('"' + $log + '"'))) -WindowStyle Hidden -PassThru
    try {
        Assert ($process.WaitForExit(120000)) "QA MSI timed out: $Label (process $($process.Id))"
        $code = $process.ExitCode
        $msiRuns.Add([ordered]@{Label=$Label; ExitCode=$code; Log=$log})
        Assert ($code -in @(0,3010)) "QA MSI $Label failed with exit $code; see $log"
    } finally { $process.Dispose() }
}
$originalPath=$env:PATH
$failure=$null
try {
    $env:PATH=$WixBin + ';' + $originalPath
    $stage=Join-Path $root 'project'
    New-Item -ItemType Directory -Path (Join-Path $stage 'scripts/installer'), (Join-Path $stage 'release/installer') | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'installer/prepare-resources.ps1') -Destination (Join-Path $stage 'scripts/installer')
    $action=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'installer/DirectoryChooser.cpp')).Replace($formalFamily, $qaFamily).Replace('Lumen Music', $qaDisplay)
    Assert (-not $action.Contains($formalFamily)) 'QA custom action still references the formal family.'
    [IO.File]::WriteAllText((Join-Path $stage 'scripts/installer/DirectoryChooser.cpp'), $action, [Text.UTF8Encoding]::new($false))
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $projectRoot 'release/installer') -File) {
        $text=[IO.File]::ReadAllText($file.FullName).Replace('Lumen Music', $qaDisplay)
        Assert (-not $text.Contains('Software\Lumen Music')) 'QA template still references a formal registry key.'
        [IO.File]::WriteAllText((Join-Path $stage ('release/installer/' + $file.Name)), $text, [Text.UTF8Encoding]::new($false))
    }
    foreach($build in @('a','b')) {
        $buildRoot=Join-Path $root $build
        $image=Join-Path $buildRoot 'image'
        New-Item -ItemType Directory -Path (Join-Path $image 'app'), (Join-Path $buildRoot 'dist') | Out-Null
        Copy-Item -LiteralPath (Join-Path $FixtureImage 'LumenMusic.exe') -Destination (Join-Path $image ($qaName + '.exe'))
        $metadata=[IO.File]::ReadAllText((Join-Path $FixtureImage 'app/.jpackage.xml')).Replace('<main-launcher>LumenMusic</main-launcher>', '<main-launcher>' + $qaName + '</main-launcher>')
        [IO.File]::WriteAllText((Join-Path $image 'app/.jpackage.xml'),$metadata,[Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $image 'app/QA_ONLY.txt'), "QA synthetic payload $build; never launch this executable.", [Text.Encoding]::ASCII)
        $resources=Join-Path $buildRoot 'resources'
        & (Join-Path $stage 'scripts/installer/prepare-resources.ps1') -Destination $resources -WixBin $WixBin -BuildId ('qa-' + $testId + '-' + $build)
        $compileLog=Join-Path $buildRoot 'compile.log'
        & $Jpackage '-J-Duser.language=zh' '-J-Duser.country=CN' --verbose --type exe --name $qaName --app-version 0.2.2 --app-image $image --dest (Join-Path $buildRoot 'dist') --temp (Join-Path $buildRoot 'temp') --resource-dir $resources --vendor $qaDisplay --description $qaDisplay --icon (Join-Path $projectRoot 'desktop/src/main/resources/icons/lumen.ico') --win-per-user-install --win-dir-chooser --win-menu --win-menu-group $qaDisplay --win-upgrade-uuid ($qaFamily.Trim('{}')) *> $compileLog
        Assert ($LASTEXITCODE -eq 0) "QA build failed: $compileLog"
        $msi=Join-Path $buildRoot ('temp/images/win-exe.image/' + $qaName + '-0.2.2.msi')
        $properties=Guard-Msi $msi
        $codes.Add($properties.ProductCode)
        $builds.Add([ordered]@{Build=$build; Msi=$msi; ProductCode=$properties.ProductCode; Setup=(Join-Path $buildRoot ('dist/' + $qaName + '-0.2.2.exe'))})
    }
    Assert ($codes.Count -eq 2 -and $codes[0] -cne $codes[1]) 'QA builds must have distinct product codes.'
    Run-Msi 'first-install' @('/i', ('"' + $builds[0].Msi + '"'), ('INSTALLDIR="' + $installDir + '"'))
    Assert ((Product-State $codes[0]) -eq 5 -and (Test-Path -LiteralPath (Join-Path $installDir 'app/QA_ONLY.txt'))) 'First QA installation did not register or install its payload.'
    Assert ([IO.File]::ReadAllText((Join-Path $installDir 'app/QA_ONLY.txt')) -match 'payload a;') 'Wrong first-install payload.'
    # Exercise the real native export in a guarded QA MSI session with no explicit path.
    # It must discover the installed older QA product's custom project directory.
    if (-not ('LifecycleNativeProbe' -as [type])) { Add-Type -Path (Join-Path $PSScriptRoot 'installer/LifecycleNativeProbe.cs') }
    $preserved=[LifecycleNativeProbe]::Probe($builds[1].Msi, (Join-Path $root 'b/resources/LumenInstallerActions.CA.dll'), $qaFamily, $qaName)
    Assert ($preserved.TrimEnd('\') -ceq $installDir) 'Native upgrade action did not reuse the older QA product custom install directory.'
    $result.NativePreservedDirectory=$preserved
    # The real execute sequence must discover the earlier custom directory itself.
    Run-Msi 'same-version-major-upgrade' @('/i', ('"' + $builds[1].Msi + '"'))
    Assert ((Product-State $codes[0]) -eq -1 -and (Product-State $codes[1]) -eq 5) 'Same-version major upgrade did not replace the QA product registration.'
    Assert ([IO.File]::ReadAllText((Join-Path $installDir 'app/QA_ONLY.txt')) -match 'payload b;') 'Major upgrade did not replace the QA payload.'
    $shortcutPath=Join-Path $installDir ('卸载 ' + $qaDisplay + '.lnk')
    $shell=New-Object -ComObject WScript.Shell
    try { $shortcut=$shell.CreateShortcut($shortcutPath); $target=$shortcut.TargetPath; $arguments=$shortcut.Arguments }
    finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) }
    Assert ((Test-Path -LiteralPath $shortcutPath) -and [IO.Path]::GetFileName($target) -ieq 'msiexec.exe' -and $arguments -ceq ('/x ' + $codes[1])) 'Installed uninstall shortcut does not target the current cached QA product.'
    Assert (Test-Path -LiteralPath (Join-Path $menuDir ('卸载 ' + $qaDisplay + '.lnk'))) 'QA Start Menu uninstall shortcut is missing.'
    # Move both downloaded wrappers and build MSI sources away; retained evidence is recoverable.
    # Only the Windows Installer product cache remains at its registered source location.
    foreach($build in $builds) {
        Move-Item -LiteralPath $build.Setup -Destination ($build.Setup + '.download-removed')
        Move-Item -LiteralPath $build.Msi -Destination ($build.Msi + '.source-removed')
    }
    Run-Msi 'cached-shortcut-uninstall' ($arguments -split ' ') $target
    Assert ((Product-State $codes[1]) -eq -1 -and -not (Test-Path -LiteralPath (Join-Path $installDir 'app/QA_ONLY.txt')) -and -not (Test-Path -LiteralPath $shortcutPath)) 'Cached QA uninstall did not remove its program/entry.'
    $result.UninstallShortcut=[ordered]@{Path=$shortcutPath; Target=$target; Arguments=$arguments; DownloadWrappersRemoved=$true; SourceMsisRemoved=$true}
    $result.Passed=@('first installation', 'native action discovers prior custom directory', 'same-version major upgrade', 'old QA registration removed', 'new payload installed', 'both durable shortcuts', 'uninstall with downloaded EXE and source MSI absent')
} catch { $failure=$_; $result.Failure=$_.ToString() }
finally {
    $env:PATH=$originalPath
    # Clean up only owned products whose guarded QA identity was recorded above.
    foreach($code in $codes) {
        if ((Product-State $code) -eq 5) {
            try { Run-Msi ('cleanup-' + $code.Trim('{}')) @('/x',$code) } catch { $result.CleanupFailure=$_.ToString(); if(-not $failure){$failure=$_} }
        }
    }
    $formalAfter=Formal-Registration
    [IO.File]::WriteAllText((Join-Path $root 'formal-registration-after.json'), $formalAfter, [Text.UTF8Encoding]::new($false))
    $result.FormalRegistrationUnchanged=($formalBefore -ceq $formalAfter)
    $result.QAProductsAbsent=@($codes | ForEach-Object { [ordered]@{ProductCode=$_; State=(Product-State $_)} })
    $result.QAMenuAbsent=(-not (Test-Path -LiteralPath $menuDir))
    $result.QAInstallerRegistryAbsent=(-not (Test-Path ($qaRegistry + '/Installer')))
    $result.Builds=$builds.ToArray(); $result.MsiRuns=$msiRuns.ToArray()
    $result.Gui='Not exercised: native UI automation is unavailable to this agent; silent MSI lifecycle is separately verified.'
    [IO.File]::WriteAllText((Join-Path $root 'result.json'), ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
}
Write-Output "QA lifecycle evidence: $root"
if($failure) { throw $failure }
Assert ($result.FormalRegistrationUnchanged -and $result.QAMenuAbsent -and $result.QAInstallerRegistryAbsent) 'Formal registration changed or owned QA menu/installer registry remained.'
Write-Output 'PASS real isolated MSI first install, same-version major upgrade, and cached-product uninstall; formal registration preserved.'
