# AGENTS.md

Guidance for working in the AADL tooling repository.

## Repository layout

- [`aadl-language-server/`](aadl-language-server/) — Java/Xtext language
  server and Tycho p2 repository. Read
  [aadl-language-server/AGENTS.md](aadl-language-server/AGENTS.md).
- [`vscode-extension/`](vscode-extension/) — TypeScript VS Code extension that
  packages and launches the server. Read
  [vscode-extension/AGENTS.md](vscode-extension/AGENTS.md).
- [`osate-cli/`](osate-cli/) — CLI, long-lived workspace server, assembled
  distribution, and release packaging. Read
  [osate-cli/AGENTS.md](osate-cli/AGENTS.md) and
  [osate-cli/OSATE-CLI.md](osate-cli/OSATE-CLI.md).
- `osate2/` — pinned `osate/osate2` Git submodule. Treat it as a separate
  repository and read `osate2/AGENTS.md` before making explicitly requested
  OSATE changes.

The root Maven reactor contains the language server and VS Code extension. The
CLI uses a separate Maven reactor and consumes the generated server repository.

## Build and validation

Requirements are JDK 21+, Maven 3.9+, Git, and an initialized OSATE submodule:

```bash
git submodule update --init osate2
```

Run the complete clean build from the repository root:

```bash
./scripts/build-test-release
```

It verifies the submodule gitlink, builds OSATE, builds/tests the language
server and VS Code extension, verifies the CLI, validates packaged runtimes,
and writes `target/build-provenance.properties`.

When OSATE has already been built:

```bash
mvn verify -Dtycho.localArtifacts=ignore
mvn -f osate-cli/pom.xml verify
```

Important outputs:

- `aadl-language-server/releng/org.osate.aadl.ls.repository/target/repository/`
- `vscode-extension/aadl2-*.vsix`
- `osate-cli/dist/target/dist/`

Run Maven/Tycho builds, CLI integration tests, VS Code integration tests, and
release packaging in an unrestricted shell because they may need the Maven/p2
cache, network access, loopback ports, or native service managers. Extension
unit tests and type checks have no such requirements.

Before committing, stage only intended files, run `git diff --cached --check`,
inspect the staged diff, and preserve unrelated user changes.

## Repository invariants

- The repository-root `.mvn/` directory is load-bearing: it fixes
  `maven.multiModuleProjectDirectory` so the nested server POM resolves the
  root `osate2/` repository.
- Advance `osate2/` only to an exact reviewed commit. If its parent version
  changes, update `aadl-language-server/pom.xml` in the same commit.
- `vscode-extension/server/aadl/lib` is a symlink to the generated server p2
  plug-ins. Packaging follows the symlink; do not add a JAR-copy step.
- Keep the plug-in exclusion lists in `osate-cli/dist/pom.xml` and
  `vscode-extension/.vscodeignore` synchronized.
- The CLI workspace server loads language-server plug-ins from sibling JARs
  using an isolated `URLClassLoader`. Do not shade it or nest the plug-in JARs.
- Protocol-visible command changes may require coordinated updates to the
  server, VS Code extension, CLI workspace server, documentation, and tests.
