/*******************************************************************************
 * OSATE Command Line Interface
 *
 * Copyright 2026 Carnegie Mellon University.
 *
 * NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
 * FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
 * EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
 * FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
 * MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
 * FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * Licensed under a BSD (SEI)-style license, please see LICENSE.txt
 * or contact permission@sei.cmu.edu for full terms.
 *
 * [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
 * distribution.  Please see Copyright notice for non-US Government use and distribution.
 *
 * This Software includes and/or makes use of Third-Party Software each subject to its own license.
 *
 * DM26-0838
 ******************************************************************************/
package org.osate.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses osate-cli command-line arguments. Local project commands start with {@code project};
 * all other commands start with the client id and then either {@code init [...] [<root>...]}
 * or {@code -p <port> <command> [args...]}.
 */
public final class ArgParser {

	public static final Set<String> REMOTE_COMMANDS = Set.of(
			"check", "update", "instantiate", "analyze-latency", "analyze-bus-load", "analyze-modes", "exit", "ping",
			"add-project", "remove-project", "list-projects");

	private static final Set<String> ROOT_PATH_COMMANDS = Set.of("add-project", "remove-project");

	private ArgParser() {
	}

	public record Args(
			String clientId,
			String command,
			int port,
			int timeoutSec,
			int serverTimeoutSec,
			List<Path> roots,
			List<String> commandArgs) {
	}

	public static Args parse(String[] argv) {
		if (argv.length == 0) {
			throw new IllegalArgumentException(usage());
		}
		if ("help".equals(argv[0])) {
			return new Args("", "help", 0, 0, 0, List.of(), List.of());
		}
		if (isShortHelpFlag(argv[0])) {
			return new Args("", "usage", 0, 0, 0, List.of(), List.of());
		}
		if (isVersionFlag(argv[0])) {
			return new Args("", "version", 0, 0, 0, List.of(), List.of());
		}
		if ("project".equals(argv[0])) {
			return parseProject(argv);
		}
		if (argv.length < 2) {
			throw new IllegalArgumentException(usage());
		}
		var clientId = argv[0];
		var rest = new ArrayList<String>(argv.length - 1);
		for (int i = 1; i < argv.length; i++) {
			rest.add(argv[i]);
		}

		if ("init".equals(rest.get(0))) {
			return parseInit(clientId, rest.subList(1, rest.size()));
		}
		return parseRemote(clientId, rest);
	}

	private static Args parseProject(String[] argv) {
		if (argv.length < 2) {
			throw new IllegalArgumentException(projectUsage());
		}
		var commandArgs = new ArrayList<String>(argv.length - 1);
		for (int i = 1; i < argv.length; i++) {
			commandArgs.add(argv[i]);
		}
		var subcommand = commandArgs.get(0);
		switch (subcommand) {
			case "list", "validate" -> {
				if (commandArgs.size() != 1) {
					throw new IllegalArgumentException("usage: project " + subcommand);
				}
			}
			case "show" -> {
				if (commandArgs.size() != 2) {
					throw new IllegalArgumentException("usage: project show <project>");
				}
			}
			case "create" -> validateProjectCreateArgs(commandArgs);
			case "add-dependency", "remove-dependency" -> {
				if (commandArgs.size() < 3) {
					throw new IllegalArgumentException(
							"usage: project " + subcommand + " <project> <dependency>...");
				}
			}
			default -> throw new IllegalArgumentException("unknown project command: " + subcommand);
		}
		return new Args("", "project", 0, 0, 0, List.of(), List.copyOf(commandArgs));
	}

