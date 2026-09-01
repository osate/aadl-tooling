# AADL Tooling

[![CI](https://github.com/osate/aadl-tooling/actions/workflows/ci.yml/badge.svg)](https://github.com/osate/aadl-tooling/actions/workflows/ci.yml)

Language tooling for the Architecture Analysis & Design Language (AADL), built
on OSATE 2.19.0 and Xtext.

This repository contains a reusable language server and two ways to work with
it: a bundled Visual Studio Code extension and a command-line interface.
Together they support interactive AADL editing as well as model instantiation
and latency, bus-load, and mode reachability analyses.

## Choose an interface

| Interface | Use it when | Documentation |
| --- | --- | --- |
| Bundled VS Code extension | You want the normal editor experience and automatic language-server startup | [VS Code extension guide](vscode-extension/README.md) |
| `osate-cli` | You want scriptable validation, instantiation, analysis, or multi-project workspace management | [CLI manual](osate-cli/OSATE-CLI.md) |
| Language server | You are integrating another LSP client or changing the Java/Xtext implementation | [Server development guide](aadl-language-server/AGENTS.md) |

## Repository map

- [`aadl-language-server/`](aadl-language-server/) — the Java/Xtext language server and its
  Tycho repository.
- [`vscode-extension/`](vscode-extension/) — the primary VS Code extension; it
  packages and starts the server.
- [`osate-cli/`](osate-cli/) — the CLI, long-lived workspace server, assembled
  distribution, and release packaging.
- [`osate2/`](osate2/) — the pinned OSATE source submodule used to build the
  parent POM, target platform, and p2 repository consumed by the language server.

The top-level Maven build covers the language server and primary VS Code
extension. The CLI has a separate Maven reactor that consumes the server build.
See the [repository working guide](AGENTS.md) for prerequisites, build commands,
outputs, validation commands, and cross-module constraints.

## Clone and build

Clone with the pinned OSATE source:

```bash
git clone --recurse-submodules https://github.com/osate/aadl-tooling.git
cd aadl-tooling
```

For an existing checkout:

```bash
git submodule update --init osate2
```

Run the complete build:

```bash
./scripts/build-test-release
```

The script verifies the submodule gitlink, builds OSATE first, then builds the
language server and VS Code extension, and finally verifies the CLI reactor.

On Windows, clone with symlink support enabled (`git clone -c core.symlinks=true`
and Developer Mode, or an elevated shell). `vscode-extension/server/aadl/lib` is
a tracked relative symlink to the generated language-server plug-ins, and VSIX
packaging depends on it.

See [CONTRIBUTING.md](CONTRIBUTING.md) for prerequisites and the development
workflow.

## Documentation

- [Repository build and architecture guide](AGENTS.md)
- [Language-server development guide](aadl-language-server/AGENTS.md)
- [VS Code user guide](vscode-extension/README.md)
- [VS Code development guide](vscode-extension/AGENTS.md)
- [CLI overview](osate-cli/README.md)
- [CLI behavior and command reference](osate-cli/OSATE-CLI.md)
- [CLI development guide](osate-cli/AGENTS.md)
- [CLI manual test plan](osate-cli/osate-cli/manual-test.md)
- [CLI release packaging](osate-cli/packaging/README.md)
- [Contributing](CONTRIBUTING.md)
- [Release process](RELEASING.md)
- [Security policy](SECURITY.md)

## License

This repository is licensed per directory: the language server is under the
Eclipse Public License 2.0, and the VS Code extension and `osate-cli` are under
a BSD (SEI)-style license. See [LICENSE.md](LICENSE.md) for the full breakdown
and per-module license and copyright files.
