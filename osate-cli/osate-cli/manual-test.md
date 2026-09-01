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

# osate-cli manual test plan

## Setup

```bash
cd <repository-root>
mvn -f aadl-language-server/pom.xml verify -Dtycho.localArtifacts=ignore -DskipTests
mvn -f osate-cli/pom.xml package
export PATH="$PWD/osate-cli/dist/target/dist/bin:$PATH"
WS="$(mktemp -d -t osate-ws-XXXX)"
cp osate-cli/osate-workspace-server/src/test/resources/fixtures/simple-aadl-project/control.aadl "$WS/"
```

## Native supervisor smoke tests (unrestricted shell)

These tests register a launchd job or transient systemd user unit and bind loopback ports. Run
them in an unrestricted shell on macOS, or on Linux with an active systemd user manager; a
sandbox that blocks loopback binds or native service managers will fail them.

### A. Default managed startup and shell independence

Leave `OSATE_CLI_SERVER_LAUNCH` unset to exercise the default `auto` mode:

```bash
unset OSATE_CLI_SERVER_LAUNCH
PORT=$(osate-cli c1 init "$WS")
MARKER="$WS/.osate-cli/server.json"
PID=$(sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p' "$MARKER")
SESSION="$HOME/.osate-cli/sessions/$PORT.json"
KIND=$(sed -n 's/.*"supervisorKind":"\([^"]*\)".*/\1/p' "$SESSION")
SERVICE=$(sed -n 's/.*"supervisorId":"\([^"]*\)".*/\1/p' "$SESSION")
test "$KIND" = launchd -o "$KIND" = systemd
kill -0 "$PID"                             # server survives after the init shell has exited
PPID_VALUE=$(ps -o ppid= -p "$PID" | tr -d ' ')
ps -o command= -p "$PPID_VALUE"            # contains launchd on macOS or systemd on Linux
osate-cli c1 -p "$PORT" ping
PID_AFTER=$(sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p' "$MARKER")
test "$PID" = "$PID_AFTER"                  # later CLI process reused the same JVM
```

If `auto` prints
`WARN detached workspace-server launch unavailable (<reason>); using direct child process`,
the current login does not have a usable native manager; run the strict check below and perform
the managed ownership checks on another supported login session.

### B. Mode parsing, direct mode, and strict managed mode

```bash
osate-cli c1 -p "$PORT" exit

OSATE_CLI_SERVER_LAUNCH=direct osate-cli c1 init "$WS"
# Expect a direct session: "supervisorKind":"direct","supervisorId":""
DIRECT_PORT=$(OSATE_CLI_SERVER_LAUNCH=direct osate-cli c1 init "$WS")
grep -q '"supervisorKind":"direct"' "$HOME/.osate-cli/sessions/$DIRECT_PORT.json"
OSATE_CLI_SERVER_LAUNCH=direct osate-cli c1 -p "$DIRECT_PORT" exit

OSATE_CLI_SERVER_LAUNCH=bogus osate-cli c1 init "$WS"; echo "rc=$?"
# Expect nonzero and exactly:
# OSATE_CLI_SERVER_LAUNCH must be one of auto, managed, direct: bogus
```

In a Windows shell, headless Linux session without a systemd user manager, or another
environment known not to have a supported manager:

```bash
OSATE_CLI_SERVER_LAUNCH=managed osate-cli c1 init "$WS"; echo "rc=$?"
# Expect nonzero:
# managed workspace-server launch unavailable: <reason>
# No direct workspace-server child may remain.
```

### C. Explicit-exit cleanup

```bash
PORT=$(OSATE_CLI_SERVER_LAUNCH=managed osate-cli c1 init "$WS")
MARKER="$WS/.osate-cli/server.json"
PID=$(sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p' "$MARKER")
SESSION="$HOME/.osate-cli/sessions/$PORT.json"
KIND=$(sed -n 's/.*"supervisorKind":"\([^"]*\)".*/\1/p' "$SESSION")
SERVICE=$(sed -n 's/.*"supervisorId":"\([^"]*\)".*/\1/p' "$SESSION")
OSATE_CLI_SERVER_LAUNCH=managed osate-cli c1 -p "$PORT" exit
! kill -0 "$PID" 2>/dev/null
test ! -e "$MARKER"
test ! -e "$SESSION"
if test "$KIND" = launchd; then
  UID_VALUE=$(/usr/bin/id -u)
  ! /bin/launchctl print "gui/$UID_VALUE/$SERVICE"
else
  ! systemctl --user is-active --quiet "$SERVICE"
fi
```

