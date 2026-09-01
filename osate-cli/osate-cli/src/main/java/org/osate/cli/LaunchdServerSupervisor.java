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
import java.time.Duration;
import java.util.List;

final class LaunchdServerSupervisor implements ServerSupervisor {
	private static final Duration TIMEOUT = Duration.ofSeconds(5);
	private final CommandExecutor commands;
	private final Path launchctl;
	private final String domain;

	LaunchdServerSupervisor(CommandExecutor commands, Path launchctl, String domain) {
		this.commands = commands;
		this.launchctl = launchctl;
		this.domain = domain;
	}
	@Override public String kind() { return "launchd"; }
	@Override public Availability checkAvailability() throws IOException, InterruptedException {
		var r = commands.run(List.of(launchctl.toString(), "print", domain), TIMEOUT);
		return r.exitCode() == 0 ? Availability.yes() : Availability.no(detail(r));
	}
	@Override public LaunchHandle start(LaunchSpec spec) throws IOException, InterruptedException {
		var label = ServerSupervisors.serviceId(kind(), spec.firstRoot());
		cleanup(label);
		Files.createDirectories(spec.stdoutFile().getParent());
		var plist = Files.createTempFile(spec.stdoutFile().getParent(), label + ".", ".plist");
		try {
			Files.writeString(plist, plistXml(label, spec), StandardCharsets.UTF_8);
			var r = commands.run(List.of(launchctl.toString(), "bootstrap", domain, plist.toString()), TIMEOUT);
			if (r.exitCode() != 0) {
				var failure = failure("bootstrap", r);
				try {
					cleanup(label);
				} catch (IOException | InterruptedException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
					if (cleanupFailure instanceof InterruptedException) {
						Thread.currentThread().interrupt();
					}
				}
				throw failure;
			}
			return new LaunchHandle(kind(), label, java.util.Optional.empty());
		} finally {
			try {
				Files.deleteIfExists(plist);
			} catch (IOException e) {
				plist.toFile().deleteOnExit();
			}
		}
	}
	@Override public boolean isRunning(LaunchHandle handle) throws IOException, InterruptedException {
		var r = commands.run(List.of(launchctl.toString(), "print", domain + "/" + handle.serviceId()), TIMEOUT);
		return r.exitCode() == 0 && r.stdout().lines().anyMatch(s -> s.trim().equals("state = running"));
	}
	@Override
	public void stop(LaunchHandle handle) throws IOException, InterruptedException {
		cleanup(handle.serviceId());
	}
	@Override public void cleanup(String serviceId) throws IOException, InterruptedException {
		if (serviceId == null || serviceId.isBlank()) return;
		var target = domain + "/" + serviceId;
		var r = commands.run(List.of(launchctl.toString(), "bootout", target), TIMEOUT);
		if (r.exitCode() != 0) {
			if (absent(r)) {
				return;
			}
			throw failure("bootout", r);
		}
		var deadline = System.nanoTime() + 2_000_000_000L;
		while (System.nanoTime() < deadline) {
			var p = commands.run(List.of(launchctl.toString(), "print", target), TIMEOUT);
			if (p.exitCode() != 0) {
				if (absent(p)) {
					return;
				}
				throw failure("print", p);
			}
			Thread.sleep(100);
		}
		throw new IOException("launchd bootout failed: service remained registered: " + target);
	}
	static String plistXml(String label, LaunchSpec spec) {
		var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
				+ "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
				+ "<plist version=\"1.0\"><dict>");
		entry(b, "Label", "string", label);
		b.append("<key>ProgramArguments</key><array>");
		for (var arg : spec.command()) b.append("<string>").append(xmlEscape(arg)).append("</string>");
		b.append("</array>");
		b.append("<key>RunAtLoad</key><true/><key>KeepAlive</key><false/>"
				+ "<key>ProcessType</key><string>Standard</string>");
		entry(b, "StandardInPath", "string", "/dev/null");
		entry(b, "StandardOutPath", "string", spec.stdoutFile().toString());
		entry(b, "StandardErrorPath", "string", spec.stderrFile().toString());
		return b.append("</dict></plist>\n").toString();
	}
	private static void entry(StringBuilder b, String key, String type, String value) {
		b.append("<key>").append(xmlEscape(key)).append("</key><").append(type).append(">")
				.append(xmlEscape(value)).append("</").append(type).append(">");
	}

	static String xmlEscape(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&apos;");
	}

	private static boolean absent(CommandExecutor.CommandResult r) {
		var s = (r.stdout() + "\n" + r.stderr()).toLowerCase(java.util.Locale.ROOT);
		return s.contains("could not find service") || s.contains("no such process");
	}

	private static IOException failure(String op, CommandExecutor.CommandResult r) {
		return new IOException("launchd " + op + " failed: " + detail(r));
	}

	private static String detail(CommandExecutor.CommandResult r) {
		var s = r.stderr().strip();
		s = s.isEmpty() ? r.stdout().strip() : s;
		return s.length() > 4096 ? s.substring(0, 4096) : s;
	}
}
