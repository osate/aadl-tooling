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
import java.util.Optional;

final class DirectServerSupervisor implements ServerSupervisor {
	private final CommandExecutor commands;

	DirectServerSupervisor(CommandExecutor commands) {
		this.commands = commands;
	}

	@Override
	public String kind() {
		return "direct";
	}

	@Override
	public Availability checkAvailability() {
		return Availability.yes();
	}

	@Override
	public LaunchHandle start(LaunchSpec spec) throws IOException {
		var process = commands.start(spec.command(), spec.stdoutFile(), spec.stderrFile());
		return new LaunchHandle(kind(), "", Optional.of(process));
	}

	@Override
	public boolean isRunning(LaunchHandle handle) {
		return handle.process().map(Process::isAlive).orElse(false);
	}

	@Override
	public void stop(LaunchHandle handle) throws InterruptedException {
		if (handle.process().isEmpty()) {
			return;
		}
		var p = handle.process().get();
		if (!p.isAlive()) {
			return;
		}
		p.destroy();
		if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
			p.destroyForcibly();
			p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	@Override
	public void cleanup(String serviceId) {
	}
}
