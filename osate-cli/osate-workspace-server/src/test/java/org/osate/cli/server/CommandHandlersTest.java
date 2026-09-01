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
package org.osate.cli.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class CommandHandlersTest {

	@Test
	void rejectsArgumentsForZeroArgumentCommands(@TempDir Path root) throws Exception {
		var serverStdout = new PipedOutputStream();
		var lsp = new LspClient(new ByteArrayOutputStream(), new PipedInputStream(serverStdout, 1 << 16));
		var handlers = new CommandHandlers(lsp, List.of(root), "client1", null);

		assertEquals("ERR invalid args: usage: ping",
				handlers.dispatch(new LineProtocol.Request("any-client", "ping", List.of("extra"))).status());
		assertEquals("ERR invalid args: usage: update",
				handlers.dispatch(new LineProtocol.Request("client1", "update", List.of("extra"))).status());
		assertEquals("ERR invalid args: usage: exit",
				handlers.dispatch(new LineProtocol.Request("client1", "exit", List.of("extra"))).status());

		serverStdout.close();
	}

	@Test
	void formatsStructuredAnalysisResultsForTheCli(@TempDir Path root) {
		var report = new JsonObject();
		report.addProperty("kind", "csv");
		report.addProperty("uri", root.resolve("report.csv").toUri().toString());
		var reports = new JsonArray();
		reports.add(report);

		var diagnostic = new JsonObject();
		diagnostic.addProperty("severity", "error");
		diagnostic.addProperty("uri", root.resolve("instance.aaxl2").toUri().toString());
		diagnostic.addProperty("elementPath", "top.bus");
		diagnostic.addProperty("message", "Actual bandwidth > budget");
		var diagnostics = new JsonArray();
		diagnostics.add(diagnostic);

		var result = new JsonObject();
		result.addProperty("status", "error");
		result.addProperty("summary", "Bus load analysis completed with errors");
		result.add("reports", reports);
		result.add("diagnostics", diagnostics);

		var lines = CommandHandlers.formatAnalysisResult(result);
		assertEquals("Bus load analysis completed with errors", lines.get(0));
		assertEquals(root.resolve("report.csv").toString(), lines.get(1));
		assertTrue(lines.get(2).endsWith(":top.bus: error: Actual bandwidth > budget"));
	}

	@Test
	void rejectsLegacyStringAnalysisResults() {
		assertThrows(IllegalArgumentException.class,
				() -> CommandHandlers.formatAnalysisResult(new com.google.gson.JsonPrimitive("legacy result")));
	}

}
