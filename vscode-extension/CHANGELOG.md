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

# Changelog

Notable user-facing changes to the AADL2 extension and its bundled language
server are listed by extension release.

## [Unreleased]

### Changed

- Update the bundled OSATE libraries to the 2.21 development version.

## [0.2.1] - 2026-09-25

### Fixed

- Fix the language server failing to start when the workspace contains a package
  rename whose target cannot be resolved, for example a package defined in more
  than one file.

## [0.2.0] - 2026-09-25

### Added

- Add language-server support for embedded Behavior Annex subclauses, including
  completion for annex references using the enclosing AADL classifier's scope.
- Support Rename Symbol for AADL declarations and references in Behavior Annex
  and EMV2, including behavior variables, AADL features used inside annexes, and
  EMV2 propagation points.
- Support Find All References and occurrence highlighting inside Behavior Annex
  and EMV2. Qualified references select the relevant name segment, and annex
  renames include unsaved editor changes.

### Changed

- Update the bundled OSATE libraries from 2.19 to 2.20.
- Bundle an Eclipse Temurin 21 JRE and run the language server with it. The Red
  Hat Java extension is no longer required or used, and no Java installation on
  the machine is consulted.
- Ignore `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, and `JDK_JAVA_OPTIONS` when
  launching the language server so machine-wide Java options do not change the
  bundled runtime's configuration.
- Publish one package per platform: macOS x64/arm64, Linux x64/arm64, and Windows
  x64/arm64. The Marketplace installs the matching one automatically; an
  installation from a downloaded VSIX has to match the platform. Alpine Linux and
  32-bit ARM are not supported.

### Fixed

- Show an actionable error when the language server cannot start, including
  guidance for an unusable runtime or a package built for another platform.
  Keep **AADL2: Restart Language Server** available so startup can be retried.
- Attempt to restore the bundled runtime's executable permissions on macOS and
  Linux when a failed startup probe indicates they may have been lost.

## [0.1.0] - 2026-09-02 (pre-release)

Initial public pre-release, published as a single universal VSIX.

### Added

- Provide AADL editing and validation, code completion, Go to Definition,
  breadcrumbs, outline view, and AadlDoc hover information, with EMV2 support.
- Highlight AADL, EMV2, and Behavior Annex syntax.
- Open pre-declared and plugin-contributed AADL definitions as read-only virtual
  documents, with navigation between contributed definitions.
- Instantiate component implementations and run latency, bus load, and mode
  reachability analyses on instance models. Show analysis summaries in
  notifications and detailed diagnostics and report paths in the language-server
  output channel.
- Add **AADL2: Restart Language Server** to restart without reloading VS Code.
- Provide settings for diagnostic limits, protocol tracing, latency-analysis
  assumptions, and mode-reachability report formats.
- Add the AADL vector mark as the extension icon.

### Requirements

- Require VS Code 1.110 or newer and the Red Hat Java extension's Java 21 or newer
  tooling runtime. Install the Red Hat Java extension automatically as a
  dependency.

### Changed

- Align the extension version with the language server and osate-cli at 0.1.0.

## [0.0.2]

- Update the bundled AADL language server.

[Unreleased]: https://github.com/osate/aadl-tooling/compare/vscode-v0.2.1...main
[0.2.1]: https://github.com/osate/aadl-tooling/releases/tag/vscode-v0.2.1
[0.2.0]: https://github.com/osate/aadl-tooling/releases/tag/vscode-v0.2.0
[0.1.0]: https://github.com/osate/aadl-tooling/releases/tag/vscode-v0.1.0-pre
