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

# AGENTS.md

Guidance for the TypeScript VS Code extension.

## Runtime contract

- The extension requires `redhat.java` and launches the server with that
  extension's tooling JRE. Java 21 or newer is required; do not add a fallback
  to another Java installation.
- The bundled server is launched over stdio using
  `org.osate.aadl.ls.RunAadl2Server`.
- `server/aadl/lib` is a symlink to the generated language-server p2 plug-ins.
  VSIX packaging must continue to use `--follow-symlinks`.
- `serverClasspath` builds an explicit classpath, excludes the incompatible
  standalone ANTLR runtime bundle, and fails if `antlr-runtime-4.4.jar` is
  absent. Keep its tests with any classpath change.
- Reuse the existing file watcher and language-client lifecycle when
  implementing restart behavior.

## Build and tests

From this directory:

```bash
npm install
npm run check-types
npm run lint
npm run test:unit
```

`npm run test:integration` downloads/launches VS Code and may install
`redhat.java`, so it needs network access. `npm test` runs unit and integration
tests.

Package the extension with:

```bash
npm run package              # stable
npm run package:pre-release  # marked as a pre-release
```

The pre-release marker is written into the VSIX manifest at package time, and
`vsce publish` refuses to publish a package as a pre-release unless it was built
as one, so it cannot be added later. `vscode-extension/pom.xml` selects the script
through the `vsce.package.script` property, which is how a tagged release drives
it. See [../RELEASING.md](../RELEASING.md).

For a server plus extension build, first build OSATE, then run these from the
repository root in this order:

```bash
mvn -f aadl-language-server/pom.xml verify -Dtycho.localArtifacts=ignore
mvn -f vscode-extension/pom.xml verify
```

The two invocations are deliberate. `server/aadl/lib` is a symlink, so Maven has
no dependency edge from this module to the server and a single reactor build with
`-T` can package the VSIX before the plug-ins exist. This module fails at
`validate` if they are missing.

The Maven build installs pinned Node, runs `npm install`, compiles the
extension, and packages `aadl2-*.vsix`. If the server changed, rebuild its p2
repository before packaging.

## Tests

- Unit tests cover helpers, Java selection, lifecycle behavior, server
  classpath construction, command arguments, symbols, and syntax grammars.
- Integration tests cover manifest contributions, extension discovery, and
  activation/language features when `redhat.java` is available.
- Test output is compiled through `tsconfig.test.json` into `out/test/` and is
  excluded from the VSIX.

## Commands and configuration

Client commands are registered in `src/extension.ts` and declared in
`package.json`:

- `aadl2.instantiate` → `aadl.instantiate`
- `aadl2.analyze.latency` → `aadl.analyze.latency`
- `aadl2.analyze.busLoad` → `aadl.analyze.busLoad`
- `aadl2.analyze.reachability` → `aadl.analyze.reachability`
- `aadl2.restart` — client-only restart

When changing commands or settings:

- update `package.json`, `src/extension.ts`, and relevant tests;
- update the server and CLI when the protocol surface changes;
- use the existing result-presentation helpers for reports and diagnostics;
- keep latency and reachability defaults aligned with server defaults.

## Packaging and versioning

- `package.json` is the authoritative extension version; update
  `package.json` and `package-lock.json` together.
- Record user-facing changes in `CHANGELOG.md`.
- Keep `.vscodeignore` synchronized with `osate-cli/dist/pom.xml` when
  changing bundled plug-in exclusions.
- Verify the generated VSIX rather than assuming a successful TypeScript
  compile proves packaging.

See [README.md](README.md) for user-facing extension behavior and
[../aadl-language-server/AGENTS.md](../aadl-language-server/AGENTS.md) for
server implementation guidance.
