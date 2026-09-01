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

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import com.google.gson.JsonParser;

public final class ServerSpawner {
	private static final int STDERR_CAP = 4096;
	private ServerSpawner() {
	}

	public static int spawn(ArgParser.Args args) throws IOException, InterruptedException {
		return spawn(args, RuntimeContext.system());
	}

	public static void cleanupUnavailableSession(int port) throws IOException, InterruptedException {
		cleanupUnavailableSession(port, RuntimeContext.system(), ServerSpawner::probe);
	}

	static int spawn(ArgParser.Args args, RuntimeContext runtime) throws IOException, InterruptedException {
		return spawn(args, runtime, ServerSpawner::probe);
	}

	static int spawn(ArgParser.Args args, RuntimeContext runtime, IntPredicate portProbe)
			throws IOException, InterruptedException {
		var root = args.roots().get(0).toAbsolutePath().normalize();
		var deadline = deadlineAfterSeconds(args.timeoutSec());
		try (var lock = acquireLock(root, deadline, args.timeoutSec())) {
			var markerPath = markerPath(root);
			var marker = readMarker(markerPath);
			if (marker != null && isProcessAlive(marker.pid()) && portProbe.test(marker.port())) {
				warnIfRootsDiffer(marker, args.roots(), runtime.stderr());
				return marker.port();
			}
			Files.deleteIfExists(markerPath);
			cleanupDeadSessions(root, runtime, portProbe);
			return start(args, runtime, deadline);
		}
	}

	static boolean cleanupUnavailableSession(int port, RuntimeContext runtime, IntPredicate portProbe)
			throws IOException, InterruptedException {
		var optionalSession = SessionFile.read(port);
		if (optionalSession.isEmpty() || optionalSession.get().port() != port) {
			return false;
		}
		var session = optionalSession.get();
		var root = session.roots().get(0).toAbsolutePath().normalize();
		var cleanupTimeoutSec = 30;
		var deadline = deadlineAfterSeconds(cleanupTimeoutSec);
		try (var lock = acquireLock(root, deadline, cleanupTimeoutSec)) {
			if (portProbe.test(port)) {
				return false;
			}
			if (isProcessAlive(session.pid())) {
				return false;
			}

			var markerPath = markerPath(root);
			var marker = readMarker(markerPath);
			var liveMarker = marker != null && isProcessAlive(marker.pid())
					&& portProbe.test(marker.port());
			if (!liveMarker) {
				cleanupSupervisor(session, root, runtime);
				Files.deleteIfExists(markerPath);
			}
			SessionFile.delete(port);
			return true;
		}
	}

	private static void cleanupDeadSessions(Path root, RuntimeContext runtime,
			IntPredicate portProbe) throws IOException, InterruptedException {
		var directory = SessionFile.baseDir();
		if (!Files.isDirectory(directory)) {
			return;
		}
		try (var files = Files.list(directory)) {
			for (var file : files.filter(Files::isRegularFile).toList()) {
				var name = file.getFileName().toString();
				if (!name.endsWith(".json")) {
					continue;
				}
				int port;
				try {
					port = Integer.parseInt(name.substring(0, name.length() - ".json".length()));
				} catch (NumberFormatException e) {
					continue;
				}
				var optionalSession = SessionFile.read(port);
				if (optionalSession.isEmpty()) {
					continue;
				}
				var session = optionalSession.get();
				if (session.port() != port
						|| !session.roots().get(0).toAbsolutePath().normalize().equals(root)
						|| isProcessAlive(session.pid()) || portProbe.test(port)) {
					continue;
				}
				cleanupSupervisor(session, root, runtime);
				SessionFile.delete(port);
			}
		}
	}

	private static void cleanupSupervisor(SessionFile.Session session, Path root,
			RuntimeContext runtime) throws IOException, InterruptedException {
		var kind = session.supervisorKind();
		var expected = ServerSupervisors.serviceId(kind, root);
		if ("direct".equals(kind) || !expected.equals(session.supervisorId())) {
			return;
		}
		var supervisor = ServerSupervisors.forSession(kind, runtime.osName(),
				runtime.environment(), runtime.commands());
		if (supervisor.isEmpty()) {
			throw new IOException("cannot clean up " + kind
					+ " workspace-server supervisor on this host");
		}
		supervisor.get().cleanup(expected);
	}

	static void cleanupAfterExit(SessionFile.Session session)
			throws IOException, InterruptedException {
		cleanupAfterExit(session, RuntimeContext.system());
	}

