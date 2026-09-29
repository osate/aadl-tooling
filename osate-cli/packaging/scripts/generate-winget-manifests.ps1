# OSATE Command Line Interface
#
# Copyright 2026 Carnegie Mellon University.
#
# NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
# FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
# EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
# FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
# MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
# FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
#
# Licensed under a BSD (SEI)-style license, please see LICENSE.txt
# or contact permission@sei.cmu.edu for full terms.
#
# [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
# distribution.  Please see Copyright notice for non-US Government use and distribution.
#
# This Software includes and/or makes use of Third-Party Software each subject to its own license.
#
# DM26-0838

# Write the WinGet manifests for both MSIs. Runs on Windows because the product
# and upgrade codes are read out of the MSIs themselves: WiX generates a new
# ProductCode per build, so there is nothing else to take them from.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$MsiDir,
    # URL prefix the MSIs are published under, e.g. the GitHub release download URL.
    [Parameter(Mandatory)][string]$BaseUrl,
    [string]$ReleaseNotesUrl,
    [string]$OutputDir = "$PSScriptRoot/../target/recipes/winget"
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $IsWindows) { throw 'Reading MSI properties requires Windows and PowerShell 7.' }
$PackageIdentifier = 'OSATE.osate-cli'
$ManifestVersion = '1.12.0'
$MsiDir = (Resolve-Path -LiteralPath $MsiDir).Path
$BaseUrl = $BaseUrl.TrimEnd('/')

$metadata = @{}
foreach ($line in Get-Content -LiteralPath "$PSScriptRoot/../metadata.env") {
    if ($line -match '^([A-Z_]+)="(.*)"$') { $metadata[$Matches[1]] = $Matches[2] }
}
$sums = @{}
foreach ($line in Get-Content -LiteralPath "$MsiDir/SHA256SUMS") {
    if ($line -match '^([a-fA-F0-9]{64})  (.+)$') { $sums[$Matches[2]] = $Matches[1].ToUpperInvariant() }
}

function Invoke-Com($Object, [string]$Member, [Reflection.BindingFlags]$Kind, [object[]]$Arguments) {
    $Object.GetType().InvokeMember($Member, $Kind, $null, $Object, $Arguments)
}

