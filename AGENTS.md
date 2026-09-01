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

It verifies the submodule gitlink, builds OSATE, builds and tests the language
server, tests the VS Code extension, verifies the CLI, validates packaged
runtimes, and writes `target/build-provenance.properties`.

Options: `--skip-osate` reuses an existing OSATE build after checking that both
the p2 repository and the `osate2-platform` target artifact are present;
`--skip-extension-tests` skips the suites that launch VS Code.

`./scripts/assert-test-counts` fails if a required suite reported zero executed
tests. Run it after a build rather than trusting the exit status, because the
integration suites are `@EnabledIf`-gated and vanish silently when the dist
layout is incomplete.

When OSATE has already been built, build the language server before its
consumers, each as its own invocation:

```bash
mvn -f aadl-language-server/pom.xml verify -Dtycho.localArtifacts=ignore
mvn -f vscode-extension/pom.xml verify
mvn -f osate-cli/pom.xml verify
```

Do not build the root reactor with `-T`: the extension reaches the server
plug-ins through a symlink Maven cannot see, so a parallel reactor may package
the VSIX before those plug-ins exist.

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

## Continuous integration

Workflows live in `.github/workflows/`. `build.yml` is a reusable workflow that
owns the OSATE cache and delegates the rest to `scripts/build-test-release`;
`ci.yml` calls it on pull requests and pushes to `main`; the three
`release-*.yml` workflows are triggered by component-prefixed tags. See
[RELEASING.md](RELEASING.md).

- The OSATE build output is cached under a key containing the `osate2` gitlink
  SHA, with **no `restore-keys`** — a p2 repository built from a different OSATE
  commit must never be silently reused. Moving the pin misses the cache and
  rebuilds.
- Actions cache entries are immutable and branch-scoped. A pull request reads
  `main`'s caches but its own are invisible elsewhere, so a pull request that
  bumps the submodule always pays for a full OSATE build. The `prune-osate-cache`
  job on `main` deletes entries for superseded pins.
- Keep build logic in `scripts/build-test-release`, not in YAML, so local and CI
  builds cannot diverge.
- Action majors are pinned to the lowest one that declares `runs.using: node24`,
  which is why the numbers differ: `checkout@v5`, `setup-java@v5`,
  `setup-node@v5`, `cache@v5`, `upload-artifact@v6`, `download-artifact@v7`.
  Older majors still declare `node20` and make the runner emit a deprecation
  warning. Check `action.yml` in the action's repository before bumping.
- The OSATE cache uses `cache/restore` plus an explicit `cache/save` immediately
  after the build. The combined `cache` action saves in a post step that is
  skipped when the job fails, which would throw away a successful OSATE build
  because something later broke.

## Repository invariants

- **The OSATE phase must run `clean install`, never `verify`.** The language
  server is a separate Maven invocation and Tycho resolves the
  `org.osate:osate2-platform` target definition from the Maven local repository,
  which only `install` populates. `verify` leaves the p2 repository usable and the
  target definition missing, so the failure looks unrelated.
- The repository-root `.mvn/` directory is load-bearing: it fixes
  `maven.multiModuleProjectDirectory` so the nested server POM resolves the
  root `osate2/` repository. Never run Maven from another working directory.
- The `osate2/` working tree is needed for every Maven build even when the OSATE
  output is cached: `aadl-language-server/pom.xml` reads its parent POM from
  `../osate2/releng/org.osate.build.main` via `<relativePath>`.
- Advance `osate2/` only to an exact reviewed commit. If its parent version
  changes, update `aadl-language-server/pom.xml` in the same commit.
- `vscode-extension/server/aadl/lib` is a symlink to the generated server p2
  plug-ins. Packaging follows the symlink; do not add a JAR-copy step. Because
  Maven cannot see that edge, the language server must be built in a separate,
  earlier invocation. `vscode-extension/pom.xml` fails at `validate` when the
  plug-ins are missing, which is the only thing standing between a reordered
  build and a VSIX that silently ships no server or a stale one.
- Keep the plug-in exclusion lists in `osate-cli/dist/pom.xml` and
  `vscode-extension/.vscodeignore` synchronized.
- The CLI workspace server loads language-server plug-ins from sibling JARs
  using an isolated `URLClassLoader`. Do not shade it or nest the plug-in JARs.
- Protocol-visible command changes may require coordinated updates to the
  server, VS Code extension, CLI workspace server, documentation, and tests.
