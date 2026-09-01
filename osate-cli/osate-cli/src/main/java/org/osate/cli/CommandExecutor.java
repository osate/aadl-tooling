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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

interface CommandExecutor {
	Process start(List<String> command, Path stdoutFile, Path stderrFile) throws IOException;

	CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;

	record CommandResult(int exitCode, String stdout, String stderr) {
	}
}

final class SystemCommandExecutor implements CommandExecutor {
	private static List<String> copy(List<String> command) {
		if (command == null || command.isEmpty()) {
			throw new IllegalArgumentException("command must not be empty");
		}
		return List.copyOf(command);
	}

	@Override
	public Process start(List<String> command, Path stdoutFile, Path stderrFile) throws IOException {
		return new ProcessBuilder(copy(command))
				.redirectErrorStream(false)
				.redirectOutput(stdoutFile.toFile())
				.redirectError(stderrFile.toFile())
				.start();
	}

	@Override
	public CommandResult run(List<String> command, Duration timeout)
			throws IOException, InterruptedException {
		var copiedCommand = copy(command);
		var process = new ProcessBuilder(copiedCommand).redirectErrorStream(false).start();
		var deadline = System.nanoTime() + timeout.toNanos();
		var stdout = new StringBuilder();
		var stderr = new StringBuilder();
		var outThread = drain(process.getInputStream(), stdout);
		var errThread = drain(process.getErrorStream(), stderr);
		try {
			if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
				terminate(process);
				throw timeoutFailure(timeout, copiedCommand);
			}
			if (!joinUntil(outThread, deadline) || !joinUntil(errThread, deadline)) {
				process.getInputStream().close();
				process.getErrorStream().close();
				throw timeoutFailure(timeout, copiedCommand);
			}
			return new CommandResult(process.exitValue(), stdout.toString(), stderr.toString());
		} catch (InterruptedException e) {
			try {
				terminate(process);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			Thread.currentThread().interrupt();
			throw e;
		}
	}

	private static boolean joinUntil(Thread thread, long deadline) throws InterruptedException {
		while (thread.isAlive()) {
			var remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				return false;
			}
			thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
		}
		return true;
	}

	private static void terminate(Process process) throws InterruptedException {
		try {
			process.destroy();
			if (!process.waitFor(1, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(1, TimeUnit.SECONDS);
			}
		} finally {
			try {
				process.getInputStream().close();
			} catch (IOException ignored) {
			}
			try {
				process.getErrorStream().close();
			} catch (IOException ignored) {
			}
		}
	}

	private static IOException timeoutFailure(Duration timeout, List<String> command) {
		return new IOException("command timed out after " + timeout.toSeconds() + "s: "
				+ command.get(0));
	}

	private static Thread drain(InputStream input, StringBuilder output) {
		return Thread.ofVirtual().start(() -> {
			try (input) {
				output.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException ignored) {
			}
		});
	}
}