The process, marker, session, and native registration must all be absent/inactive.

### D. Idle timeout requires re-init

```bash
PORT=$(OSATE_CLI_SERVER_LAUNCH=managed osate-cli c1 init --server-timeout 5 "$WS")
MARKER="$WS/.osate-cli/server.json"
OLD_PID=$(sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p' "$MARKER")
sleep 8
! kill -0 "$OLD_PID" 2>/dev/null
test ! -e "$MARKER"
test ! -e "$HOME/.osate-cli/sessions/$PORT.json"
! OSATE_CLI_SERVER_LAUNCH=managed osate-cli c1 -p "$PORT" ping
NEW_PORT=$(OSATE_CLI_SERVER_LAUNCH=managed osate-cli c1 init --server-timeout 5 "$WS")
OSATE_CLI_SERVER_LAUNCH=managed osate-cli c1 -p "$NEW_PORT" exit
```

### E. Server persistence across two separate shell sessions

The first shell session must exit after writing shared state:

```bash
PORT=$(OSATE_CLI_SERVER_LAUNCH=managed osate-cli c2 init "$WS")
PID=$(sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p' "$WS/.osate-cli/server.json")
printf '%s\n%s\n%s\n' "$PORT" "$PID" "$WS" > /tmp/osate-cli-managed-state
```

In a second, separate shell session:

```bash
PORT=$(sed -n '1p' /tmp/osate-cli-managed-state)
PID=$(sed -n '2p' /tmp/osate-cli-managed-state)
WS=$(sed -n '3p' /tmp/osate-cli-managed-state)
kill -0 "$PID"
OSATE_CLI_SERVER_LAUNCH=managed osate-cli c2 -p "$PORT" ping
PID_AFTER=$(sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p' "$WS/.osate-cli/server.json")
test "$PID" = "$PID_AFTER"
OSATE_CLI_SERVER_LAUNCH=managed osate-cli c2 -p "$PORT" exit
```

The second session must reach the same PID even though the first session terminated every
process in its own process tree.

## 1. Usage & help

| # | Command | Expect |
|---|---|---|
| 1.1 | `osate-cli` | exit 2; usage on stderr |
| 1.2 | `osate-cli help` | exit 0; full help on stdout, first line `osate-cli <version>` (Arguments, Commands, Examples) |
| 1.3 | `osate-cli -h` | exit 0; just usage on stdout |
| 1.4 | `osate-cli --help` | exit 0; just usage on stdout |
| 1.4a | `osate-cli -v` | exit 0; `osate-cli <version>` on stdout |
| 1.4b | `osate-cli --version` | exit 0; same output as 1.4a |
| 1.4c | `osate-cli -v` from an installed package | version matches the installed Homebrew/deb/rpm package version (`brew list --versions osate-cli`, `dpkg -s osate-cli`, or `rpm -q osate-cli`) |
| 1.5 | `osate-cli c1 bogus` | exit 2; "missing -p <port>" or unknown-command error |
| 1.6 | `osate-cli c1 -p 1 bogus` | exit 2; "unknown command: bogus" |
| 1.7 | `osate-cli c1 -p abc ping` | exit 2; "-p/--port must be an integer in 1..65535: abc" |
| 1.8 | `osate-cli c1 -p 99999 ping` | exit 2; "-p/--port must be an integer in 1..65535: 99999" |
| 1.9 | `osate-cli c1 init --timeout 0 "$WS"` | exit 2; "--timeout must be a positive integer: 0" |

## 1b. Local project management

