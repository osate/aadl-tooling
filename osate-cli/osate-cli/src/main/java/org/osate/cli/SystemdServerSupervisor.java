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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class SystemdServerSupervisor implements ServerSupervisor {
	private static final Duration TIMEOUT = Duration.ofSeconds(5);
	private final CommandExecutor commands;
	private final Path systemdRun;
	private final Path systemctl;

	SystemdServerSupervisor(CommandExecutor commands, Path systemdRun, Path systemctl) {
		this.commands = commands;
		this.systemdRun = systemdRun;
		this.systemctl = systemctl;
	}

	@Override
	public String kind() {
		return "systemd";
	}

	@Override
	public Availability checkAvailability() throws IOException, InterruptedException {
		var r = commands.run(List.of(systemctl.toString(), "--user", "show-environment"), TIMEOUT);
		return r.exitCode() == 0 ? Availability.yes() : Availability.no(detail(r));
	}
	@Override
	public LaunchHandle start(LaunchSpec spec) throws IOException, InterruptedException {
		var unit = ServerSupervisors.serviceId(kind(), spec.firstRoot());
		cleanup(unit);
		var cmd = new ArrayList<String>();
		cmd.add(systemdRun.toString());
		cmd.add("--user");
		cmd.add("--quiet");
		cmd.add("--collect");
		cmd.add("--unit=" + unit);
		cmd.add("--property=Restart=no");
		cmd.add("--property=StandardInput=null");
		cmd.add("--property=StandardOutput=file:" + spec.stdoutFile());
		cmd.add("--property=StandardError=file:" + spec.stderrFile());
		cmd.add("--");
		cmd.addAll(spec.command());
		var r = commands.run(cmd, TIMEOUT);
		if (r.exitCode() != 0) {
			var failure = new IOException("systemd-run failed: " + detail(r));
			try {
				cleanup(unit);
			} catch (IOException | InterruptedException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
				if (cleanupFailure instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
			}
			throw failure;
		}
		return new LaunchHandle(kind(), unit, java.util.Optional.empty());
	}
	@Override
	public boolean isRunning(LaunchHandle handle) throws IOException, InterruptedException {
		var r = commands.run(List.of(systemctl.toString(), "--user", "is-active", "--quiet", handle.serviceId()), TIMEOUT);
		return r.exitCode() == 0;
	}
	@Override
	public void stop(LaunchHandle handle) throws IOException, InterruptedException {
		cleanup(handle.serviceId());
	}

	@Override
	public void cleanup(String serviceId) throws IOException, InterruptedException {
		if (serviceId == null || serviceId.isBlank()) {
			return;
		}
		var stop = commands.run(List.of(systemctl.toString(), "--user", "stop", serviceId), TIMEOUT);
		if (stop.exitCode() != 0 && !absent(stop)) {
			throw failure("stop", stop);
		}
		var reset = commands.run(List.of(systemctl.toString(), "--user", "reset-failed", serviceId), TIMEOUT);
		if (reset.exitCode() != 0 && !absent(reset)) {
			throw failure("reset-failed", reset);
		}
	}
	private static boolean absent(CommandExecutor.CommandResult r) {
		var s = (r.stdout() + "\n" + r.stderr()).toLowerCase(java.util.Locale.ROOT);
		return s.contains("not loaded") || s.contains("not found")
				|| s.contains("could not be found") || s.contains("not-found")
				|| s.contains("inactive") || s.contains("does not exist");
	}

	private static IOException failure(String op, CommandExecutor.CommandResult r) {
		return new IOException("systemd " + op + " failed: " + detail(r));
	}

	private static String detail(CommandExecutor.CommandResult r) {
		var s = r.stderr().strip();
		s = s.isEmpty() ? r.stdout().strip() : s;
		return s.length() > 4096 ? s.substring(0, 4096) : s;
	}
}