	private static void validateProjectCreateArgs(List<String> args) {
		if (args.size() < 2) {
			throw new IllegalArgumentException(
					"usage: project create <name> [--depends-on <project>]... [--adopt]");
		}
		if (args.get(1).startsWith("--")) {
			throw new IllegalArgumentException(
					"usage: project create <name> [--depends-on <project>]... [--adopt]");
		}
		for (int i = 2; i < args.size(); i++) {
			var arg = args.get(i);
			if ("--adopt".equals(arg)) {
				continue;
			}
			if ("--depends-on".equals(arg)) {
				if (++i >= args.size() || args.get(i).startsWith("--")) {
					throw new IllegalArgumentException("--depends-on requires a value");
				}
				continue;
			}
			throw new IllegalArgumentException("unknown flag: " + arg);
		}
	}

	private static boolean isShortHelpFlag(String s) {
		return "-h".equals(s) || "--help".equals(s);
	}

	private static boolean isVersionFlag(String s) {
		return "-v".equals(s) || "--version".equals(s);
	}

	private static Args parseInit(String clientId, List<String> rest) {
		int timeout = 30;
		int serverTimeout = 300;
		var roots = new ArrayList<Path>();
		for (int i = 0; i < rest.size(); i++) {
			var a = rest.get(i);
			switch (a) {
				case "--timeout" -> {
					if (i + 1 >= rest.size()) {
						throw new IllegalArgumentException("--timeout requires a value");
					}
					timeout = parsePositiveInt(rest.get(++i), "--timeout");
				}
				case "--server-timeout" -> {
					if (i + 1 >= rest.size()) {
						throw new IllegalArgumentException("--server-timeout requires a value");
					}
					serverTimeout = parsePositiveInt(rest.get(++i), "--server-timeout");
				}
				default -> {
					var p = Path.of(a).toAbsolutePath().normalize();
					if (!Files.isDirectory(p)) {
						throw new IllegalArgumentException("workspace root is not a directory: " + p);
					}
					roots.add(p);
				}
			}
		}
		if (roots.isEmpty()) {
			roots.add(Path.of("").toAbsolutePath().normalize());
		}
		return new Args(clientId, "init", 0, timeout, serverTimeout, List.copyOf(roots), List.of());
	}

	private static Args parseRemote(String clientId, List<String> rest) {
		int port = -1;
		var commandArgs = new ArrayList<String>();
		String command = null;
		int i = 0;
		while (i < rest.size()) {
			var a = rest.get(i);
			if (("-p".equals(a) || "--port".equals(a)) && command == null) {
				if (i + 1 >= rest.size()) {
					throw new IllegalArgumentException(a + " requires a value");
				}
				port = parsePort(rest.get(++i));
				i++;
			} else if (command == null) {
				command = a;
				i++;
			} else {
				commandArgs.add(a);
				i++;
			}
		}
		if (command == null) {
			throw new IllegalArgumentException(usage());
		}
		if (port <= 0) {
			throw new IllegalArgumentException("missing -p <port>");
		}
		if (!REMOTE_COMMANDS.contains(command)) {
			throw new IllegalArgumentException("unknown command: " + command);
		}
		if (ROOT_PATH_COMMANDS.contains(command)) {
			if (commandArgs.size() != 1) {
				throw new IllegalArgumentException("usage: " + command + " <root>");
			}
			var p = Path.of(commandArgs.get(0)).toAbsolutePath().normalize();
			if ("add-project".equals(command) && !Files.isDirectory(p)) {
				throw new IllegalArgumentException("workspace root is not a directory: " + p);
			}
			commandArgs.set(0, p.toString());
		}
		if (("check".equals(command) && commandArgs.size() == 1)
				|| ("instantiate".equals(command) && commandArgs.size() == 2)) {
			commandArgs.set(0, Path.of(commandArgs.get(0)).toAbsolutePath().normalize().toString());
		}
		if ("analyze-latency".equals(command)) {
			commandArgs = parseAnalyzeLatencyArgs(commandArgs);
		} else if ("analyze-bus-load".equals(command)) {
			commandArgs = parseAnalyzeBusLoadArgs(commandArgs);
		} else if ("analyze-modes".equals(command)) {
			commandArgs = parseAnalyzeModesArgs(commandArgs);
		}
		return new Args(clientId, command, port, 0, 0, List.of(), List.copyOf(commandArgs));
	}

