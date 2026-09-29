<!--
    OSATE Command Line Interface

    Copyright 2026 Carnegie Mellon University.

    NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
    FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
    EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
    FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
    MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
    FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.

    Licensed under a BSD (SEI)-style license, please see LICENSE.txt
    or contact permission@sei.cmu.edu for full terms.

    [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
    distribution.  Please see Copyright notice for non-US Government use and distribution.

    This Software includes and/or makes use of Third-Party Software each subject to its own license.

    DM26-0838
 -->

# osate-cli Packaging

This directory contains release packaging support for:

- macOS Homebrew tap formulas
- Linux `.deb` and `.rpm` packages built with nFPM
- Windows x64 and ARM64 portable ZIPs and MSI installers built with WiX
- WinGet manifests for the MSIs, published as `OSATE.osate-cli`

The packages bundle Eclipse Temurin Java 21. The existing Maven `dist` layout
remains the source payload: `osate-cli.jar`, the platform launcher under `bin/`, and the sibling
`lib/*.jar` language-server plugins stay together on disk.

## Version

The version is declared in exactly one place: the `<revision>` property of
`osate-cli/pom.xml`. Maven filters it into `org/osate/cli/version.properties`
inside `osate-cli.jar`, and the packaging scripts read it back out of the jar they
are packaging. The archive names, `release.properties`, `.deb`/`.rpm`/`.msi` version,
Homebrew formula version, and `osate-cli -v` therefore always agree.

To release a new version, bump `<revision>`, rebuild the dist layout, and run the
packaging scripts. Keep it a plain release version — rpm forbids `-` in `Version`,
so a `-SNAPSHOT` suffix will not survive packaging.

`build-release-artifacts.sh` records the resolved version in
`packaging/target/artifacts/VERSION` so `generate-homebrew-formula.sh` can version
the formula without the dist tree. Pass `--expect-version <version>` to the builder
to fail the build if the dist does not carry the version you expect.

## Metadata

The remaining package metadata lives in `metadata.env`:

- maintainer email: `info@osate.org`
- vendor: `CMU/SEI`
- license: `LicenseRef-BSD-SEI` (SPDX reference to the BSD (SEI)-style license;
  it has no SPDX-listed identifier)
- homepage: `https://github.com/osate/aadl-tooling`
- Java runtime: Eclipse Temurin feature version `21`

Verify the license metadata before publishing public packages.

## Build Inputs

Build the server and `osate-cli` dist layout first:

```sh
mvn -f aadl-language-server/pom.xml verify -Dtycho.localArtifacts=ignore -DskipTests
mvn -f osate-cli/pom.xml package
```

The packaging script expects:

```text
osate-cli/dist/target/dist/
  osate-cli.jar
  bin/osate-cli
  bin/osate-cli.bat
  lib/*.jar
```

## Build Release Artifacts

```sh
osate-cli/packaging/scripts/build-release-artifacts.sh
```

Default targets:

- `macos-x64`
- `macos-arm64`
- `linux-x64`
- `linux-arm64`
- `windows-x64`
- `windows-arm64`

The script downloads the matching Eclipse Temurin 21 JRE from Adoptium, stages it
under `runtime/`, writes a launcher that uses that bundled runtime, and creates
Unix `.tar.gz` archives and Windows `.zip` archives under:

```text
osate-cli/packaging/target/artifacts/
```

The download, checksum verification against Adoptium's published digest, and
unpacking live in `scripts/lib/temurin.sh`, shared with VS Code extension
packaging, which bundles the same runtimes. `TEMURIN_FEATURE_VERSION` in
`metadata.env` still pins the version used here.

If `nfpm` is on `PATH`, Linux `.deb` and `.rpm` packages are also built. Use
`--nfpm` to require nFPM, or `--no-nfpm` to skip native Linux packages.

Linux packages install to:

```text
/opt/osate-cli
/usr/bin/osate-cli
```

## Windows packages

Each ZIP contains `bin/osate-cli.bat`, the CLI and server JARs, the matching
Temurin JRE, and the license notices. Extract the whole ZIP and run
`bin\osate-cli.bat`; Java does not need to be installed separately. Keep the
runtime and JARs together. Windows uses direct workspace-server launch mode.

To build just the Windows archives on macOS or Linux (requires `zip` and `unzip`):

```sh
osate-cli/packaging/scripts/build-release-artifacts.sh \
  --target windows-x64 --target windows-arm64 --no-nfpm
```

To turn those archives into MSI installers, copy the artifacts directory
(including `VERSION` and `SHA256SUMS`) to a Windows checkout. With PowerShell 7
and the .NET 8 SDK, install the pinned WiX tool and UI extension, then run:

```powershell
dotnet tool install --global wix --version 5.0.2
wix extension add -g WixToolset.UI.wixext/5.0.2
./osate-cli/packaging/scripts/build-windows-msi.ps1 `
  -ArtifactsDir osate-cli/packaging/target/artifacts
```

The script verifies both ZIP checksums and their versions, then builds
`osate-cli-<version>-windows-x64.msi` and
`osate-cli-<version>-windows-arm64.msi` under `packaging/target/windows-msi/`,
with their own `SHA256SUMS`. MSI versions must have three numeric fields within
Windows Installer's limits: `0..255`, `0..255`, and `0..65535`.

The installer offers a folder chooser. The default is
`C:\Program Files\osate-cli`, but users can select another location. It installs
for all users (requires elevation), adds the selected `bin` directory to the
machine PATH, and provides standard Windows upgrade and uninstall support.
Upgrades remember the selected folder. Open a new terminal after installation
so it sees the updated PATH.

Silent installation also accepts a custom location, for example in cmd.exe:

```bat
msiexec /i "osate-cli-0.3.0-windows-x64.msi" /qn /norestart INSTALLFOLDER="D:\Tools\OSATE CLI"
```

Stop active CLI workspace servers with `osate-cli <id> -p <port> exit` before
upgrading or uninstalling so Windows can replace the bundled JARs and runtime.

## WinGet

Once a release's manifests are merged into
[`microsoft/winget-pkgs`](https://github.com/microsoft/winget-pkgs), users can
install and upgrade with:

```powershell
winget install --id OSATE.osate-cli
winget install osate-cli --location "D:\Tools\OSATE CLI"
winget upgrade --id OSATE.osate-cli
```

The manifests use the MSIs, not the ZIPs, so a WinGet installation gets the same PATH
entry, upgrade behavior and Apps & Features registration as a manual MSI install.
`--location` maps to the MSI's `INSTALLFOLDER` property.

`generate-winget-manifests.ps1` writes the version, installer and default-locale
manifests (schema 1.12.0) under
`packaging/target/recipes/winget/manifests/o/OSATE/osate-cli/<version>/`. It runs
on Windows because it reads each MSI's ProductCode and UpgradeCode out of the
MSI itself. WiX assigns a new ProductCode every build, so the manifests must come
from the very MSIs that are published. The generator also refuses MSIs whose
name, manufacturer or version disagree with `metadata.env` and their file name,
because WinGet matches installed copies by those Apps & Features entries.

```powershell
./osate-cli/packaging/scripts/generate-winget-manifests.ps1 `
  -MsiDir osate-cli/packaging/target/windows-msi `
  -BaseUrl https://github.com/osate/aadl-tooling/releases/download/osate-cli-v<version>
winget validate --manifest osate-cli/packaging/target/recipes/winget/manifests/o/OSATE/osate-cli/<version>
```

Every CI and release build generates and validates the manifests next to the MSIs.
After the GitHub release exists, the release workflow calls
[`publish-winget.yml`](../../.github/workflows/publish-winget.yml). It downloads
the published MSIs and regenerates the manifests from them, then runs
`test-winget-manifest.ps1`, which installs from the manifests through WinGet into
a custom location, checks the PATH entry and version, and uninstalls. Only then
does it submit the manifests with `wingetcreate submit`, which opens a pull
request against `microsoft/winget-pkgs`. Microsoft's validation pipeline and
moderators still have to approve that pull request before `winget install` sees
the new version. WinGet on GitHub-hosted runners is available only on x64, so
the ARM64 manifest entry is validated but not installed through WinGet. Its MSI
is still covered by the native ARM64 smoke test.

To submit a release that is already published, for example one that predates
WinGet support or whose pull request was closed, dispatch the same workflow from
`main`. Pass `-f submit=false` to generate and install-test without opening a
pull request:

```sh
gh workflow run publish-winget.yml --repo osate/aadl-tooling -f version=0.3.0
```

## Packaging tests

The offline regression suite needs Python 3, Bash, `zip`, `unzip`, and `tar`:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s osate-cli/packaging/tests -v
```

CI and releases compile both MSIs on Windows and then test each ZIP and MSI on
its native Windows architecture. The test installs into a custom directory
containing spaces, verifies PATH registration, runs version and error-exit
checks plus `init`, `ping`, `check`, and `instantiate` using bundled Java, and
verifies uninstall and PATH restoration. On an elevated PowerShell 7 terminal:

```powershell
./osate-cli/packaging/scripts/test-windows-package.ps1 `
  -Zip osate-cli/packaging/target/artifacts/osate-cli-0.3.0-windows-x64.zip `
  -Msi osate-cli/packaging/target/windows-msi/osate-cli-0.3.0-windows-x64.msi
```

Use matching ARM64 files on ARM64 Windows. Installer logs are saved under
`packaging/target/windows-test-logs/`. The interactive folder chooser should also
be checked manually, including installing and upgrading in a nondefault folder.

## Publish a GitHub Release

Releases are automated. Pushing an `osate-cli-v<version>` tag runs
[`.github/workflows/release-osate-cli.yml`](../../.github/workflows/release-osate-cli.yml),
which validates the tag against `<revision>` in `osate-cli/pom.xml`, runs the
archive builder with `--nfpm`, builds and tests the Windows MSIs, verifies the
complete artifact set against `SHA256SUMS`, creates the release, updates the
Homebrew tap, and submits the WinGet manifests. See
[RELEASING.md](../../RELEASING.md).

The rest of this section is the manual fallback.

Build every artifact, requiring nFPM so the Linux `.deb` and `.rpm` packages are
included. Without `--nfpm` a missing nFPM only warns, and the four native Linux
packages are omitted:

```sh
osate-cli/packaging/scripts/build-release-artifacts.sh --nfpm
```

Build and test the MSIs on Windows as described above, then copy the two `.msi`
files into `packaging/target/artifacts/` and append their checksum file to the
archive checksum file. Verify that all twelve packages are present:

```sh
osate-cli/packaging/scripts/verify-release-artifacts.sh
```

Create the release for the tag and attach the artifacts. `VERSION` is an internal
handoff to the formula generator, not a release asset:

```sh
cd osate-cli/packaging/target/artifacts
version=$(cat VERSION)
gh release create "osate-cli-v$version" \
  --repo osate/aadl-tooling \
  --title "osate-cli $version" \
  --notes "osate-cli $version" \
  osate-cli-*.tar.gz osate-cli-*.zip osate-cli-*.msi \
  osate-cli*.deb osate-cli*.rpm SHA256SUMS
```

To add or replace an artifact on an existing release:

```sh
gh release upload "osate-cli-v$version" \
  --repo osate/aadl-tooling --clobber \
  osate-cli/packaging/target/artifacts/SHA256SUMS
```

To verify a Linux package, download it from the release and install it directly:

```sh
gh release download "osate-cli-v$version" --repo osate/aadl-tooling --pattern '*_amd64.deb'
sudo apt-get install ./osate-cli_"$version"_amd64.deb
osate-cli --help
```

## Smoke Test

Run the packaged CLI smoke test in an unrestricted shell. It starts the
workspace server, which binds a loopback port; a sandbox that blocks that bind
fails with `java.net.SocketException: Operation not permitted`.

On macOS arm64:

```sh
version=$(cat osate-cli/packaging/target/artifacts/VERSION)
pkg=osate-cli/packaging/target/staging/osate-cli-$version-macos-arm64/bin/osate-cli
"$pkg" --version   # must print "osate-cli $version"
fixture=osate-cli/osate-workspace-server/src/test/resources/fixtures/simple-aadl-project
tmp=$(mktemp -d)
workspace="$tmp/simple-aadl-project"
cp -R "$fixture" "$workspace"
port=$("$pkg" smoke init --timeout 90 --server-timeout 30 "$workspace")
"$pkg" smoke -p "$port" check
"$pkg" smoke -p "$port" ping
"$pkg" smoke -p "$port" exit
```

## Generate Homebrew Formula

For local testing, generate a formula inside an ignored local tap under
`target/`:

```text
osate-cli/packaging/target/local-homebrew/Formula/osate-cli.rb
```

It points at local `file://` artifact URLs and is not suitable for publishing.
Homebrew requires formulae to be installed from a tap, so prepare the local tap
and install from that tap:

```sh
osate-cli/packaging/scripts/prepare-local-homebrew-tap.sh
brew tap osate/local "file://$PWD/osate-cli/packaging/target/local-homebrew"
brew install osate/local/osate-cli
brew test osate/local/osate-cli
```

After publishing the macOS tarballs, generate the formula with the URL prefix
where those tarballs are hosted. For a GitHub release created as above, that is
the release's download prefix:

```sh
version=$(cat osate-cli/packaging/target/artifacts/VERSION)
osate-cli/packaging/scripts/generate-homebrew-formula.sh \
  --base-url "https://github.com/osate/aadl-tooling/releases/download/osate-cli-v$version"
```

The generated formula is written to:

```text
osate-cli/packaging/target/recipes/homebrew/Formula/osate-cli.rb
```

Copy that formula into the separate Homebrew tap repository during release.
