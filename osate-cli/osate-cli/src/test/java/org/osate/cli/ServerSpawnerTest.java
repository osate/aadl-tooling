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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@ResourceLock("osate.cli.home")
class ServerSpawnerTest {
	private Path testServerJar;

	@BeforeEach
	void createDiscoverableServerJar() throws Exception {
		var classes = Path.of(ServerSpawner.class.getProtectionDomain().getCodeSource()
				.getLocation().toURI());
		testServerJar = classes.resolve("osate-workspace-server-test.jar");
		Files.write(testServerJar, new byte[0]);
	}

	@AfterEach
	void removeDiscoverableServerJar() throws Exception {
		Files.deleteIfExists(testServerJar);
	}

	@Test
	void successfulHandshakeUsesMarkerPidAndWritesSupervisorMetadata(
			@TempDir Path root, @TempDir Path home) throws Exception {
		withHome(home, () -> {
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);
			var runtime = runtime(Map.of(ServerLaunchMode.ENV_VAR, "direct"), "Plan9", executor);
			var args = args(root, 3);

			var port = ServerSpawner.spawn(args, runtime);

			assertEquals(43210, port);
			var session = SessionFile.read(port).orElseThrow();
			assertEquals(ProcessHandle.current().pid(), session.pid());
			assertEquals("direct", session.supervisorKind());
			assertEquals("", session.supervisorId());
			assertEquals(1, executor.startCount());
			assertRunDirectoryEmpty(home);
		});
	}

	@Test
	void malformedPortStopsStartedProcess(@TempDir Path root, @TempDir Path home) throws Exception {
		withHome(home, () -> {
			var executor = new StartupExecutor(root, StartupBehavior.MALFORMED_PORT);
			var failure = assertThrows(IOException.class,
					() -> ServerSpawner.spawn(args(root, 2),
							runtime(directEnvironment(), "Plan9", executor)));
			assertTrue(failure.getMessage().contains("unexpected first line"));
			assertTrue(executor.process.destroyed);
			assertRunDirectoryEmpty(home);
		});
	}

	@Test
	void missingOrMismatchedMarkerStopsStartedProcess(@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			var missing = new StartupExecutor(root, StartupBehavior.MISSING_MARKER);
			assertThrows(IOException.class, () -> ServerSpawner.spawn(args(root, 2),
					runtime(directEnvironment(), "Plan9", missing)));
			assertTrue(missing.process.destroyed);

			var mismatch = new StartupExecutor(root, StartupBehavior.MISMATCHED_MARKER);
			assertThrows(IOException.class, () -> ServerSpawner.spawn(args(root, 2),
					runtime(directEnvironment(), "Plan9", mismatch)));
			assertTrue(mismatch.process.destroyed);
		});
	}

	@Test
	void stoppedProcessFailsEarly(@TempDir Path root, @TempDir Path home) throws Exception {
		withHome(home, () -> {
			var executor = new StartupExecutor(root, StartupBehavior.STOPPED);
			var failure = assertThrows(IOException.class, () -> ServerSpawner.spawn(args(root, 3),
					runtime(directEnvironment(), "Plan9", executor)));
			assertTrue(failure.getMessage().contains("stopped during startup"));
		});
	}

	@Test
	void timeoutStopsProcessAndCapsStartupStderr(@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			var executor = new StartupExecutor(root, StartupBehavior.TIMEOUT);
			var failure = assertThrows(IOException.class, () -> ServerSpawner.spawn(args(root, 1),
					runtime(directEnvironment(), "Plan9", executor)));
			assertTrue(failure.getMessage().contains("timed out waiting for workspace-server startup"));
			assertTrue(failure.getMessage().length()
					<= "timed out waiting for workspace-server startup; startup stderr: ".length() + 4096);
			assertTrue(executor.process.destroyed);
		});
	}

	@Test
	void autoFallbackWarningIsExact(@TempDir Path root, @TempDir Path home) throws Exception {
		withHome(home, () -> {
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);
			var stderr = new ByteArrayOutputStream();
			var runtime = new ServerSpawner.RuntimeContext(Map.of(), "Plan9", executor,
					new PrintStream(stderr, true, StandardCharsets.UTF_8));

			ServerSpawner.spawn(args(root, 3), runtime);

			assertEquals("WARN detached workspace-server launch unavailable "
					+ "(unsupported operating system); using direct child process\n",
					stderr.toString(StandardCharsets.UTF_8));
		});
	}

	@Test
	void strictManagedFailureNeverStartsDirectProcess(@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);
			var failure = assertThrows(IOException.class, () -> ServerSpawner.spawn(args(root, 2),
					runtime(Map.of(ServerLaunchMode.ENV_VAR, "managed"), "Plan9", executor)));
			assertEquals("managed workspace-server launch unavailable: unsupported operating system",
					failure.getMessage());
			assertEquals(0, executor.startCount());
		});
	}

	@Test
	void liveMarkerReuseNeverStartsSupervisor(@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			writeMarker(root, 45123, ProcessHandle.current().pid());
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);

			var port = ServerSpawner.spawn(args(root, 2),
					runtime(directEnvironment(), "Plan9", executor), candidate -> true);

			assertEquals(45123, port);
			assertEquals(0, executor.startCount());
		});
	}

	@Test
	void staleMarkerIsRemovedWhileStartupLockIsHeld(@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			writeMarker(root, 45124, Long.MAX_VALUE);
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);
			executor.requireAbsentMarkerAndHeldLock = true;

			assertEquals(43210, ServerSpawner.spawn(args(root, 2),
					runtime(directEnvironment(), "Plan9", executor), candidate -> false));
			assertTrue(executor.observedAbsentMarkerAndHeldLock);
		});
	}

	@Test
	void initRemovesDeadSessionsForTheWorkspace(@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			int oldPort = 45124;
			SessionFile.write(new SessionFile.Session(oldPort, List.of(root), "c1", 300,
					Long.MAX_VALUE, 1L));
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);

			assertEquals(43210, ServerSpawner.spawn(args(root, 2),
					runtime(directEnvironment(), "Plan9", executor), candidate -> false));

			assertTrue(SessionFile.read(oldPort).isEmpty());
			assertTrue(SessionFile.read(43210).isPresent());
		});
	}

	@Test
	void concurrentStartsCreateOneServerAndOneReuse(@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);
			var runtime = runtime(directEnvironment(), "Plan9", executor);
			try (var pool = Executors.newFixedThreadPool(2)) {
				var first = pool.submit(() -> ServerSpawner.spawn(args(root, 3), runtime,
						candidate -> true));
				var second = pool.submit(() -> ServerSpawner.spawn(args(root, 3), runtime,
						candidate -> true));

				assertEquals(43210, first.get(5, TimeUnit.SECONDS));
				assertEquals(43210, second.get(5, TimeUnit.SECONDS));
			}
			assertEquals(1, executor.startCount());
		});
	}

	@Test
	void unavailableSessionCleanupRemovesDeadSessionAndMarkerUnderLock(
			@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			int port = 45125;
			SessionFile.write(new SessionFile.Session(port, List.of(root), "c1", 300,
					Long.MAX_VALUE, 1L));
			writeMarker(root, port, Long.MAX_VALUE);
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);
			var probedWhileLocked = new AtomicBoolean();

			var cleaned = ServerSpawner.cleanupUnavailableSession(port,
					runtime(directEnvironment(), "Plan9", executor), candidate -> {
						probedWhileLocked.set(isLocked(root.resolve(".osate-cli/server.lock")));
						return false;
					});

			assertTrue(cleaned);
			assertTrue(probedWhileLocked.get());
			assertTrue(SessionFile.read(port).isEmpty());
			assertFalse(Files.exists(root.resolve(".osate-cli/server.json")));
			assertEquals(0, executor.startCount());
		});
	}

	@Test
	void unavailableSessionCleanupDoesNotDisturbNewerLiveServer(
			@TempDir Path root, @TempDir Path home)
			throws Exception {
		withHome(home, () -> {
			int oldPort = 45125;
			int newPort = 45126;
			var supervisorId = ServerSupervisors.serviceId("launchd", root);
			SessionFile.write(new SessionFile.Session(oldPort, List.of(root), "c1", 300,
					Long.MAX_VALUE, 1L, "launchd", supervisorId));
			writeMarker(root, newPort, ProcessHandle.current().pid());
			var executor = new StartupExecutor(root, StartupBehavior.SUCCESS);

			var cleaned = ServerSpawner.cleanupUnavailableSession(oldPort,
					runtime(Map.of(), "Plan9", executor), candidate -> candidate == newPort);

			assertTrue(cleaned);
			assertTrue(SessionFile.read(oldPort).isEmpty());
			assertTrue(Files.exists(root.resolve(".osate-cli/server.json")));
			assertEquals(0, executor.startCount());
		});
	}

	@Test
	void acceptedManagerLaunchFailureDoesNotFallBackToDirect(
			@TempDir Path root, @TempDir Path home, @TempDir Path bin) throws Exception {
		withHome(home, () -> {
			var systemdRun = executable(bin.resolve("systemd-run"));
			var systemctl = executable(bin.resolve("systemctl"));
			var executor = new ManagerFailureExecutor(systemdRun, systemctl);
			var failure = assertThrows(IOException.class, () -> ServerSpawner.spawn(args(root, 2),
					runtime(Map.of("PATH", bin.toString()), "Linux", executor),
					candidate -> false));

			assertEquals("systemd-run failed: manager rejected launch", failure.getMessage());
			assertEquals(0, executor.directStarts);
		});
	}

	@Test
	void managedActivationWindowIsNotMistakenForStoppedService(
			@TempDir Path root, @TempDir Path home, @TempDir Path bin) throws Exception {
		withHome(home, () -> {
			var systemdRun = executable(bin.resolve("systemd-run"));
			var systemctl = executable(bin.resolve("systemctl"));
			var executor = new DelayedManagerExecutor(root, systemdRun, systemctl);

			assertEquals(43210, ServerSpawner.spawn(args(root, 3),
					runtime(Map.of("PATH", bin.toString()), "Linux", executor),
					candidate -> false));

			assertNull(executor.backgroundFailure.get());
			assertEquals("systemd", SessionFile.read(43210).orElseThrow().supervisorKind());
		});
	}

	private static ArgParser.Args args(Path root, int timeoutSec) {
		return new ArgParser.Args("c1", "init", 0, timeoutSec, 300,
				List.of(root.toAbsolutePath().normalize()), List.of());
	}

	private static Map<String, String> directEnvironment() {
		return Map.of(ServerLaunchMode.ENV_VAR, "direct");
	}

	private static ServerSpawner.RuntimeContext runtime(Map<String, String> environment,
			String osName, CommandExecutor executor) {
		return new ServerSpawner.RuntimeContext(environment, osName, executor,
				new PrintStream(OutputStream.nullOutputStream()));
	}

	private static void assertRunDirectoryEmpty(Path home) throws IOException {
		var run = home.resolve(".osate-cli").resolve("run");
		try (var files = Files.list(run)) {
			assertEquals(0, files.count());
		}
	}

	private static Path executable(Path path) throws IOException {
		Files.write(path, new byte[0]);
		assertTrue(path.toFile().setExecutable(true));
		return path.toAbsolutePath().normalize();
	}

	private static boolean isLocked(Path lockFile) {
		try (var channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
				var ignored = channel.tryLock()) {
			return false;
		} catch (OverlappingFileLockException e) {
			return true;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void writeMarker(Path root, int port, long pid) throws IOException {
		var directory = root.resolve(".osate-cli");
		Files.createDirectories(directory);
		var json = new JsonObject();
		json.addProperty("port", port);
		json.addProperty("pid", pid);
		json.addProperty("workspaceRoot", root.toAbsolutePath().toString());
		var roots = new JsonArray();
		roots.add(root.toAbsolutePath().toString());
		json.add("roots", roots);
		Files.writeString(directory.resolve("server.json"), json.toString(),
				StandardCharsets.UTF_8);
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

	private enum StartupBehavior {
		SUCCESS,
		MALFORMED_PORT,
		MISSING_MARKER,
		MISMATCHED_MARKER,
		STOPPED,
		TIMEOUT
	}

	private static final class StartupExecutor implements CommandExecutor {
		private final Path root;
		private final StartupBehavior behavior;
		private final int handshakePort;
		private final FakeProcess process = new FakeProcess();
		private final AtomicInteger starts = new AtomicInteger();
		private volatile boolean requireAbsentMarkerAndHeldLock;
		private volatile boolean observedAbsentMarkerAndHeldLock;

		private StartupExecutor(Path root, StartupBehavior behavior) {
			this(root, behavior, 43210);
		}

		private StartupExecutor(Path root, StartupBehavior behavior, int handshakePort) {
			this.root = root;
			this.behavior = behavior;
			this.handshakePort = handshakePort;
		}

		@Override
		public synchronized Process start(List<String> command, Path stdoutFile, Path stderrFile)
				throws IOException {
			starts.incrementAndGet();
			if (requireAbsentMarkerAndHeldLock) {
				observedAbsentMarkerAndHeldLock = !Files.exists(root.resolve(".osate-cli/server.json"))
						&& isLocked(root.resolve(".osate-cli/server.lock"));
			}
			switch (behavior) {
				case SUCCESS -> {
					writeMarker(root, handshakePort, ProcessHandle.current().pid());
					Files.writeString(stdoutFile, handshakePort + "\n");
				}
				case MALFORMED_PORT -> Files.writeString(stdoutFile, "not-a-port\n");
				case MISSING_MARKER -> Files.writeString(stdoutFile, "43210\n");
				case MISMATCHED_MARKER -> {
					writeMarker(root, 43211, ProcessHandle.current().pid());
					Files.writeString(stdoutFile, "43210\n");
				}
				case STOPPED -> process.alive = false;
				case TIMEOUT -> Files.writeString(stderrFile, "x".repeat(5000));
			}
			return process;
		}

		@Override
		public CommandResult run(List<String> command, Duration timeout) {
			throw new AssertionError("unexpected manager command: " + command);
		}

		private int startCount() {
			return starts.get();
		}
	}

	private static final class ManagerFailureExecutor implements CommandExecutor {
		private final Path systemdRun;
		private final Path systemctl;
		private int directStarts;

		private ManagerFailureExecutor(Path systemdRun, Path systemctl) {
			this.systemdRun = systemdRun;
			this.systemctl = systemctl;
		}

		@Override
		public Process start(List<String> command, Path stdoutFile, Path stderrFile) {
			directStarts++;
			throw new AssertionError("manager failure must not fall back to direct");
		}

		@Override
		public CommandResult run(List<String> command, Duration timeout) {
			if (command.equals(List.of(systemctl.toString(), "--user", "show-environment"))) {
				return new CommandResult(0, "", "");
			}
			if (command.get(0).equals(systemdRun.toString())) {
				return new CommandResult(1, "", "manager rejected launch");
			}
			return new CommandResult(1, "", "Unit not found");
		}
	}

	private static final class DelayedManagerExecutor implements CommandExecutor {
		private final Path root;
		private final Path systemdRun;
		private final Path systemctl;
		private final AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();

		private DelayedManagerExecutor(Path root, Path systemdRun, Path systemctl) {
			this.root = root;
			this.systemdRun = systemdRun;
			this.systemctl = systemctl;
		}

		@Override
		public Process start(List<String> command, Path stdoutFile, Path stderrFile) {
			throw new AssertionError("managed launch must not start a direct process");
		}

		@Override
		public CommandResult run(List<String> command, Duration timeout) {
			if (command.equals(List.of(systemctl.toString(), "--user", "show-environment"))) {
				return new CommandResult(0, "", "");
			}
			if (command.get(0).equals(systemdRun.toString())) {
				var stdout = command.stream()
						.filter(argument -> argument.startsWith("--property=StandardOutput=file:"))
						.findFirst()
						.orElseThrow()
						.substring("--property=StandardOutput=file:".length());
				Thread.ofVirtual().start(() -> {
					try {
						Thread.sleep(250);
						writeMarker(root, 43210, ProcessHandle.current().pid());
						Files.writeString(Path.of(stdout), "43210\n");
					} catch (Throwable e) {
						backgroundFailure.set(e);
					}
				});
				return new CommandResult(0, "", "");
			}
			if (command.contains("is-active")) {
				return new CommandResult(3, "", "activating");
			}
			return new CommandResult(1, "", "Unit not found");
		}
	}

	private static final class FakeProcess extends Process {
		private boolean alive = true;
		private boolean destroyed;

		@Override
		public OutputStream getOutputStream() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() {
			alive = false;
			return 0;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) {
			return !alive;
		}

		@Override
		public int exitValue() {
			if (alive) {
				throw new IllegalThreadStateException();
			}
			return 0;
		}

		@Override
		public void destroy() {
			destroyed = true;
			alive = false;
		}

		@Override
		public Process destroyForcibly() {
			destroy();
			return this;
		}

		@Override
		public boolean isAlive() {
			return alive;
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