	static void cleanupAfterExit(SessionFile.Session session, RuntimeContext runtime)
			throws IOException, InterruptedException {
		if ("direct".equals(session.supervisorKind())) {
			return;
		}
		var expected = ServerSupervisors.serviceId(session.supervisorKind(), session.roots().get(0));
		if (!expected.equals(session.supervisorId())) {
			throw new IOException("session supervisor ID does not match workspace root");
		}
		var deadline = System.nanoTime() + 7_000_000_000L;
		while (isProcessAlive(session.pid()) && System.nanoTime() < deadline) {
			Thread.sleep(100);
		}
		var supervisor = ServerSupervisors.forSession(session.supervisorKind(), runtime.osName(),
				runtime.environment(), runtime.commands());
		if (supervisor.isEmpty()) {
			throw new IOException("cannot clean up " + session.supervisorKind()
					+ " workspace-server supervisor on this host");
		}
		supervisor.get().cleanup(expected);
	}

	private static int start(ArgParser.Args args, RuntimeContext runtime,
			long deadline)
			throws IOException, InterruptedException {
		var root = args.roots().get(0).toAbsolutePath().normalize();
		var run = SessionFile.baseDir().getParent().resolve("run");
		Files.createDirectories(run);
		var files = createStartupFiles(run);
		ServerSupervisor supervisor = null;
		ServerSupervisor.LaunchHandle handle = null;
		try {
			var mode = ServerLaunchMode.fromEnvironment(runtime.environment());
			var selection = ServerSupervisors.select(mode, runtime.osName(), runtime.environment(),
					runtime.commands());
			if (!selection.fallbackReason().isBlank()) {
				runtime.stderr().println("WARN detached workspace-server launch unavailable ("
						+ selection.fallbackReason() + "); using direct child process");
			}
			var command = buildCommand(args, locateServerJar());
			supervisor = selection.supervisor();
			handle = supervisor.start(new ServerSupervisor.LaunchSpec(command, root,
					files.stdout(), files.stderr()));
			// Native managers may accept a job before reporting it as running. Preserve the
			// supervisor's strict isRunning semantics, but do not mistake that short activation
			// window for a terminated workspace server.
			var activationDeadline = handle.process().isEmpty()
					? System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
					: Long.MIN_VALUE;
			Integer port = null;
			String failure = null;
			while (System.nanoTime() < deadline) {
				var line = firstCompleteLine(files.stdout());
				if (line != null) {
					try {
						var parsedPort = Integer.parseInt(line.strip());
						if (parsedPort >= 1 && parsedPort <= 65535) {
							port = parsedPort;
						} else {
							failure = "workspace server emitted invalid port: " + line.strip();
						}
					} catch (NumberFormatException e) {
						failure = "workspace server emitted unexpected first line: " + line.strip();
					}
					break;
				}
				if (!supervisor.isRunning(handle) && System.nanoTime() >= activationDeadline) {
					failure = "workspace server stopped during startup";
					break;
				}
				Thread.sleep(100);
			}
			if (port == null) {
				throw startupFailure(failure == null ? "timed out waiting for workspace-server startup" : failure,
						files.stderr());
			}
			var marker = readMarker(markerPath(root));
			if (marker == null || marker.port() != port || !isProcessAlive(marker.pid())) {
				throw startupFailure("workspace server startup marker mismatch", files.stderr());
			}
			SessionFile.write(new SessionFile.Session(port, args.roots(), args.clientId(),
					args.serverTimeoutSec(), marker.pid(), System.currentTimeMillis(), handle.kind(),
					handle.serviceId()));
			return port;
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (handle != null && supervisor != null) {
				try {
					supervisor.stop(handle);
				} catch (IOException | InterruptedException | RuntimeException cleanupFailure) {
					e.addSuppressed(cleanupFailure);
					if (cleanupFailure instanceof InterruptedException) {
						Thread.currentThread().interrupt();
					}
				}
			}
			throw e;
		} finally {
			files.close();
		}
	}

	private static StartupFiles createStartupFiles(Path run) throws IOException {
		var stdout = Files.createTempFile(run, "server-", ".out");
		try {
			return new StartupFiles(stdout, Files.createTempFile(run, "server-", ".err"));
		} catch (IOException e) {
			Files.deleteIfExists(stdout);
			throw e;
		}
	}

	private static IOException startupFailure(String message, Path stderr) throws IOException {
		var detail = Files.exists(stderr) ? Files.readString(stderr, StandardCharsets.UTF_8).strip() : "";
		if (detail.length() > STDERR_CAP) {
			detail = detail.substring(0, STDERR_CAP);
		}
		return new IOException(detail.isEmpty() ? message : message + "; startup stderr: " + detail);
	}

	private static String firstCompleteLine(Path file) throws IOException {
		if (!Files.exists(file)) {
			return null;
		}
		var text = Files.readString(file, StandardCharsets.UTF_8);
		var end = text.indexOf('\n');
		return end < 0 ? null : text.substring(0, end);
	}

