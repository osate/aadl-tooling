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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ArgParserTest {

	@Test
	void parsesValidRemoteCommand() {
		var args = ArgParser.parse(new String[] { "c1", "-p", "54321", "ping" });
		assertEquals("c1", args.clientId());
		assertEquals("ping", args.command());
		assertEquals(54321, args.port());
	}

	@Test
	void normalizesCheckFilePath() {
		var args = ArgParser.parse(new String[] { "c1", "-p", "54321", "check", "models/top.aadl" });
		assertEquals(Path.of("models/top.aadl").toAbsolutePath().normalize().toString(), args.commandArgs().get(0));
	}

	@Test
	void normalizesInstantiateFilePath() {
		var args = ArgParser.parse(new String[] {
				"c1", "-p", "54321", "instantiate", "models/top.aadl", "top.impl"
		});
		assertEquals(Path.of("models/top.aadl").toAbsolutePath().normalize().toString(), args.commandArgs().get(0));
		assertEquals("top.impl", args.commandArgs().get(1));
	}

	@Test
	void parsesLocalProjectCommand() {
		var args = ArgParser.parse(new String[] { "project", "create", "aadl1", "--depends-on", "aadl" });
		assertEquals("project", args.command());
		assertEquals(java.util.List.of("create", "aadl1", "--depends-on", "aadl"), args.commandArgs());
		assertEquals("", args.clientId());
	}

	@Test
	void rejectsUnknownLocalProjectCommand() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "project", "delete", "aadl" }));
		assertEquals("unknown project command: delete", e.getMessage());
	}

	@Test
	void rejectsProjectCreateDependencyWithoutValue() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "project", "create", "aadl1", "--depends-on" }));
		assertEquals("--depends-on requires a value", e.getMessage());
	}

	@Test
	void rejectsProjectCreateDependencyFollowedByFlag() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "project", "create", "aadl1", "--depends-on", "--adopt" }));
		assertEquals("--depends-on requires a value", e.getMessage());
	}

	@Test
	void initDefaultsToCurrentDirectory() {
		var args = ArgParser.parse(new String[] { "c1", "init" });
		assertEquals(java.util.List.of(Path.of("").toAbsolutePath().normalize()), args.roots());
	}

	@Test
	void parsesVersionFlags() {
		for (var flag : java.util.List.of("-v", "--version")) {
			var args = ArgParser.parse(new String[] { flag });
			assertEquals("version", args.command(), () -> "flag not recognized: " + flag);
			assertEquals("", args.clientId());
		}
	}

	@Test
	void versionLineReportsPackagedVersion() {
		assertEquals("osate-cli " + Version.get(), ArgParser.versionLine());
	}

	/**
	 * {@code --version} prints this and nothing else, so it must stay one line. The
	 * assembled-CLI test compares that output for exact equality and the packaging
	 * scripts parse it, so appending build provenance here would break both. Provenance
	 * belongs in {@code versionDetail()}, which only {@code help()} prints.
	 */
	@Test
	void versionLineStaysASingleLine() {
		assertEquals(1, ArgParser.versionLine().lines().count());
	}

	@Test
	void versionDetailReportsWhatTheCliWasBuiltFrom() {
		var detail = ArgParser.versionDetail();

		assertTrue(detail.contains("language server"), () -> detail);
		assertTrue(detail.contains("OSATE"), () -> detail);
		assertTrue(detail.contains(Version.languageServerVersion()), () -> detail);
		assertTrue(detail.contains(Version.osateVersion()), () -> detail);
		assertTrue(detail.contains(Version.abbreviate(Version.osateCommit(), 7)), () -> detail);
		// Every line indented, so the banner stays the only flush-left line in help.
		assertTrue(detail.lines().allMatch(line -> line.startsWith("  ")), () -> detail);
	}

	@Test
	void helpStartsWithVersionBanner() {
		var help = ArgParser.help();

		assertEquals(ArgParser.versionLine(), help.lines().findFirst().orElseThrow());
		assertTrue(help.contains("osate-cli -v | --version"));
		assertTrue(ArgParser.usage().contains("osate-cli -v | --version"));
	}

	@Test
	void helpIncludesBuildProvenanceUnderTheBanner() {
		var lines = ArgParser.help().lines().toList();

		assertEquals(ArgParser.versionLine(), lines.get(0));
		assertEquals(ArgParser.versionDetail(), String.join("\n", lines.subList(1, 3)));
	}

	@Test
	void helpMatchesLocalAndLanguageServerDispatch() {
		var help = ArgParser.help();

		assertTrue(help.contains("Local commands\n--------------"));
		assertTrue(help.contains("Language-server commands\n------------------------"));
		assertTrue(help.contains("Required for remote commands only; omit it for help, project,"));
		assertTrue(help.contains("Project command failures:"));
		assertTrue(help.contains("Local project operation failures (printed to stderr):"));
		assertTrue(help.contains("a local project operation or validation failed"));
		assertTrue(help.contains("OSATE_CLI_SERVER_LAUNCH"));
		assertTrue(help.contains("auto, managed, or direct"));
		assertTrue(help.contains("init [--timeout <s>] [--server-timeout <s>] [<root>...]"));
		assertFalse(help.contains("Required for every command except 'init' and 'help'."));
		assertFalse(help.lines().anyMatch(line -> line.indexOf('\t') >= 0),
				() -> "rendered help contains a tab: " + help);
		assertFalse(ArgParser.usage().contains("OSATE_CLI_SERVER_LAUNCH"));

		for (var command : ArgParser.REMOTE_COMMANDS) {
			assertTrue(help.contains("\n" + command), () -> "help omits remote command: " + command);
		}
		for (var command : java.util.List.of("list", "create", "show", "add-dependency",
				"remove-dependency", "validate")) {
			assertTrue(help.contains("\nproject " + command), () -> "help omits project command: " + command);
		}
	}

	@Test
	void parsesAnalyzeBusLoadCommand() {
		var args = ArgParser.parse(new String[] { "c1", "-p", "54321", "analyze-bus-load", "instances/top.aaxl2" });
		assertEquals("analyze-bus-load", args.command());
		assertEquals(1, args.commandArgs().size());
		assertTrue(args.commandArgs().get(0).endsWith("instances/top.aaxl2"),
				() -> "expected normalized instance path: " + args.commandArgs());
	}

	@Test
	void rejectsAnalyzeBusLoadWithoutFile() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "-p", "54321", "analyze-bus-load" }));
		assertEquals("usage: analyze-bus-load <file>", e.getMessage());
	}

	@Test
	void parsesAnalyzeModesCommandWithReportsOptedOutByDefault() {
		var args = ArgParser.parse(new String[] { "c1", "-p", "54321", "analyze-modes", "instances/top.aaxl2" });
		assertEquals("analyze-modes", args.command());
		assertEquals(4, args.commandArgs().size());
		assertTrue(args.commandArgs().get(0).endsWith("instances/top.aaxl2"),
				() -> "expected normalized instance path: " + args.commandArgs());
		assertEquals("false", args.commandArgs().get(1));
		assertEquals("false", args.commandArgs().get(2));
		assertEquals("false", args.commandArgs().get(3));
	}

	@Test
	void parsesAnalyzeModesReportFlags() {
		var args = ArgParser.parse(new String[] { "c1", "-p", "54321", "analyze-modes",
				"instances/top.aaxl2", "--dot", "--html", "--smv" });
		assertEquals("analyze-modes", args.command());
		assertEquals("true", args.commandArgs().get(1));
		assertEquals("true", args.commandArgs().get(2));
		assertEquals("true", args.commandArgs().get(3));
	}

	@Test
	void rejectsAnalyzeModesWithoutFile() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "-p", "54321", "analyze-modes" }));
		assertEquals("usage: analyze-modes <file> [--dot] [--html] [--smv]", e.getMessage());
	}

	@Test
	void rejectsAnalyzeModesUnknownFlag() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "-p", "54321", "analyze-modes",
						"instances/top.aaxl2", "--pdf" }));
		assertEquals("unknown flag: --pdf", e.getMessage());
	}

	@Test
	void rejectsNonNumericPort() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "-p", "abc", "ping" }));
		assertTrue(e.getMessage().contains("-p/--port"), () -> "unexpected: " + e.getMessage());
	}

	@Test
	void rejectsPortZero() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "-p", "0", "ping" }));
		assertTrue(e.getMessage().contains("1..65535"), () -> "unexpected: " + e.getMessage());
	}

	@Test
	void rejectsPortAboveRange() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "-p", "99999", "ping" }));
		assertTrue(e.getMessage().contains("1..65535"), () -> "unexpected: " + e.getMessage());
	}

	@Test
	void rejectsNonNumericTimeout() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "init", "--timeout", "soon", "/tmp" }));
		assertTrue(e.getMessage().contains("--timeout"), () -> "unexpected: " + e.getMessage());
	}

	@Test
	void rejectsNonPositiveServerTimeout() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ArgParser.parse(new String[] { "c1", "init", "--server-timeout", "0", "/tmp" }));
		assertTrue(e.getMessage().contains("--server-timeout"), () -> "unexpected: " + e.getMessage());
	}
}