```bash
PROJECT_WS="$(mktemp -d -t osate-projects-XXXX)"
(
  cd "$PROJECT_WS"
  osate-cli project create aadl                                  # 1b.1 creates aadl/.project
  osate-cli project create aadl1 --depends-on aadl              # 1b.2 creates dependency
  osate-cli project list                                         # 1b.3 aadl; aadl1 -> aadl
  osate-cli project show aadl1                                   # 1b.4 name, absolute directory, dependency
  osate-cli project add-dependency aadl1 aadl                    # 1b.5 idempotent; unchanged
  osate-cli project remove-dependency aadl1 stale                # 1b.6 idempotent; unchanged
  osate-cli project validate                                     # 1b.7 valid; exit 0
  mkdir adopted
  osate-cli project create adopted 2>&1                           # 1b.8 refuses existing directory; exit 1
  osate-cli project create adopted --adopt                       # 1b.9 creates adopted/.project
  osate-cli project add-dependency aadl missing 2>&1              # 1b.10 project not found; exit 1
  osate-cli project add-dependency aadl aadl1
  osate-cli project validate 2>&1                                 # 1b.11 dependency cycle; exit 1
  osate-cli project remove-dependency aadl aadl1
)
```

## 2. Server lifecycle

```bash
(
  cd "$WS"
  PORT_CWD=$(osate-cli c0 init --server-timeout 30)  # 2.0 omitted root defaults to CWD
  osate-cli c0 -p "$PORT_CWD" exit
)
PORT=$(osate-cli c1 init "$WS")          # 2.1
echo "port=$PORT"                         # numeric, > 1024
cat "$WS/.osate-cli/server.json"          # 2.2 marker present
ls "$WS/.osate-cli/"                      # 2.3 server-<pid>.log present
osate-cli c1 -p "$PORT" ping              # 2.4 → "OK c1"
osate-cli c1 -p "$PORT" ping extra 2>&1   # 2.4b → ERR invalid args: usage: ping; exit 1
PORT2=$(osate-cli c1 init "$WS")          # 2.5 reuse
[ "$PORT" = "$PORT2" ] && echo "reused"   #     same port
WSALT="$(mktemp -d -t osate-wsalt-XXXX)"
cp "$WS/control.aadl" "$WSALT/"
osate-cli c1 init "$WS" "$WSALT" 2>&1      # 2.6 same first root → reuse; extra root differs from
                                           #     marker → "reusing existing server with different
                                           #     workspace roots: [...]" on stderr; same PORT
PID=$(grep -o '"pid":[0-9]*' "$WS/.osate-cli/server.json" | cut -d: -f2)
kill -9 "$PID"                             # 2.7 stale marker: process gone, marker still present
PORT3=$(osate-cli c1 init "$WS")          #     dead-PID detected (not just port probe) → fresh
[ "$PORT" != "$PORT3" ] && echo "respawned after stale marker"
rm -rf "$WSALT"
```

## 3. Sticky client ownership

```bash
# `c1 init` above already bound the server to c1.
osate-cli c2 -p "$PORT" check             # 3.1 → ERR busy: another client is connected; exit 1
osate-cli c2 -p "$PORT" ping              # 3.2 → "OK c1" (ping bypasses sticky; reports bound id)
osate-cli c2 -p "$PORT" exit              # 3.3 → ERR busy; exit 1; server still up
osate-cli c1 -p "$PORT" exit extra 2>&1   # 3.3b → ERR invalid args: usage: exit; server still up
osate-cli c1 -p "$PORT" exit; echo "rc=$?" # 3.4 → rc=0; server stops
osate-cli c1 -p "$PORT" ping 2>&1         # 3.5 → connection refused; exit 1
```

## 4. check / update / instantiate

```bash
PORT=$(osate-cli c1 init "$WS")
# 4.0 initial-build barrier: the FIRST command right after init must return complete
#     diagnostics, not empty. `init` only prints the port once the build has settled.
osate-cli c1 -p "$PORT" check                            # 4.1 workspace-wide diagnostics
osate-cli c1 -p "$PORT" check "$WS/control.aadl"         # 4.2 single-file diagnostics, gcc-style
(
  cd "$WS"
  osate-cli c1 -p "$PORT" check control.aadl             # 4.2b relative path is normalized client-side
  osate-cli c1 -p "$PORT" instantiate control.aadl control.impl # 4.2c relative path and simple impl name work
)
osate-cli c1 -p "$PORT" check /tmp/not-in-ws.aadl        # 4.3 → ERR invalid args: file not in workspace
osate-cli c1 -p "$PORT" check "$WS/missing.aadl"         # 4.3b → ERR invalid args: no such file: <path>
echo "garbage" >> "$WS/control.aadl"
osate-cli c1 -p "$PORT" update                           # 4.4 prints errors for the new garbage line
osate-cli c1 -p "$PORT" update extra 2>&1                # 4.4b → ERR invalid args: usage: update; exit 1
git -C "$WS" checkout -- control.aadl 2>/dev/null \
  || cp osate-cli/osate-workspace-server/src/test/resources/fixtures/simple-aadl-project/control.aadl "$WS/"
osate-cli c1 -p "$PORT" update                           # 4.5 errors gone
osate-cli c1 -p "$PORT" instantiate "$WS/control.aadl" control::control.impl  # 4.6 → instances/ created under $WS
ls "$WS/instances/"
osate-cli c1 -p "$PORT" instantiate                      # 4.7 → ERR invalid args usage; exit 1
```

