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

# A Command Line Interface for OSATE

## Overview

`osate-cli` is a command-line client for OSATE. Its language operations talk over TCP to a
long-lived **workspace server** that embeds an AADL language server in-process; its local
`project` commands manage Eclipse/OSATE `.project` files without contacting a server.

There is one workspace server per workspace; only one client is bound to a given workspace
server at a time.

The topology for a language-server session has three application components across two
operating-system processes: a short-lived `osate-cli` process and a long-lived workspace server
JVM that runs the AADL LS in-process on a separate thread. Managed mode asks the existing native
user service-manager process to own the latter. Each remote `osate-cli` invocation opens a new TCP
connection, sends one request, reads the response, and exits. Local `project` invocations only
access files in the current workspace.

The client/server protocol is synchronous: the workspace server waits for any
language-server activity triggered by a request to complete before replying. This is
implemented via a custom JSON-RPC `aadlServer/waitUntilFinished` round-trip — see
[`../aadl-language-server/CLAUDE.md`](../aadl-language-server/CLAUDE.md) for the mechanism.

For implementation details (spawn lifecycle, classloader isolation, build-jar layout) see
[`CLAUDE.md`](CLAUDE.md).

### Workspace-server launch mode

`OSATE_CLI_SERVER_LAUNCH` controls who owns a newly started workspace-server JVM:

| Value | Behavior |
| --- | --- |
| `auto` | Default. Use launchd on macOS or the per-user systemd manager on Linux. If discovery or the manager availability check fails, print `WARN detached workspace-server launch unavailable (<reason>); using direct child process` and continue in direct mode. |
| `managed` | Require the native user manager. Unavailability is fatal: `managed workspace-server launch unavailable: <reason>`. A failure after the manager accepts the launch is also fatal and never falls back to direct. |
| `direct` | Start the JVM directly as a child of the `osate-cli` process without probing launchd or systemd. |

Windows and other unsupported systems use direct mode under `auto`; `managed` fails there.
Linux sessions without both `systemd-run` and `systemctl`, or without a reachable systemd user
manager, behave the same way. Values are trimmed and case-insensitive; any value other than
`auto`, `managed`, or `direct` is an argument-independent runtime error.

Managed service identities are stable for a normalized first workspace root:
`org.osate.cli.workspace.<24-hex-hash>` on launchd and
`osate-cli-workspace-<24-hex-hash>.service` on systemd. Both managers are configured for a
one-shot launch with no automatic crash restart.

## Commands

Every command except `help`, `-v|--version`, and the local `project` commands takes a
`<id>` (client identifier) as its first argument. Remote commands additionally take a
`-p|--port <port>` flag; `init` does not.

### help

```
osate-cli help
osate-cli -h | --help
```

Prints usage. The long form (`help`) prints the version banner and the build it came
from, followed by the full description of every command and exit codes; the short form
(`-h`/`--help`) prints just the synopsis lines.

```text
osate-cli 0.1.0
  language server 0.1.0.v20260902-1320 (7fbfec9)
  OSATE           2.19.0.vfinal (4256148)
```

### version

```
osate-cli -v | --version
```

Prints `osate-cli <version>` and exits 0. Exactly one line, with nothing after the
version, so it stays parseable; use `help` for the build provenance.

The version is declared in exactly one place: the `<revision>` property of
`osate-cli/pom.xml`. Maven filters it into `org/osate/cli/version.properties` inside
`osate-cli.jar`, and the packaging scripts read it back out of that jar to version the
release tarballs, `.deb`, `.rpm`, and Homebrew formula. The version reported here is
therefore always the version of the package the CLI was installed from.

### Build provenance

`help` also reports the bundled language server and the OSATE it was built against,
because the CLI's own version says nothing about either, and most behaviour comes from
them:

| Key in `version.properties` | Meaning |
| --- | --- |
| `ls.version` | Bundle version of the bundled language server, e.g. `0.1.0.v20260902-1320` |
| `ls.commit` | Commit of the `osate/aadl-tooling` repository it was built from |
| `osate.version` | OSATE version, e.g. `2.19.0.vfinal` |
| `osate.commit` | The reviewed `osate2` gitlink it was built against |

None of these can be discovered at runtime. OSATE bundles carry independent versions
(`org.osate.aadl2` is 6.1.1, not 2.19.0) and no bundle manifest records a commit, so
`scripts/build-test-release` supplies all four and Maven filters them into the jar
alongside the version.

