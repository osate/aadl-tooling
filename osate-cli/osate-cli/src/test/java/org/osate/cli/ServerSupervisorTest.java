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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerSupervisorTest {
	@Test
	void workspaceHashAndServiceIdsAreStable(@TempDir Path root) {
		var hash = ServerSupervisors.workspaceHash(root);
		assertEquals(24, hash.length());
		assertTrue(hash.matches("[0-9a-f]{24}"));
		assertEquals(hash, ServerSupervisors.workspaceHash(root));
		assertFalse(hash.equals(ServerSupervisors.workspaceHash(root.resolve("other"))));
		assertEquals("org.osate.cli.workspace." + hash,
				ServerSupervisors.serviceId("launchd", root));
		assertEquals("osate-cli-workspace-" + hash + ".service",
				ServerSupervisors.serviceId("systemd", root));
	}

	@Test
	void launchdPlistEscapesArgumentsAndUsesOneShotSettings(@TempDir Path root) {
		var spec = new ServerSupervisor.LaunchSpec(
				List.of("/java", "-Dvalue=<tag>&\"quoted\"'"),
				root,
				root.resolve("out<&.txt"),
				root.resolve("err.txt"));

		var xml = LaunchdServerSupervisor.plistXml("label<&", spec);

		assertTrue(xml.contains("<key>RunAtLoad</key><true/>"));
		assertTrue(xml.contains("<key>KeepAlive</key><false/>"));
		assertTrue(xml.contains("<key>ProcessType</key><string>Standard</string>"));
		assertTrue(xml.contains("<key>StandardInPath</key><string>/dev/null</string>"));
		assertTrue(xml.contains("<key>StandardOutPath</key>"));
		assertTrue(xml.contains("<key>StandardErrorPath</key>"));
		assertTrue(xml.contains("&lt;tag&gt;&amp;&quot;quoted&quot;&apos;"));
		assertTrue(xml.contains("label&lt;&amp;"));
		assertFalse(xml.contains("<tag>"));
		assertFalse(xml.contains("/bin/sh"));
	}

	@Test
	void launchdUsesExactLifecycleCommands(@TempDir Path root) throws Exception {
		var active = new boolean[1];
		var capturedPlist = new String[1];
		var executor = new FakeCommandExecutor(command -> {
			if (command.contains("print") && command.get(command.size() - 1).equals("gui/501")) {
				return ok();
			}
			if (command.contains("bootstrap")) {
				try {
					capturedPlist[0] = Files.readString(Path.of(command.get(command.size() - 1)));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				active[0] = true;
				return ok();
			}
			if (command.contains("bootout")) {
				if (!active[0]) {
					return result(3, "", "Could not find service");
				}
				active[0] = false;
				return ok();
			}
			if (command.contains("print")) {
				return active[0] ? result(0, "state = running\n", "")
						: result(3, "", "Could not find service");
			}
			return ok();
		});
		var supervisor = new LaunchdServerSupervisor(executor, Path.of("/bin/launchctl"), "gui/501");
		var spec = new ServerSupervisor.LaunchSpec(List.of("/java", "-jar", "/server.jar"),
				root, root.resolve("startup.out"), root.resolve("startup.err"));

		assertTrue(supervisor.checkAvailability().available());
		var handle = supervisor.start(spec);
		assertTrue(supervisor.isRunning(handle));
		assertTrue(capturedPlist[0].contains("/server.jar"));
		supervisor.cleanup(handle.serviceId());
		supervisor.cleanup(handle.serviceId());
		assertFalse(active[0]);
		assertTrue(executor.commands.stream().anyMatch(command -> command.size() == 4
				&& command.subList(0, 3).equals(
						List.of("/bin/launchctl", "bootstrap", "gui/501"))));
		assertTrue(executor.commands.stream().anyMatch(command -> command.equals(
				List.of("/bin/launchctl", "print", "gui/501/" + handle.serviceId()))));
		assertTrue(executor.commands.stream().anyMatch(command -> command.equals(
				List.of("/bin/launchctl", "bootout", "gui/501/" + handle.serviceId()))));
	}

	@Test
	void systemdRunCommandHasRequiredOrdering(@TempDir Path root) throws Exception {
		var executor = new FakeCommandExecutor(command -> {
			if (command.contains("stop") || command.contains("reset-failed")) {
				return result(5, "", "Unit not loaded");
			}
			return ok();
		});
		var supervisor = new SystemdServerSupervisor(executor, Path.of("/usr/bin/systemd-run"),
				Path.of("/usr/bin/systemctl"));
		var spec = new ServerSupervisor.LaunchSpec(List.of("/java", "-jar", "/server.jar"),
				root, root.resolve("startup.out"), root.resolve("startup.err"));

		var handle = supervisor.start(spec);
		var launch = executor.commands.stream()
				.filter(command -> command.get(0).endsWith("systemd-run"))
				.findFirst()
				.orElseThrow();

		assertEquals("/usr/bin/systemd-run", launch.get(0));
		assertEquals(List.of("--user", "--quiet", "--collect"),
				launch.subList(1, 4));
		assertEquals("--unit=" + handle.serviceId(), launch.get(4));
		assertEquals("--property=Restart=no", launch.get(5));
		assertEquals("--property=StandardInput=null", launch.get(6));
		assertEquals("--property=StandardOutput=file:" + spec.stdoutFile(), launch.get(7));
		assertEquals("--property=StandardError=file:" + spec.stderrFile(), launch.get(8));
		assertEquals("--", launch.get(9));
		assertEquals(spec.command(), launch.subList(10, launch.size()));
	}

	@Test
	void directSelectionNeverProbesAndManagedUnsupportedFails() throws Exception {
		var executor = new FakeCommandExecutor(command -> {
			throw new AssertionError("direct selection must not execute commands");
		});
		var direct = ServerSupervisors.select(ServerLaunchMode.DIRECT, "Plan9", Map.of(), executor);
		assertInstanceOf(DirectServerSupervisor.class, direct.supervisor());
		assertTrue(executor.commands.isEmpty());

		var managed = assertThrows(IOException.class,
				() -> ServerSupervisors.select(ServerLaunchMode.MANAGED, "Plan9", Map.of(), executor));
		assertEquals("managed workspace-server launch unavailable: unsupported operating system",
				managed.getMessage());

		var auto = ServerSupervisors.select(ServerLaunchMode.AUTO, "Plan9", Map.of(), executor);
		assertInstanceOf(DirectServerSupervisor.class, auto.supervisor());
		assertEquals("unsupported operating system", auto.fallbackReason());
	}

	@Test
	void autoFallsBackOnManagerIoFailureAndManagedIsStrict(@TempDir Path bin) throws Exception {
		var run = executable(bin.resolve("systemd-run"));
		var ctl = executable(bin.resolve("systemctl"));
		var environment = Map.of("PATH", bin.toString());
		var executor = new FakeCommandExecutor(command -> {
			throw new RuntimeException(new IOException("user manager unavailable"));
		});
		executor.ioFailure = new IOException("user manager unavailable");

		var auto = ServerSupervisors.select(ServerLaunchMode.AUTO, "Linux", environment, executor);
		assertInstanceOf(DirectServerSupervisor.class, auto.supervisor());
		assertEquals("user manager unavailable", auto.fallbackReason());

		var failure = assertThrows(IOException.class,
				() -> ServerSupervisors.select(ServerLaunchMode.MANAGED, "Linux", environment, executor));
		assertEquals("managed workspace-server launch unavailable: user manager unavailable",
				failure.getMessage());
		assertTrue(Files.isExecutable(run));
		assertTrue(Files.isExecutable(ctl));
	}

	@Test
	void managerErrorsAreBounded(@TempDir Path root) {
		var executor = new FakeCommandExecutor(command -> result(1, "", "x".repeat(5000)));
		var supervisor = new SystemdServerSupervisor(executor, Path.of("/systemd-run"),
				Path.of("/systemctl"));

		var failure = assertThrows(IOException.class,
				() -> supervisor.cleanup("osate-cli-workspace-test.service"));
		assertTrue(failure.getMessage().startsWith("systemd stop failed: "));
		assertTrue(failure.getMessage().length() <= "systemd stop failed: ".length() + 4096);
	}

	private static Path executable(Path path) throws IOException {
		Files.writeString(path, "");
		if (!path.toFile().setExecutable(true)) {
			throw new IOException("could not mark executable: " + path);
		}
		return path;
	}

	private static CommandExecutor.CommandResult ok() {
		return result(0, "", "");
	}

	private static CommandExecutor.CommandResult result(int exit, String stdout, String stderr) {
		return new CommandExecutor.CommandResult(exit, stdout, stderr);
	}

	private static final class FakeCommandExecutor implements CommandExecutor {
		private final List<List<String>> commands = new ArrayList<>();
		private final Function<List<String>, CommandResult> responder;
		private IOException ioFailure;

		private FakeCommandExecutor(Function<List<String>, CommandResult> responder) {
			this.responder = responder;
		}

		@Override
		public Process start(List<String> command, Path stdoutFile, Path stderrFile) {
			throw new AssertionError("unexpected direct process start");
		}

		@Override
		public CommandResult run(List<String> command, Duration timeout) throws IOException {
			commands.add(List.copyOf(command));
			if (ioFailure != null) {
				throw ioFailure;
			}
			return responder.apply(command);
		}
	}
}
