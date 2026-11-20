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

# AGENTS.md

Guidance for the OSATE command-line client and workspace server.

## Scope and modules

Read [OSATE-CLI.md](OSATE-CLI.md) before changing commands, protocol grammar,
marker/session behavior, or lifecycle semantics.

- `osate-cli/` — short-lived CLI, packaged as a shaded client JAR.
- `osate-workspace-server/` — long-lived workspace process; deliberately not
  shaded.
- `dist/` — assembled launcher, client JAR, workspace-server JAR, and
  language-server plug-ins.
- `packaging/` — release archives, native packages, and Homebrew metadata.
  See [packaging/README.md](packaging/README.md).

## Build and tests

The server p2 repository must exist first. From this directory:

```bash
mvn -f ../aadl-language-server/pom.xml verify \
  -Dtycho.localArtifacts=ignore -DskipTests
mvn test
mvn verify
```

- `mvn test` runs unit tests.
- `mvn verify` also runs workspace-server and assembled-CLI integration tests
  and requires loopback sockets.
- Set `OSATE_CLI_RUN_MANAGED_IT=true` only for the opt-in launchd/systemd
  ownership smoke test.
- Failsafe tests may skip silently when their enabling artifact properties are
  absent; inspect reports and require nonzero intended test counts.
- Every added or materially changed Java test class must have a class-level
  comment explaining what it tests and why.

Run integration tests and real release packaging in an unrestricted shell; they
bind loopback ports and invoke native service managers and packaging tools.

## Runtime invariants

- The CLI talks only to `127.0.0.1`; do not add remote-network exposure.
- The workspace server embeds the AADL language server in-process using an
  isolated `URLClassLoader` over sibling plug-in JARs in `lib/`.
- Do not shade the workspace server or nest language-server JARs inside it.
  Xtext resource loading requires on-disk `file:` URLs.
- Keep the workspace-server JAR out of the language-server classloader and
  preserve its private Gson arrangement.
- `OSATE_CLI_SERVER_LAUNCH` supports `auto`, `managed`, and `direct`. Native
  manager commands must remain argument lists, never shell strings.
- The server has sticky client ownership from startup. Preserve the documented
  exceptions and cleanup behavior.
- After notifications that can trigger an LSP build, call
  `aadlServer/waitUntilFinished` before reading diagnostics or returning a
  synchronous result.
- Marker, lock, session, timeout, and stale-process behavior is specified in
  `OSATE-CLI.md`; keep code, tests, and that specification synchronized.

## Packaging and versioning

- `dist/target/dist/` is the runnable layout. Invoke
  `dist/target/dist/bin/osate-cli`, not a module-local launcher.
- Keep plug-in exclusions in `dist/pom.xml` synchronized with
  `../vscode-extension/.vscodeignore`.
- The sole CLI version source is `<revision>` in `pom.xml`. Packaging reads the
  version from the built JAR; do not duplicate it in packaging metadata.
- Keep the version a plain release version because RPM `Version` does not
  accept the project's snapshot suffix.
- Release packaging downloads Temurin runtimes and may use nFPM; follow
  `packaging/README.md`.

## Change checklist

- When adding or changing a CLI subcommand, update `ArgParser`, help text,
  `OSATE-CLI.md`, and [osate-cli/manual-test.md](osate-cli/manual-test.md).
- When exposing a new language-server command, update the server and any client
  adapters, then add direct integration coverage.
- Preserve absolute normalized path handling and workspace-root validation.
- Diagnostics do not determine process exit status; only operational failures
  return nonzero.
