---
name: osate
description: Validate, instantiate, and analyze AADL models with osate-cli; manage OSATE .project metadata and stateful workspace-server sessions. Use for AADL diagnostics, instance generation, latency, bus-load, or mode-reachability analysis, and OSATE project or workspace setup.
---

# OSATE CLI

Run `osate-cli help` before relying on exact command syntax or output; the CLI may evolve.

## Manage projects locally

Use `osate-cli project` commands to create, inspect, update, and validate Eclipse `.project` metadata. These commands inspect immediate child directories of the current directory. They do not start a workspace server or open a TCP port.

Run `osate-cli project validate` after creating a project or changing dependencies. Treat validation warnings separately from errors.

## Use the workspace server

Treat a live workspace as an ordered set of workspace roots, not as the set of local `.project` directories.

1. Choose a stable client ID for the task. When reconnecting, recover and reuse the existing server's sticky client ID.
2. Run `osate-cli <id> init <root>...` and capture the printed port. `init` reuses a live server recorded under the first root when possible; otherwise it starts one.
3. Use `osate-cli probe -p <port> ping` when the owner is uncertain. `ping` bypasses the sticky-owner check and prints the bound client ID, if any.
4. Run `osate-cli <id> -p <port> list-projects` and verify that the ordered roots match the intended workspace before changing server state.
5. Use that same client ID and port for all subsequent remote commands.

Reuse a compatible server rather than starting another one. Do not change the roots of a reused server with `add-project` or `remove-project` unless the requested task requires that workspace change. Do not send `exit` to a reused server; allow its idle timeout to preserve other work.

`init` binds a local TCP port and may fail in a sandbox. If it fails with a socket-bind or permission error, request permission to run it outside the sandbox, then retry. Do not report OSATE as unavailable based only on a sandbox failure.

## Validate and analyze

Use this usual sequence:

1. Run `update` after adding, removing, or changing AADL files.
2. Run `check` for workspace diagnostics, or `check <file>` to revalidate one file.
3. Run `instantiate <file> <implementation>` only after reviewing source diagnostics.
4. Run the requested analysis against the generated `.aaxl2` instance.

Do not use exit status alone to decide that a remote operation succeeded:

- `check` and `update` exit zero even when their output contains model diagnostics with severity `error`.
- `instantiate` can exit zero while its first output line begins with `Error:`.
- Instantiation and analysis can print model-level `Error:` or `Exception:` results while the protocol succeeds.

For instantiation, require an `Instantiated ... as <instance-name>` first line and confirm that `instances/<instance-name>.aaxl2` exists under the workspace root. For analysis, require the expected `Ran ... analysis ...` first line, confirm every reported output file exists, and summarize all emitted diagnostics. Distinguish successful command execution from a clean model.

Generated analyses write under the instance directory:

- Latency writes `.result` and `.csv` files keyed by its settings.
- Bus load rewrites its single CSV report on a repeated run.
- Mode reachability generates only requested formats and may delete stale report formats that were not selected. Preserve needed formats by requesting them again.

Read the matching reference when interpreting results:

- [Flow latency output](references/flow-latency-output.md)
- [Bus load output](references/bus-load-output.md)
- [Mode reachability output](references/mode-reachability-output.md)