A build that bypasses that script — a bare `mvn -f osate-cli/pom.xml verify` — reports
`unknown` for all four. That is deliberate, so a hand-built CLI does not claim a
provenance it does not have; `build-release-artifacts.sh` refuses to package a
distribution that reports `unknown`, and also refuses one whose `osate.commit` disagrees
with the current gitlink.

`ls.commit` gains a `-dirty` suffix when the working tree had uncommitted changes, since
the commit alone would otherwise appear to identify code that was not what got compiled.
Packaging warns about it rather than failing, so local packaging tests still work.

Commits are abbreviated to seven characters in `help`; `version.properties` keeps them in
full for anything parsing the jar.

### project

The local project commands treat the current working directory as a workspace. Projects
are its immediate child directories that contain `.project`; a server, client id, and port
are not required.

```
osate-cli project list
osate-cli project create <name> [--depends-on <project>] ... [--adopt]
osate-cli project show <project>
osate-cli project add-dependency <project> <dependency> ...
osate-cli project remove-dependency <project> <dependency> ...
osate-cli project validate
```

`list` prints projects in declared-name order. A project without dependencies is printed as
`<name>`; one with dependencies is printed as `<name> -> <dependency>, ...`.

`create` creates `<cwd>/<name>/.project` with the Xtext builder and the OSATE AADL and
Xtext natures. Project names must be single directory names, and every `--depends-on`
target must already be a uniquely named project in the workspace. The command refuses to
overwrite `.project`. It also refuses an existing directory unless `--adopt` is present.

`show` prints the declared project name, absolute directory, and dependency list.
`add-dependency` and `remove-dependency` edit only the `<projects>` dependency list and are
idempotent. Additions must name existing projects and cannot introduce a direct
self-dependency. Removal accepts stale names so broken references can be cleaned up.

`validate` checks that all project files are well-formed, project names are unique, the
required builder and natures are present, dependencies exist and are not duplicated, and
the dependency graph has no self-reference or cycle. A directory name that differs from
the declared project name is reported as a warning. Errors produce exit 1; warnings alone
produce exit 0.

### init

```
osate-cli <id> init [--timeout <seconds>] [--server-timeout <seconds>] [<root> ...]
```