## 5. Exit code semantics

```bash
osate-cli c1 -p "$PORT" check "$WS/control.aadl"; echo "rc=$?"   # 5.1 rc=0 even with diagnostics
osate-cli c1 -p 1 ping; echo "rc=$?"                              # 5.2 rc=1 (connection refused)
osate-cli; echo "rc=$?"                                           # 5.3 rc=2 (parse error)
```

## 6. Idle timeout

```bash
osate-cli c1 -p "$PORT" exit  # if still running
PORT=$(osate-cli c1 init --server-timeout 5 "$WS")
osate-cli c1 -p "$PORT" ping
sleep 8
test ! -f "$WS/.osate-cli/server.json" && echo "marker cleaned"  # 6.1 marker is removed
test ! -f "$HOME/.osate-cli/sessions/$PORT.json" && echo "session cleaned"  # 6.2 session is removed
```

## 6b. Commands require init after idle timeout

```bash
osate-cli c1 -p "$PORT" exit  # if still running
PORT=$(osate-cli c1 init --server-timeout 5 "$WS")
test -f "$HOME/.osate-cli/sessions/$PORT.json" && echo "session written"  # 6b.1
sleep 8
test ! -f "$HOME/.osate-cli/sessions/$PORT.json" && echo "session cleaned"
osate-cli c1 -p "$PORT" ping 2>&1; echo "rc=$?"   # 6b.2 → ERR ... run init again; rc=1
PORT=$(osate-cli c1 init --server-timeout 5 "$WS")
osate-cli c1 -p "$PORT" check "$WS/control.aadl"; echo "rc=$?"  # 6b.3 diagnostics; rc=0
osate-cli c1 -p "$PORT" exit
```

## 7. Multi-root

```bash
WS2="$(mktemp -d -t osate-ws2-XXXX)"
cp "$WS/control.aadl" "$WS2/"
PORT=$(osate-cli c1 init "$WS" "$WS2")
osate-cli c1 -p "$PORT" check "$WS2/control.aadl"   # 7.1 resolves under second root
ls "$WS/.osate-cli/server.json"                     # 7.2 marker on *first* root only
osate-cli c1 -p "$PORT" exit
```

## 8. Concurrency / framing sanity

```bash
PORT=$(osate-cli c1 init "$WS")
for i in 1 2 3 4 5; do osate-cli c1 -p "$PORT" ping & done; wait   # 8.1 5 × "OK c1"; no garbled output
osate-cli c1 -p "$PORT" exit
```

## 9. Dynamic root management

```bash
WS3="$(mktemp -d -t osate-ws3-XXXX)"
cp "$WS/control.aadl" "$WS3/"
PORT=$(osate-cli c1 init "$WS")
osate-cli c1 -p "$PORT" list-projects                   # 9.1 → one line: $WS
osate-cli c1 -p "$PORT" add-project "$WS3"              # 9.2 → OK; diagnostics for both roots
osate-cli c1 -p "$PORT" list-projects                   #     → two lines: $WS then $WS3
osate-cli c1 -p "$PORT" check "$WS3/control.aadl"       #     → succeeds (file now in workspace)
osate-cli c1 -p "$PORT" add-project "$WS3" 2>&1         # 9.3 → ERR invalid args: root already in workspace; exit 1
osate-cli c1 -p "$PORT" remove-project "$WS3"           # 9.4 → OK; diagnostics for $WS3 gone
osate-cli c1 -p "$PORT" list-projects                   #     → one line: $WS
osate-cli c1 -p "$PORT" remove-project "$WS3" 2>&1      # 9.5 → ERR invalid args: root not in workspace; exit 1
osate-cli c1 -p "$PORT" remove-project "$WS" 2>&1       # 9.6 → ERR invalid args: cannot remove first root; exit 1
osate-cli c1 -p "$PORT" list-projects extra 2>&1        # 9.7 → ERR invalid args: usage: list-projects; exit 1
# 9.8 session file and marker track dynamic roots while the server is live
osate-cli c1 -p "$PORT" add-project "$WS3"
cat "$HOME/.osate-cli/sessions/$PORT.json" | grep -q "$WS3" && echo "session has WS3"
grep -q "$WS3" "$WS/.osate-cli/server.json" && echo "marker has WS3"
osate-cli c1 -p "$PORT" exit
rm -rf "$WS3"
```

