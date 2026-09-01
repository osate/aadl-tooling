# Contributing

Thanks for your interest in the AADL tooling. This repository holds three
deliverables — the Java/Xtext language server, the VS Code extension, and
`osate-cli` — plus a pinned OSATE submodule they build against.

## Prerequisites

- JDK 21 or newer
- Maven 3.9 or newer
- Git, with symlink support enabled on Windows (see [README.md](README.md))
- Node is installed by the Maven build; you do not need it separately

Initialize the OSATE submodule before building:

```bash
git submodule update --init osate2
```

## Build and test

Run the complete clean build from the repository root:

```bash
./scripts/build-test-release
```

It verifies the submodule gitlink, builds OSATE, builds and tests the language
server and VS Code extension, verifies the CLI, validates packaged runtimes, and
writes `target/build-provenance.properties`.

When OSATE has already been built, the faster loop is:

```bash
mvn verify -Dtycho.localArtifacts=ignore
mvn -f osate-cli/pom.xml verify
```

Run Maven/Tycho builds, CLI integration tests, VS Code integration tests, and
release packaging in an unrestricted shell — they need the Maven/p2 cache,
network access, loopback ports, or native service managers.

## Test expectations

- Add LSP-level coverage for protocol-visible behavior and unit tests for
  isolated helpers.
- Failsafe and plug-in tests can skip silently when an enabling property is
  absent. Inspect the reports and require a nonzero intended test count — for
  the language server, that means
  `aadl-language-server/plugins/org.osate.aadl.ls.tests/target/surefire-reports/TEST-org.osate.aadl.ls.tests.AllTests.xml`.
- Store substantial AADL fixtures under `test-models/` rather than embedding
  them as Java strings.
- Every added or materially changed Java test class needs a class-level comment
  explaining what it tests and why.

## Constraints to respect

- The language server runs headless. Do not introduce Eclipse UI or workbench
  dependencies; use EMF `URIConverter` instead of workspace-only file APIs.
- AADL identifiers are case-insensitive. Compare user-supplied names with
  `equalsIgnoreCase` or a consistent normalization.
- Advance the `osate2/` submodule only to an exact reviewed commit, and update
  `aadl-language-server/pom.xml` in the same commit if the parent version
  changes.
- A protocol-visible command change usually needs coordinated updates to the
  server, the VS Code extension, the CLI workspace server, the documentation,
  and the tests.

The per-module guides carry the detail:

- [aadl-language-server/AGENTS.md](aadl-language-server/AGENTS.md)
- [vscode-extension/AGENTS.md](vscode-extension/AGENTS.md)
- [osate-cli/AGENTS.md](osate-cli/AGENTS.md) and
  [osate-cli/OSATE-CLI.md](osate-cli/OSATE-CLI.md)
- [AGENTS.md](AGENTS.md) for repository-wide invariants

## Submitting changes

1. Open an issue first for anything larger than a bug fix, so the design can be
   discussed before implementation.
2. Work on a branch and keep commits focused. Stage only intended files, run
   `git diff --cached --check`, and read the staged diff before committing.
3. Match the licensing of the directory you touch — new files in
   `aadl-language-server/` carry the EPL-2.0 header, and new files in
   `vscode-extension/` or `osate-cli/` carry the BSD (SEI) header. See
   [LICENSE.md](LICENSE.md).
4. Update the affected documentation in the same change, including
   `osate-cli/OSATE-CLI.md` and `osate-cli/osate-cli/manual-test.md` for CLI
   command changes.
5. Open a pull request describing what you changed, how you validated it, and
   which build commands you ran.

By contributing you agree that your contribution is licensed under the license
of the directory it lands in.