Starts a workspace server for the given roots, or reuses an existing one (see
[Server lifecycle](#server-lifecycle)). Prints the listening port on stdout.

`--timeout` (default 30) bounds how long the client waits for the server to start.
`--server-timeout` (default 300) is the server's idle timeout. When no root is given the
current working directory is used. Every explicit root must exist and be a directory or
the client exits 2 (argument-parse error).

The server emits the port only **after** the initial workspace build has settled: it waits
for the language server to publish diagnostics for every `.aadl` file under the roots (see
[Initial build barrier](#initial-build-barrier)). As a result, the first command issued
right after `init` — including a workspace-wide `check` — sees complete diagnostics rather
than an empty or partial result.

### exit

```
osate-cli <id> -p <port> exit
```

Stops the workspace server. The server replies `OK`, then shuts the LS down before
terminating its JVM. Subject to the sticky-owner check (see [General](#general)).
Passing any positional argument yields `ERR invalid args: usage: exit` (exit 1).

### ping

```
osate-cli <id> -p <port> ping
```

Liveness check. The status line is always `OK`. The single output line is `OK` if no
client is bound, or `OK <bound-id>` if `<bound-id>` is the sticky owner. `ping` is the
only command that bypasses the sticky-owner check.
Passing any positional argument yields `ERR invalid args: usage: ping` (exit 1).

### check

```
osate-cli <id> -p <port> check [<file>]
```

Validates AADL files. With `<file>`, re-validates that file; without, reports diagnostics
for the whole workspace. Output format is described in [Diagnostics](#diagnostics).

If `<file>` does not resolve under at least one workspace root, the server replies
`ERR invalid args: file not in workspace` and the client exits 1. If it resolves under a
root but does not exist on disk, the server replies `ERR invalid args: no such file: <path>`.
Passing more than one positional argument yields `ERR invalid args: usage: check [<file>]`.

### update

```
osate-cli <id> -p <port> update
```

Incremental rebuild: rescans workspace roots for `*.aadl` files, reports every discovered
file to the language server as changed, waits for the resulting build, and prints diagnostics.
Output format is described in [Diagnostics](#diagnostics).
Passing any positional argument yields `ERR invalid args: usage: update` (exit 1).

### instantiate

```
osate-cli <id> -p <port> instantiate <file> <impl>
```

Instantiates a component implementation. `<impl>` may be the simple AADL name `Impl.impl` or the
fully qualified name `Package::Impl.impl`; matching is case-insensitive and implementations in
either the public or private package section are accepted. The instance model is written to
`<root>/instances/`.

The first line of stdout is `Instantiated <impl> as <instance-name>` on success, or one
of the lookup-failure lines below if the implementation cannot be resolved. Subsequent
lines are instance-anchored diagnostics — see [Diagnostics](#diagnostics).

Lookup-failure lines (returned with status `OK`, exit 0):

- `Error: <uri> does not contain an AADL package`
- `Error: component implementation <impl> not found.`

Argument-form errors (returned with status `ERR`, exit 1):

- `ERR invalid args: usage: instantiate <file> <impl-name>`
- `ERR invalid args: file not in workspace`
- `ERR invalid args: no such file: <path>`

All three analysis commands require the language server's structured analysis result. The
first stdout line is its summary, followed by report paths and instance-anchored diagnostics.
The summary is the highest-severity diagnostic when the analysis reports a warning or error;
otherwise it is the command's completion message. Model-level analysis errors retain protocol
status `OK` and exit code 0, while command execution failures return `ERR` and exit 1. Scripts
should inspect stdout diagnostics as well as the process exit code.

### analyze-latency

```
osate-cli <id> -p <port> analyze-latency <instance.aaxl2> [flags]
```

Runs flow latency analysis on the given instance model and writes a `.result` XML under
`<instance-folder>/reports/latency/`, plus a `.csv` when end-to-end flow results exist. The
first stdout line is the analysis summary; the next lines are the absolute paths of the
generated report files. Subsequent lines are instance-anchored diagnostics
(see [Diagnostics](#diagnostics)) emitted by the analysis itself — for example
"actual latency exceeds max flow latency" or "Assume Periodic dispatch because period
is set".

`<instance.aaxl2>` must be a path that resolves under a workspace root and ends in
`.aaxl2`. Use `instantiate` to create one if needed.

Five flags toggle the analysis parameters one at a time. Defaults match the ones the
VSCode client uses today:

| Flag                   | Effect                                          |
|------------------------|-------------------------------------------------|
| `--sync-system`        | Treat system as synchronous (default: async)    |
| `--no-major-frame`     | Disable major-frame delay (default: enabled)    |
| `--best-case-deadline` | Use best-case deadlines (default: worst case)   |
| `--full-queue`         | Assume full queues (default: best-case empty)   |
| `--disable-queuing`    | Disable queuing latency (default: enabled)      |

Errors:

- Client-side, exit 2 (no `ERR` prefix on stderr): `usage: analyze-latency <file>
  [flags]` unless exactly one positional argument is given, or `unknown flag:
  <flag>` for an unrecognized flag.
- Server-side, exit 1: `ERR invalid args: file not in workspace`,
  `ERR invalid args: not an instance file (.aaxl2): <path>`, and
  `ERR invalid args: no such file: <path>`.

### analyze-bus-load

```
osate-cli <id> -p <port> analyze-bus-load <instance.aaxl2>
```

Runs bus load analysis on the given instance model and writes one CSV report file
under `<instance-folder>/reports/BusLoad/`. The first stdout line is the analysis
summary; the next line is the absolute path of the
CSV file. Subsequent lines are instance-anchored diagnostics (see
[Diagnostics](#diagnostics)) emitted by the analysis itself — for example
missing bus capacity, missing bandwidth budget, or actual bandwidth exceeding budget.

`<instance.aaxl2>` must be a path that resolves under a workspace root and ends in
`.aaxl2`. Use `instantiate` to create one if needed.

Errors:

- Client-side, exit 2 (no `ERR` prefix on stderr): `usage: analyze-bus-load <file>`
  when the wrong number of arguments is passed.
- Server-side, exit 1: `ERR invalid args: file not in workspace`,
  `ERR invalid args: not an instance file (.aaxl2): <path>`, and
  `ERR invalid args: no such file: <path>`.

### analyze-modes

```
osate-cli <id> -p <port> analyze-modes <instance.aaxl2> [--dot] [--html] [--smv]
```

Runs SOM mode reachability analysis on the given instance model. Report files are
opt-in. When requested, reports are written under
`<instance-folder>/reports/som-reachability/`. The first stdout line is the analysis
summary; the next lines are the absolute paths
of generated report files, if any. Subsequent lines are instance-anchored
diagnostics (see [Diagnostics](#diagnostics)) emitted by the analysis itself —
for example, `som_2 is not reachable`.

`<instance.aaxl2>` must be a path that resolves under a workspace root and ends in
`.aaxl2`. Use `instantiate` to create one if needed.

Report flags:

| Flag     | Effect                         |
|----------|--------------------------------|
| `--dot`  | Generate a Graphviz DOT report |
| `--html` | Generate an HTML report        |
| `--smv`  | Generate a NuSMV report        |

Errors:

- Client-side, exit 2 (no `ERR` prefix on stderr): `usage: analyze-modes <file>
  [--dot] [--html] [--smv]` unless exactly one positional argument is given, or
  `unknown flag: <flag>` for an unrecognized flag.
- Server-side, exit 1: `ERR invalid args: file not in workspace`,
  `ERR invalid args: not an instance file (.aaxl2): <path>`, and
  `ERR invalid args: no such file: <path>`.

### add-project

```
osate-cli <id> -p <port> add-project <root>
```

Adds an existing directory to the live workspace, indexes it, and reports diagnostics
in the same format as `update`. The new root participates in subsequent `check`,
`update`, and `instantiate` commands. The per-port session file and workspace marker are
rewritten to reflect the live root set.

`<root>` must be an existing directory; the path is resolved to an absolute, normalized
path on the client. If `<root>` doesn't exist or is not a directory the client exits 2
with `workspace root is not a directory: <path>` on stderr. If `<root>` is already a
workspace root the server replies `ERR invalid args: root already in workspace` and the
client exits 1.

### remove-project

```
osate-cli <id> -p <port> remove-project <root>
```

Removes a directory from the live workspace and prunes diagnostics for files under it.
The remaining workspace's diagnostics are reported in the same format as `update`. The
per-port session file and workspace marker are rewritten to reflect the live root set.

`<root>` is resolved to an absolute, normalized path on the client; the directory does
not need to still exist on disk. The first workspace root cannot be removed (it owns
the marker file at `<root1>/.osate-cli/server.json`); attempting it yields
`ERR invalid args: cannot remove first root`. If `<root>` is not a current workspace
root the server replies `ERR invalid args: root not in workspace`. Both server-side
errors exit 1.

### list-projects

```
osate-cli <id> -p <port> list-projects
```

Prints the current workspace roots in order, one absolute path per line. Read-only:
does not trigger a build and does not change the session file. Passing any positional
argument yields `ERR invalid args: usage: list-projects` (exit 1).

## Protocol

The wire protocol is a line-based text format over TCP/IP on `127.0.0.1` (loopback only).

### Connection model

Each `osate-cli` invocation opens a fresh TCP connection, sends one request line, reads
the response (zero or more output lines, a blank line, then a status line), and closes
the connection. The server closes the connection after writing the status line.

### Request grammar

```
request := <id> SP <command> [SP <arg>]* LF
arg     := <unquoted> | "<quoted>"
```

Arguments containing whitespace, `"`, or `\` are quoted with double quotes. Inside quoted
arguments, `\"` and `\\` are the escape sequences. `<id>` is the client identifier passed
on the command line.

### Response grammar

```
response := <output-line>* LF <status-line> LF
status-line := "OK" | "ERR " <message>
```

Output lines are written in order; a single empty line separates them from the status
line. The status line is consumed by the client and not printed; on `ERR <message>` the
client writes the message to stderr and exits 1.

### Error vocabulary

| Status | Meaning |
| --- | --- |
| `ERR busy: another client is connected` | Sticky-owner check rejected the request |
| `ERR unknown command: <name>` | Server doesn't recognize the command |
| `ERR invalid args: <detail>` | Argument parsing or validation failed |
| `ERR <ExceptionClass>: <message>` | Catch-all for unhandled server exceptions during dispatch |
| `ERR no workspace server is running on port <n>; run init again` | Client-side: connection was refused |
| `ERR connection to workspace server on port <n> was lost; command outcome is unknown; run init again` | Client-side: connection reset after a request may have been sent |

## Diagnostics

`check`, `update`, `instantiate`, `analyze-latency`, `analyze-bus-load`, and
`analyze-modes` all emit diagnostics in a single line per issue.
Severity is rendered lowercase as one of `error`, `warning`, `info`, `hint` (LSP
`Information` → `info`; LSP `Hint` → `hint`). Lines are sorted so that all entries for the
same file are contiguous (sorted by file path, then position); no per-file header is
emitted.

Two layouts share the same suffix `: <severity>: <message>` but differ in the position
they record:

```
<path>:<line>:<col>: <severity>: <message>      # check, update                — source-anchored
<path>:<instance-path>: <severity>: <message>   # instantiate, analyze-* — instance-anchored
```

Source-anchored diagnostics are gcc-style; positions are 1-based (LSP positions are
0-based and converted server-side).

Instance-anchored diagnostics use the component-instance path (e.g. `s`, `proc.thread1`,
or an end-to-end flow path like `s.flow_etef`) in place of `<line>:<col>` because the
issues attach to model elements, not source locations. `<path>` is the saved instance
file (`*.aaxl2`).

For `instantiate`, the first stdout line is the success header `Instantiated <impl> as
<instance-name>`; diagnostics follow on subsequent lines. For every `analyze-*`
command, the first stdout line is the structured result summary. Generated report paths
follow, then diagnostics. Latency always emits a `.result` path and emits a `.csv` path
only when end-to-end flow results exist; bus load emits its `.csv` path; mode reachability
emits paths only for requested report formats.

## Server lifecycle

### Initial build barrier

After sending the LSP `initialized` notification, the workspace server blocks until the
language server has published diagnostics for every `.aadl` file under the workspace roots,
then emits the port handshake. Because the port is the client's signal that the server is
ready, no client command can race the initial build — the first `check`/`update` sees a
fully built workspace.

The barrier waits on observable state (a `publishDiagnostics` notification per file, which
the LS emits even for clean files with an empty diagnostics array) rather than on
`aadlServer/waitUntilFinished`. `waitUntilFinished` resolves on the *next* build edge and
can miss the initial build entirely if that build completes before the request registers;
waiting for diagnostics-present has no such race. If a file fails to publish within 120s
the barrier gives up and starts the server anyway, logging how many files were still
pending.

### Marker file

The workspace server writes `<root1>/.osate-cli/server.json` with the listening port,
JVM process id, the first workspace root, and the full ordered set of workspace roots.
`add-project` and `remove-project` rewrite its root set atomically so it continues to
describe the live workspace.
`osate-cli init` checks for this file before spawning:

- If the marker is live (process exists *and* port responds to a `ping` probe), `init`
  prints the existing port and exits 0 — no new server is started, even if the requested
  roots differ from the marker's (a warning naming the live server's roots is printed to
  stderr).
- If the marker is stale (process gone or port dead), it is removed and a new server is
  started.

The marker is removed on every clean exit (idle timeout, `exit`, JVM shutdown hook). Marker
validation, stale-marker removal, and startup are serialized by
`<root1>/.osate-cli/server.lock`. After acquiring the lock, the client repeats the PID and TCP
checks so concurrent `init` processes cannot start duplicate servers.

The `server.lock` pathname is intentionally retained. The operating-system lock is released
when the client closes it or exits; deleting and recreating the file could allow concurrent
clients to lock different files.

### Session file and unavailable-server cleanup

Independently of the workspace-keyed marker, the client persists a per-port session file
at `~/.osate-cli/sessions/<port>.json` containing port, ordered roots, client-id,
server-timeout, workspace-server PID, start time, `supervisorKind`, and `supervisorId`. The
system property `osate.cli.home` overrides `~`. `supervisorKind` is `direct`, `launchd`, or
`systemd`; managed sessions require the deterministic service ID described above. Session files
written before those two supervisor fields existed remain valid and are interpreted as direct.
Persisted manager IDs are never used for cleanup until they match the ID recomputed from the
first root.

The session file is written by `init` and is deleted on explicit `exit` and idle timeout.
An abrupt process death can leave a stale session behind. `init` removes dead sessions for
the requested first root while it holds `server.lock`, before starting a new server.

When a remote command cannot connect, the client does not restart the workspace server or
retry the request. It attempts stale cleanup before returning an error:

1. Read `~/.osate-cli/sessions/<port>.json`. Missing or corrupt state requires no cleanup.
2. If a valid session exists, take the first-root startup lock and probe the port again.
3. If the recorded process is dead, clean validated native-manager metadata unless a newer
   live server now owns the workspace, then delete stale marker and session state.
4. Print `ERR no workspace server is running on port <n>; run init again` and exit 1.

A connection reset is reported separately because the request may already have taken effect:
`ERR connection to workspace server on port <n> was lost; command outcome is unknown; run init
again`. Cleanup is conservative: live processes, responding ports, and newer live workspace
markers are not disturbed.

### Idle timeout

The server exits when no client request has arrived for `--server-timeout` seconds
(default 300). Before terminating the JVM it sends LSP `shutdown` and `exit` to the
language server and waits briefly for the handshake to complete. The marker and session
files are removed. A later remote command using the old port fails and tells the user to
run `init` again.

For a launchd-owned server, the unloaded process can leave its non-running job registration
present until the next `init`, whose managed launch cleans the deterministic registration
before starting again. A transient systemd unit created with `--collect` is normally
collected after the process exits.

On a successful explicit `exit`, the workspace server deletes the marker and session, and the
client waits briefly for the recorded PID to die before unloading/stopping the validated manager
registration. A sticky-owner rejection does not clean the registration. Cleanup failure is a
warning and does not turn a successful protocol response into a failure.

### Managed-launch limitations

Managed launch keeps the workspace server independent of the shell that invoked `osate-cli`, but
it is not a machine-level daemon and has no persistence guarantee across logout or reboot.
launchd and systemd are explicitly told not to restart a crashed server. The user must run
`init` again after a crash or idle timeout.

On macOS, launchd starts the JVM outside the initiating terminal application's process tree.
macOS privacy/TCC permissions can therefore differ from those granted to Terminal, an IDE, or a
runner. Workspace roots in protected locations may require permission for the Java executable or
the managed process context. Use `direct` when terminal-inherited access is required.

## Workspace server flags

The workspace server (`osate-workspace-server-*.jar`) accepts:

| Flag                       | Default                                       | Meaning                                                      |
|----------------------------|-----------------------------------------------|--------------------------------------------------------------|
| `--server-timeout <sec>`   | 300                                           | Idle timeout                                                 |
| `--client-id <id>`         | (none — required when spawned by the client)  | Sticky owner id from startup                                 |
| `--log <path>`             | `<root1>/.osate-cli/server-<pid>.log`         | Server log file                                              |
| `--port <n>`               | OS-chosen ephemeral port                      | Bind a specific TCP port                                     |
| `--session-base <dir>`     | (none)                                        | Directory holding `<port>.json`; deleted on exit or timeout  |

Positional arguments are workspace roots; the client always supplies at least one, using
its current working directory when none was specified on the command line.

## General

- Exit codes: `osate-cli` exits 0 on success, non-zero on operational errors. Diagnostics
  with severity `error`/`warning`/`info`/`hint` do not change the exit status.
- Workspace-root arguments for `add-project` and `remove-project`, source-file arguments
  for `check` and `instantiate`, and instance-file arguments for `analyze-*` are resolved
  by the client to absolute, normalized filesystem paths. `file://` URIs are not accepted
  as command-line path arguments.
- Local `project` commands do not use the wire protocol and resolve the workspace directly
  from the client's current working directory.
- `<impl>` may be the simple AADL name `Impl.impl` or the fully qualified name
  `Package::Impl.impl` (the same value is passed to the underlying `aadl.instantiate`
  LSP command).
- **Sticky ownership.** `osate-cli init` passes `<id>` to the spawned workspace server via
  `--client-id`, binding the server to that client at startup. Subsequent connections
  from a different `<id>` are rejected with `ERR busy: another client is connected` until
  the server exits. `ping` bypasses this check; all other commands — including `exit` —
  are sticky-gated.
- Loopback only: the workspace server binds `127.0.0.1`. There is no remote-network mode.
- There is no service-discovery mechanism; clients always pass `--port` for remote
  commands.

## Building

Three Maven modules live under the `osate-cli/` directory: `osate-workspace-server`
(long-lived server), `osate-cli` (client), and `dist`, which assembles the runnable layout
(`bin/osate-cli{,.bat}`, `osate-cli.jar`, `lib/osate-workspace-server-*.jar`).

The workspace-server build depends on the Tycho output of `../aadl-language-server`. Run that
once first:

```
mvn -f ../aadl-language-server/pom.xml verify -Dtycho.localArtifacts=ignore -DskipTests
```

Then from `osate-cli/`:

```
mvn package
```

For runtime/architecture detail (classloader isolation, why shading is avoided, embedded
plugin-jar layout) see [`CLAUDE.md`](CLAUDE.md).

## Requirements

- Java 21
- Maven 3.9+
- Existing AADL language server (in `../aadl-language-server/`)
