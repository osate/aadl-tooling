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

# Install and uninstall through WinGet itself, from the manifests about to be
# submitted. The installer URLs must already be live, so this runs after the
# GitHub release exists. Needs an elevated shell, as on GitHub-hosted runners.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ManifestDir,
    [string]$LogDir = "$PSScriptRoot/../target/windows-test-logs"
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
if (-not $IsWindows) { throw 'This test requires Windows and PowerShell 7.' }
$ManifestDir = (Resolve-Path -LiteralPath $ManifestDir).Path
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogDir = (Resolve-Path -LiteralPath $LogDir).Path

function Invoke-Winget([string[]]$Arguments) {
    & winget @Arguments
    if ($LASTEXITCODE -ne 0) { throw "winget $($Arguments[0]) failed with exit code $LASTEXITCODE" }
}

$version = (Get-Content -Raw -LiteralPath (Get-ChildItem -LiteralPath $ManifestDir -Filter '*.installer.yaml').FullName |
    Select-String -Pattern '(?m)^PackageVersion: (.+)$').Matches[0].Groups[1].Value.Trim()
# Not under TEMP: its 8.3 short name would not match the PATH entry literally.
$installDir = Join-Path $env:SystemDrive "osate-cli winget test $([guid]::NewGuid())"

& winget --version
Invoke-Winget @('validate', '--manifest', $ManifestDir)
Invoke-Winget @('settings', '--enable', 'LocalManifestFiles')
# --location exercises the manifest's InstallLocation switch, not just the default.
Invoke-Winget @('install', '--manifest', $ManifestDir, '--silent', '--accept-package-agreements',
    '--disable-interactivity', '--location', $installDir, '--log', "$LogDir/winget-install.log")
try {
    $launcher = "$installDir/bin/osate-cli.bat"
    if (-not (Test-Path -LiteralPath $launcher)) { throw "WinGet did not install into $installDir" }
    $pathEntries = [Environment]::GetEnvironmentVariable('Path', 'Machine').Split(';') |
        ForEach-Object { $_.TrimEnd('\') }
    if ($pathEntries -notcontains "$installDir\bin") { throw 'The installed bin folder is not on the machine PATH.' }
    $reported = (& $launcher --version | Out-String).Trim()
    if ($reported -ne "osate-cli $version") { throw "Installed launcher reported '$reported'" }
    Write-Host "Passed WinGet install of $version into $installDir"
} finally {
    Invoke-Winget @('uninstall', '--manifest', $ManifestDir, '--silent',
        '--disable-interactivity', '--log', "$LogDir/winget-uninstall.log")
}
if (Test-Path -LiteralPath "$installDir/bin/osate-cli.bat") { throw 'WinGet uninstall left the launcher behind.' }
Write-Host 'Passed WinGet uninstall'
