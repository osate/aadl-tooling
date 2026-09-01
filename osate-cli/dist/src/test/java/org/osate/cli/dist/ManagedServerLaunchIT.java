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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

@EnabledIf("isManagedLaunchTestEnabled")
class ManagedServerLaunchIT {
	private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	private static final boolean IS_MAC = OS.contains("mac") || OS.contains("darwin");
	private static final boolean IS_LINUX = OS.contains("linux");

	static boolean isManagedLaunchTestEnabled() {
		if (!"true".equalsIgnoreCase(System.getenv("OSATE_CLI_RUN_MANAGED_IT"))
				|| (!IS_MAC && !IS_LINUX)) {
			return false;
		}
		var jar = System.getProperty("osate.cli.jar");
		return jar != null && Files.isRegularFile(Path.of(jar));
	}

	@Test
	void serviceManagerOwnsServerAcrossCliInvocations(
			@TempDir Path workspace, @TempDir Path home) throws Exception {
		copyFixture(workspace);
		int port = -1;
		try {
			var init = run(home, "c1", "init", workspace.toString());
			assertEquals(0, init.exitCode(), () -> "managed init failed: " + init);
			port = Integer.parseInt(init.stdout().strip());

			var marker = workspace.resolve(".osate-cli").resolve("server.json");
			var pid = markerPid(marker);
			assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));

			var parent = ProcessHandle.of(pid)
					.flatMap(ProcessHandle::parent)
					.orElseThrow(() -> new AssertionError("managed server has no parent process"));
			var parentCommand = parent.info().command().orElseGet(() -> {
				try {
					return runCommand(List.of("/bin/ps", "-o", "comm=", "-p",
							String.valueOf(parent.pid()))).stdout().strip();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new CompletionException(e);
				}
			});
			if (IS_MAC) {
				assertTrue(parentCommand.toLowerCase(Locale.ROOT).contains("launchd"),
						() -> "expected launchd parent, got: " + parentCommand);
			} else {
				assertTrue(parentCommand.toLowerCase(Locale.ROOT).contains("systemd"),
						() -> "expected systemd parent, got: " + parentCommand);
			}

			var ping = run(home, "c1", "-p", String.valueOf(port), "ping");
			assertEquals(0, ping.exitCode(), () -> "managed ping failed: " + ping);
			var check = run(home, "c1", "-p", String.valueOf(port), "check");
			assertEquals(0, check.exitCode(), () -> "managed check failed: " + check);
			assertEquals(pid, markerPid(marker), "server PID changed between CLI invocations");

			var sessionFile = home.resolve(".osate-cli").resolve("sessions")
					.resolve(port + ".json");
			var session = JsonParser.parseString(Files.readString(sessionFile)).getAsJsonObject();
			var supervisorKind = session.get("supervisorKind").getAsString();
			var supervisorId = session.get("supervisorId").getAsString();
			assertEquals(IS_MAC ? "launchd" : "systemd", supervisorKind);

			var exit = run(home, "c1", "-p", String.valueOf(port), "exit");
			assertEquals(0, exit.exitCode(), () -> "managed exit failed: " + exit);
			waitFor(() -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), false,
					15_000, "managed server still alive");
			waitFor(() -> Files.exists(marker), false, 5_000, "marker still exists");
			waitFor(() -> Files.exists(sessionFile), false, 5_000, "session still exists");
			waitFor(() -> managerRegistrationActive(supervisorKind, supervisorId), false,
					10_000, "manager registration still active");
			port = -1;
		} finally {
			if (port > 0) {
				run(home, "c1", "-p", String.valueOf(port), "exit");
			}
		}
	}

	private static boolean managerRegistrationActive(String kind, String id)
			throws IOException, InterruptedException {
		List<String> command;
		if ("launchd".equals(kind)) {
			var uid = runCommand(List.of("/usr/bin/id", "-u")).stdout().strip();
			command = List.of("/bin/launchctl", "print", "gui/" + uid + "/" + id);
		} else {
			command = List.of("systemctl", "--user", "is-active", "--quiet", id);
		}
		return runCommand(command).exitCode() == 0;
	}

	private static long markerPid(Path marker) throws IOException {
		return JsonParser.parseString(Files.readString(marker)).getAsJsonObject()
				.get("pid").getAsLong();
	}

	private static void copyFixture(Path workspace) throws IOException {
		var fixture = Path.of(System.getProperty("osate.cli.fixture"));
		try (var files = Files.list(fixture)) {
			for (var source : files.toList()) {
				Files.copy(source, workspace.resolve(source.getFileName()),
						StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static RunResult run(Path home, String... args) throws Exception {
		var jar = System.getProperty("osate.cli.jar");
		var command = new ArrayList<String>();
		command.add(javaBin());
		command.add("-Dosate.cli.home=" + home.toAbsolutePath());
		command.add("-jar");
		command.add(jar);
		command.addAll(List.of(args));
		var builder = new ProcessBuilder(command);
		builder.environment().put("OSATE_CLI_SERVER_LAUNCH", "managed");
		return runProcess(builder);
	}

	private static RunResult runCommand(List<String> command)
			throws IOException, InterruptedException {
		return runProcess(new ProcessBuilder(command));
	}

	private static RunResult runProcess(ProcessBuilder builder)
			throws IOException, InterruptedException {
		var process = builder.start();
		var stdoutReader = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
		var stderrReader = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
		if (!process.waitFor(120, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			fail("process timed out: " + builder.command());
		}
		try {
			return new RunResult(process.exitValue(),
					new String(stdoutReader.join(), StandardCharsets.UTF_8),
					new String(stderrReader.join(), StandardCharsets.UTF_8));
		} catch (CompletionException e) {
			if (e.getCause() instanceof UncheckedIOException io) {
				throw io.getCause();
			}
			throw e;
		}
	}

	private static void waitFor(CheckedBoolean state, boolean expected, long timeoutMillis,
			String failureMessage) throws Exception {
		var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (System.nanoTime() < deadline) {
			if (state.get() == expected) {
				return;
			}
			Thread.sleep(100);
		}
		fail(failureMessage);
	}

	private static byte[] readAll(java.io.InputStream input) {
		try (input) {
			return input.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String javaBin() {
		var bin = Path.of(System.getProperty("java.home"), "bin", "java");
		return Files.exists(bin) ? bin.toString() : "java";
	}

	private record RunResult(int exitCode, String stdout, String stderr) {
		@Override
		public String toString() {
			return "exit=" + exitCode + " stdout=<<" + stdout.strip()
					+ ">> stderr=<<" + stderr.strip() + ">>";
		}
	}

	@FunctionalInterface
	private interface CheckedBoolean {
		boolean get() throws Exception;
	}
}
