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

# Exercise the actual ZIP and MSI on their native Windows architecture, including
# a custom installation directory, bundled Java, server lifecycle and PATH cleanup.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Zip,
    [Parameter(Mandatory)][string]$Msi,
    [string]$LogDir = "$PSScriptRoot/../target/windows-test-logs"
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
if (-not $IsWindows) { throw 'This smoke test requires Windows and PowerShell 7.' }
$Zip = (Resolve-Path -LiteralPath $Zip).Path
$Msi = (Resolve-Path -LiteralPath $Msi).Path
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogDir = (Resolve-Path -LiteralPath $LogDir).Path
$scratch = Join-Path ([IO.Path]::GetTempPath()) ("osate package test " + [guid]::NewGuid())
$previousPath = $env:PATH
$previousLaunch = $env:OSATE_CLI_SERVER_LAUNCH
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$installed = $false
$installer = "$env:SystemRoot/System32/msiexec.exe"

function Invoke-Cli([string]$Launcher, [string[]]$Arguments, [int]$ExpectedExit = 0) {
    $output = & $Launcher @Arguments
    if ($LASTEXITCODE -ne $ExpectedExit) {
        throw "CLI exit $LASTEXITCODE, expected ${ExpectedExit}: $Arguments"
    }
    return ($output -join "`n").Trim()
}

function Test-Payload([string]$Payload, [string]$Label) {
    $properties = Get-Content -Raw -LiteralPath "$Payload/release.properties" | ConvertFrom-StringData
    $nativeArch = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
    if ($properties.target -ne "windows-$nativeArch") { throw "Wrong target for this runner: $($properties.target)" }
    $launcher = "$Payload/bin/osate-cli.bat"
    if ((Invoke-Cli $launcher @('--version')) -ne "osate-cli $($properties.version)") {
        throw 'Packaged launcher reported the wrong version.'
    }
    $null = Invoke-Cli $launcher @() 2
    $workspace = Join-Path $scratch "$Label workspace"
    Copy-Item -LiteralPath "$PSScriptRoot/../../osate-workspace-server/src/test/resources/fixtures/simple-aadl-project" `
        -Destination $workspace -Recurse
    $port = Invoke-Cli $launcher @('packaging', 'init', '--timeout', '90', '--server-timeout', '30', $workspace)
    if ($port -notmatch '^\d+$' -or [int]$port -lt 1 -or [int]$port -gt 65535) {
        throw "init did not return a valid port: $port"
    }
    $marker = Get-Content -Raw -LiteralPath "$workspace/.osate-cli/server.json" | ConvertFrom-Json
    $serverProcess = Get-Process -Id $marker.pid
    try {
        if ((Invoke-Cli $launcher @('packaging', '-p', $port, 'ping')) -ne 'OK packaging') {
            throw 'Packaged workspace server did not answer ping.'
        }
        $diagnostics = Invoke-Cli $launcher @('packaging', '-p', $port, 'check')
        if ($diagnostics -match '(?m): (error|warning): ') { throw "Unexpected diagnostics: $diagnostics" }
        $null = Invoke-Cli $launcher @('packaging', '-p', $port, 'instantiate', "$workspace/control.aadl", 'control::control.impl')
        if (-not (Test-Path -LiteralPath "$workspace/instances/control_control_impl_Instance.aaxl2")) {
            throw 'Instantiation did not produce an instance model.'
        }
    } finally {
        $null = Invoke-Cli $launcher @('packaging', '-p', $port, 'exit')
        # Shutdown is asynchronous. Wait for the JVM to release its JARs before MSI removal.
        if (-not $serverProcess.WaitForExit(30000)) { throw 'Workspace server did not stop.' }
        $serverProcess.Dispose()
    }
    Write-Host "Passed ${Label}: version, exit code, init, ping, check and instantiate"
}

try {
    Expand-Archive -LiteralPath $Zip -DestinationPath $scratch
    $payload = Join-Path $scratch ([IO.Path]::GetFileNameWithoutExtension($Zip))
    # A successful launch must not depend on Java from the CI machine's PATH.
    $env:PATH = "$env:SystemRoot/System32;$env:SystemRoot"
    $env:OSATE_CLI_SERVER_LAUNCH = 'direct'
    Test-Payload $payload 'ZIP'

    $installDir = Join-Path $scratch 'Custom OSATE CLI (test)'
    $process = Start-Process -FilePath $installer -Wait -PassThru -ArgumentList `
        "/i `"$Msi`" /qn /norestart INSTALLFOLDER=`"$installDir`" /l*v `"$LogDir/install.log`""
    if ($process.ExitCode -notin 0, 3010) { throw "MSI install failed: $($process.ExitCode)" }
    $installed = $true
    $pathEntries = [Environment]::GetEnvironmentVariable('Path', 'Machine').Split(';') |
        ForEach-Object { $_.TrimEnd('\') }
    if ($pathEntries -notcontains "$installDir\bin") { throw 'MSI did not add the chosen folder to PATH.' }
    Test-Payload $installDir 'MSI'
} finally {
    $env:PATH = $previousPath
    $env:OSATE_CLI_SERVER_LAUNCH = $previousLaunch
    if ($installed) {
        $process = Start-Process -FilePath $installer -Wait -PassThru -ArgumentList `
            "/x `"$Msi`" /qn /norestart /l*v `"$LogDir/uninstall.log`""
        if ($process.ExitCode -notin 0, 3010) { throw "MSI uninstall failed: $($process.ExitCode)" }
        if (Test-Path -LiteralPath "$installDir/bin/osate-cli.bat") { throw 'Uninstall left the launcher behind.' }
        $pathAfter = [Environment]::GetEnvironmentVariable('Path', 'Machine')
        if (@(Compare-Object $machinePath.Split(';') $pathAfter.Split(';')).Count -ne 0) {
            throw 'Uninstall did not restore the original machine PATH entries.'
        }
        Write-Host 'Passed MSI uninstall and PATH restoration'
    }
    if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force }
}
