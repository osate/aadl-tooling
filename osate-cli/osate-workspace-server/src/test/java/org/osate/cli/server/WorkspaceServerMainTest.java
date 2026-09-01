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
package org.osate.cli.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Verifies that the embedded language server classpath selects the ANTLR 4.4 runtime required by
 * the Behavior Annex parser and rejects newer standalone ANTLR runtimes.
 */
class WorkspaceServerMainTest {

	@Test
	void excludesNewerAntlrRuntime() {
		assertFalse(WorkspaceServerMain.isServerRuntimeJar(Path.of("org.antlr.antlr4-runtime_4.13.2.jar")));
	}

	@Test
	void includesAntlr44AndServerBundles() {
		assertTrue(WorkspaceServerMain.isServerRuntimeJar(Path.of("antlr-runtime-4.4.jar")));
		assertTrue(WorkspaceServerMain.isServerRuntimeJar(Path.of("org.osate.aadl.ls_1.0.0.jar")));
	}
}
