# AADL Language Server

This directory contains the Java/Xtext language server for the Architecture
Analysis & Design Language (AADL). It is one component of the
[AADL tooling repository](../README.md), alongside the VS Code extension and
command-line interface.

The server depends on OSATE through the pinned repository-root `osate2/` Git
submodule. A tooling revision therefore records the exact OSATE source revision
used to build and test the server.

## Prerequisites

- Git
- JDK 21 or newer
- Maven 3.9 or newer
- Initialized repository-root `osate2/` submodule

For an existing checkout, run this from the repository root:

```bash
git submodule update --init osate2
```

The submodule uses a normal clone from `https://github.com/osate/osate2.git`.
No Git reference repository is configured.

## Build OSATE and the language tooling

Run the complete build from the repository root:

```bash
./scripts/build-test-release
```

The script performs three build phases:

1. Build and test the pinned OSATE source, including its p2 repository.
2. Build and test the language server and package the VS Code extension.
3. Build and verify the CLI reactor.

The language-server p2 repository is written to:

```text
aadl-language-server/releng/org.osate.aadl.ls.repository/target/repository/
```

Build provenance is written to:

```text
target/build-provenance.properties
```

## Rebuild only the language server

After OSATE has been built, run this from the repository root:

```bash
mvn -f aadl-language-server/pom.xml clean verify \
  -Dtycho.localArtifacts=ignore
```

The language-server reactor must be built as a whole; individual bundles cannot
be built independently.

## Update OSATE

Update the submodule deliberately, validate the complete build, and commit the
new gitlink:

```bash
git -C osate2 fetch origin
git -C osate2 checkout <osate-commit>
./scripts/build-test-release
git add osate2 aadl-language-server/pom.xml
```

If the selected OSATE commit changes the OSATE parent version, update the
version in `aadl-language-server/pom.xml` in the same commit.

## Release provenance

Test-release artifacts retain:

- the tooling commit SHA;
- the OSATE submodule commit SHA;
- the expected OSATE gitlink SHA;
- the tooling, OSATE, and CLI Maven project versions; and
- the build timestamp.

The build script rejects a dirty or mismatched OSATE submodule and unexpected
duplicate language-server bundle versions.

## Supported language server commands

The server advertises these commands through LSP `workspace/executeCommand`:

- `aadl.instantiate` — Instantiate an AADL component implementation and write
  the resulting `.aaxl2` instance model.
- `aadl.analyze.latency` — Run flow latency analysis on an instance model.
- `aadl.analyze.busLoad` — Run bus load analysis on an instance model and
  generate its CSV report.
- `aadl.analyze.reachability` — Run SOM mode reachability analysis on an
  instance model, with optional DOT, HTML, and SMV reports.

## Related documentation

- [Repository build and architecture guide](../AGENTS.md)
- [Language-server development guide](AGENTS.md)
- [VS Code extension](../vscode-extension/README.md)
- [CLI command reference](../osate-cli/OSATE-CLI.md)

## Project notices

See [COPYRIGHT](COPYRIGHT), [CONTRIBUTORS](CONTRIBUTORS),
[ACKNOWLEDGEMENTS](ACKNOWLEDGEMENTS), and [LICENSE](LICENSE).
