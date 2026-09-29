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

# Build on Windows using WiX 5.0.2 and its UI extension. Both architectures can be
# compiled on x64; each result must be smoke-tested on its native architecture.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ArtifactsDir,
    [string]$OutputDir = "$PSScriptRoot/../target/windows-msi"
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $IsWindows) { throw 'MSI compilation requires Windows and PowerShell 7.' }
$ArtifactsDir = (Resolve-Path -LiteralPath $ArtifactsDir).Path
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$OutputDir = (Resolve-Path -LiteralPath $OutputDir).Path
$version = (Get-Content -Raw -LiteralPath "$ArtifactsDir/VERSION").Trim()
# MSI compares three numeric fields, with these upper bounds. Do not silently
# truncate a qualifier or fourth field and publish misleading upgrade metadata.
if ($version -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$') {
    throw "MSI requires a three-part numeric version, got $version"
}
$parts = $version.Split('.')
if ([long]$parts[0] -gt 255 -or [long]$parts[1] -gt 255 -or [long]$parts[2] -gt 65535) {
    throw "MSI version is out of range: $version"
}

$metadata = @{}
foreach ($line in Get-Content -LiteralPath "$PSScriptRoot/../metadata.env") {
    if ($line -match '^([A-Z_]+)="(.*)"$') { $metadata[$Matches[1]] = $Matches[2] }
}
$sums = @{}
foreach ($line in Get-Content -LiteralPath "$ArtifactsDir/SHA256SUMS") {
    if ($line -match '^([a-fA-F0-9]{64})  (.+)$') { $sums[$Matches[2]] = $Matches[1] }
}
$checksums = @()
foreach ($arch in 'x64', 'arm64') {
    $base = "osate-cli-$version-windows-$arch"
    $zip = "$ArtifactsDir/$base.zip"
    if (-not $sums.ContainsKey("$base.zip") -or
        (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $sums["$base.zip"]) {
        throw "Missing or incorrect SHA256SUMS entry for $base.zip"
    }
    $scratch = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    try {
        Expand-Archive -LiteralPath $zip -DestinationPath $scratch
        $payload = Join-Path $scratch $base
        foreach ($file in 'bin/osate-cli.bat', 'osate-cli.jar', 'runtime/bin/java.exe',
                          'runtime/release', 'COPYRIGHT', 'LICENSE.txt', 'release.properties') {
            if (-not (Test-Path -LiteralPath "$payload/$file" -PathType Leaf)) {
                throw "Incomplete Windows payload: $file"
            }
        }
        $properties = Get-Content -Raw -LiteralPath "$payload/release.properties" | ConvertFrom-StringData
        if ($properties.version -ne $version -or $properties.target -ne "windows-$arch") {
            throw "ZIP metadata disagrees with $base"
        }
        # Read the single version source again at the MSI boundary.
        $jar = [IO.Compression.ZipFile]::OpenRead("$payload/osate-cli.jar")
        try {
            $reader = [IO.StreamReader]::new($jar.GetEntry('org/osate/cli/version.properties').Open())
            try { $jarProperties = $reader.ReadToEnd() | ConvertFrom-StringData }
            finally { $reader.Dispose() }
        } finally { $jar.Dispose() }
        if ($jarProperties.version -ne $version) { throw 'CLI JAR version disagrees with ZIP metadata.' }

        # Display the license shipped inside this payload; no second license
        # text to keep synchronized with LICENSE.txt.
        $license = (Get-Content -Raw -LiteralPath "$payload/LICENSE.txt").
            Replace('\', '\\').Replace('{', '\{').Replace('}', '\}')
        $license = $license -replace '\r?\n', '\par '
        $license = [regex]::Replace($license, '[^\x00-\x7f]', {
            param($match)
            $code = [int][char]$match.Value
            if ($code -gt 32767) { $code -= 65536 }
            "\u${code}?"
        })
        $licenseRtf = "$scratch/license.rtf"
        ('{\rtf1\ansi{\fonttbl{\f0 Segoe UI;}}\f0\fs18 ' + $license + '}') |
            Set-Content -LiteralPath $licenseRtf -Encoding ascii
        & wix build "$PSScriptRoot/../windows/osate-cli.wxs" -arch $arch `
            -d "Version=$version" -d "Vendor=$($metadata.OSATE_CLI_VENDOR)" `
            -d "Homepage=$($metadata.OSATE_CLI_HOMEPAGE)" -b "Payload=$payload" `
            -d "LicenseRtf=$licenseRtf" -ext WixToolset.UI.wixext/5.0.2 `
            -intermediateFolder "$scratch/obj" -out "$OutputDir/$base.msi"
        if ($LASTEXITCODE -ne 0) { throw "WiX failed for $arch with exit code $LASTEXITCODE" }
        $hash = (Get-FileHash -LiteralPath "$OutputDir/$base.msi" -Algorithm SHA256).Hash.ToLowerInvariant()
        $checksums += "$hash  $base.msi"
    } finally {
        if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force }
    }
}
# LF, not Set-Content's CRLF: the release job appends this to the Linux-built
# SHA256SUMS and reads it with awk, which would keep the CR in the file name.
[IO.File]::WriteAllText("$OutputDir/SHA256SUMS", (($checksums -join "`n") + "`n"))
