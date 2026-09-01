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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class ServerSupervisors {
	private ServerSupervisors() {
	}

	record Selection(ServerSupervisor supervisor, String fallbackReason) {
	}

	private record NativeCandidate(Optional<ServerSupervisor> supervisor, String reason) {
	}

	static Selection select(ServerLaunchMode mode, String osName, Map<String, String> environment,
			CommandExecutor commands) throws IOException, InterruptedException {
		if (mode == ServerLaunchMode.DIRECT) {
			return new Selection(new DirectServerSupervisor(commands), "");
		}
		NativeCandidate candidate;
		try {
			candidate = nativeSupervisor(osName, environment, commands);
		} catch (IOException e) {
			return unavailable(mode, commands, commandFailureReason(e));
		}
		if (candidate.supervisor().isPresent()) {
			var supervisor = candidate.supervisor().get();
			try {
				var availability = supervisor.checkAvailability();
				if (availability.available()) {
					return new Selection(supervisor, "");
				}
				return unavailable(mode, commands, availability.reason());
			} catch (IOException e) {
				return unavailable(mode, commands, commandFailureReason(e));
			}
		}
		return unavailable(mode, commands, candidate.reason());
	}

	private static Selection unavailable(ServerLaunchMode mode, CommandExecutor commands,
			String reason) throws IOException {
		var effectiveReason = reason == null || reason.isBlank()
				? "native user service manager unavailable"
				: reason;
		if (mode == ServerLaunchMode.MANAGED) {
			throw new IOException("managed workspace-server launch unavailable: " + effectiveReason);
		}
		return new Selection(new DirectServerSupervisor(commands), effectiveReason);
	}

	static Optional<ServerSupervisor> forSession(String kind, String osName,
			Map<String, String> environment, CommandExecutor commands)
			throws IOException, InterruptedException {
		if ("direct".equals(kind)) {
			return Optional.of(new DirectServerSupervisor(commands));
		}
		if ("launchd".equals(kind) && isMac(osName)) {
			var launchctl = Path.of("/bin/launchctl");
			if (!Files.isExecutable(launchctl) || !Files.isExecutable(Path.of("/usr/bin/id"))) {
				return Optional.empty();
			}
			return uid(commands).map(uid -> new LaunchdServerSupervisor(commands, launchctl, "gui/" + uid));
		}
		if ("systemd".equals(kind) && isLinux(osName)) {
			var systemdRun = findExecutable("systemd-run", environment);
			var systemctl = findExecutable("systemctl", environment);
			if (systemdRun.isPresent() && systemctl.isPresent()) {
				return Optional.of(new SystemdServerSupervisor(commands, systemdRun.get(), systemctl.get()));
			}
		}
		return Optional.empty();
	}

	static String workspaceHash(Path firstRoot) {
		try {
			var normalized = firstRoot.toAbsolutePath().normalize().toString();
			var digest = MessageDigest.getInstance("SHA-256")
					.digest(normalized.getBytes(StandardCharsets.UTF_8));
			var result = new StringBuilder(24);
			for (int i = 0; i < 12; i++) {
				result.append(String.format(Locale.ROOT, "%02x", digest[i]));
			}
			return result.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	static String serviceId(String kind, Path firstRoot) {
		var hash = workspaceHash(firstRoot);
		return switch (kind) {
		case "launchd" -> "org.osate.cli.workspace." + hash;
		case "systemd" -> "osate-cli-workspace-" + hash + ".service";
		default -> "";
		};
	}

	static Optional<Path> findExecutable(String executable, Map<String, String> environment) {
		var path = environment.get("PATH");
		if (path != null) {
			for (var item : path.split(java.io.File.pathSeparator)) {
				if (item.isBlank()) {
					continue;
				}
				var candidate = Path.of(item).resolve(executable).toAbsolutePath().normalize();
				if (Files.isExecutable(candidate)) {
					return Optional.of(candidate);
				}
			}
		}
		for (var directory : List.of(Path.of("/usr/bin"), Path.of("/bin"))) {
			var candidate = directory.resolve(executable);
			if (Files.isExecutable(candidate)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private static NativeCandidate nativeSupervisor(String osName,
			Map<String, String> environment, CommandExecutor commands)
			throws IOException, InterruptedException {
		if (isMac(osName)) {
			var launchctl = Path.of("/bin/launchctl");
			var id = Path.of("/usr/bin/id");
			if (!Files.isExecutable(launchctl)) {
				return new NativeCandidate(Optional.empty(), "/bin/launchctl is not executable");
			}
			if (!Files.isExecutable(id)) {
				return new NativeCandidate(Optional.empty(), "/usr/bin/id is not executable");
			}
			var uid = uid(commands);
			if (uid.isEmpty()) {
				return new NativeCandidate(Optional.empty(),
						"/usr/bin/id -u did not return a numeric user ID");
			}
			return new NativeCandidate(Optional.of(new LaunchdServerSupervisor(commands,
					launchctl, "gui/" + uid.get())), "");
		}
		if (isLinux(osName)) {
			var systemdRun = findExecutable("systemd-run", environment);
			var systemctl = findExecutable("systemctl", environment);
			if (systemdRun.isPresent() && systemctl.isPresent()) {
				return new NativeCandidate(Optional.of(new SystemdServerSupervisor(commands,
						systemdRun.get(), systemctl.get())), "");
			}
			var missing = systemdRun.isEmpty() && systemctl.isEmpty()
					? "systemd-run and systemctl are unavailable"
					: systemdRun.isEmpty() ? "systemd-run is unavailable" : "systemctl is unavailable";
			return new NativeCandidate(Optional.empty(), missing);
		}
		return new NativeCandidate(Optional.empty(), unsupportedReason(osName));
	}

	private static Optional<String> uid(CommandExecutor commands)
			throws IOException, InterruptedException {
		var result = commands.run(List.of("/usr/bin/id", "-u"), Duration.ofSeconds(5));
		var uid = result.stdout().strip();
		return result.exitCode() == 0 && uid.matches("[0-9]+") ? Optional.of(uid) : Optional.empty();
	}

	private static boolean isMac(String osName) {
		var name = osName.toLowerCase(Locale.ROOT);
		return name.contains("mac") || name.contains("darwin");
	}

	private static boolean isLinux(String osName) {
		return osName.toLowerCase(Locale.ROOT).contains("linux");
	}

	private static String unsupportedReason(String osName) {
		return isMac(osName) ? "launchctl unavailable"
				: isLinux(osName) ? "systemd unavailable" : "unsupported operating system";
	}

	private static String commandFailureReason(IOException e) {
		var message = e.getMessage();
		return message == null || message.isBlank()
				? "native user service manager check failed"
				: message;
	}
}