## 10. analyze-latency

```bash
PORT=$(osate-cli c1 init "$WS")
osate-cli c1 -p "$PORT" instantiate "$WS/control.aadl" control::control.impl
INST=$(ls "$WS/instances/"*.aaxl2 | head -n1)

osate-cli c1 -p "$PORT" analyze-latency "$INST"               # 10.1 → analysis summary + .result/.csv paths + diagnostics
ls "$WS/instances/reports/latency/"                            # 10.2 → AS-MF-DL-EQ-EQL.result + AS-MF-DL-EQ-EQL.csv (defaults)
osate-cli c1 -p "$PORT" analyze-latency "$INST" \
    --sync-system --best-case-deadline                          # 10.3 → second run; report filename suffix differs (SS instead of AS, BC instead of WC)
osate-cli c1 -p "$PORT" analyze-latency "$WS/control.aadl" 2>&1 # 10.4 → ERR invalid args: not an instance file (.aaxl2): ...
osate-cli c1 -p "$PORT" analyze-latency 2>&1                    # 10.5 → exit 2; usage line on stderr
osate-cli c1 -p "$PORT" exit
```

## 11. analyze-bus-load

```bash
PORT=$(osate-cli c1 init "$WS")
osate-cli c1 -p "$PORT" instantiate "$WS/control.aadl" control::control.impl
INST=$(ls "$WS/instances/"*.aaxl2 | head -n1)

osate-cli c1 -p "$PORT" analyze-bus-load "$INST"            # 11.1 -> analysis summary + .csv path + diagnostics
ls "$WS/instances/reports/BusLoad/"                          # 11.2 -> *_BusLoad.csv report
osate-cli c1 -p "$PORT" analyze-bus-load "$WS/control.aadl" 2>&1 # 11.3 -> ERR invalid args: not an instance file (.aaxl2): ...
osate-cli c1 -p "$PORT" analyze-bus-load 2>&1                # 11.4 -> exit 2; usage line on stderr
osate-cli c1 -p "$PORT" exit
```

## 12. analyze-modes

```bash
PORT=$(osate-cli c1 init "$WS")
osate-cli c1 -p "$PORT" instantiate "$WS/control.aadl" control::control.impl
INST=$(ls "$WS/instances/"*.aaxl2 | head -n1)

osate-cli c1 -p "$PORT" analyze-modes "$INST"                 # 12.1 -> analysis summary + diagnostics, no report paths
osate-cli c1 -p "$PORT" analyze-modes "$INST" --dot --html --smv # 12.2 -> report paths + diagnostics
ls "$WS/instances/reports/som-reachability/"                  # 12.3 -> .dot, .html, and .smv reports
osate-cli c1 -p "$PORT" analyze-modes "$WS/control.aadl" 2>&1 # 12.4 -> ERR invalid args: not an instance file (.aaxl2): ...
osate-cli c1 -p "$PORT" analyze-modes 2>&1                    # 12.5 -> exit 2; usage line on stderr
osate-cli c1 -p "$PORT" exit
```

## Cleanup

```bash
rm -rf "$WS" "$WS2" "$PROJECT_WS"
```

## What to watch for

- Port handshake from `init` is exactly one numeric line on stdout — anything else means LS chatter is leaking past the redirected streams.
- `server-<pid>.log` should contain init banner and (on shutdown) the idle/exit message.
- Source diagnostic lines must be `path:line:col: severity: message` with **lowercase** severity
  and **1-based** positions; instance diagnostics use `instance-file:instance-path: severity: message`.
- `instances/` directory created under the *workspace root that owns the file*, not under cwd.
