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
import java.util.List;
import java.util.Optional;

interface ServerSupervisor {
	String kind();
	Availability checkAvailability() throws IOException, InterruptedException;
	LaunchHandle start(LaunchSpec spec) throws IOException, InterruptedException;
	boolean isRunning(LaunchHandle handle) throws IOException, InterruptedException;
	void stop(LaunchHandle handle) throws IOException, InterruptedException;
	void cleanup(String serviceId) throws IOException, InterruptedException;

	record Availability(boolean available, String reason) {
		static Availability yes() { return new Availability(true, ""); }
		static Availability no(String reason) { return new Availability(false, reason); }
	}

	record LaunchSpec(List<String> command, Path firstRoot, Path stdoutFile, Path stderrFile) {
		public LaunchSpec {
			command = List.copyOf(command);
			firstRoot = firstRoot.toAbsolutePath().normalize();
			stdoutFile = stdoutFile.toAbsolutePath().normalize();
			stderrFile = stderrFile.toAbsolutePath().normalize();
		}
	}

	record LaunchHandle(String kind, String serviceId, Optional<Process> process) {
		public LaunchHandle { process = process == null ? Optional.empty() : process; }
	}
}
