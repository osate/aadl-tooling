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
package org.osate.cli.dist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests that drive the assembled {@code osate-cli} launcher script the way a user
 * would in a terminal session. Mirrors the scenarios in {@code osate-cli/manual-test.md}.
 */
@EnabledIf("isLauncherAvailable")
class CliEndToEndIT {

	private static final Pattern DIAG_LINE = Pattern.compile("^.+:\\d+:\\d+: (error|warning|info|hint): .+$");
	private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	/**
	 * Budget for a single CLI invocation and for reading one workspace-server
	 * response. Commands that trigger a workspace rebuild are the slow ones, and
	 * how slow depends heavily on the machine: the same suite that finishes in
	 * about a minute on a developer workstation takes several minutes on a
	 * two-core CI runner. Configurable so CI can be generous without making a
	 * genuine hang take minutes to surface locally.
	 */
	private static final int TIMEOUT_SECONDS = Integer.getInteger("osate.cli.test.timeout.seconds", 120);

	static boolean isLauncherAvailable() {
		var dir = System.getProperty("osate.cli.bin");
		if (dir == null) {
			return false;
		}
		return Files.isExecutable(launcher(Path.of(dir)));
	}

	private static Path launcher(Path bin) {
		return bin.resolve(IS_WINDOWS ? "osate-cli.bat" : "osate-cli");
	}

	@Test
	void usageAndHelp() throws Exception {
		var noArgs = run(List.of());
		assertEquals(2, noArgs.exit, () -> "no-args should exit 2: " + noArgs);
		assertTrue(noArgs.stderr.contains("usage:"), () -> "expected usage on stderr: " + noArgs);

		var help = run(List.of("help"));
		assertEquals(0, help.exit, () -> "help should exit 0: " + help);
		assertTrue(help.stdout.contains("Local commands")
				&& help.stdout.contains("project create <name>")
				&& help.stdout.contains("Language-server commands")
				&& help.stdout.contains("Exit codes"),
				() -> "expected detailed help: " + help);

		var shortHelp = run(List.of("-h"));
		assertEquals(0, shortHelp.exit);
		assertTrue(shortHelp.stdout.contains("usage:"));
		assertFalse(shortHelp.stdout.contains("Language-server commands"),
				() -> "-h should print usage only: " + shortHelp);

		var unknown = run(List.of("c1", "-p", "1", "bogus"));
		assertEquals(2, unknown.exit, () -> "unknown command should exit 2: " + unknown);
	}

	/**
	 * The assembled launcher must report the version baked into the jar, because the
	 * packaging scripts derive the tarball, deb, rpm, and Homebrew versions from that same
	 * resource. A mismatch here means an installed package would misreport its version.
	 */
	@Test
	@EnabledIf("isCliJarAvailable")
	void versionMatchesJarResource() throws Exception {
		var expected = versionFromJar();

		var longFlag = run(List.of("--version"));
		assertEquals(0, longFlag.exit, () -> "--version should exit 0: " + longFlag);
		assertEquals("osate-cli " + expected, longFlag.stdout.trim(),
				() -> "unexpected --version output: " + longFlag);

		var shortFlag = run(List.of("-v"));
		assertEquals(0, shortFlag.exit, () -> "-v should exit 0: " + shortFlag);
		assertEquals(longFlag.stdout.trim(), shortFlag.stdout.trim(),
				() -> "-v and --version disagree: " + shortFlag);

		var help = run(List.of("help"));
		assertEquals(0, help.exit, () -> "help should exit 0: " + help);
		assertTrue(help.stdout.startsWith("osate-cli " + expected),
				() -> "help should start with the version banner: " + help);
	}