	private static ArrayList<String> parseAnalyzeLatencyArgs(List<String> raw) {
		String file = null;
		boolean asynchronousSystem = true;
		boolean majorFrameDelay = true;
		boolean worstCaseDeadline = true;
		boolean bestCaseEmptyQueue = true;
		boolean disableQueuingLatency = false;
		for (var a : raw) {
			switch (a) {
				case "--sync-system" -> asynchronousSystem = false;
				case "--no-major-frame" -> majorFrameDelay = false;
				case "--best-case-deadline" -> worstCaseDeadline = false;
				case "--full-queue" -> bestCaseEmptyQueue = false;
				case "--disable-queuing" -> disableQueuingLatency = true;
				default -> {
					if (a.startsWith("--")) {
						throw new IllegalArgumentException("unknown flag: " + a);
					}
					if (file != null) {
						throw new IllegalArgumentException(
								"usage: analyze-latency <file> [flags]");
					}
					file = a;
				}
			}
		}
		if (file == null) {
			throw new IllegalArgumentException("usage: analyze-latency <file> [flags]");
		}
		var out = new ArrayList<String>(6);
		out.add(Path.of(file).toAbsolutePath().normalize().toString());
		out.add(Boolean.toString(asynchronousSystem));
		out.add(Boolean.toString(majorFrameDelay));
		out.add(Boolean.toString(worstCaseDeadline));
		out.add(Boolean.toString(bestCaseEmptyQueue));
		out.add(Boolean.toString(disableQueuingLatency));
		return out;
	}

	private static ArrayList<String> parseAnalyzeBusLoadArgs(List<String> raw) {
		if (raw.size() != 1) {
			throw new IllegalArgumentException("usage: analyze-bus-load <file>");
		}
		var out = new ArrayList<String>(1);
		out.add(Path.of(raw.get(0)).toAbsolutePath().normalize().toString());
		return out;
	}

	private static ArrayList<String> parseAnalyzeModesArgs(List<String> raw) {
		String file = null;
		boolean generateDot = false;
		boolean generateHtml = false;
		boolean generateSmv = false;
		for (var a : raw) {
			switch (a) {
				case "--dot" -> generateDot = true;
				case "--html" -> generateHtml = true;
				case "--smv" -> generateSmv = true;
				default -> {
					if (a.startsWith("--")) {
						throw new IllegalArgumentException("unknown flag: " + a);
					}
					if (file != null) {
						throw new IllegalArgumentException(
								"usage: analyze-modes <file> [--dot] [--html] [--smv]");
					}
					file = a;
				}
			}
		}
		if (file == null) {
			throw new IllegalArgumentException("usage: analyze-modes <file> [--dot] [--html] [--smv]");
		}
		var out = new ArrayList<String>(4);
		out.add(Path.of(file).toAbsolutePath().normalize().toString());
		out.add(Boolean.toString(generateDot));
		out.add(Boolean.toString(generateHtml));
		out.add(Boolean.toString(generateSmv));
		return out;
	}

	private static int parsePositiveInt(String value, String name) {
		int n;
		try {
			n = Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(name + " must be a positive integer: " + value);
		}
		if (n <= 0) {
			throw new IllegalArgumentException(name + " must be a positive integer: " + value);
		}
		return n;
	}

	private static int parsePort(String value) {
		int n;
		try {
			n = Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("-p/--port must be an integer in 1..65535: " + value);
		}
		if (n < 1 || n > 65535) {
			throw new IllegalArgumentException("-p/--port must be an integer in 1..65535: " + value);
		}
		return n;
	}

