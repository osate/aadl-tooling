# AGENTS.md

Guidance for the Java/Xtext AADL language server.

## Scope and layout

The server runs without the Eclipse workbench and consumes OSATE 2.19.0 from
the repository-root `../osate2/` submodule.

- `org.osate.aadl.ls/` — launchers, Guice setup, scoping, LSP services, and
  custom commands.
- `plugins/org.osate.aadl.ls.tests/` — JUnit plug-in tests and AADL fixtures.
- `releng/org.osate.aadl.ls.repository/` — packaged p2 repository.
- `releng/aadl.ls.releng/` — Eclipse/Tycho launch configuration.

Do not modify the OSATE submodule during ordinary language-server work.

## Build and tests

The Tycho reactor must be built as a whole; do not invoke individual server
modules. From the repository root:

```bash
mvn -f aadl-language-server/pom.xml clean verify \
  -Dtycho.localArtifacts=ignore
```

For a clean checkout or release-equivalent validation, use
`./scripts/build-test-release`, which builds OSATE first.

Require a nonzero test count in:

```text
plugins/org.osate.aadl.ls.tests/target/surefire-reports/TEST-org.osate.aadl.ls.tests.AllTests.xml
```

Testing conventions:

- Add LSP-level coverage for protocol-visible behavior and unit tests for
  isolated helpers.
- Store substantial AADL fixtures under `test-models/`; do not embed them as
  Java strings.
- Every added or materially changed Java test class must have a class-level
  comment explaining what it tests and why.
- Keep `AllTests` complete so each test runs exactly once.

## Implementation constraints

- Preserve the headless boundary. Do not introduce Eclipse UI/workbench
  dependencies; use EMF `URIConverter` rather than workspace-only file APIs.
- AADL identifiers are case-insensitive. Compare user-supplied names with
  `equalsIgnoreCase` or a consistent normalization.
- `Aadl2LsGlobalScopeProvider` replaces Eclipse container scoping and loads
  contributed/predeclared AADL resources on demand.
- `Aadl2LsResourceServiceProviderRegistry` must process every registered
  `ISetup`; setup ordering must not remove the custom language-server
  extension or `aadlServer/waitUntilFinished`.
- Execute model-reading commands through `ILanguageServerAccess.doRead(...)`
  so they use the live index.
- After a notification that can trigger a build, CLI-facing workflows must
  wait through `aadlServer/waitUntilFinished` before reading diagnostics or
  returning synchronous results.
- Multi-root support depends on `MultiProjectWorkspaceConfigFactory` and
  `.project` dependency parsing; preserve both when changing workspace setup.

## Custom commands

Commands live under `org.osate.aadl.ls.commands` and are registered by
`CommandService`. Current commands are:

- `aadl.instantiate`
- `aadl.analyze.latency`
- `aadl.analyze.busLoad`
- `aadl.analyze.reachability`

When adding or changing a command, update every client that exposes it and add
direct coverage for its server-side behavior. Reuse `CommandUtil` for argument
and URI handling.

## Launching

- `RunAadl2Server.launch` / `RunAadl2Server` — production stdio launcher.
- `Aadl2ServerLauncher.launch` — socket launcher for debugging.
- Set `-Daadl.ls.debug=true` for workspace log files.

See [README.md](README.md) for build outputs, provenance, and supported command
behavior.
