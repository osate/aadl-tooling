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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the workspace server directly over its raw line protocol. End-to-end CLI scenarios
 * that involve the {@code osate-cli} launcher live in the {@code dist} module's
 * {@code CliEndToEndIT}.
 */
@EnabledIf("isWorkspaceServerJarAvailable")
class EndToEndIT {

	private static final Pattern DIAG_LINE = Pattern.compile("^.+:\\d+:\\d+: (error|warning|info|hint): .+$");

	static boolean isWorkspaceServerJarAvailable() {
		var jar = System.getProperty("workspace.server.jar");
		return jar != null && Files.exists(Path.of(jar));
	}

	@Test
	void checkUpdateInstantiateExitCycle(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);
		var aadl = workspace.resolve("control.aadl");

		var proc = startServer(workspace);
		try {
			int port = readFirstLineAsPort(proc);

			var checkResp = send(port, "client1 check " + quote(aadl.toString()));
			assertEquals("OK", checkResp.status, () -> "check failed: " + checkResp);
			for (var l : checkResp.lines) {
				assertTrue(DIAG_LINE.matcher(l).matches(), "unexpected line: " + l);
			}

			var instResp = send(port, "client1 instantiate " + quote(aadl.toString()) + " control::control.impl");
			assertEquals("OK", instResp.status, () -> "instantiate failed: " + instResp);
			assertTrue(Files.isDirectory(workspace.resolve("instances"))
					|| anyAaxlExists(workspace),
					"expected instances directory or *.aaxl2 file under " + workspace);

			var updateResp = send(port, "client1 update");
			assertEquals("OK", updateResp.status, () -> "update failed: " + updateResp);

			var checkAllResp = send(port, "client1 check");
			assertEquals("OK", checkAllResp.status, () -> "check (all) failed: " + checkAllResp);

			var exitResp = send(port, "client1 exit");
			assertEquals("OK", exitResp.status, () -> "exit failed: " + exitResp);

			assertTrue(proc.waitFor(15, TimeUnit.SECONDS), "server did not exit in 15s");
			assertEquals(0, proc.exitValue());
		} finally {
			if (proc.isAlive()) {
				proc.destroyForcibly();
			}
		}
	}

	@Test
	void removesMarkerFileOnIdleTimeout(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);

		var proc = startServer(workspace, "2");
		try {
			int port = readFirstLineAsPort(proc);
			var marker = workspace.resolve(".osate-cli").resolve("server.json");
			assertTrue(Files.exists(marker), "marker file not written: " + marker);

			assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "server did not exit on idle timeout");
			assertEquals(0, proc.exitValue());

			var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (Files.exists(marker) && System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
			assertTrue(!Files.exists(marker),
					"marker file was not removed after idle timeout: " + marker);

			try (var s = new Socket()) {
				s.connect(new InetSocketAddress("127.0.0.1", port), 250);
				throw new AssertionError("expected port " + port + " to be closed after timeout");
			} catch (IOException expected) {
			}
		} finally {
			if (proc.isAlive()) {
				proc.destroyForcibly();
			}
		}
	}

	@Test
	void addRemoveAndListProjects(@TempDir Path wsA, @TempDir Path wsB) throws Exception {
		copyFixture(wsA);
		copyFixture(wsB);
		var fileInB = wsB.resolve("control.aadl");

		var proc = startServer(wsA);
		try {
			int port = readFirstLineAsPort(proc);

			var listInitial = send(port, "client1 list-projects");
			assertEquals("OK", listInitial.status, () -> "list-projects failed: " + listInitial);
			assertEquals(List.of(wsA.toAbsolutePath().toString()), listInitial.lines,
					() -> "expected single root: " + listInitial);

			var addResp = send(port, "client1 add-project " + quote(wsB.toString()));
			assertEquals("OK", addResp.status, () -> "add-project failed: " + addResp);
			for (var l : addResp.lines) {
				assertTrue(DIAG_LINE.matcher(l).matches(), "unexpected line: " + l);
			}
			assertEquals(List.of(wsA.toAbsolutePath().toString(), wsB.toAbsolutePath().toString()),
					MarkerFile.read(MarkerFile.markerPath(wsA)).roots());

			var listBoth = send(port, "client1 list-projects");
			assertEquals("OK", listBoth.status);
			assertEquals(List.of(wsA.toAbsolutePath().toString(), wsB.toAbsolutePath().toString()),
					listBoth.lines, () -> "expected both roots in order: " + listBoth);

			var checkInB = send(port, "client1 check " + quote(fileInB.toString()));
			assertEquals("OK", checkInB.status, () -> "check inside added root failed: " + checkInB);

			var addAgain = send(port, "client1 add-project " + quote(wsB.toString()));
			assertTrue(addAgain.status.startsWith("ERR"), () -> "expected ERR, got: " + addAgain);
			assertTrue(addAgain.status.contains("root already in workspace"),
					() -> "expected duplicate-root error: " + addAgain);

			var removeFirst = send(port, "client1 remove-project " + quote(wsA.toString()));
			assertTrue(removeFirst.status.startsWith("ERR"), () -> "expected ERR, got: " + removeFirst);
			assertTrue(removeFirst.status.contains("cannot remove first root"),
					() -> "expected first-root error: " + removeFirst);

			var removeB = send(port, "client1 remove-project " + quote(wsB.toString()));
			assertEquals("OK", removeB.status, () -> "remove-project failed: " + removeB);
			assertEquals(List.of(wsA.toAbsolutePath().toString()),
					MarkerFile.read(MarkerFile.markerPath(wsA)).roots());

			var listAfter = send(port, "client1 list-projects");
			assertEquals(List.of(wsA.toAbsolutePath().toString()), listAfter.lines,
					() -> "expected single root after removal: " + listAfter);

			var checkAfter = send(port, "client1 check " + quote(fileInB.toString()));
			assertTrue(checkAfter.status.startsWith("ERR"), () -> "expected ERR, got: " + checkAfter);
			assertTrue(checkAfter.status.contains("file not in workspace"),
					() -> "expected file-not-in-workspace: " + checkAfter);

			var removeMissing = send(port, "client1 remove-project " + quote(wsB.toString()));
			assertTrue(removeMissing.status.startsWith("ERR"));
			assertTrue(removeMissing.status.contains("root not in workspace"),
					() -> "expected not-in-workspace: " + removeMissing);

			var listExtra = send(port, "client1 list-projects extra");
			assertTrue(listExtra.status.startsWith("ERR"));
			assertTrue(listExtra.status.contains("usage: list-projects"),
					() -> "expected usage error: " + listExtra);

			send(port, "client1 exit");
			proc.waitFor(15, TimeUnit.SECONDS);
		} finally {
			if (proc.isAlive()) {
				proc.destroyForcibly();
			}
		}
	}

	@Test
	void checkRejectsMissingFile(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);
		var missing = workspace.resolve("does-not-exist.aadl");

		var proc = startServer(workspace);
		try {
			int port = readFirstLineAsPort(proc);

			var resp = send(port, "client1 check " + quote(missing.toString()));
			assertTrue(resp.status.startsWith("ERR"), () -> "expected ERR, got: " + resp);
			assertTrue(resp.status.contains("no such file"),
					() -> "expected no-such-file error: " + resp);

			send(port, "client1 exit");
			proc.waitFor(15, TimeUnit.SECONDS);
		} finally {
			if (proc.isAlive()) {
				proc.destroyForcibly();
			}
		}
	}

	@Test
	void rejectsMismatchedClientId(@TempDir Path workspace) throws Exception {
		copyFixture(workspace);
		var aadl = workspace.resolve("control.aadl");

		var proc = startServer(workspace);
		try {
			int port = readFirstLineAsPort(proc);

			var first = send(port, "client1 check " + quote(aadl.toString()));
			assertEquals("OK", first.status);

			var second = send(port, "client2 check " + quote(aadl.toString()));
			assertTrue(second.status.startsWith("ERR"), "expected ERR, got: " + second.status);
			assertTrue(second.status.contains("busy"), "expected 'busy' in: " + second.status);

			send(port, "client1 exit");
			proc.waitFor(15, TimeUnit.SECONDS);
		} finally {
			if (proc.isAlive()) {
				proc.destroyForcibly();
			}
		}
	}

	private Process startServer(Path workspace) throws IOException {
		return startServer(workspace, "120");
	}

	private Process startServer(Path workspace, String timeoutSec) throws IOException {
		var jar = System.getProperty("workspace.server.jar");
		var cmd = new ArrayList<String>();
		cmd.add(javaBin());
		// The bare server jar in the module's target/ has no sibling lib/ for plugins; the
		// failsafe config supplies the Tycho plugins dir, which we forward to the child JVM.
		var pluginsDir = System.getProperty("aadl.plugins.dir");
		if (pluginsDir != null) {
			cmd.add("-Daadl.plugins.dir=" + pluginsDir);
		}
		cmd.add("-jar");
		cmd.add(jar);
		cmd.add("--server-timeout");
		cmd.add(timeoutSec);
		cmd.add(workspace.toString());
		var pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(false);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		return pb.start();
	}

	private int readFirstLineAsPort(Process proc) throws IOException {
		var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
		var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (System.nanoTime() < deadline) {
			if (reader.ready() || !proc.isAlive()) {
				var line = reader.readLine();
				assertNotNull(line, "server died before emitting port");
				return Integer.parseInt(line.trim());
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}
		throw new IOException("timed out waiting for port from workspace server");
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
			sock.setSoTimeout((int) TimeUnit.SECONDS.toMillis(120));
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

	private static boolean anyAaxlExists(Path workspace) throws IOException {
		try (Stream<Path> stream = Files.walk(workspace)) {
			return stream.filter(Files::isRegularFile)
					.anyMatch(p -> p.getFileName().toString().endsWith(".aaxl2"));
		}
	}

	private static void copyFixture(Path workspace) throws IOException {
		var src = Path.of("src/test/resources/fixtures/simple-aadl-project").toAbsolutePath();
		try (var stream = Files.walk(src)) {
			stream.filter(Files::isRegularFile).forEach(p -> {
				try {
					var rel = src.relativize(p);
					var dst = workspace.resolve(rel);
					Files.createDirectories(dst.getParent());
					Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}

	private static String javaBin() {
		var javaHome = System.getProperty("java.home");
		var sep = System.getProperty("file.separator");
		var bin = javaHome + sep + "bin" + sep + "java";
		return Files.exists(Path.of(bin)) ? bin : "java";
	}

	private static String quote(String s) {
		boolean needs = s.isEmpty();
		for (int i = 0; !needs && i < s.length(); i++) {
			var c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '"' || c == '\\') {
				needs = true;
			}
		}
		if (!needs) {
			return s;
		}
		var sb = new StringBuilder().append('"');
		for (int i = 0; i < s.length(); i++) {
			var c = s.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.append('"').toString();
	}
}
