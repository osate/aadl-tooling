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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCommandsTest {

	@TempDir
	Path workspace;

	@Test
	void createsStandardProjectFile() throws Exception {
		var result = run("create", "aadl");

		assertEquals(0, result.exitCode());
		assertEquals("Created " + workspace.resolve("aadl/.project") + System.lineSeparator(), result.stdout());
		assertEquals("", result.stderr());
		assertEquals("""
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>aadl</name>
					<comment></comment>
					<projects>
					</projects>
					<buildSpec>
						<buildCommand>
							<name>org.eclipse.xtext.ui.shared.xtextBuilder</name>
							<arguments>
							</arguments>
						</buildCommand>
					</buildSpec>
					<natures>
						<nature>org.osate.core.aadlnature</nature>
						<nature>org.eclipse.xtext.ui.shared.xtextNature</nature>
					</natures>
				</projectDescription>
				""", Files.readString(workspace.resolve("aadl/.project")));
	}

	@Test
	void createsAndListsProjectDependencies() throws Exception {
		assertEquals(0, run("create", "aadl").exitCode());
		assertEquals(0, run("create", "aadl1", "--depends-on", "aadl").exitCode());

		var list = run("list");
		assertEquals(0, list.exitCode());
		assertEquals("aadl" + System.lineSeparator() + "aadl1 -> aadl" + System.lineSeparator(), list.stdout());

		var show = run("show", "aadl1");
		assertEquals(0, show.exitCode());
		assertTrue(show.stdout().contains("Name: aadl1"));
		assertTrue(show.stdout().contains("Directory: " + workspace.resolve("aadl1")));
		assertTrue(show.stdout().contains("Dependencies:" + System.lineSeparator() + "  aadl"));
	}

	@Test
	void requiresAdoptForExistingDirectory() throws Exception {
		Files.createDirectory(workspace.resolve("aadl"));

		var refused = run("create", "aadl");
		assertEquals(1, refused.exitCode());
		assertTrue(refused.stderr().contains("use --adopt"));
		assertFalse(Files.exists(workspace.resolve("aadl/.project")));

		var adopted = run("create", "aadl", "--adopt");
		assertEquals(0, adopted.exitCode());
		assertTrue(Files.isRegularFile(workspace.resolve("aadl/.project")));
	}

	@Test
	void refusesDuplicateDeclaredProjectName() throws Exception {
		writeProject("alias", "aadl", List.of(), true);

		var result = run("create", "aadl");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("project already exists: aadl"));
		assertFalse(Files.exists(workspace.resolve("aadl")));
	}

	@Test
	void addsAndRemovesDependenciesIdempotently() throws Exception {
		run("create", "aadl");
		run("create", "aadl1");

		assertEquals(0, run("add-dependency", "aadl1", "aadl", "aadl").exitCode());
		assertEquals("aadl" + System.lineSeparator() + "aadl1 -> aadl" + System.lineSeparator(),
				run("list").stdout());
		assertTrue(run("add-dependency", "aadl1", "aadl").stdout().contains("unchanged"));

		assertEquals(0, run("remove-dependency", "aadl1", "aadl", "aadl").exitCode());
		assertEquals("aadl" + System.lineSeparator() + "aadl1" + System.lineSeparator(), run("list").stdout());
		assertTrue(run("remove-dependency", "aadl1", "aadl").stdout().contains("unchanged"));
	}

	@Test
	void rejectsMissingAndSelfDependencies() throws Exception {
		run("create", "aadl");

		var missing = run("add-dependency", "aadl", "missing");
		assertEquals(1, missing.exitCode());
		assertTrue(missing.stderr().contains("project not found"));

		var self = run("add-dependency", "aadl", "aadl");
		assertEquals(1, self.exitCode());
		assertTrue(self.stderr().contains("cannot depend on itself"));
	}

	@Test
	void validatesStandardWorkspace() throws Exception {
		run("create", "aadl");
		run("create", "aadl1", "--depends-on", "aadl");

		var result = run("validate");
		assertEquals(0, result.exitCode());
		assertEquals("Valid workspace: 2 project(s), 0 warning(s)" + System.lineSeparator(), result.stdout());
		assertEquals("", result.stderr());
	}

	@Test
	void validationReportsMetadataProblemsAndCycles() throws Exception {
		writeProject("one", "duplicate", List.of("duplicate", "missing", "missing"), true);
		writeProject("two", "duplicate", List.of(), false);

		var result = run("validate");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("duplicate project name: duplicate"));
		assertTrue(result.stderr().contains("duplicate dependency: missing"));
		assertTrue(result.stderr().contains("dependency not found: missing"));
		assertTrue(result.stderr().contains("project depends on itself"));
		assertTrue(result.stderr().contains("missing Xtext nature"));
		assertTrue(result.stderr().contains("directory name 'one' differs"));
	}

	@Test
	void validationReportsDependencyCycle() throws Exception {
		writeProject("one", "one", List.of("two"), true);
		writeProject("two", "two", List.of("one"), true);

		var result = run("validate");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("dependency cycle: one -> two -> one"));
	}

	@Test
	void validationReportsMalformedProjectFile() throws Exception {
		var directory = Files.createDirectory(workspace.resolve("broken"));
		Files.writeString(directory.resolve(".project"), "<projectDescription>");

		var result = run("validate");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("cannot read .project"));
		assertTrue(result.stderr().contains("no AADL projects found"));
	}

	@Test
	void dependencyEditsPreserveOtherProjectMetadata() throws Exception {
		run("create", "aadl");
		run("create", "aadl1");
		var file = workspace.resolve("aadl1/.project");
		var source = Files.readString(file).replace("<comment></comment>",
				"<comment>keep me</comment><custom>also keep me</custom>");
		Files.writeString(file, source);

		assertEquals(0, run("add-dependency", "aadl1", "aadl").exitCode());
		var updated = Files.readString(file);
		assertTrue(updated.contains("<comment>keep me</comment>"));
		assertTrue(updated.contains("<custom>also keep me</custom>"));
	}

	@Test
	void rejectsUnsafeProjectDirectoryName() throws Exception {
		var result = run("create", "../outside");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("invalid project name"));
		assertFalse(Files.exists(workspace.getParent().resolve("outside")));
	}

	private void writeProject(String directory, String name, List<String> dependencies, boolean includeXtextNature)
			throws IOException {
		var projectDirectory = Files.createDirectory(workspace.resolve(directory));
		var dependencyXml = new StringBuilder();
		for (var dependency : dependencies) {
			dependencyXml.append("<project>").append(dependency).append("</project>");
		}
		var xtextNature = includeXtextNature
				? "<nature>org.eclipse.xtext.ui.shared.xtextNature</nature>"
				: "";
		Files.writeString(projectDirectory.resolve(".project"), """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
				  <name>%s</name>
				  <projects>%s</projects>
				  <buildSpec><buildCommand><name>org.eclipse.xtext.ui.shared.xtextBuilder</name></buildCommand></buildSpec>
				  <natures><nature>org.osate.core.aadlnature</nature>%s</natures>
				</projectDescription>
				""".formatted(name, dependencyXml, xtextNature));
	}

	private CommandResult run(String... args) throws IOException {
		var stdout = new ByteArrayOutputStream();
		var stderr = new ByteArrayOutputStream();
		int exitCode;
		try (var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
				var err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
			exitCode = ProjectCommands.run(workspace, List.of(args), out, err);
		}
		return new CommandResult(exitCode, stdout.toString(StandardCharsets.UTF_8),
				stderr.toString(StandardCharsets.UTF_8));
	}

	private record CommandResult(int exitCode, String stdout, String stderr) {
	}
}
