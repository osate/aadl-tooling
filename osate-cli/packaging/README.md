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

The packages bundle Eclipse Temurin Java 21. The existing Maven `dist` layout
remains the source payload: `osate-cli.jar`, `bin/osate-cli`, and the sibling
`lib/*.jar` language-server plugins stay together on disk.

## Version

The version is declared in exactly one place: the `<revision>` property of
`osate-cli/pom.xml`. Maven filters it into `org/osate/cli/version.properties`
inside `osate-cli.jar`, and the packaging scripts read it back out of the jar they
are packaging. The tarball names, `release.properties`, `.deb`/`.rpm` version,
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
- homepage: empty for now
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

The script downloads the matching Eclipse Temurin 21 JRE from Adoptium, stages it
under `runtime/`, rewrites the Unix launcher to use that bundled runtime, and
writes tarballs under:

```text
osate-cli/packaging/target/artifacts/
```

If `nfpm` is on `PATH`, Linux `.deb` and `.rpm` packages are also built. Use
`--nfpm` to require nFPM, or `--no-nfpm` to skip native Linux packages.

Linux packages install to:

```text
/opt/osate-cli
/usr/bin/osate-cli
```

## Publish a GitHub Release

Releases are automated. Pushing an `osate-cli-v<version>` tag runs
[`.github/workflows/release-osate-cli.yml`](../../.github/workflows/release-osate-cli.yml),
which validates the tag against `<revision>` in `osate-cli/pom.xml`, runs the
script below with `--nfpm`, verifies the artifact set against `SHA256SUMS`,
creates the release, and updates the Homebrew tap. See
[RELEASING.md](../../RELEASING.md).

The rest of this section is the manual fallback.

Build every artifact, requiring nFPM so the Linux `.deb` and `.rpm` packages are
included. Without `--nfpm` a missing nFPM only warns, and four of the eight
packages are silently dropped:

```sh
osate-cli/packaging/scripts/build-release-artifacts.sh --nfpm
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
  osate-cli-*.tar.gz osate-cli*.deb osate-cli*.rpm SHA256SUMS
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
