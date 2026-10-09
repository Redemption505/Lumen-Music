param(
    [Parameter(Mandatory)][string]$Msi,
    [Parameter(Mandatory)][string]$BuildId,
    [string]$Report
)
$ErrorActionPreference = 'Stop'
$Msi = (Resolve-Path -LiteralPath $Msi).Path
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Rows([string]$Query) {
    $view = $database.OpenView($Query)
    try {
        [void]$view.Execute()
        while ($record = $view.Fetch()) {
            try {
                $fieldCount = $record.GetType().InvokeMember('FieldCount', [Reflection.BindingFlags]::GetProperty, $null, $record, $null)
                $row = @(); for ($i = 1; $i -le $fieldCount; $i++) { $row += $record.StringData($i) }
                Write-Output -NoEnumerate $row
            } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) }
        }
    } finally { [void]$view.Close(); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) }
}
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($Msi, 0) # Read-only; this script never executes the package.
try {
    $properties = @{}; foreach ($row in (Rows 'SELECT `Property`, `Value` FROM `Property`')) { $properties[$row[0]] = $row[1] }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes('LumenMusic/MSI/' + $BuildId)) } finally { $sha.Dispose() }
    $guidBytes = New-Object byte[] 16; [Array]::Copy($bytes, $guidBytes, 16)
    $expectedCode = [Guid]::new($guidBytes).ToString('B').ToUpperInvariant()
    Assert ($properties.ProductCode -ceq $expectedCode) 'MSI ProductCode does not match the requested build.'
    Assert ($properties.ProductLanguage -eq '2052') 'MSI product language is not Simplified Chinese.'
    Assert ($properties.UpgradeCode -eq '{6F3FA127-20B0-3D74-BD13-D27F8BF9770B}') 'MSI upgrade family changed.'
    Assert ($properties.ARPNOMODIFY -eq '1') 'Windows maintenance must expose repair/uninstall, not a broken change action.'
    $shortcuts = @(Rows 'SELECT `Shortcut`, `Directory_`, `Target`, `Arguments` FROM `Shortcut`')
    foreach ($expected in @(@('LumenInstallUninstall', 'INSTALLDIR'), @('LumenMenuUninstall', 'LumenUninstallMenuFolder'))) {
        $shortcut = @($shortcuts | Where-Object { $_[0] -ceq $expected[0] })
        Assert ($shortcut.Count -eq 1 -and $shortcut[0][1] -ceq $expected[1] -and
            $shortcut[0][2] -ceq '[SystemFolder]msiexec.exe' -and $shortcut[0][3] -ceq '/x [ProductCode]') 'Missing durable cached-product uninstall shortcut.'
    }
    $actions = @(Rows 'SELECT `Action`, `Type`, `Source`, `Target` FROM `CustomAction`')
    foreach ($expected in @(@('LumenChooseDirectory', 'ChooseDirectory'), @('LumenPreserveInstallDirectory', 'PreserveInstallDirectory'))) {
        $action = @($actions | Where-Object { $_[0] -ceq $expected[0] })
        Assert ($action.Count -eq 1 -and $action[0][1] -eq '1' -and $action[0][2] -ceq 'LumenInstallerActions' -and $action[0][3] -ceq $expected[1]) 'Missing native directory/upgrade action.'
    }
    $controls = @(Rows 'SELECT `Dialog_`, `Control`, `Type`, `Property`, `Text` FROM `Control`')
    Assert (@($controls | Where-Object { $_[0] -ceq 'LumenInstallDirDlg' -and $_[1] -ceq 'Folder' -and $_[2] -ceq 'PathEdit' -and $_[3] -ceq 'INSTALLDIR' }).Count -eq 1) 'Installer has no direct path editor.'
    Assert (@($controls | Where-Object { $_[0] -ceq 'LumenInstallDirDlg' -and $_[1] -ceq 'ChooseFolder' -and $_[2] -ceq 'PushButton' -and $_[4] -match '\u6d4f\u89c8' }).Count -eq 1) 'Installer has no Chinese folder chooser.'
    $events = @(Rows 'SELECT `Dialog_`, `Control_`, `Event`, `Argument`, `Condition`, `Ordering` FROM `ControlEvent`')
    # NewDialog to the current dialog creates a second copy and fails with MSI error 2856.
    Assert (@($events | Where-Object { $_[2] -ceq 'NewDialog' -and $_[3] -ceq $_[0] }).Count -eq 0) 'Installer NewDialog creates a second copy of the current dialog (MSI error 2856).'
    $browseEvents = @($events | Where-Object { $_[0] -ceq 'LumenInstallDirDlg' -and $_[1] -ceq 'ChooseFolder' })
    $browseAction = @($browseEvents | Where-Object { $_[2] -ceq 'DoAction' -and $_[3] -ceq 'LumenChooseDirectory' })
    Assert ($browseAction.Count -eq 1 -and $browseAction[0][4] -ceq '1') 'Browse button does not unconditionally invoke the Windows Shell directory picker.'
    $browseProperty = @($browseEvents | Where-Object { $_[2] -ceq '[INSTALLDIR]' -and $_[3] -ceq '[LUMEN_CHOSEN_INSTALLDIR]' })
    Assert ($browseProperty.Count -eq 1 -and $browseProperty[0][4] -ceq '1') 'Browse button does not publish the chosen directory to the INSTALLDIR UI property.'
    $browseTarget = @($browseEvents | Where-Object { $_[2] -ceq 'SetTargetPath' -and $_[3] -ceq 'INSTALLDIR' })
    Assert ($browseTarget.Count -eq 1 -and $browseTarget[0][4] -ceq '1') 'Browse button does not apply the selected INSTALLDIR target path.'
    Assert ([int]$browseAction[0][5] -lt [int]$browseProperty[0][5] -and
        [int]$browseProperty[0][5] -lt [int]$browseTarget[0][5]) 'Browse events must select, publish INSTALLDIR, then apply the target path.'
    Assert (@($events | Where-Object { $_[0] -ceq 'WelcomeDlg' -and $_[1] -ceq 'Next' -and $_[3] -ceq 'LumenInstallDirDlg' }).Count -eq 1) 'Fresh install does not reach the new directory screen.'
    $sequence = @{}; foreach ($row in (Rows 'SELECT `Action`, `Sequence` FROM `InstallExecuteSequence`')) { $sequence[$row[0]] = [int]$row[1] }
    Assert ($sequence.LumenPreserveInstallDirectory -lt $sequence.CostInitialize) 'Existing installation location is read too late.'
    Assert ($sequence.RemoveExistingProducts -gt $sequence.InstallInitialize -and $sequence.RemoveExistingProducts -lt $sequence.InstallFiles) 'Upgrade removal is outside the rollback transaction.'
    $upgrades = @(Rows 'SELECT `VersionMax`, `Attributes`, `ActionProperty` FROM `Upgrade`')
    Assert (@($upgrades | Where-Object { $_[2] -ceq 'JP_UPGRADABLE_FOUND' -and $_[0] -ceq $properties.ProductVersion -and (([int]$_[1] -band 512) -ne 0) }).Count -eq 1) 'Same-version builds are not eligible for major upgrade.'
    $result = [ordered]@{ Msi = $Msi; Sha256 = (Get-FileHash -LiteralPath $Msi).Hash.ToLowerInvariant(); BuildId = $BuildId;
        ProductCode = $properties.ProductCode; Version = $properties.ProductVersion; Language = $properties.ProductLanguage;
        Passed = @('build-specific MSI identity', 'same-version major upgrade', 'rollback-safe upgrade order', 'reuse registered install directory',
            'Chinese direct path editor', 'Windows Shell picker action', 'chosen result to INSTALLDIR UI property',
            'picker then UI property then target path', 'no same-dialog NewDialog',
            'installation-folder uninstall shortcut', 'Start Menu uninstall shortcut');
        Scope = 'Read-only MSI database checks. Does not install, run GUI, or verify a live uninstall.' }
    if ($Report) {
        $Report = [IO.Path]::GetFullPath($Report)
        New-Item -ItemType Directory -Path (Split-Path $Report -Parent) -Force | Out-Null
        [IO.File]::WriteAllText($Report, ($result | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    }
    $result | ConvertTo-Json -Depth 4
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
}