	public static String usage() {
		return """
				usage:
				  osate-cli help | -h | --help
				  osate-cli -v | --version
				  osate-cli project <command> [args...]
				    commands: list | create | show | add-dependency | remove-dependency | validate
				  osate-cli <id> init [--timeout <s>] [--server-timeout <s>] [<root>...]
				  osate-cli <id> -p <port> <command> [args...]
				    commands: ping | check [<file>] | update | instantiate <file> <impl>
				              | analyze-latency <instance.aaxl2> [flags]
				              | analyze-bus-load <instance.aaxl2>
				              | analyze-modes <instance.aaxl2> [--dot] [--html] [--smv]
				              | add-project <root> | remove-project <root> | list-projects | exit""";
	}

	private static String projectUsage() {
		return "usage: project <list|create|show|add-dependency|remove-dependency|validate> [args...]";
	}

	/** Version banner printed by {@code -v}/{@code --version} and atop the help text. */
	public static String versionLine() {
		return "osate-cli " + Version.get();
	}

	public static String help() {
		return versionLine() + "\n\n" + usage() + "\n" + """

				osate-cli is a command-line client for the OSATE AADL language server. Remote
				commands talk to a long-lived workspace server (one per workspace) over a TCP
				loopback connection. The local 'project' commands manage .project files under
				the current working directory without starting or contacting a server.

				Arguments for language-server commands:
				  <id>          Client identifier required by init and remote commands.
				                'osate-cli <id> init' passes <id> to the spawned workspace server,
				                binding it to that client at startup; other
				                ids are rejected with 'ERR busy: another client is connected'
				                until the server exits. The 'ping' command bypasses this check.
				  -p, --port    TCP port of a running workspace server (printed by 'init').
				                Required for remote commands only; omit it for help, project,
				                and init commands.

				Workspace-server launch:
				  OSATE_CLI_SERVER_LAUNCH may be auto, managed, or direct. It defaults to auto;
				  auto uses a supported native manager when available and otherwise falls back to
				  a direct child process with a warning. managed makes manager unavailability fatal.

				Remote response framing:
				  Each remote request gets back zero or more output lines, then a blank line, then a
				  status line ('OK' or 'ERR <reason>'). The status line is consumed by the
				  client and is NOT printed on stdout: on success nothing extra is written; on
				  'ERR <reason>' the line goes to stderr and the client exits 1. Output lines
				  always go to stdout. See 'Exit codes' below.

				Local commands
				--------------

				help
				  Synopsis:  osate-cli help
				             osate-cli -h | --help
				  Output:    This help text (long form), or the synopsis lines (short form).
				             The long form starts with the version banner.
				  Example:   osate-cli help

				version
				  Synopsis:  osate-cli -v | --version
				  Output:    'osate-cli <version>'. The version is the one of the installed
				             package (Homebrew, deb, rpm, or release tarball), because the
				             packages are versioned from this same value.
				  Example:   osate-cli --version

				project list
				  Synopsis:  Discover immediate child directories of the current working directory
				             that contain a regular .project file.
				  Output:    One declared project name per line, sorted by name. A project with
				             dependencies is printed as '<name> -> <dependency>, ...'. An empty
				             workspace produces no output and exits 0.

				project create <name> [--depends-on <project>]... [--adopt]
				  Synopsis:  Create <cwd>/<name>/.project with the OSATE AADL and Xtext natures
				             and the Xtext builder.
				  Args:      --depends-on  Add a dependency on an existing declared project name;
				                           repeat the option for multiple dependencies.
				             --adopt       Add .project to an existing <cwd>/<name> directory.
				  Output:    'Created <absolute-path>/.project'.
				  Errors:    Refuses unsafe names, missing or self-dependencies, duplicate project
				             names, existing .project files, and existing directories without
				             --adopt. It never overwrites an existing .project file.

				project show <project>
				  Output:    The declared name, absolute directory, and dependencies, with labels
				             'Name:', 'Directory:', and 'Dependencies:'.

				project add-dependency <project> <dependency>...
				project remove-dependency <project> <dependency>...
				  Synopsis:  Add or remove dependency entries in <project>/.project. Operations
				             are idempotent. Added dependencies must name existing projects;
				             removal also accepts stale dependency names.
				  Output:    'Updated dependencies for <project>' when the file changes, otherwise
				             'Dependencies unchanged for <project>'.

				project validate
				  Synopsis:  Check .project XML, unique names, the dependency section, required
				             builder and natures, dependency targets, duplicates, self-dependencies,
				             and cycles. Having no projects is an error. A directory/name mismatch
				             is a warning.
				  Output:    On success, 'Valid workspace: <n> project(s), <n> warning(s)' on
				             stdout. Issues are printed to stderr as 'ERROR ...' or 'WARNING ...'.
				             Validation errors add a final count on stderr and exit 1.

				Project command failures:
				  Invalid command syntax is printed to stderr without an 'ERR ' prefix and exits 2.
				  Discovery, XML, name, and dependency operation failures are printed to stderr as
				  'ERR <reason>' and exit 1. Project commands inspect immediate child directories
				  only and do not follow directory or .project symbolic links.

				Language-server commands
				------------------------
				The instantiate and analysis operations pass language-server result text through
				to stdout. Model-level 'Error:' or 'Exception:' results can therefore accompany an
				'OK' protocol status and exit 0; scripts should inspect their stdout.

				init [--timeout <s>] [--server-timeout <s>] [<root>...]
				  Synopsis:  Start a workspace server for one or more workspace roots, or reuse
				             a live one already recorded in <root1>/.osate-cli/server.json.
				  Args:      <root>           Existing directory. When omitted, use the current
				                              working directory.
				             --timeout        Seconds the client waits for the server to start
				                              (default 30).
				             --server-timeout Seconds the server stays idle before exiting
				                              (default 300).
				  Output:    The server's listening port on stdout (one line, decimal integer).
				  Errors:    Exit 2 if any <root> is not an existing directory, an option value
				             is missing, or a timeout is not a positive integer. Startup and I/O
				             failures are prefixed with 'ERR ' and exit 1.
				  Example:   osate-cli c1 init /path/to/workspace
				             # -> 54321

				ping
				  Synopsis:  Liveness check. Bypasses the sticky-owner check.
				  Output:    A single output line on stdout: 'OK' if no client is currently
				             bound, 'OK <bound-id>' if <bound-id> is the sticky owner. The
				             status line is always 'OK'.
				  Errors:    'ERR invalid args: usage: ping' if any positional argument is
				             passed (exit 1).
				  Example:   osate-cli c1 -p 54321 ping
				             # stdout: OK c1

				check [<file>]
				  Synopsis:  Validate AADL files. With <file>, re-validate that file; without,
				             report diagnostics for the whole workspace.
				  Args:      <file> is resolved to an absolute, normalized filesystem path
				             on the client.
				  Output:    One diagnostic per line, gcc-style:
				               <path>:<line>:<col>: <severity>: <message>
				             where <severity> is one of error|warning|info|hint. Lines are
				             sorted by file, then line, then column. No diagnostics produces no
				             stdout; the protocol's 'OK' status line is not printed.
				  Errors:    'ERR invalid args: file not in workspace' when <file> does not
				             resolve under any workspace root.
				             'ERR invalid args: no such file: <path>' when <file> resolves
				             under a root but does not exist.
				             'ERR invalid args: usage: check [<file>]' when more than one
				             positional argument is passed.
				             All exit 1.
				  Example:   osate-cli c1 -p 54321 check /path/to/workspace/control.aadl

				update
				  Synopsis:  Incremental rebuild: rescan workspace roots for *.aadl files,
				             rebuild changed resources, print diagnostics.
				  Output:    Same diagnostic format as 'check'.
				  Errors:    'ERR invalid args: usage: update' if any positional argument is
				             passed (exit 1).
				  Example:   osate-cli c1 -p 54321 update

				instantiate <file> <impl>
				  Synopsis:  Instantiate a component implementation. The instance model is
				             written under <root>/instances/.
				  Args:      <file>  AADL file containing the implementation. Resolved to an
				                     absolute, normalized filesystem path on the client; must
				                     contain an AADL package.
				             <impl>  Implementation name. Either the simple form 'Impl.impl'
				                     or the fully qualified form 'Package::Impl.impl' (nested
				                     packages: 'Pkg::Sub::Impl.impl'). Matching is case-
				                     insensitive (AADL identifiers are case-insensitive).
				                     The implementation may be declared in the package's
				                     public or private section.
				  Output:    First line:  'Instantiated <impl> as <instance-name>' on success,
				                          or one of the lookup-failure lines below.
				             Following lines: instance-anchored diagnostics, gcc-style:
				               <instance-file.aaxl2>:<instance-path>: <severity>: <message>
				             where <instance-path> (e.g. 's', 'proc.thread1') replaces line/col
				             because the issue attaches to a model element, not a source span.
				  Lookup failures (status 'OK', exit 0 — wrappers must inspect the first stdout
				  line, not the exit code):
				             'Error: <uri> does not contain an AADL package'
				             'Error: component implementation <impl> not found.'
				  Errors:    'ERR invalid args: usage: instantiate <file> <impl-name>' when
				             the wrong number of arguments is passed.
				             'ERR invalid args: file not in workspace' when <file> does not
				             resolve under any workspace root.
				             'ERR invalid args: no such file: <path>' when <file> resolves
				             under a root but does not exist.
				             All exit 1.
				  Example:   osate-cli c1 -p 54321 instantiate control.aadl control::control.impl
				             # stdout:
				             #   Instantiated control::control.impl as control_control_Instance

				analyze-latency <instance.aaxl2> [flags]
				  Synopsis:  Run flow latency analysis on an instance model. Writes a
				             '.result' XML and a '.csv' file under
				             <instance-folder>/reports/latency/ and prints their absolute
				             paths.
				  Args:      <instance.aaxl2>  Path to an instance file produced by
				                                'instantiate'. Must resolve under a
				                                workspace root.
				  Flags:     --sync-system         Treat the system as synchronous
				                                   (default: asynchronous).
				             --no-major-frame      Disable major-frame delay
				                                   (default: enabled).
				             --best-case-deadline  Use best-case (not worst-case)
				                                   deadlines (default: worst case).
				             --full-queue          Assume queues are full, not empty
				                                   (default: best-case empty queue).
				             --disable-queuing     Disable queuing latency
				                                   (default: enabled).
				  Output:    First line: structured analysis summary (a warning/error
				                          diagnostic when present; otherwise completion).
				             Next lines: absolute paths of the '.result' and, when flow
				                         results exist, '.csv' report files written.
				             Following lines: instance-anchored diagnostics, gcc-style:
				               <instance-file.aaxl2>:<element-instance-path>: <severity>: <message>
				             Sorted by file then path.
				  Errors:    Client-side (no 'ERR ' prefix; exit 2):
				               'usage: analyze-latency <file> [flags]' unless exactly one
				               positional argument is given.
				               'unknown flag: <flag>' for an unrecognized flag.
				             Server-side (status 'ERR <reason>'; exit 1):
				               'ERR invalid args: file not in workspace' when <file>
				               does not resolve under any workspace root.
				               'ERR invalid args: not an instance file (.aaxl2): <path>'
				               when the file does not have a .aaxl2 extension.
				               'ERR invalid args: no such file: <path>' when <file>
				               resolves under a root but does not exist.
				  Example:   osate-cli c1 -p 54321 analyze-latency \\
				                 instances/control_control_Instance.aaxl2

				analyze-bus-load <instance.aaxl2>
				  Synopsis:  Run bus load analysis on an instance model. Writes a '.csv'
				             report under <instance-folder>/reports/BusLoad/ and prints
				             its absolute path.
				  Args:      <instance.aaxl2>  Path to an instance file produced by
				                                'instantiate'. Must resolve under a
				                                workspace root.
				  Output:    First line: structured analysis summary.
				             Next line:   absolute path of the '.csv' report file written.
				             Following lines: instance-anchored diagnostics, gcc-style:
				               <instance-file.aaxl2>:<element-instance-path>: <severity>: <message>
				             Sorted by file then path.
				  Errors:    Client-side (no 'ERR ' prefix; exit 2):
				               'usage: analyze-bus-load <file>' when the wrong number of
				               arguments is passed.
				             Server-side (status 'ERR <reason>'; exit 1):
				               'ERR invalid args: file not in workspace' when <file>
				               does not resolve under any workspace root.
				               'ERR invalid args: not an instance file (.aaxl2): <path>'
				               when the file does not have a .aaxl2 extension.
				               'ERR invalid args: no such file: <path>' when <file>
				               resolves under a root but does not exist.
				  Example:   osate-cli c1 -p 54321 analyze-bus-load \\
				                 instances/control_control_Instance.aaxl2

				analyze-modes <instance.aaxl2> [--dot] [--html] [--smv]
				  Synopsis:  Run SOM mode reachability analysis on an instance model.
				             Report files are opt-in and are written under
				             <instance-folder>/reports/som-reachability/.
				  Args:      <instance.aaxl2>  Path to an instance file produced by
				                                'instantiate'. Must resolve under a
				                                workspace root.
				  Flags:     --dot   Generate a Graphviz DOT report.
				             --html  Generate an HTML report.
				             --smv   Generate a NuSMV report.
				  Output:    First line: structured analysis summary.
				             Next lines:  absolute paths of generated report files, if
				                          any formats were requested.
				             Following lines: instance-anchored diagnostics, gcc-style:
				               <instance-file.aaxl2>:<element-instance-path>: <severity>: <message>
				             Sorted by file then path.
				  Errors:    Client-side (no 'ERR ' prefix; exit 2):
				               'usage: analyze-modes <file> [--dot] [--html] [--smv]'
				               unless exactly one positional argument is given.
				               'unknown flag: <flag>' for an unrecognized flag.
				             Server-side (status 'ERR <reason>'; exit 1):
				               'ERR invalid args: file not in workspace' when <file>
				               does not resolve under any workspace root.
				               'ERR invalid args: not an instance file (.aaxl2): <path>'
				               when the file does not have a .aaxl2 extension.
				               'ERR invalid args: no such file: <path>' when <file>
				               resolves under a root but does not exist.
				  Example:   osate-cli c1 -p 54321 analyze-modes \\
				                 instances/control_control_Instance.aaxl2 --html

				add-project <root>
				  Synopsis:  Add an existing directory to the live workspace, index it, and
				             report diagnostics. The new root participates in subsequent
				             workspace commands, including checks, instantiation, and analyses.
				  Args:      <root>  Existing directory; resolved to an absolute path on the
				                     client. Must not already be a workspace root.
				  Output:    Same diagnostic format as 'check' / 'update' (workspace-wide).
				  Errors:    Client-side (no 'ERR ' prefix; exit 2):
				               'usage: add-project <root>' on missing/extra args.
				               'workspace root is not a directory: <path>' if <root> doesn't
				               exist or is not a directory.
				             Server-side (status 'ERR <reason>'; exit 1):
				               'ERR invalid args: root already in workspace' if <root> is
				               already a workspace root.
				  Side effects:
				             The per-port session file and workspace marker are rewritten to
				             reflect the live root set.
				  Example:   osate-cli c1 -p 54321 add-project /path/to/extra/project

				remove-project <root>
				  Synopsis:  Remove a directory from the live workspace and prune diagnostics
				             for files under it. The first workspace root cannot be removed.
				  Args:      <root>  Workspace root to remove; resolved to an absolute path on
				                     the client. The directory does not need to still exist.
				  Output:    Same diagnostic format as 'check' / 'update' for the remaining
				             workspace.
				  Errors:    Client-side (no 'ERR ' prefix; exit 2):
				               'usage: remove-project <root>' on missing/extra args.
				             Server-side (status 'ERR <reason>'; exit 1):
				               'ERR invalid args: cannot remove first root' if <root> is the
				               first workspace root (it owns the marker file).
				               'ERR invalid args: root not in workspace' if <root> is not a
				               current workspace root.
				  Side effects:
				             The per-port session file and workspace marker are rewritten to
				             reflect the live root set.
				  Example:   osate-cli c1 -p 54321 remove-project /path/to/extra/project

				list-projects
				  Synopsis:  Print the current workspace roots in order, one per line. Read-only;
				             does not trigger a build.
				  Output:    One absolute path per line; first root listed first.
				  Errors:    'ERR invalid args: usage: list-projects' if any positional argument
				             is passed (exit 1).
				  Example:   osate-cli c1 -p 54321 list-projects

				exit
				  Synopsis:  Stop the workspace server. Subject to the sticky-owner check.
				  Output:    No stdout. After returning protocol status 'OK', the server shuts the
				             language server down and terminates.
				  Errors:    'ERR busy: another client is connected' if <id> is not the owner.
				             'ERR invalid args: usage: exit' if any positional argument is
				             passed (exit 1).
				  Example:   osate-cli c1 -p 54321 exit

				Unavailable servers
				-------------------
				If a remote command (-p <port> ...) cannot connect, the client cleans stale
				session state when it can safely do so, prints
				  'ERR no workspace server is running on port <n>; run init again'
				and exits 1. It does not restart the server or retry the request. If a connection
				resets after a request may have been sent, the error instead says that the command
				outcome is unknown.

				Error output vocabulary
				-----------------------
				Server-side (returned in the status line):
				  ERR busy: another client is connected
				  ERR unknown command: <name>
				  ERR invalid args: <detail>
				  ERR <ExceptionClass>: <message>          # catch-all for unhandled
				                                           # server-side exceptions

				Remote client failures (printed to stderr by the client itself):
				  ERR no workspace server is running on port <n>; run init again
				  ERR connection to workspace server on port <n> was lost; command outcome is unknown; run init again
				  ERR truncated response from server
				  ERR <ExceptionClass>: <message>          # catch-all for I/O failures

				Local project operation failures (printed to stderr):
				  ERR <reason>

				Exit codes
				----------
				  0   Success. Remote diagnostics with severity error|warning|info|hint and local
				      validation warnings do not change this; a 'check' that reports errors still
				      exits 0.
				  1   Operational failure: a local project operation or validation failed, the
				      server returned 'ERR ...', or a network, I/O, or startup operation failed.
				  2   Command-line parse error, including a missing <id> or port, an unknown
				      command, client-side command syntax validation, a missing/invalid option
				      value, or an invalid init workspace root.

				Examples
				--------
				  osate-cli project create aadl
				  osate-cli project create aadl1 --depends-on aadl
				  osate-cli project validate
				  osate-cli c1 init
				  osate-cli c1 init /path/to/workspace
				  osate-cli c1 -p 54321 check /path/to/workspace/control.aadl
				  osate-cli c1 -p 54321 instantiate control.aadl control::control.impl
				  osate-cli c1 -p 54321 analyze-bus-load instances/control_control_Instance.aaxl2
				  osate-cli c1 -p 54321 analyze-modes instances/control_control_Instance.aaxl2 --html
				  osate-cli c1 -p 54321 ping
				  osate-cli c1 -p 54321 exit""";
	}
}