function Get-MsiProperties([string]$Path, [string[]]$Names) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = Invoke-Com $installer 'OpenDatabase' 'InvokeMethod' @($Path, 0)
    $values = @{}
    try {
        foreach ($name in $Names) {
            $view = Invoke-Com $database 'OpenView' 'InvokeMethod' `
                @("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$name'")
            try {
                $null = Invoke-Com $view 'Execute' 'InvokeMethod' $null
                $record = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
                if ($null -eq $record) { throw "$Path has no $name property" }
                $values[$name] = Invoke-Com $record 'StringData' 'GetProperty' @(1)
                $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record)
            } finally {
                $null = Invoke-Com $view 'Close' 'InvokeMethod' $null
                $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
            }
        }
    } finally {
        # Release the database so the MSI is not left locked for the upload step.
        $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
        $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
    }
    return $values
}

# Single-quoted YAML scalars need only embedded quotes doubled.
function Quote([string]$Value) { "'" + $Value.Replace("'", "''") + "'" }

$version = $null
$installers = @()
foreach ($arch in 'x64', 'arm64') {
    $found = @(Get-ChildItem -LiteralPath $MsiDir -Filter "osate-cli-*-windows-$arch.msi")
    if ($found.Count -ne 1) { throw "Expected exactly one $arch MSI in $MsiDir" }
    $msi = $found[0]
    $properties = Get-MsiProperties $msi.FullName 'ProductCode', 'UpgradeCode', 'ProductVersion',
        'ProductName', 'Manufacturer'
    # The manifest's identity must match what the MSI writes to Apps & Features,
    # or WinGet cannot correlate an installed copy for upgrade and uninstall.
    if ($properties.ProductName -ne $metadata.OSATE_CLI_PACKAGE_NAME -or
        $properties.Manufacturer -ne $metadata.OSATE_CLI_VENDOR) {
        throw "$($msi.Name) identifies as $($properties.ProductName) by $($properties.Manufacturer)"
    }
    if ($msi.Name -ne "osate-cli-$($properties.ProductVersion)-windows-$arch.msi") {
        throw "$($msi.Name) carries ProductVersion $($properties.ProductVersion)"
    }
    if ($null -eq $version) { $version = $properties.ProductVersion }
    elseif ($version -ne $properties.ProductVersion) { throw 'The MSIs disagree on the version.' }
    $hash = (Get-FileHash -LiteralPath $msi.FullName -Algorithm SHA256).Hash
    if ($sums[$msi.Name] -ne $hash) { throw "Missing or incorrect SHA256SUMS entry for $($msi.Name)" }
    $installers += @"
- Architecture: $arch
  InstallerUrl: $(Quote "$BaseUrl/$($msi.Name)")
  InstallerSha256: $hash
  ProductCode: $(Quote $properties.ProductCode)
  AppsAndFeaturesEntries:
  - ProductCode: $(Quote $properties.ProductCode)
    UpgradeCode: $(Quote $properties.UpgradeCode)
"@
}

$manifestDir = Join-Path $OutputDir "manifests/o/$($PackageIdentifier.Replace('.', '/'))/$version"
New-Item -ItemType Directory -Force -Path $manifestDir | Out-Null
$header = "# Generated by osate-cli/packaging/scripts/generate-winget-manifests.ps1.`n" +
    "# yaml-language-server: `$schema=https://aka.ms/winget-manifest"

@"
$header.version.$ManifestVersion.schema.json

PackageIdentifier: $PackageIdentifier
PackageVersion: $version
DefaultLocale: en-US
ManifestType: version
ManifestVersion: $ManifestVersion
"@ | Set-Content -LiteralPath "$manifestDir/$PackageIdentifier.yaml" -Encoding utf8NoBOM

@"
$header.installer.$ManifestVersion.schema.json

PackageIdentifier: $PackageIdentifier
PackageVersion: $version
InstallerType: wix
Scope: machine
InstallModes:
- interactive
- silent
- silentWithProgress
# The MSI's folder chooser property; see packaging/windows/osate-cli.wxs.
InstallerSwitches:
  InstallLocation: INSTALLFOLDER="<INSTALLPATH>"
UpgradeBehavior: install
Commands:
- osate-cli
ReleaseDate: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd'))
InstallationMetadata:
  DefaultInstallLocation: '%ProgramFiles%\osate-cli'
Installers:
$($installers -join "`n")
ManifestType: installer
ManifestVersion: $ManifestVersion
"@ | Set-Content -LiteralPath "$manifestDir/$PackageIdentifier.installer.yaml" -Encoding utf8NoBOM

$homepage = $metadata.OSATE_CLI_HOMEPAGE
$locale = @"
$header.defaultLocale.$ManifestVersion.schema.json

PackageIdentifier: $PackageIdentifier
PackageVersion: $version
PackageLocale: en-US
Publisher: $(Quote $metadata.OSATE_CLI_VENDOR)
PublisherUrl: https://www.sei.cmu.edu/
PackageName: $(Quote $metadata.OSATE_CLI_PACKAGE_NAME)
PackageUrl: $homepage
License: BSD (SEI)-style
LicenseUrl: $homepage/blob/HEAD/osate-cli/LICENSE.txt
Copyright: Copyright 2026 Carnegie Mellon University
ShortDescription: $(Quote $metadata.OSATE_CLI_DESCRIPTION)
Moniker: osate-cli
Tags:
- aadl
- architecture
- cli
- model-based-engineering
- osate
"@
if ($ReleaseNotesUrl) { $locale += "`nReleaseNotesUrl: $ReleaseNotesUrl" }
$locale += @"

Documentations:
- DocumentLabel: User guide
  DocumentUrl: $homepage/blob/HEAD/osate-cli/OSATE-CLI.md
ManifestType: defaultLocale
ManifestVersion: $ManifestVersion
"@
$locale | Set-Content -LiteralPath "$manifestDir/$PackageIdentifier.locale.en-US.yaml" -Encoding utf8NoBOM

Write-Host "WinGet manifests: $manifestDir"
