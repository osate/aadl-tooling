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

- The extension runs the Eclipse Temurin JRE bundled at `runtime/` and nothing
  else. There is no discovery, no `JAVA_HOME` or `PATH` fallback, no dependency
  on `redhat.java`, and no setting that points elsewhere: one supported runtime
  means a user's failure is reproducible. Do not add a fallback.
- `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` are removed from
  the server's environment. They would let a machine inject agents or repoint the
  trust store into the runtime we bundle precisely to control.
- The **JRE** image is enough. It ships `libjdwp`, so the `-agentlib:jdwp` debug
  launch works; do not switch to the ~40 MB larger JDK for it.
- `runtime/` is a symlink that `packaging/scripts/stage-runtime` creates, like
  `server/aadl/lib`. Both reach the VSIX only because packaging passes
  `--follow-symlinks`; keep it.
- The bundled server is launched over stdio using
  `org.osate.aadl.ls.RunAadl2Server`.
- `serverClasspath` builds an explicit classpath from every jar in
  `server/aadl/lib`. The ANTLR 4 runtime bundle, which would collide with the
  ANTLR 3 runtime Xtext parsers link against, is excluded at packaging time by
  `.vscodeignore`. Keep its tests with any classpath change.
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

Unit tests run with no runtime staged, so keep the bundled-runtime helpers pure.

`npm run test:integration` stages the host runtime, then downloads and launches
VS Code, so it needs network access the first time. It runs with
`--disable-extensions`; the suite must never skip itself.

```bash
npm run stage-runtime        # host platform, into runtime/
npm run stage-runtime linux-x64
```

Package the extension with:

```bash
npm run package              # stable, host platform only
npm run package:pre-release  # marked as a pre-release
AADL_VSIX_TARGETS=all npm run package
```

Each VSIX bundles a platform-specific JRE, so there is no universal package:
`vsce package --target` produces `aadl2-<target>-<version>.vsix` for every
supported platform (`packaging/scripts/stage-runtime --all-targets`). A client on
any other platform is offered nothing, because no untargeted fallback is
published. Maven selects the set through `-Dvsce.package.targets` (`host`,
`all`, or a list), which reaches `packaging/scripts/package-vsix` as
`AADL_VSIX_TARGETS`.

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
extension, stages the bundled runtime, and packages `aadl2-*.vsix`. If the server
changed, rebuild its p2 repository before packaging.

`mvn clean` deliberately leaves `runtime/` alone: it is a symlink into a JRE
cache under the repository-root `target/`, and maven-clean deletes the contents
of the link's target even with `followSymlinks=false`. Staging recreates the link
on every build anyway.

## Tests

- Unit tests cover helpers, bundled-runtime resolution, lifecycle behavior,
  server classpath construction, command arguments, symbols, and syntax grammars.
- Integration tests cover manifest contributions, extension discovery, and
  activation/language features. They are unconditional: the runtime ships in the
  extension, so there is nothing left to be absent and nothing to skip for.
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
- The bundled runtime is downloaded through `scripts/lib/temurin.sh`, shared with
  osate-cli packaging. Change the Temurin feature version there, and keep it at or
  above `minimumJavaMajorVersion` in `src/javaRuntime.ts`.
- Verify the generated VSIX rather than assuming a successful TypeScript
  compile proves packaging.

See [README.md](README.md) for user-facing extension behavior and
[../aadl-language-server/AGENTS.md](../aadl-language-server/AGENTS.md) for
server implementation guidance.
