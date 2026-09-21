<!--
    VSCode extension for AADL

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

    DM26-0821
 -->

# Extension packaging

The extension runs an Eclipse Temurin JRE that ships inside it, so a package is
only valid for one platform. These two scripts are what make that work; Maven
calls them through the `package` and `package:pre-release` npm scripts.

## `scripts/stage-runtime`

Downloads the Temurin JRE for a target and points `../runtime` at it.

```bash
packaging/scripts/stage-runtime                # host platform
packaging/scripts/stage-runtime linux-arm64
packaging/scripts/stage-runtime --host-target  # print the host's target id
packaging/scripts/stage-runtime --all-targets  # print every supported target id
```

`runtime` is a symlink, like `server/aadl/lib`, and packaging dereferences both
with `vsce package --follow-symlinks`. Archives are cached in
`target/temurin-downloads` and unpacked under `target/temurin-runtimes/<target>`,
both at the repository root and outside anything `mvn clean` deletes. Override
them with `TEMURIN_DOWNLOAD_DIR` and `TEMURIN_RUNTIME_DIR`.

The download, checksum verification and unpacking are in
[`scripts/lib/temurin.sh`](../../scripts/lib/temurin.sh), shared with osate-cli
release packaging. macOS archives nest the runtime under `Contents/Home`; the
helper locates `bin/java` and stages the home it finds, so `runtime/bin/java` is
the same path on every platform.

## `scripts/package-vsix`

Packages one VSIX per target, staging each runtime first.

```bash
packaging/scripts/package-vsix                              # host platform
packaging/scripts/package-vsix --target linux-x64 --target win32-x64
AADL_VSIX_TARGETS=all packaging/scripts/package-vsix        # every platform
packaging/scripts/package-vsix --pre-release
```

Two details are deliberate:

- the host target is packaged first, because packaging is the step most likely to
  fail and the host is the cheapest failure to diagnose;
- the host runtime is re-staged at the end, because the test phase runs straight
  after packaging and execs `runtime/bin/java`. A foreign-architecture runtime
  left behind would fail there, a long way from the cause.

`AADL_VSIX_TARGETS` accepts `host`, `all`, or a comma- or space-separated list;
Maven passes `-Dvsce.package.targets` through it.

## Platforms

`darwin-x64`, `darwin-arm64`, `linux-x64`, `linux-arm64`, `win32-x64`,
`win32-arm64`. No untargeted package is published, so clients on any other
platform — Alpine Linux, 32-bit ARM, the web — are offered nothing. Adding one
means adding its Adoptium coordinates in `stage-runtime` and its expected
artifact name to the release workflow's check.
