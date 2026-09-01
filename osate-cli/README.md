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

# osate-cli

A command-line interface for OSATE. It validates and instantiates AADL models,
runs latency, bus-load, and mode reachability analyses, and manages
Eclipse/OSATE multi-project workspaces — all without an Eclipse workbench.

Language operations talk over loopback TCP to a long-lived **workspace server**
that embeds the AADL language server in-process, so repeated commands against
the same workspace reuse a warm model index. Local `project` commands manage
`.project` files directly and contact no server.

## Documentation

- [**OSATE-CLI.md**](OSATE-CLI.md) — the command reference and behavioral
  specification: every subcommand, the client/server protocol, launch modes,
  session and marker files, timeouts, and exit-status rules. Start here.
- [AGENTS.md](AGENTS.md) — development guide: module layout, build and test
  commands, runtime invariants, and the change checklist.
- [packaging/README.md](packaging/README.md) — release archives, `.deb`/`.rpm`
  packages, Homebrew formula, and how to publish a GitHub release.
- [osate-cli/manual-test.md](osate-cli/manual-test.md) — the manual test plan
  for behavior that automated tests cannot cover.

## Modules

- `osate-cli/` — the short-lived CLI client, packaged as a shaded JAR.
- `osate-workspace-server/` — the long-lived workspace process; deliberately not
  shaded, because Xtext resource loading requires on-disk `file:` URLs.
- `dist/` — the assembled runnable layout: launcher, client JAR,
  workspace-server JAR, and language-server plug-ins.
- `packaging/` — release archives, native packages, and Homebrew metadata.

## Build

The language-server p2 repository must exist first. From this directory:

```bash
mvn -f ../aadl-language-server/pom.xml verify \
  -Dtycho.localArtifacts=ignore -DskipTests
mvn verify
```

The runnable CLI is then at `dist/target/dist/bin/osate-cli`. Invoke that
launcher rather than a module-local one.

For the full repository build, see the
[repository README](../README.md).

## License

`osate-cli` is licensed under a BSD (SEI)-style license; see
[LICENSE.txt](LICENSE.txt) and [COPYRIGHT](COPYRIGHT). Other directories in this
repository are licensed differently — see [../LICENSE.md](../LICENSE.md).