	private static StartupLock acquireLock(Path root, long deadline, int timeoutSec)
			throws IOException, InterruptedException {
		var dir = root.resolve(".osate-cli");
		Files.createDirectories(dir);
		var channel = FileChannel.open(dir.resolve("server.lock"),
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
		try {
			while (System.nanoTime() < deadline) {
				try {
					var lock = channel.tryLock();
					if (lock != null) {
						return new StartupLock(channel, lock);
					}
				} catch (OverlappingFileLockException ignored) {
				}
				Thread.sleep(100);
			}
			throw new IOException("timed out waiting for workspace-server startup lock after "
					+ timeoutSec + "s");
		} catch (IOException | InterruptedException | RuntimeException e) {
			try {
				channel.close();
			} catch (IOException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw e;
		}
	}

	private static long deadlineAfterSeconds(int seconds) {
		return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
	}

	private static List<String> buildCommand(ArgParser.Args args, Path jar) {
		var command = new ArrayList<String>();
		command.add(javaBin());
		command.add("-jar");
		command.add(jar.toString());
		command.add("--server-timeout");
		command.add(String.valueOf(args.serverTimeoutSec()));
		command.add("--client-id");
		command.add(args.clientId());
		command.add("--session-base");
		command.add(SessionFile.baseDir().toString());
		for (var root : args.roots()) {
			command.add(root.toString());
		}
		return command;
	}

	private static Path markerPath(Path root) {
		return root.resolve(".osate-cli").resolve("server.json");
	}

	private static Path locateServerJar() throws IOException {
		var source = ServerSpawner.class.getProtectionDomain().getCodeSource();
		if (source == null || source.getLocation() == null) {
			throw new IOException("cannot locate own jar");
		}
		try {
			var self = Path.of(source.getLocation().toURI());
			var directory = Files.isRegularFile(self) ? self.getParent() : self;
			var match = singleServerJar(directory);
			if (match.isPresent()) {
				return match.get();
			}
			return singleServerJar(directory.resolve("lib")).orElseThrow();
		} catch (Exception e) {
			throw new IOException("osate-workspace-server jar not found", e);
		}
	}

	/**
	 * Finds the one server jar in {@code directory}. Refuses to guess when a stale jar from a
	 * previous version was left behind, since picking the wrong one runs old server code.
	 */
	private static Optional<Path> singleServerJar(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) {
			return Optional.empty();
		}
		try (var stream = Files.list(directory)) {
			var matches = stream.filter(ServerSpawner::isServerJar).sorted().toList();
			if (matches.size() > 1) {
				throw new IOException("multiple osate-workspace-server jars in " + directory
						+ ": " + matches.stream().map(p -> p.getFileName().toString()).toList()
						+ "; remove the stale one or rebuild with 'mvn clean package'");
			}
			return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
		}
	}

	private static boolean isServerJar(Path path) {
		var name = path.getFileName().toString();
		return name.startsWith("osate-workspace-server") && name.endsWith(".jar");
	}

	private static String javaBin() {
		var path = Path.of(System.getProperty("java.home"), "bin", "java");
		if (Files.exists(path)) {
			return path.toString();
		}
		var executable = path.resolveSibling("java.exe");
		return Files.exists(executable) ? executable.toString() : "java";
	}

	private record Marker(int port, long pid, List<String> roots) {
	}

	private static Marker readMarker(Path file) {
		try {
			var json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
					.getAsJsonObject();
			var roots = new ArrayList<String>();
			if (json.has("roots")) {
				for (var element : json.getAsJsonArray("roots")) {
					roots.add(element.getAsString());
				}
			} else if (json.has("workspaceRoot")) {
				roots.add(json.get("workspaceRoot").getAsString());
			}
			return new Marker(json.get("port").getAsInt(), json.get("pid").getAsLong(),
					List.copyOf(roots));
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isProcessAlive(long pid) {
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	private static void warnIfRootsDiffer(Marker marker, List<Path> roots, PrintStream err) {
		var requested = roots.stream().map(path -> path.toAbsolutePath().toString()).toList();
		if (!marker.roots().isEmpty() && !marker.roots().equals(requested)) {
			err.println("reusing existing server with different workspace roots: " + marker.roots());
		}
	}

	private static boolean probe(int port) {
		try (var socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
			socket.setSoTimeout(1000);
			socket.getOutputStream().write("probe ping\n".getBytes(StandardCharsets.UTF_8));
			socket.getOutputStream().flush();
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("OK");
		} catch (IOException e) {
			return false;
		}
	}

	private record StartupFiles(Path stdout, Path stderr) implements AutoCloseable {
		@Override
		public void close() {
			for (var path : List.of(stdout, stderr)) {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					path.toFile().deleteOnExit();
				}
			}
		}
	}

	private record StartupLock(FileChannel channel, FileLock lock) implements AutoCloseable {
		@Override
		public void close() throws IOException {
			lock.release();
			channel.close();
		}
	}

	record RuntimeContext(Map<String, String> environment, String osName, CommandExecutor commands,
			PrintStream stderr) {
		static RuntimeContext system() {
			return new RuntimeContext(System.getenv(), System.getProperty("os.name", ""),
					new SystemCommandExecutor(), System.err);
		}
	}
}