	private static String versionFromJar() throws IOException {
		var jar = Path.of(System.getProperty("osate.cli.jar"));
		try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
			var entry = zip.getEntry("org/osate/cli/version.properties");
			assertNotNull(entry, () -> "no version.properties in " + jar);
			var props = new java.util.Properties();
			try (var in = zip.getInputStream(entry)) {
				props.load(in);
			}
			var version = props.getProperty("version", "").trim();
			assertFalse(version.isEmpty() || version.startsWith("${"),
					() -> "unfiltered or missing version in " + jar + ": " + version);
			return version;
		}
	}

	@Test
	void serverLifecycleAndStickyOwnership(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);

		// 2. Server lifecycle
		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());
		assertTrue(port > 0, () -> "expected positive port, got " + port);

		try {
			var marker = workspace.resolve(".osate-cli/server.json");
			assertTrue(Files.isRegularFile(marker), "expected marker at " + marker);

			var pingC1 = run(List.of("c1", "-p", String.valueOf(port), "ping"));
			assertEquals(0, pingC1.exit, () -> "ping failed: " + pingC1);
			assertEquals("OK c1", pingC1.stdout.trim(), () -> "ping should report bound id: " + pingC1);

			var initReuse = run(List.of("c1", "init", workspace.toString()));
			assertEquals(0, initReuse.exit, () -> "init reuse failed: " + initReuse);
			assertEquals(port, Integer.parseInt(initReuse.stdout.trim()),
					"reuse should yield same port");

			// 3. Sticky ownership: ping bypasses, all other commands are gated to c1.
			var pingC2 = run(List.of("c2", "-p", String.valueOf(port), "ping"));
			assertEquals(0, pingC2.exit, () -> "c2 ping should succeed: " + pingC2);
			assertEquals("OK c1", pingC2.stdout.trim(), "c2 ping should still report c1 as bound");

			var checkC2 = run(List.of("c2", "-p", String.valueOf(port), "check"));
			assertEquals(1, checkC2.exit, () -> "c2 check should exit 1: " + checkC2);
			assertTrue(checkC2.stderr.contains("busy"), () -> "expected 'busy' on stderr: " + checkC2);

			var exitC2 = run(List.of("c2", "-p", String.valueOf(port), "exit"));
			assertEquals(1, exitC2.exit, () -> "c2 exit should be rejected: " + exitC2);
			assertTrue(exitC2.stderr.contains("busy"), () -> "expected 'busy' on stderr: " + exitC2);

			// Server should still be alive.
			var pingAfterReject = run(List.of("c1", "-p", String.valueOf(port), "ping"));
			assertEquals(0, pingAfterReject.exit, () -> "server should still be up: " + pingAfterReject);

			var exitC1 = run(List.of("c1", "-p", String.valueOf(port), "exit"));
			assertEquals(0, exitC1.exit, () -> "c1 exit failed: " + exitC1);

			waitUntilDead(port, 15_000);
			assertFalse(Files.exists(marker), "marker should be cleaned up after exit");
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
		}
	}

	@Test
	void reusesWorkspaceServerAcrossCommands(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);

		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());
		var marker = workspace.resolve(".osate-cli/server.json");
		long serverPid = markerPid(marker);
		var serverLogs = serverLogFiles(workspace);
		assertEquals(1, serverLogs.size(), () -> "expected one server log after init: " + serverLogs);

		try {
			var ping = run(List.of("c1", "-p", String.valueOf(port), "ping"));
			assertEquals(0, ping.exit, () -> "ping failed: " + ping);
			assertSameServer(workspace, serverPid, serverLogs);

			var check = run(List.of("c1", "-p", String.valueOf(port), "check"));
			assertEquals(0, check.exit, () -> "check failed: " + check);
			assertSameServer(workspace, serverPid, serverLogs);

			var update = run(List.of("c1", "-p", String.valueOf(port), "update"));
			assertEquals(0, update.exit, () -> "update failed: " + update);
			assertSameServer(workspace, serverPid, serverLogs);

			var initAgain = run(List.of("c1", "init", workspace.toString()));
			assertEquals(0, initAgain.exit, () -> "repeated init failed: " + initAgain);
			assertEquals(port, Integer.parseInt(initAgain.stdout.trim()),
					"repeated init should return the existing server port");
			assertSameServer(workspace, serverPid, serverLogs);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void checkUpdateInstantiate(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);
		var aadl = workspace.resolve("control.aadl");

		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			// 4.1 workspace-wide
			var checkAll = run(List.of("c1", "-p", String.valueOf(port), "check"));
			assertEquals(0, checkAll.exit, () -> "check (all) failed: " + checkAll);
			for (var l : nonEmptyLines(checkAll.stdout)) {
				assertTrue(DIAG_LINE.matcher(l).matches(), () -> "unexpected diag line: " + l);
			}

			// 4.2 single-file
			var checkOne = run(List.of("c1", "-p", String.valueOf(port), "check", aadl.toString()));
			assertEquals(0, checkOne.exit, () -> "check <file> failed: " + checkOne);

			// 4.3 file outside workspace
			var bogusFile = Files.createTempFile("not-in-ws", ".aadl");
			try {
				var outside = run(List.of("c1", "-p", String.valueOf(port), "check", bogusFile.toString()));
				assertEquals(1, outside.exit, () -> "out-of-ws should exit 1: " + outside);
				assertTrue(outside.stderr.contains("file not in workspace"),
						() -> "expected 'file not in workspace' on stderr: " + outside);
			} finally {
				Files.deleteIfExists(bogusFile);
			}

			// 4.4 update reflects edits
			Files.writeString(aadl, Files.readString(aadl) + "\nthis is garbage\n");
			var updateBroken = run(List.of("c1", "-p", String.valueOf(port), "update"));
			assertEquals(0, updateBroken.exit, () -> "update failed: " + updateBroken);
			assertTrue(updateBroken.stdout.contains("error:"),
					() -> "expected error diagnostic after introducing garbage: " + updateBroken);

			// 4.5 restore + update
			copyFixture(workspace);
			var updateClean = run(List.of("c1", "-p", String.valueOf(port), "update"));
			assertEquals(0, updateClean.exit, () -> "update failed: " + updateClean);
			assertFalse(updateClean.stdout.contains("error:"),
					() -> "no errors expected after restore: " + updateClean);

			// 4.6 instantiate
			var inst = run(List.of("c1", "-p", String.valueOf(port), "instantiate", aadl.toString(),
					"control::control.impl"));
			assertEquals(0, inst.exit, () -> "instantiate failed: " + inst);
			assertTrue(Files.isDirectory(workspace.resolve("instances")) || anyAaxlExists(workspace),
					"expected instances directory or *.aaxl2 file under " + workspace);

			// 4.7 instantiate with no args → exit 2
			var instMissing = run(List.of("c1", "-p", String.valueOf(port), "instantiate"));
			assertNotEquals(0, instMissing.exit, () -> "instantiate w/o args should fail: " + instMissing);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void instantiateReportsWarningDiagnostic(@TempDir Path workspace) throws Exception {
		// A subcomponent without a classifier triggers a stable instantiation warning from
		// InstantiateModel. Use it to assert the documented diagnostic format:
		//   <path>:<instance-path>: <severity>: <message>
		var aadl = workspace.resolve("warn.aadl");
		Files.writeString(aadl, """
				package warn
				public
					system top
					end top;

					system implementation top.impl
						subcomponents
							s: system;
					end top.impl;
				end warn;
				""");

		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var inst = run(List.of("c1", "-p", String.valueOf(port), "instantiate",
					aadl.toString(), "warn::top.impl"));
			assertEquals(0, inst.exit, () -> "instantiate failed: " + inst);

			var lines = nonEmptyLines(inst.stdout);
			assertFalse(lines.isEmpty(), () -> "expected output: " + inst);
			assertTrue(lines.get(0).startsWith("Instantiated warn::top.impl as "),
					() -> "expected success header on first line: " + inst);

			var diagPattern = Pattern.compile(
					"^.+\\.aaxl2:[^:]+: (error|warning|info|hint): .+$");
			var diagLines = lines.subList(1, lines.size());
			assertFalse(diagLines.isEmpty(),
					() -> "expected at least one diagnostic line: " + inst);
			for (var l : diagLines) {
				assertTrue(diagPattern.matcher(l).matches(),
						() -> "unexpected diag line: " + l);
			}
			assertTrue(diagLines.stream().anyMatch(l ->
					l.contains(": warning: Instantiated subcomponent doesn't have a component classifier")),
					() -> "expected classifier-less subcomponent warning, got: " + diagLines);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void analyzeLatencyEmitsReportPathsAndDiagnostics(@TempDir Path workspace) throws Exception {
		var aadl = workspace.resolve("latencytest.aadl");
		Files.writeString(aadl, """
				package latencytest
				public
					system src
						features
							out_data: out data port;
						flows
							src_flow: flow source out_data;
					end src;

					system snk
						features
							in_data: in data port;
						flows
							snk_flow: flow sink in_data;
					end snk;

					system top
					end top;

					system implementation top.impl
						subcomponents
							s: system src;
							d: system snk;
						connections
							c1: port s.out_data -> d.in_data;
						flows
							etef: end to end flow s.src_flow -> c1 -> d.snk_flow;
					end top.impl;
				end latencytest;
				""");

		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var inst = run(List.of("c1", "-p", String.valueOf(port), "instantiate",
					aadl.toString(), "latencytest::top.impl"));
			assertEquals(0, inst.exit, () -> "instantiate failed: " + inst);

			Path instanceFile;
			try (var stream = Files.walk(workspace)) {
				instanceFile = stream.filter(Files::isRegularFile)
						.filter(p -> p.getFileName().toString().endsWith(".aaxl2"))
						.findFirst()
						.orElseThrow(() -> new AssertionError(
								"expected .aaxl2 file under " + workspace + ", inst=" + inst));
			}

			var latency = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-latency", instanceFile.toString()));
			assertEquals(0, latency.exit, () -> "analyze-latency failed: " + latency);

			var lines = nonEmptyLines(latency.stdout);
			assertFalse(lines.isEmpty(), () -> "expected output: " + latency);
			assertEquals("Expected end to end latency is not specified", lines.get(0),
					() -> "expected warning summary on first line: " + latency);
			assertTrue(lines.size() >= 3,
					() -> "expected header + .result + .csv lines: " + latency);
			assertTrue(lines.get(1).endsWith(".result"),
					() -> ".result path expected on second line: " + latency);
			assertTrue(lines.get(2).endsWith(".csv"),
					() -> ".csv path expected on third line: " + latency);
			assertTrue(Files.isRegularFile(Path.of(lines.get(1))),
					() -> ".result file not on disk: " + lines.get(1));
			assertTrue(Files.isRegularFile(Path.of(lines.get(2))),
					() -> ".csv file not on disk: " + lines.get(2));

			var diagPattern = Pattern.compile(
					"^.+\\.aaxl2:[^:]+: (error|warning|info|hint): .+$");
			var diagLines = lines.subList(3, lines.size());
			for (var l : diagLines) {
				assertTrue(diagPattern.matcher(l).matches(),
						() -> "unexpected diag line: " + l);
			}

			var flagged = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-latency", instanceFile.toString(),
					"--sync-system", "--best-case-deadline"));
			assertEquals(0, flagged.exit, () -> "flagged analyze-latency failed: " + flagged);
			var flaggedLines = nonEmptyLines(flagged.stdout);
			assertTrue(flaggedLines.size() >= 3, () -> "expected flagged output: " + flagged);
			assertNotEquals(lines.get(1), flaggedLines.get(1),
					"flag changes should produce a different .result filename: "
							+ lines.get(1) + " vs " + flaggedLines.get(1));

			var nonInstance = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-latency", aadl.toString()));
			assertEquals(1, nonInstance.exit, () -> "non-instance should exit 1: " + nonInstance);
			assertTrue(nonInstance.stderr.contains("not an instance file"),
					() -> "expected not-an-instance error: " + nonInstance);

			var missing = run(List.of("c1", "-p", String.valueOf(port), "analyze-latency"));
			assertNotEquals(0, missing.exit, () -> "missing args should fail: " + missing);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void analyzeBusLoadEmitsReportPathAndDiagnostics(@TempDir Path workspace) throws Exception {
		var aadl = workspace.resolve("busloadtest.aadl");
		Files.writeString(aadl, """
				package busloadtest
				public
					with SEI;

					data D8
						properties
							Data_Size => 8 Bytes;
					end D8;

					data D16
						properties
							Data_Size => 16 Bytes;
					end D16;

					data D24
						properties
							Data_Size => 24 Bytes;
					end D24;

					bus B
						properties
							SEI::BandWidthBudget => 64.0 KBytesps;
							SEI::BandwidthCapacity => 96.0 KBytesps;
					end B;

					system S1
						features
							out1: out data port D8;
							out2: out data port D16;
							out3: out data port D24;
					end S1;

					system S2
						features
							in1: in data port D8;
							in2: in data port D16;
							in3: in data port D24;
					end S2;

					system top
					end top;

					system implementation top.i
						subcomponents
							sub1: system S1;
							sub2: system S2;
							theBus: bus B;
						connections
							conn1: port sub1.out1 -> sub2.in1;
							conn2: port sub1.out2 -> sub2.in2 {
								SEI::BandWidthBudget => 8.0 KBytesps;
							};
							conn3: port sub1.out3 -> sub2.in3 {
								SEI::BandWidthBudget => 32.0 KBytesps;
							};
						properties
							Actual_Connection_Binding => (reference (theBus)) applies to conn1;
							Actual_Connection_Binding => (reference (theBus)) applies to conn2;
							Actual_Connection_Binding => (reference (theBus)) applies to conn3;
							Communication_Properties::Output_Rate => [
								Value_Range => 800.0 .. 1000.0;
								Rate_Unit => PerSecond;
							] applies to sub1.out1;
							Communication_Properties::Output_Rate => [
								Value_Range => 800.0 .. 1000.0;
								Rate_Unit => PerSecond;
							] applies to sub1.out2;
							Communication_Properties::Output_Rate => [
								Value_Range => 800.0 .. 1000.0;
								Rate_Unit => PerSecond;
							] applies to sub1.out3;
					end top.i;
				end busloadtest;
				""");

		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var inst = run(List.of("c1", "-p", String.valueOf(port), "instantiate",
					aadl.toString(), "busloadtest::top.i"));
			assertEquals(0, inst.exit, () -> "instantiate failed: " + inst);

			Path instanceFile;
			try (var stream = Files.walk(workspace)) {
				instanceFile = stream.filter(Files::isRegularFile)
						.filter(p -> p.getFileName().toString().endsWith(".aaxl2"))
						.findFirst()
						.orElseThrow(() -> new AssertionError(
								"expected .aaxl2 file under " + workspace + ", inst=" + inst));
			}

			var busLoad = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-bus-load", instanceFile.toString()));
			assertEquals(0, busLoad.exit, () -> "analyze-bus-load failed: " + busLoad);

			var lines = nonEmptyLines(busLoad.stdout);
			assertTrue(lines.size() >= 2, () -> "expected header + .csv path: " + busLoad);
			assertFalse(lines.get(0).isBlank(), () -> "expected analysis summary on first line: " + busLoad);
			assertTrue(lines.get(1).endsWith("__BusLoad.csv"),
					() -> ".csv path expected on second line: " + busLoad);
			assertTrue(Files.isRegularFile(Path.of(lines.get(1))),
					() -> ".csv file not on disk: " + lines.get(1));

			var diagPattern = Pattern.compile(
					"^.+\\.aaxl2:[^:]+: (error|warning|info|hint): .+$");
			var diagLines = lines.subList(2, lines.size());
			assertFalse(diagLines.isEmpty(),
					() -> "expected bus-load diagnostics: " + busLoad);
			for (var l : diagLines) {
				assertTrue(diagPattern.matcher(l).matches(),
						() -> "unexpected diag line: " + l);
			}
			assertTrue(diagLines.stream().anyMatch(l -> l.contains("has no bandwidth budget")),
					() -> "expected missing-budget warning, got: " + diagLines);
			assertTrue(diagLines.stream().anyMatch(l -> l.contains("Actual bandwidth > budget")),
					() -> "expected bandwidth-over-budget error, got: " + diagLines);

			var nonInstance = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-bus-load", aadl.toString()));
			assertEquals(1, nonInstance.exit, () -> "non-instance should exit 1: " + nonInstance);
			assertTrue(nonInstance.stderr.contains("not an instance file"),
					() -> "expected not-an-instance error: " + nonInstance);

			var missing = run(List.of("c1", "-p", String.valueOf(port), "analyze-bus-load"));
			assertEquals(2, missing.exit, () -> "missing args should fail client-side: " + missing);
			assertTrue(missing.stderr.contains("usage: analyze-bus-load <file>"),
					() -> "expected usage error: " + missing);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void analyzeModesEmitsDiagnosticsAndOptionalReports(@TempDir Path workspace) throws Exception {
		var aadl = workspace.resolve("modetest.aadl");
		Files.writeString(aadl, reachabilityModel());

		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var inst = run(List.of("c1", "-p", String.valueOf(port), "instantiate",
					aadl.toString(), "modetest::S.i"));
			assertEquals(0, inst.exit, () -> "instantiate failed: " + inst);

			Path instanceFile;
			try (var stream = Files.walk(workspace)) {
				instanceFile = stream.filter(Files::isRegularFile)
						.filter(p -> p.getFileName().toString().endsWith(".aaxl2"))
						.findFirst()
						.orElseThrow(() -> new AssertionError(
								"expected .aaxl2 file under " + workspace + ", inst=" + inst));
			}

			var modesDefault = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-modes", instanceFile.toString()));
			assertEquals(0, modesDefault.exit, () -> "analyze-modes failed: " + modesDefault);

			var defaultLines = nonEmptyLines(modesDefault.stdout);
			assertFalse(defaultLines.isEmpty(), () -> "expected output: " + modesDefault);
			assertEquals("Mode reachability analysis completed", defaultLines.get(0),
					() -> "expected analysis header on first line: " + modesDefault);
			assertTrue(reportPathLines(defaultLines).isEmpty(),
					() -> "default analyze-modes should not emit report paths: " + modesDefault);
			assertReachabilityDiagnostics(defaultLines.subList(1, defaultLines.size()));

			var modesReports = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-modes", instanceFile.toString(), "--dot", "--html", "--smv"));
			assertEquals(0, modesReports.exit, () -> "flagged analyze-modes failed: " + modesReports);

			var reportOutputLines = nonEmptyLines(modesReports.stdout);
			var reports = reportPathLines(reportOutputLines);
			assertEquals(3, reports.size(), () -> "expected .dot/.html/.smv paths: " + modesReports);
			assertTrue(reports.stream().anyMatch(l -> l.endsWith(".dot")),
					() -> ".dot path expected: " + modesReports);
			assertTrue(reports.stream().anyMatch(l -> l.endsWith(".html")),
					() -> ".html path expected: " + modesReports);
			assertTrue(reports.stream().anyMatch(l -> l.endsWith(".smv")),
					() -> ".smv path expected: " + modesReports);
			for (var report : reports) {
				assertTrue(Files.isRegularFile(Path.of(report)),
						() -> "report file not on disk: " + report);
			}
			var diagLines = reportOutputLines.stream()
					.filter(l -> !l.equals("Mode reachability analysis completed"))
					.filter(l -> !reports.contains(l))
					.toList();
			assertReachabilityDiagnostics(diagLines);

			var nonInstance = run(List.of("c1", "-p", String.valueOf(port),
					"analyze-modes", aadl.toString()));
			assertEquals(1, nonInstance.exit, () -> "non-instance should exit 1: " + nonInstance);
			assertTrue(nonInstance.stderr.contains("not an instance file"),
					() -> "expected not-an-instance error: " + nonInstance);

			var missing = run(List.of("c1", "-p", String.valueOf(port), "analyze-modes"));
			assertEquals(2, missing.exit, () -> "missing args should fail client-side: " + missing);
			assertTrue(missing.stderr.contains("usage: analyze-modes <file> [--dot] [--html] [--smv]"),
					() -> "expected usage error: " + missing);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void checkReportsUnresolvedReferenceAfterEdit(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);
		var aadl = workspace.resolve("control.aadl");

		var init = run(List.of("c1", "init", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var clean = run(List.of("c1", "-p", String.valueOf(port), "check", aadl.toString()));
			assertEquals(0, clean.exit, () -> "initial check failed: " + clean);
			assertFalse(clean.stdout.contains("error:"),
					() -> "fixture should be clean: " + clean);

			var original = "c2: port c.cmd -> a.cmd;";
			var broken = "c2: port c.cmdx -> a.cmd;";
			var content = Files.readString(aadl);
			assertTrue(content.contains(original), "fixture missing expected line: " + original);
			Files.writeString(aadl, content.replace(original, broken));

			var afterEdit = run(List.of("c1", "-p", String.valueOf(port), "check", aadl.toString()));
			assertEquals(0, afterEdit.exit, () -> "check after edit failed: " + afterEdit);
			var expected = aadl.toAbsolutePath() + ":50:15: error: Couldn't resolve reference to ConnectionEnd 'cmdx'.";
			assertTrue(afterEdit.stdout.contains(expected),
					() -> "expected diagnostic " + expected + ", got: " + afterEdit);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void idleTimeoutShutsDownServer(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);
		var init = run(List.of("c1", "init", "--server-timeout", "3", workspace.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var ping = run(List.of("c1", "-p", String.valueOf(port), "ping"));
			assertEquals(0, ping.exit);

			waitUntilDead(port, 15_000);
			assertFalse(Files.exists(workspace.resolve(".osate-cli/server.json")),
					"marker should be removed after idle shutdown");
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
		}
	}

	@Test
	void dynamicRoots(@TempDir Path wsA, @TempDir Path wsB) throws Exception {
		copyFixture(wsA);
		copyFixture(wsB);
		var fileInB = wsB.resolve("control.aadl");

		var init = run(List.of("c1", "init", wsA.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var listInitial = run(List.of("c1", "-p", String.valueOf(port), "list-projects"));
			assertEquals(0, listInitial.exit, () -> "list-projects failed: " + listInitial);
			assertEquals(wsA.toAbsolutePath().toString(), listInitial.stdout.trim(),
					() -> "expected single root: " + listInitial);

			var add = run(List.of("c1", "-p", String.valueOf(port), "add-project", wsB.toString()));
			assertEquals(0, add.exit, () -> "add-project failed: " + add);
			for (var l : nonEmptyLines(add.stdout)) {
				assertTrue(DIAG_LINE.matcher(l).matches(), () -> "unexpected line: " + l);
			}

			var listBoth = run(List.of("c1", "-p", String.valueOf(port), "list-projects"));
			assertEquals(0, listBoth.exit);
			assertEquals(List.of(wsA.toAbsolutePath().toString(), wsB.toAbsolutePath().toString()),
					nonEmptyLines(listBoth.stdout),
					() -> "expected both roots in order: " + listBoth);

			var checkInB = run(List.of("c1", "-p", String.valueOf(port), "check", fileInB.toString()));
			assertEquals(0, checkInB.exit, () -> "check under added root failed: " + checkInB);

			var addAgain = run(List.of("c1", "-p", String.valueOf(port), "add-project", wsB.toString()));
			assertEquals(1, addAgain.exit, () -> "duplicate add should fail: " + addAgain);
			assertTrue(addAgain.stderr.contains("root already in workspace"),
					() -> "expected duplicate-root err: " + addAgain);

			var removeFirst = run(List.of("c1", "-p", String.valueOf(port), "remove-project", wsA.toString()));
			assertEquals(1, removeFirst.exit, () -> "removing first root should fail: " + removeFirst);
			assertTrue(removeFirst.stderr.contains("cannot remove first root"),
					() -> "expected first-root err: " + removeFirst);

			var removeB = run(List.of("c1", "-p", String.valueOf(port), "remove-project", wsB.toString()));
			assertEquals(0, removeB.exit, () -> "remove-project failed: " + removeB);

			var listAfter = run(List.of("c1", "-p", String.valueOf(port), "list-projects"));
			assertEquals(wsA.toAbsolutePath().toString(), listAfter.stdout.trim(),
					() -> "expected single root after removal: " + listAfter);

			var checkAfter = run(List.of("c1", "-p", String.valueOf(port), "check", fileInB.toString()));
			assertEquals(1, checkAfter.exit, () -> "check after removal should fail: " + checkAfter);
			assertTrue(checkAfter.stderr.contains("file not in workspace"),
					() -> "expected file-not-in-workspace: " + checkAfter);

			var removeMissing = run(List.of("c1", "-p", String.valueOf(port), "remove-project", wsB.toString()));
			assertEquals(1, removeMissing.exit);
			assertTrue(removeMissing.stderr.contains("root not in workspace"),
					() -> "expected not-in-workspace: " + removeMissing);

			var listExtra = run(List.of("c1", "-p", String.valueOf(port), "list-projects", "extra"));
			assertEquals(1, listExtra.exit);
			assertTrue(listExtra.stderr.contains("usage: list-projects"),
					() -> "expected usage err: " + listExtra);
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	void multiRoot(@TempDir Path workspace1, @TempDir Path workspace2) throws Exception {
		copyFixture(workspace1);
		copyFixture(workspace2);

		var init = run(List.of("c1", "init", workspace1.toString(), workspace2.toString()));
		assertEquals(0, init.exit, () -> "init failed: " + init);
		int port = Integer.parseInt(init.stdout.trim());

		try {
			var inWs2 = workspace2.resolve("control.aadl");
			var checkWs2 = run(List.of("c1", "-p", String.valueOf(port), "check", inWs2.toString()));
			assertEquals(0, checkWs2.exit, () -> "check under second root failed: " + checkWs2);

			assertTrue(Files.isRegularFile(workspace1.resolve(".osate-cli/server.json")),
					"marker should live under first root");
			assertFalse(Files.exists(workspace2.resolve(".osate-cli/server.json")),
					"marker should not live under second root");
		} finally {
			run(List.of("c1", "-p", String.valueOf(port), "exit"));
			waitUntilDead(port, 15_000);
		}
	}

	@Test
	@EnabledIf("isCliJarAvailable")
	void idleTimeoutRemovesSessionAndRequiresInit(@TempDir Path workspace, @TempDir Path home)
			throws Exception {
		copyFixture(workspace);

		// init: short server-timeout so we can wait for the server to die.
		var initResult = runJar(home, "c1", "init", "--server-timeout", "2", workspace.toString());
		assertEquals(0, initResult.exit, () -> "init failed: " + initResult);
		int port = Integer.parseInt(initResult.stdout.trim());

		var sessionFile = home.resolve(".osate-cli").resolve("sessions").resolve(port + ".json");
		assertTrue(Files.exists(sessionFile), "session file not written: " + sessionFile);
		var marker = workspace.resolve(".osate-cli").resolve("server.json");

		// Wait for the original server to time out and clean both lifecycle files.
		var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while ((Files.exists(marker) || Files.exists(sessionFile))
				&& System.nanoTime() < deadline) {
			Thread.sleep(100);
		}
		assertFalse(Files.exists(marker),
				"original server did not exit within 30s: marker still present " + marker);
		assertFalse(Files.exists(sessionFile),
				"session file should be removed after idle timeout: " + sessionFile);
		waitUntilDead(port, 15_000);

		var ping = runJar(home, "c1", "-p", String.valueOf(port), "ping");
		assertEquals(1, ping.exit, () -> "dead server should require init: " + ping);
		assertTrue(ping.stderr.contains("no workspace server is running on port " + port)
				&& ping.stderr.contains("run init again"),
				() -> "expected unavailable-server error: " + ping);
	}

	@Test
	@EnabledIf("isCliJarAvailable")
	void failsLoudlyWhenNoSessionFile(@TempDir Path home) throws Exception {
		// Pick a port that's almost certainly closed.
		int port = 1;
		var result = runJar(home, "c1", "-p", String.valueOf(port), "ping");
		assertEquals(1, result.exit, () -> "expected exit 1, got: " + result);
		assertTrue(result.stderr.contains("no workspace server is running on port " + port),
				"expected unavailable-server error in stderr, got: " + result.stderr);
	}

	static boolean isCliJarAvailable() {
		var jar = System.getProperty("osate.cli.jar");
		return jar != null && Files.exists(Path.of(jar));
	}

	/**
	 * Runs {@code java -jar osate-cli.jar} directly so the caller can set
	 * {@code -Dosate.cli.home}. The launcher script in {@code bin/} doesn't expose system
	 * properties, so tests that need to redirect the session-file location go through this.
	 */
	private RunResult runJar(Path home, String... args) throws IOException, InterruptedException {
		var jar = System.getProperty("osate.cli.jar");
		var cmd = new ArrayList<String>();
		cmd.add(javaBin());
		cmd.add("-Dosate.cli.home=" + home.toAbsolutePath());
		cmd.add("-jar");
		cmd.add(jar);
		for (var a : args) {
			cmd.add(a);
		}
		var pb = new ProcessBuilder(cmd);
		pb.environment().put("OSATE_CLI_SERVER_LAUNCH", "direct");
		pb.redirectErrorStream(false);
		var proc = pb.start();
		var stdoutFut = readAsync(proc.getInputStream());
		var stderrFut = readAsync(proc.getErrorStream());
		if (!proc.waitFor(60, TimeUnit.SECONDS)) {
			proc.destroyForcibly();
			fail("osate-cli did not exit in 60s: " + cmd);
		}
		try {
			return new RunResult(proc.exitValue(), stdoutFut.get(), stderrFut.get());
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	private static String javaBin() {
		var javaHome = System.getProperty("java.home");
		var sep = System.getProperty("file.separator");
		var bin = javaHome + sep + "bin" + sep + "java";
		return Files.exists(Path.of(bin)) ? bin : "java";
	}

	private record Resp(List<String> lines, String status) {
		@Override
		public String toString() {
			return "lines=" + lines + " status=" + status;
		}
	}

	private Resp send(int port, String requestLine) throws IOException {
		try (var sock = new Socket()) {
			sock.connect(new InetSocketAddress("127.0.0.1", port), 5000);
			sock.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
			try (var out = new PrintWriter(
					new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), false);
					var in = new BufferedReader(
							new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
				out.print(requestLine);
				out.print('\n');
				out.flush();
				sock.shutdownOutput();
				var lines = new ArrayList<String>();
				String status = "";
				boolean inStatus = false;
				String line;
				while ((line = in.readLine()) != null) {
					if (!inStatus) {
						if (line.isEmpty()) {
							inStatus = true;
						} else {
							lines.add(line);
						}
					} else if (status.isEmpty()) {
						status = line;
					}
				}
				return new Resp(lines, status);
			}
		}
	}

	private record RunResult(int exit, String stdout, String stderr) {
		@Override
		public String toString() {
			return "exit=" + exit + " stdout=<<" + stdout.strip() + ">> stderr=<<" + stderr.strip() + ">>";
		}
	}

	private RunResult run(List<String> cliArgs) throws IOException, InterruptedException {
		var bin = Path.of(System.getProperty("osate.cli.bin"));
		var cmd = new ArrayList<String>();
		cmd.add(launcher(bin).toString());
		cmd.addAll(cliArgs);
		var pb = new ProcessBuilder(cmd);
		pb.environment().put("OSATE_CLI_SERVER_LAUNCH", "direct");
		pb.redirectErrorStream(false);
		var proc = pb.start();
		var stdoutFut = readAsync(proc.getInputStream());
		var stderrFut = readAsync(proc.getErrorStream());
		if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			proc.destroyForcibly();
			fail("CLI invocation did not terminate within " + TIMEOUT_SECONDS + "s: " + cmd);
		}
		try {
			return new RunResult(proc.exitValue(), stdoutFut.get(), stderrFut.get());
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	private static java.util.concurrent.Future<String> readAsync(java.io.InputStream in) {
		return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static List<String> nonEmptyLines(String s) {
		var out = new ArrayList<String>();
		for (var l : s.split("\\R")) {
			if (!l.isEmpty()) {
				out.add(l);
			}
		}
		return out;
	}

	private static boolean anyAaxlExists(Path workspace) throws IOException {
		try (var stream = Files.walk(workspace)) {
			return stream.filter(Files::isRegularFile)
					.anyMatch(p -> p.getFileName().toString().endsWith(".aaxl2"));
		}
	}

	private static List<String> reportPathLines(List<String> lines) {
		return lines.stream()
				.filter(l -> l.endsWith(".html") || l.endsWith(".dot") || l.endsWith(".smv"))
				.toList();
	}

	private static void assertReachabilityDiagnostics(List<String> diagLines) {
		var diagPattern = Pattern.compile(
				"^.+\\.aaxl2:[^:]+: (error|warning|info|hint): .+$");
		assertFalse(diagLines.isEmpty(), "expected reachability diagnostics");
		for (var l : diagLines) {
			assertTrue(diagPattern.matcher(l).matches(),
					() -> "unexpected diag line: " + l);
		}
		assertTrue(diagLines.stream().anyMatch(l -> l.contains("is not reachable")),
				() -> "expected unreachable SOM diagnostic, got: " + diagLines);
	}

	private static String reachabilityModel() {
		return """
				package modetest
				public
					system S
						features
							e0: in event port;
							e1: in event port;
					end S;

					system implementation S.i
						subcomponents
							s0: system R.i1 in modes (m1 => m12);
							s1: system S.i2;
						connections
							c00: port e1 -> s0.e1;
							c01: port e0 -> s1.e1;
						modes
							m0: initial mode;
							m1: mode;
							m0 -[e0]-> m1;
							m1 -[e0]-> m0;
					end S.i;

					system R extends S
						requires modes
							m10: mode;
							m11: mode;
							m12: mode;
					end R;

					system implementation R.i1
						subcomponents
							a: system S.i2 in modes (m11);
							b: system S.i2 in modes (m12);
						connections
							c11: port e1 -> a.e1;
							c12: port e1 -> b.e1;
					end R.i1;

					system implementation S.i2
						modes
							m20: initial mode;
							m21: mode;
							m22: mode;
							m20 -[e1]-> m21;
							m21 -[e1]-> m22;
					end S.i2;
				end modetest;
				""";
	}

	private static void copyFixture(Path workspace) throws IOException {
		var src = Path.of(System.getProperty("osate.cli.fixture")).toAbsolutePath();
		assertNotNull(src, "osate.cli.fixture system property not set");
		try (var stream = Files.walk(src)) {
			stream.filter(Files::isRegularFile).forEach(p -> {
				try {
					var rel = src.relativize(p);
					var dst = workspace.resolve(rel);
					Files.createDirectories(dst.getParent() == null ? workspace : dst.getParent());
					Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}

	private static void assertSameServer(Path workspace, long expectedPid, List<Path> expectedLogs)
			throws IOException {
		var marker = workspace.resolve(".osate-cli/server.json");
		assertEquals(expectedPid, markerPid(marker), "workspace-server PID changed between commands");
		assertTrue(ProcessHandle.of(expectedPid).map(ProcessHandle::isAlive).orElse(false),
				"workspace-server process is no longer alive: " + expectedPid);
		assertEquals(expectedLogs, serverLogFiles(workspace),
				"a command created another workspace-server log");
	}

	private static long markerPid(Path marker) throws IOException {
		assertTrue(Files.isRegularFile(marker), "expected workspace-server marker: " + marker);
		var matcher = Pattern.compile("\\\"pid\\\"\\s*:\\s*(\\d+)").matcher(Files.readString(marker));
		assertTrue(matcher.find(), "workspace-server marker has no PID: " + marker);
		return Long.parseLong(matcher.group(1));
	}

	private static List<Path> serverLogFiles(Path workspace) throws IOException {
		var stateDirectory = workspace.resolve(".osate-cli");
		if (!Files.isDirectory(stateDirectory)) {
			return List.of();
		}
		try (var stream = Files.list(stateDirectory)) {
			return stream.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().matches("server-\\d+\\.log"))
					.sorted()
					.toList();
		}
	}

	private static void waitUntilDead(int port, long timeoutMs) throws InterruptedException {
		var deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			try (var s = new java.net.Socket()) {
				s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
			} catch (IOException e) {
				return;
			}
			Thread.sleep(200);
		}
		fail("server on port " + port + " still accepting connections after " + timeoutMs + "ms");
	}
}
