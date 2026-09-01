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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@ResourceLock("osate.cli.home")
class SessionFileTest {
	@Test
	void managedMetadataRoundTripsAndPreservesRootOrder(@TempDir Path home) throws Exception {
		withHome(home, () -> {
			var roots = List.of(home.resolve("one"), home.resolve("two"));
			var session = new SessionFile.Session(43210, roots, "c1", 300, 1234, 5678,
					"launchd", "org.osate.cli.workspace.abc");

			SessionFile.write(session);

			var actual = SessionFile.read(43210).orElseThrow();
			assertEquals(roots.stream().map(Path::toAbsolutePath).toList(), actual.roots());
			assertEquals("launchd", actual.supervisorKind());
			assertEquals("org.osate.cli.workspace.abc", actual.supervisorId());
			assertFalse(Files.exists(SessionFile.path(43210).resolveSibling("43210.json.tmp")));
		});
	}

	@Test
	void legacyJsonDefaultsToDirect(@TempDir Path home) throws Exception {
		withHome(home, () -> {
			writeJson(12345, baseJson(12345, List.of(home.resolve("workspace"))));

			var session = SessionFile.read(12345).orElseThrow();
			assertEquals("direct", session.supervisorKind());
			assertEquals("", session.supervisorId());
		});
	}

	@Test
	void malformedSupervisorMetadataAndEmptyRootsAreRejected(@TempDir Path home) throws Exception {
		withHome(home, () -> {
			var unknown = baseJson(12001, List.of(home.resolve("workspace")));
			unknown.addProperty("supervisorKind", "unknown");
			unknown.addProperty("supervisorId", "anything");
			writeJson(12001, unknown);
			assertTrue(SessionFile.read(12001).isEmpty());

			var blankManaged = baseJson(12002, List.of(home.resolve("workspace")));
			blankManaged.addProperty("supervisorKind", "systemd");
			blankManaged.addProperty("supervisorId", "");
			writeJson(12002, blankManaged);
			assertTrue(SessionFile.read(12002).isEmpty());

			writeJson(12003, baseJson(12003, List.of()));
			assertTrue(SessionFile.read(12003).isEmpty());
		});
	}

	@Test
	void missingRequiredFieldIsRejected(@TempDir Path home) throws Exception {
		withHome(home, () -> {
			var json = baseJson(12004, List.of(home.resolve("workspace")));
			json.remove("clientId");
			writeJson(12004, json);
			assertTrue(SessionFile.read(12004).isEmpty());
		});
	}

	private static JsonObject baseJson(int port, List<Path> roots) {
		var json = new JsonObject();
		json.addProperty("port", port);
		json.addProperty("clientId", "c1");
		json.addProperty("serverTimeoutSec", 300);
		json.addProperty("pid", 1234);
		json.addProperty("startedAtEpochMs", 5678);
		var rootsJson = new JsonArray();
		roots.forEach(root -> rootsJson.add(root.toAbsolutePath().toString()));
		json.add("roots", rootsJson);
		return json;
	}

	private static void writeJson(int port, JsonObject json) throws Exception {
		var path = SessionFile.path(port);
		Files.createDirectories(path.getParent());
		Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
	}

	private static void withHome(Path home, ThrowingRunnable body) throws Exception {
		var previous = System.getProperty("osate.cli.home");
		System.setProperty("osate.cli.home", home.toString());
		try {
			body.run();
		} finally {
			if (previous == null) {
				System.clearProperty("osate.cli.home");
			} else {
				System.setProperty("osate.cli.home", previous);
			}
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
