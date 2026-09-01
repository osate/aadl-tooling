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
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link LspClient} that do not need the embedded language server.
 */
class LspClientTest {

	/**
	 * When the LS output stream hits EOF, the reader thread must fail every outstanding
	 * request instead of leaving callers blocked until their own {@code .get} timeout.
	 */
	@Test
	@Timeout(5)
	void failsPendingRequestsWhenReaderHitsEof() throws Exception {
		var toServer = new ByteArrayOutputStream();
		var serverStdout = new PipedOutputStream();
		var fromServer = new PipedInputStream(serverStdout, 1 << 16);

		var lsp = new LspClient(toServer, fromServer);
		var future = lsp.sendRequest("aadlServer/waitUntilFinished", null);

		// Simulate the LS closing its output: the reader sees clean EOF.
		serverStdout.close();

		var ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
		assertTrue(ex.getCause() instanceof LspClient.LsErrorException,
				() -> "expected LsErrorException, got: " + ex.getCause());
		assertTrue(ex.getCause().getMessage().contains("connection closed"),
				() -> "unexpected message: " + ex.getCause().getMessage());
	}

	/**
	 * A request issued after the connection has already dropped is failed immediately rather
	 * than queued forever.
	 */
	@Test
	@Timeout(5)
	void failsRequestIssuedAfterReaderClosed() throws Exception {
		var toServer = new ByteArrayOutputStream();
		var serverStdout = new PipedOutputStream();
		var fromServer = new PipedInputStream(serverStdout, 1 << 16);

		var lsp = new LspClient(toServer, fromServer);
		serverStdout.close();
		// Give the reader thread a moment to observe EOF and flip `closed`.
		var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		var future = lsp.sendRequest("shutdown", null);
		while (!future.isDone() && System.nanoTime() < deadline) {
			Thread.sleep(20);
			future = lsp.sendRequest("shutdown", null);
		}

		var f = future;
		var ex = assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
		assertTrue(ex.getCause() instanceof LspClient.LsErrorException,
				() -> "expected LsErrorException, got: " + ex.getCause());
	}

	/**
	 * The initial-build barrier releases once {@code publishDiagnostics} has arrived for every
	 * expected URI — including a clean file whose diagnostics array is empty.
	 */
	@Test
	@Timeout(5)
	void awaitDiagnosticsReleasesWhenAllUrisPublished() throws Exception {
		var toServer = new ByteArrayOutputStream();
		var serverStdout = new PipedOutputStream();
		var fromServer = new PipedInputStream(serverStdout, 1 << 16);

		var lsp = new LspClient(toServer, fromServer);
		var clean = "file:///ws/clean.aadl";
		var broken = "file:///ws/broken.aadl";

		var missingHolder = new AtomicReference<List<String>>();
		var waiter = Thread.ofPlatform().start(() -> {
			try {
				missingHolder.set(lsp.awaitDiagnostics(List.of(clean, broken), 4000));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		// Clean file: empty diagnostics array still lands a map entry.
		writeFrame(serverStdout, publishDiagnostics(clean, 0));
		writeFrame(serverStdout, publishDiagnostics(broken, 2));

		waiter.join(4000);
		assertEquals(List.of(), missingHolder.get(), "barrier should report nothing missing");
	}

	/**
	 * If a file never publishes diagnostics, the barrier returns the still-missing URIs after the
	 * timeout instead of blocking forever.
	 */
	@Test
	@Timeout(5)
	void awaitDiagnosticsReportsMissingOnTimeout() throws Exception {
		var toServer = new ByteArrayOutputStream();
		var serverStdout = new PipedOutputStream();
		var fromServer = new PipedInputStream(serverStdout, 1 << 16);

		var lsp = new LspClient(toServer, fromServer);
		var present = "file:///ws/present.aadl";
		var never = "file:///ws/never.aadl";

		writeFrame(serverStdout, publishDiagnostics(present, 1));

		var missing = lsp.awaitDiagnostics(List.of(present, never), 300);
		assertEquals(List.of(never), missing);
	}

	@Test
	@Timeout(5)
	void pruneDiagnosticsUsesPathBoundaries(@TempDir Path workspace) throws Exception {
		var toServer = new ByteArrayOutputStream();
		var serverStdout = new PipedOutputStream();
		var fromServer = new PipedInputStream(serverStdout, 1 << 16);
		var lsp = new LspClient(toServer, fromServer);
		var removedRoot = workspace.resolve("foo");
		var removedFile = removedRoot.resolve("removed.aadl").toUri().toString();
		var siblingFile = workspace.resolve("foobar/retained.aadl").toUri().toString();

		writeFrame(serverStdout, publishDiagnostics(removedFile, 1));
		writeFrame(serverStdout, publishDiagnostics(siblingFile, 1));
		assertEquals(List.of(), lsp.awaitDiagnostics(List.of(removedFile, siblingFile), 2000));

		lsp.pruneDiagnosticsUnder(removedRoot);

		assertTrue(lsp.diagnosticsFor(removedFile).isEmpty());
		assertEquals(1, lsp.diagnosticsFor(siblingFile).size());
		serverStdout.close();
	}

	private static String publishDiagnostics(String uri, int count) {
		var diags = new StringBuilder();
		for (int i = 0; i < count; i++) {
			if (i > 0) {
				diags.append(',');
			}
			diags.append("{\"message\":\"m").append(i).append("\"}");
		}
		return "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
				+ "\"params\":{\"uri\":\"" + uri + "\",\"diagnostics\":[" + diags + "]}}";
	}

	private static void writeFrame(OutputStream out, String json) throws Exception {
		var payload = json.getBytes(StandardCharsets.UTF_8);
		out.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		out.write(payload);
		out.flush();
	}
}
